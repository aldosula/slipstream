using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Link;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Transport;

/// <summary>
/// USB link: framed INPUT in, framed STATUS out, on 127.0.0.1:47802 (reached from the phone through
/// <c>adb reverse tcp:47802 tcp:47802</c>). TCP_NODELAY on every connection, one reader thread per
/// connection, and a frame whose length is not 52 closes the connection.
/// </summary>
public sealed class TcpInputServer : IDisposable
{
    private const int MaxConnections = 8;

    private readonly HubEngine _engine;
    private readonly Socket _listener;
    private readonly Func<IDisposable?>? _hotThreadInit;
    private readonly List<Connection> _connections = new();
    private Thread? _acceptThread;
    private volatile bool _stopping;
    private long _accepted, _closedBadLength, _rejectedSetup, _evicted;

    /// <param name="hotThreadInit">Optional, runs first on every connection thread (the Windows hub
    /// registers the thread with MMCSS there). The returned token is disposed when the thread ends.</param>
    public TcpInputServer(HubEngine engine, int port = Wire.DefaultTcpPort, IPAddress? bindAddress = null, Func<IDisposable?>? hotThreadInit = null)
    {
        _engine = engine ?? throw new ArgumentNullException(nameof(engine));
        _hotThreadInit = hotThreadInit;
        _listener = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        try
        {
            // No SO_EXCLUSIVEADDRUSE here: on Windows an exclusive listener cannot be bound again while
            // connections it accepted are still in TIME_WAIT, so a hub restarted within minutes of a USB
            // session would lose its USB link. The port is loopback only, so exclusivity buys nothing.
            _listener.Bind(new IPEndPoint(bindAddress ?? IPAddress.Loopback, port));
            _listener.Listen(8);
        }
        catch
        {
            _listener.Dispose();
            throw;
        }
        LocalPort = ((IPEndPoint)_listener.LocalEndPoint!).Port;
    }

    public int LocalPort { get; }

    public int ConnectionCount
    {
        get { lock (_connections) return _connections.Count; }
    }

    /// <summary>Connections accepted since start.</summary>
    public long AcceptedConnections => Interlocked.Read(ref _accepted);

    /// <summary>Connections closed because a frame length was not 52.</summary>
    public long ClosedForBadLength => Interlocked.Read(ref _closedBadLength);

    /// <summary>Connections that were already gone (reset) when the hub tried to set them up.</summary>
    public long RejectedAtSetup => Interlocked.Read(ref _rejectedSetup);

    /// <summary>Idle connections closed to make room for a new one.</summary>
    public long Evicted => Interlocked.Read(ref _evicted);

    public void Start()
    {
        if (_acceptThread is not null) return;
        _acceptThread = new Thread(AcceptLoop) { IsBackground = true, Name = "slipstream-tcp-accept" };
        _acceptThread.Start();
    }

    private void AcceptLoop()
    {
        while (!_stopping)
        {
            Socket client;
            try
            {
                // Poll first so Dispose is noticed on every OS (a blocked Accept is not always woken by Close).
                if (!_listener.Poll(250_000, SelectMode.SelectRead)) continue;
                client = _listener.Accept();
            }
            catch (ObjectDisposedException) { break; }
            catch (Exception)
            {
                if (_stopping) break;
                Thread.Sleep(20);
                continue;
            }

            try
            {
                Admit(client);
            }
            catch (Exception)
            {
                // A connection reset between connect and accept fails its socket options (or its peer
                // address lookup). Drop it: nothing here may end this thread, let alone the process.
                Interlocked.Increment(ref _rejectedSetup);
                try { client.Dispose(); } catch { }
            }
        }
    }

    private void Admit(Socket client)
    {
        var conn = new Connection(this, client); // throws for a connection that is already gone
        Connection? evicted = null;
        lock (_connections)
        {
            if (_stopping)
            {
                conn.Close();
                return;
            }
            if (_connections.Count >= MaxConnections)
            {
                // Full: the connection that delivered nothing for the longest time makes room, so
                // stale or idle sockets can never lock the phone out of the USB link.
                evicted = _connections[0];
                foreach (Connection c in _connections)
                    if (c.LastActivityMs < evicted.LastActivityMs) evicted = c;
                _connections.Remove(evicted);
            }
            _connections.Add(conn);
        }
        if (evicted is not null)
        {
            Interlocked.Increment(ref _evicted);
            evicted.Close();
        }
        Interlocked.Increment(ref _accepted);
        try
        {
            conn.Start();
        }
        catch
        {
            conn.Close(); // never leave a connection in the table that has no reader
            throw;
        }
    }

