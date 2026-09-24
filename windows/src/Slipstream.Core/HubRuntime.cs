using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Config;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;

namespace Slipstream.Core;

public sealed record HubRuntimeOptions
{
    public bool EnableBeacon { get; init; } = true;
    public bool EnableTcp { get; init; } = true;
    /// <summary>null follows <see cref="HubConfig.UsbEnabled"/>.</summary>
    public bool? EnableAdb { get; init; }
    public IPAddress UdpBind { get; init; } = IPAddress.Any;
    public IPAddress TcpBind { get; init; } = IPAddress.Loopback;
    public IProcessRunner? ProcessRunner { get; init; }
    public IClock? Clock { get; init; }
    /// <summary>
    /// Runs first on every input thread (UDP receive, each TCP connection). The Windows hub registers the
    /// thread with MMCSS here so a game that loads every core cannot delay the apply path. The token it
    /// returns is disposed on the same thread when that thread ends.
    /// </summary>
    public Func<IDisposable?>? HotThreadInit { get; init; }
}

/// <summary>
/// Wires the engine to its transports from a <see cref="HubConfig"/>: UDP input, TCP (USB) input,
/// beacon and adb reverse. Used by the Windows hub and by the headless CLI. A port that cannot be
/// bound is a reported state (<see cref="UdpError"/>, <see cref="TcpError"/>), not a crash.
/// </summary>
public sealed class HubRuntime : IDisposable
{
    private readonly HubRuntimeOptions _options;
    private readonly object _gate = new();
    private AdbReverseManager? _adb;
    private bool _started;
    private bool _usbWanted;

    public HubRuntime(HubConfig config, IOutputDevice output, HubRuntimeOptions? options = null)
    {
        Config = config ?? throw new ArgumentNullException(nameof(config));
        _options = options ?? new HubRuntimeOptions();
        Config.Normalize();
        Engine = new HubEngine(PairingKey.FromCode(Config.PairingCode!), output, Config.ToEngineOptions(), _options.Clock);
    }

    public HubConfig Config { get; }
    public HubEngine Engine { get; }
    public UdpInputServer? Udp { get; private set; }
    public TcpInputServer? Tcp { get; private set; }
    public BeaconBroadcaster? Beacon { get; private set; }
    public string? UdpError { get; private set; }
    public string? TcpError { get; private set; }

    public AdbStatus AdbStatus
    {
        get
        {
            lock (_gate)
            {
                if (_adb is not null) return _adb.Status;
                string why = _usbWanted && _started && Tcp is null
                    ? "USB link off: the hub is not listening on its TCP port, so adb reverse is not run."
                    : "USB mode is off.";
                return new AdbStatus(AdbState.Disabled, why, null, Array.Empty<AdbDevice>());
            }
        }
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_started) return;
            _started = true;
        }
        Engine.Start();

        try
        {
            Udp = new UdpInputServer(Engine, Config.Ports.Udp, _options.UdpBind, _options.HotThreadInit);
            Udp.Start();
        }
        catch (SocketException ex)
        {
            UdpError = ex.SocketErrorCode == SocketError.AddressAlreadyInUse
                ? $"UDP port {Config.Ports.Udp} is in use by another program (another hub or a different wheel server). Close it and restart Slipstream Hub."
                : $"Cannot listen on UDP port {Config.Ports.Udp}: {ex.Message}";
        }

        if (_options.EnableTcp)
        {
            try
            {
                Tcp = new TcpInputServer(Engine, Config.Ports.Tcp, _options.TcpBind, _options.HotThreadInit);
                Tcp.Start();
            }
            catch (SocketException ex)
            {
                TcpError = ex.SocketErrorCode == SocketError.AddressAlreadyInUse
                    ? $"TCP port {Config.Ports.Tcp} is in use by another program, so the USB link is off."
                    : $"Cannot listen on TCP port {Config.Ports.Tcp}: {ex.Message}";
            }
        }

        if (_options.EnableBeacon && Config.BeaconEnabled)
        {
            try
            {
                Beacon = new BeaconBroadcaster(Engine, () => Config.HubName ?? HubConfig.DefaultHubName(),
                    Udp?.LocalPort ?? Config.Ports.Udp, Tcp?.LocalPort ?? Config.Ports.Tcp, Config.Ports.Beacon);
                Beacon.Start();
            }
            catch (SocketException) { Beacon = null; }
        }

        SetUsbEnabled(_options.EnableAdb ?? Config.UsbEnabled);
    }

    /// <summary>Starts or stops the adb reverse manager.</summary>
    public void SetUsbEnabled(bool enabled)
    {
        AdbReverseManager? toDispose = null;
        lock (_gate)
        {
            _usbWanted = enabled;
            // Only with our own listener: reversing a port the hub could not bind would hand the phone's
            // USB link to whatever program holds it.
            if (enabled && _adb is null && _started && Tcp is not null)
            {
                // The port actually bound (the configured one may be 0, "any free port").
                _adb = new AdbReverseManager(_options.ProcessRunner ?? new SystemProcessRunner(), () => Config.AdbFolder, Tcp.LocalPort);
                _adb.Start();
            }
            else if (!enabled && _adb is not null)
            {
                toDispose = _adb;
                _adb = null;
            }
        }
        toDispose?.Dispose();
    }

    /// <summary>Pushes timing and inversion from <see cref="Config"/> into the engine.</summary>
    public void ApplySettings()
    {
        Config.Normalize();
        Engine.UpdateOptions(Config.ToEngineOptions());
    }

    /// <summary>Generates a new pairing code, switches the engine to it and stores it in <see cref="Config"/>.</summary>
    public PairingKey RegeneratePairing()
    {
        PairingKey key = PairingKey.Generate();
        Config.PairingCode = key.Code;
        Engine.SetPairing(key);
        return key;
    }

    /// <summary>The QR payload for the current code, listing every usable IPv4 address.</summary>
    public string PairUri() => PairUri(NetworkInfo.GetUsableHosts());

    /// <summary>The QR payload with the given hosts and the ports actually bound.</summary>
    public string PairUri(IEnumerable<string> hosts)
        => Pairing.BuildPairUri(Engine.Pairing.Code, Config.HubName ?? HubConfig.DefaultHubName(),
            Udp?.LocalPort ?? Config.Ports.Udp, Tcp?.LocalPort ?? Config.Ports.Tcp, hosts);

    public void Dispose()
    {
        SetUsbEnabled(false);
        Beacon?.Dispose();
        Udp?.Dispose();
        Tcp?.Dispose();
        Engine.Dispose();
    }
}
