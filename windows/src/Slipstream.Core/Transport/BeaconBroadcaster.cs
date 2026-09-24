using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Link;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Transport;

/// <summary>
/// Sends BEACON once per second to 255.255.255.255:47801 and to the directed broadcast address of
/// every up, non-loopback IPv4 interface, so Wi-Fi, USB tethering and the phone's hotspot all see
/// it. The fingerprint always follows the engine's current pairing key.
/// </summary>
public sealed class BeaconBroadcaster : IDisposable
{
    private readonly HubEngine _engine;
    private readonly Func<string> _hubName;
    private readonly ushort _udpPort, _tcpPort;
    private readonly int _beaconPort;
    private readonly bool _includeBroadcast;
    private readonly IReadOnlyList<IPEndPoint> _extraTargets;
    private readonly Socket _socket;
    private readonly byte[] _buffer = new byte[Wire.BeaconMaxLength];
    private IPEndPoint[] _targets = Array.Empty<IPEndPoint>();
    private long _nextRefreshMs;
    private Thread? _thread;
    private readonly ManualResetEventSlim _stop = new(false);
    private long _sent, _failed;

    /// <param name="includeBroadcast">false sends only to <paramref name="extraTargets"/> (tests).</param>
    public BeaconBroadcaster(HubEngine engine, Func<string> hubName, int udpPort = Wire.DefaultUdpPort, int tcpPort = Wire.DefaultTcpPort,
        int beaconPort = Wire.DefaultBeaconPort, bool includeBroadcast = true, IEnumerable<IPEndPoint>? extraTargets = null)
    {
        _engine = engine ?? throw new ArgumentNullException(nameof(engine));
        _hubName = hubName ?? throw new ArgumentNullException(nameof(hubName));
        _udpPort = checked((ushort)udpPort);
        _tcpPort = checked((ushort)tcpPort);
        _beaconPort = beaconPort;
        _includeBroadcast = includeBroadcast;
        _extraTargets = extraTargets?.ToArray() ?? Array.Empty<IPEndPoint>();
        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { EnableBroadcast = true };
        _socket.Bind(new IPEndPoint(IPAddress.Any, 0));
    }

    public long Sent => Interlocked.Read(ref _sent);
    public long Failed => Interlocked.Read(ref _failed);
    public string? LastError { get; private set; }

    /// <summary>Destinations used by the last send.</summary>
    public IReadOnlyList<string> Targets => Volatile.Read(ref _targets).Select(t => t.ToString()).ToArray();

    public void Start()
    {
        if (_thread is not null) return;
        _thread = new Thread(Loop) { IsBackground = true, Name = "slipstream-beacon" };
        _thread.Start();
    }

    private void Loop()
    {
        while (true)
        {
            try { SendOnce(); }
            catch (Exception ex) { LastError = ex.Message; }
            try
            {
                if (_stop.Wait(1000)) return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    /// <summary>Builds the beacon from the current key and name, and sends it to every target.</summary>
    public void SendOnce()
    {
        long nowMs = Environment.TickCount64;
        if (nowMs >= _nextRefreshMs)
        {
            Volatile.Write(ref _targets, ComputeTargets());
            _nextRefreshMs = nowMs + 5000; // interfaces come and go (hotspot, tethering)
        }

        string name = Pairing.TruncateName(_hubName());
        int n = Beacon.Encode(_buffer, _udpPort, _tcpPort, _engine.Pairing.Fingerprint, name);
        foreach (IPEndPoint target in Volatile.Read(ref _targets))
        {
            try
            {
                _socket.SendTo(_buffer.AsSpan(0, n), SocketFlags.None, target);
                Interlocked.Increment(ref _sent);
            }
            catch (SocketException ex)
            {
                // A down interface or a blocked broadcast is normal; keep going with the others.
                Interlocked.Increment(ref _failed);
                LastError = $"{target}: {ex.SocketErrorCode}";
            }
        }
    }

    private IPEndPoint[] ComputeTargets()
    {
        var set = new List<IPEndPoint>();
        if (_includeBroadcast)
        {
            set.Add(new IPEndPoint(IPAddress.Broadcast, _beaconPort));
            foreach (Ipv4Interface nic in NetworkInfo.GetIpv4Interfaces())
            {
                if (nic.DirectedBroadcast is null) continue;
                var ep = new IPEndPoint(nic.DirectedBroadcast, _beaconPort);
                if (!set.Contains(ep)) set.Add(ep);
            }
        }
        foreach (IPEndPoint extra in _extraTargets)
            if (!set.Contains(extra)) set.Add(extra);
        return set.ToArray();
    }

    public void Dispose()
    {
        _stop.Set();
        // Interface enumeration can be slow on some PCs. If the thread is still inside a send, leave the
        // event alone (disposing it under the thread would make its next wait throw and end the process).
        Thread? t = _thread;
        bool finished = t is null || t.Join(1500);
        _socket.Dispose();
        if (finished) _stop.Dispose();
    }
}