    private void Remove(Connection conn)
    {
        lock (_connections) _connections.Remove(conn);
    }

    public void Dispose()
    {
        _stopping = true;
        try { _listener.Close(); } catch { }
        Connection[] all;
        lock (_connections) all = _connections.ToArray();
        foreach (Connection c in all) c.Close();
        _acceptThread?.Join(1000);
        // Wait briefly for the readers, so no frame is applied after the hub has shut down its transports.
        foreach (Connection c in all) c.Join(500);
    }

    private sealed class Connection : IStatusSink
    {
        private readonly TcpInputServer _server;
        private readonly Socket _socket;
        private readonly byte[] _frame = new byte[Wire.FrameHeaderLength + Wire.StatusLength];
        private readonly object _sendGate = new();
        private volatile bool _open = true;
        private int _closed;
        private long _lastActivityMs;
        private Thread? _thread;

        public Connection(TcpInputServer server, Socket socket)
        {
            _server = server;
            _socket = socket;
            _socket.NoDelay = true;
            _socket.SendTimeout = 20;
            _socket.ReceiveBufferSize = 64 * 1024;
            // Abortive close (RST) whenever the hub closes: the link carries state, there is nothing to
            // flush, and it leaves no TIME_WAIT behind on the hub's port.
            _socket.LingerState = new LingerOption(true, 0);
            Endpoint = "usb " + (_socket.RemoteEndPoint?.ToString() ?? "loopback");
            _lastActivityMs = Environment.TickCount64;
        }

        public TransportKind Transport => TransportKind.Tcp;
        public string Endpoint { get; }
        public bool IsOpen => _open;
        public long LastActivityMs => Volatile.Read(ref _lastActivityMs);

        public void Start()
        {
            _thread = new Thread(ReadLoop)
            {
                IsBackground = true,
                Name = "slipstream-tcp",
                Priority = ThreadPriority.Highest,
            };
            _thread.Start();
        }

        public void Join(int timeoutMs)
        {
            Thread? t = _thread;
            if (t is not null && t != Thread.CurrentThread) t.Join(timeoutMs);
        }

        private void ReadLoop()
        {
            IDisposable? hot = null;
            try { hot = _server._hotThreadInit?.Invoke(); } catch { /* scheduling hint only */ }
            byte[] buffer = GC.AllocateArray<byte>(Wire.InputLength, pinned: true);
            IClock clock = _server._engine.Clock;
            try
            {
                while (_open)
                {
                    Framing.ReadResult r = Framing.ReadFrame(_socket, buffer, Wire.InputLength);
                    if (r == Framing.ReadResult.BadLength)
                    {
                        Interlocked.Increment(ref _server._closedBadLength);
                        _server._engine.CountMalformed(TransportKind.Tcp);
                        break;
                    }
                    if (r != Framing.ReadResult.Ok) break;
                    long rx = clock.GetTimestamp();
                    ReceiveOutcome outcome = _server._engine.Receive(TransportKind.Tcp, buffer, this, rx);
                    if (outcome is ReceiveOutcome.Accepted or ReceiveOutcome.AcceptedNewEpoch or ReceiveOutcome.Duplicate)
                        Volatile.Write(ref _lastActivityMs, Environment.TickCount64);
                }
            }
            catch (SocketException) { }
            catch (ObjectDisposedException) { }
            catch (Exception) { /* never let a connection thread end the process; the phone reconnects */ }
            finally
            {
                Close();
                try { hot?.Dispose(); } catch { }
            }
        }

        public void SendStatus(ReadOnlySpan<byte> status)
        {
            if (!_open) return;
            lock (_sendGate)
            {
                try
                {
                    // Never stall the housekeeping thread on a phone that stopped reading:
                    // if the send buffer is full, skip this STATUS, the next one is 50 ms away.
                    if (!_socket.Poll(0, SelectMode.SelectWrite)) return;
                    int n = Framing.WriteFrame(_frame, status);
                    _socket.Send(_frame, 0, n, SocketFlags.None);
                }
                catch (SocketException) { Close(); }
                catch (ObjectDisposedException) { _open = false; }
            }
        }

        public void Close()
        {
            _open = false;
            if (Interlocked.Exchange(ref _closed, 1) != 0) return;
            try { _socket.Shutdown(SocketShutdown.Both); } catch { } // wakes the blocked reader on every OS
            try { _socket.Close(); } catch { }
            _server.Remove(this);
        }
    }
}
