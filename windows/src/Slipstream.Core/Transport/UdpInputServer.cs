using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Link;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Transport;

/// <summary>
/// Wi-Fi link: INPUT and PAD in on UDP 47800, STATUS out to each sender's address from the same socket.
/// One dedicated thread at <see cref="ThreadPriority.Highest"/> does a blocking receive into a
/// reused buffer and hands the bytes to <see cref="HubEngine"/>, which writes the virtual device
/// before the next receive. The receive path does not allocate.
/// </summary>
public sealed class UdpInputServer : IDisposable
{
    private const int MaxPeers = 16;
    // WSAIOCTL SIO_UDP_CONNRESET = _WSAIOW(IOC_VENDOR, 12)
    private const int SioUdpConnReset = unchecked((int)0x9800000C);

    private readonly HubEngine _engine;
    private readonly Socket _socket;
    private readonly Func<IDisposable?>? _hotThreadInit;
    private readonly UdpPeer[] _peers = new UdpPeer[MaxPeers];
    private int _peerCount;
    private Thread? _thread;
    private volatile bool _stopping;
    private long _datagrams;

    /// <param name="hotThreadInit">Optional, runs first on the receive thread (the Windows hub registers
    /// the thread with MMCSS there). The returned token is disposed when the thread ends.</param>
    public UdpInputServer(HubEngine engine, int port = Wire.DefaultUdpPort, IPAddress? bindAddress = null, Func<IDisposable?>? hotThreadInit = null)
    {
        _engine = engine ?? throw new ArgumentNullException(nameof(engine));
        _hotThreadInit = hotThreadInit;
        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        try
        {
            if (OperatingSystem.IsWindows())
            {
                // An ICMP "port unreachable" (phone app closed) must not break the receive loop.
                _socket.IOControl(SioUdpConnReset, new byte[] { 0, 0, 0, 0 }, null);
                // Do not let another process bind the same port and steal the traffic.
                _socket.ExclusiveAddressUse = true;
            }
            _socket.ReceiveBufferSize = 256 * 1024;
            // On Windows closesocket aborts a blocked receive, so Dispose is noticed at once. Elsewhere a
            // blocked receive is not always woken by Close: a timeout lets the loop see the stop flag.
            if (!OperatingSystem.IsWindows()) _socket.ReceiveTimeout = 500;
            _socket.SendBufferSize = 64 * 1024;
            _socket.Bind(new IPEndPoint(bindAddress ?? IPAddress.Any, port));
        }
        catch
        {
            _socket.Dispose();
            throw;
        }
        LocalPort = ((IPEndPoint)_socket.LocalEndPoint!).Port;
    }

    /// <summary>The bound port (useful when constructed with port 0).</summary>
    public int LocalPort { get; }

    /// <summary>Datagrams received, valid or not.</summary>
    public long Datagrams => Interlocked.Read(ref _datagrams);

    public string? LastError { get; private set; }

    public void Start()
    {
        if (_thread is not null) return;
        _thread = new Thread(ReceiveLoop)
        {
            IsBackground = true,
            Name = "slipstream-udp",
            Priority = ThreadPriority.Highest,
        };
        _thread.Start();
    }

    private void ReceiveLoop()
    {
        IDisposable? hot = null;
        try { hot = _hotThreadInit?.Invoke(); } catch { /* scheduling hint only */ }
        try
        {
            var ctx = new ReceiveContext();
            while (!_stopping && ReceiveOnce(ctx)) { }
        }
        finally
        {
            try { hot?.Dispose(); } catch { }
        }
    }

    /// <summary>Per-thread receive state: one pinned buffer and one address, reused forever.</summary>
    internal sealed class ReceiveContext
    {
        public readonly byte[] Buffer = GC.AllocateArray<byte>(2048, pinned: true);
        public readonly SocketAddress From = new(AddressFamily.InterNetwork);
        public readonly int AddressCapacity;

        public ReceiveContext() => AddressCapacity = From.Size;
    }

    /// <summary>
    /// One blocking receive and its processing. Returns false when the socket is gone. Internal so
    /// tests can run it on their own thread and prove it does not allocate.
    /// </summary>
    internal bool ReceiveOnce(ReceiveContext ctx)
    {
        int n;
        try
        {
            // A receive (or a timed-out one) shrinks Size to what it wrote; restore the capacity
            // every time or the next call rejects the address buffer as too small.
            ctx.From.Size = ctx.AddressCapacity;
            n = _socket.ReceiveFrom(ctx.Buffer, SocketFlags.None, ctx.From);
        }
        catch (SocketException ex) when (ex.SocketErrorCode is SocketError.TimedOut or SocketError.WouldBlock
                                              or SocketError.ConnectionReset or SocketError.MessageSize or SocketError.NetworkReset)
        {
            return true;
        }
        catch (SocketException ex)
        {
            if (_stopping) return false;
            LastError = ex.Message;
            Thread.Sleep(5);
            return true;
        }
        catch (ObjectDisposedException)
        {
            return false;
        }
        catch (Exception ex)
        {
            // Never let the receive thread die: an unhandled exception here would end the process.
            if (_stopping) return false;
            LastError = ex.Message;
            Thread.Sleep(5);
            return true;
        }

        long rx = _engine.Clock.GetTimestamp();
        Interlocked.Increment(ref _datagrams);
        try
        {
            ReadOnlySpan<byte> data = ctx.Buffer.AsSpan(0, n);
            if (n == Wire.PadLength)
            {
                if (_engine.ValidatePad(TransportKind.Udp, data, out PadPacket pad, out PairingKey padKey) != ReceiveOutcome.Accepted)
                    return true;
                _engine.Accept(TransportKind.Udp, in pad, FindOrAddPeer(ctx.From), rx, padKey);
                return true;
            }
            if (_engine.Validate(TransportKind.Udp, data, out InputPacket packet, out PairingKey key) != ReceiveOutcome.Accepted)
                return true;
            // Peers are only created for packets with a valid tag, so strangers cannot grow the table.
            UdpPeer peer = FindOrAddPeer(ctx.From);
            _engine.Accept(TransportKind.Udp, in packet, peer, rx, key);
        }
        catch (Exception ex)
        {
            LastError = ex.Message;
        }
        return true;
    }

    private UdpPeer FindOrAddPeer(SocketAddress from)
    {
        for (int i = 0; i < _peerCount; i++)
        {
            UdpPeer p = _peers[i];
            if (p.Address.Equals(from))
            {
                p.LastSeenTicks = Environment.TickCount64;
                return p;
            }
        }
        var peer = new UdpPeer(_socket, from) { LastSeenTicks = Environment.TickCount64 };
        if (_peerCount < MaxPeers)
        {
            _peers[_peerCount++] = peer;
        }
        else
        {
            int oldest = 0;
            for (int i = 1; i < _peerCount; i++)
                if (_peers[i].LastSeenTicks < _peers[oldest].LastSeenTicks) oldest = i;
            _peers[oldest].Close();
            _peers[oldest] = peer;
        }
        return peer;
    }

    public void Dispose()
    {
        _stopping = true;
        try { _socket.Close(); } catch { }
        _thread?.Join(1000);
        for (int i = 0; i < _peerCount; i++) _peers[i].Close();
    }

    /// <summary>One phone address. STATUS goes back to it from the listening socket.</summary>
    private sealed class UdpPeer : IStatusSink
    {
        private readonly Socket _socket;
        private volatile bool _open = true;

        public UdpPeer(Socket socket, SocketAddress from)
        {
            _socket = socket;
            Address = new SocketAddress(from.Family, from.Size);
            from.Buffer.Span[..from.Size].CopyTo(Address.Buffer.Span);
            Endpoint = "udp " + ((IPEndPoint)new IPEndPoint(IPAddress.Any, 0).Create(Address));
        }

        public SocketAddress Address { get; }
        public long LastSeenTicks { get; set; }
        public TransportKind Transport => TransportKind.Udp;
        public string Endpoint { get; }
        public bool IsOpen => _open;

        public void SendStatus(ReadOnlySpan<byte> status)
        {
            if (!_open) return;
            try { _socket.SendTo(status, SocketFlags.None, Address); }
            catch (ObjectDisposedException) { _open = false; }
            catch (SocketException) { /* transient, the next STATUS is 50 ms away */ }
        }

        public void Close() => _open = false;
    }
}
