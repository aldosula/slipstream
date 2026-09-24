using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using Slipstream.Core.Config;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;
using Slipstream.Hub.Native;
using Slipstream.Hub.Output;

namespace Slipstream.Core.Tests;

/// <summary>Regression tests for the defects found in the adversarial review of the Windows hub.</summary>
public class ReviewRegressionTests
{
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(5);

    private static bool WaitFor(Func<bool> condition)
    {
        var sw = Stopwatch.StartNew();
        while (!condition())
        {
            if (sw.Elapsed > Deadline) return false;
            Thread.Sleep(5);
        }
        return true;
    }

    // ------------------------------------------------------------------- TCP ---

    [Fact]
    public void Tcp_connections_reset_before_accept_do_not_end_the_server_or_the_process()
    {
        // Before the fix the socket options on an already reset connection threw out of the accept
        // thread, and the unhandled exception terminated the whole hub.
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        engine.Start();
        using var server = new TcpInputServer(engine, port: 0);
        for (int i = 0; i < 6; i++) // queued before the accept loop runs (backlog is 8)
        {
            var c = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            c.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
            c.LingerState = new LingerOption(true, 0);
            c.Close(); // RST
        }
        Thread.Sleep(100);
        server.Start();
        Assert.True(WaitFor(() => server.RejectedAtSetup + server.AcceptedConnections >= 6 || server.AcceptedConnections >= 6));

        // The server still serves a real phone afterwards.
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
        var sim = new FakePhone(TestKeys.Main, epoch: 0x51);
        var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
        int n = Framing.WriteFrame(frame, sim.Next(0));
        phone.Send(frame, 0, n, SocketFlags.None);
        Assert.True(WaitFor(() => engine.GetSnapshot().Accepted >= 1));
    }

    [Fact]
    public void Tcp_server_that_is_full_evicts_the_idlest_connection_instead_of_refusing_the_phone()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        engine.Start();
        using var server = new TcpInputServer(engine, port: 0);
        server.Start();
        var idle = new List<Socket>();
        try
        {
            for (int i = 0; i < 8; i++)
            {
                var s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
                s.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
                idle.Add(s);
            }
            Assert.True(WaitFor(() => server.ConnectionCount == 8));

            using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
            phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
            phone.ReceiveTimeout = 3000;
            var sim = new FakePhone(TestKeys.Main, epoch: 0x52);
            var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
            var sw = Stopwatch.StartNew();
            while (engine.GetSnapshot().Accepted < 5 && sw.Elapsed < Deadline)
            {
                int n = Framing.WriteFrame(frame, sim.Next(sw.ElapsedTicks));
                phone.Send(frame, 0, n, SocketFlags.None);
                Thread.Sleep(5);
            }
            Assert.True(engine.GetSnapshot().Accepted >= 5);
            Assert.Equal(1, server.Evicted);
            Assert.Equal(8, server.ConnectionCount);

            // And the phone gets STATUS back on its connection.
            var status = new byte[Wire.StatusLength];
            using var stream = new NetworkStream(phone, ownsSocket: false);
            Assert.Equal(Framing.ReadResult.Ok, Framing.ReadFrame(stream, status, Wire.StatusLength));
            Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(status, TestKeys.Main.Auth, out _));
        }
        finally
        {
            foreach (Socket s in idle) s.Dispose();
        }
    }

    [Fact]
    public void Tcp_port_binds_again_right_after_a_hub_closed_its_usb_connection()
    {
        // A restarted hub must get its USB port back at once, even though the previous one closed an
        // accepted connection a moment ago (on Windows an exclusive listener could not rebind while that
        // connection sat in TIME_WAIT).
        int port;
        using (var engine = new HubEngine(TestKeys.Main, new NullOutput()))
        using (var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp))
        {
            var first = new TcpInputServer(engine, port: 0);
            port = first.LocalPort;
            first.Start();
            phone.Connect(new IPEndPoint(IPAddress.Loopback, port));
            var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
            int n = Framing.WriteFrame(frame, new FakePhone(TestKeys.Main, epoch: 3).Next(0));
            phone.Send(frame, 0, n, SocketFlags.None);
            Assert.True(WaitFor(() => engine.GetSnapshot().Accepted >= 1));
            first.Dispose(); // the hub closes first
        }
        using var engine2 = new HubEngine(TestKeys.Main, new NullOutput());
        using var second = new TcpInputServer(engine2, port: port);
        Assert.Equal(port, second.LocalPort);
    }

    [Fact]
    public void Hot_thread_hook_runs_on_each_input_thread_and_is_released_on_that_thread()
    {
        int started = 0, released = 0, wrongThread = 0;
        IDisposable? Hook()
        {
            Interlocked.Increment(ref started);
            int id = Environment.CurrentManagedThreadId;
            return new Token(() =>
            {
                if (Environment.CurrentManagedThreadId != id) Interlocked.Increment(ref wrongThread);
                Interlocked.Increment(ref released);
            });
        }

        using (var engine = new HubEngine(TestKeys.Main, new NullOutput()))
        {
            var udp = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback, hotThreadInit: Hook);
            var tcp = new TcpInputServer(engine, port: 0, hotThreadInit: Hook);
            udp.Start();
            tcp.Start();
            using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            phone.Connect(new IPEndPoint(IPAddress.Loopback, tcp.LocalPort));
            Assert.True(WaitFor(() => Volatile.Read(ref started) == 2));
            udp.Dispose();
            tcp.Dispose();
        }
        Assert.True(WaitFor(() => Volatile.Read(ref released) == 2));
        Assert.Equal(0, wrongThread);
    }

    private sealed class Token : IDisposable
    {
        private readonly Action _onDispose;
        public Token(Action onDispose) => _onDispose = onDispose;
        public void Dispose() => _onDispose();
    }

    // ------------------------------------------------------------- multipath ---

    [Fact]
    public void Multipath_path_that_always_loses_the_race_still_gets_status()
    {
        // USB always arrives first, so every Wi-Fi copy is a duplicate. The phone rates each path by
        // the STATUS it gets back on it: Wi-Fi must still be answered, or it looks dead.
        var clock = new ManualClock();
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, clock);
        var usb = new FakeSink(TransportKind.Tcp, "usb");
        var wifi = new FakeSink(TransportKind.Udp, "udp");
        var phone = new FakePhone(TestKeys.Main, epoch: 0x77);
        phone.State.Flags = Wire.FlagMultipath;
        for (int ms = 0; ms < 500; ms++)
        {
            if (ms % 2 == 0)
            {
                byte[] p = phone.Next(clock.NowUs);
                Assert.NotEqual(ReceiveOutcome.Duplicate, engine.Receive(TransportKind.Tcp, p, usb, clock.GetTimestamp()));
                Assert.Equal(ReceiveOutcome.Duplicate, engine.Receive(TransportKind.Udp, p, wifi, clock.GetTimestamp()));
            }
            clock.AdvanceUs(1000);
            engine.Tick();
        }
        Assert.InRange(usb.Received.Count, 9, 11);
        Assert.InRange(wifi.Received.Count, 9, 11);
        Assert.Contains("udp", engine.GetSnapshot().StatusEndpoints);
    }

    [Fact]
    public void Replayed_old_packet_from_another_address_never_makes_the_hub_answer_it()
    {
        var clock = new ManualClock();
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, clock);
        var real = new FakeSink(TransportKind.Udp, "real");
        var spoofed = new FakeSink(TransportKind.Udp, "victim");
        var phone = new FakePhone(TestKeys.Main, epoch: 0x78);
        byte[] captured = phone.Next(0);
        engine.Receive(TransportKind.Udp, captured, real, clock.GetTimestamp());
        for (int i = 0; i < 400; i++)
        {
            clock.AdvanceUs(2000);
            engine.Receive(TransportKind.Udp, phone.Next(clock.NowUs), real, clock.GetTimestamp());
            engine.Tick();
        }
        for (int i = 0; i < 100; i++)
        {
            Assert.Equal(ReceiveOutcome.Duplicate, engine.Receive(TransportKind.Udp, captured, spoofed, clock.GetTimestamp()));
            clock.AdvanceUs(2000);
            engine.Receive(TransportKind.Udp, phone.Next(clock.NowUs), real, clock.GetTimestamp());
            engine.Tick();
        }
        Assert.Empty(spoofed.Received);
        Assert.NotEmpty(real.Received);
    }

    // -------------------------------------------------------------- shutdown ---

    [Fact]
    public void Engine_after_dispose_never_drives_the_device()
    {
        var clock = new ManualClock();
        var output = new RecordingOutput(clock);
        var engine = new HubEngine(TestKeys.Main, output, null, clock);
        var phone = new FakePhone(TestKeys.Main, epoch: 9);
        phone.State.Throttle = 65535;
        engine.Receive(TransportKind.Udp, phone.Next(0), null, clock.GetTimestamp());
        engine.Dispose();
        int frames = output.Frames.Count;
        engine.Receive(TransportKind.Tcp, phone.Next(0), null, clock.GetTimestamp()); // a reader finishing late
        Assert.Equal(frames, output.Frames.Count);
        Assert.Equal(1, output.NeutralCalls);
    }

    private sealed class SlowRunner : IProcessRunner
    {
        public int Calls;
        public ProcessResult Run(string fileName, IReadOnlyList<string> arguments, int timeoutMs)
        {
            Interlocked.Increment(ref Calls);
            Thread.Sleep(1500); // adb starting its server
            return new ProcessResult(0, "List of devices attached\n\n", "", false);
        }
    }

    [Fact]
    public void Adb_manager_disposed_during_a_slow_adb_call_does_not_crash_the_process()
    {
        // Before the fix Dispose disposed the stop event after a 1 s join; the poll thread then waited
        // on the disposed event and the ObjectDisposedException ended the process (USB switch turned
        // off, or the hub closed, while adb was starting).
        var runner = new SlowRunner();
        var m = new AdbReverseManager(runner, () => "pt", 47802, _ => true, _ => null);
        m.Start();
        Thread.Sleep(100);
        var sw = Stopwatch.StartNew();
        m.Dispose();
        Assert.True(sw.ElapsedMilliseconds < 1400, "Dispose must not wait for adb");
        Thread.Sleep(1800); // the slow call returns and the thread reaches its wait
        Assert.Equal(1, runner.Calls); // the loop ended instead of polling again
    }

    // ---------------------------------------------------------------- config ---

    [Fact]
    public void Config_that_cannot_be_read_does_not_stop_the_hub_and_is_left_untouched()
    {
        string dir = Path.Combine(Path.GetTempPath(), "slipstream-tests-" + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(dir, "hub.json");
        Directory.CreateDirectory(dir);
        try
        {
            var original = new HubConfig { PairingCode = "SLIPSTREAMTEST22", HubName = "RACING-PC" };
            original.Save(path);
            string before = File.ReadAllText(path);
            HubConfig cfg;
            string? note;
            using (new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) // locked by another program
            {
                cfg = HubConfig.LoadOrCreate(path, out note);
            }
            Assert.NotNull(note);
            Assert.Contains("could not be read", note);
            Assert.NotNull(Pairing.Normalize(cfg.PairingCode));
            Assert.Equal(before, File.ReadAllText(path));
            Assert.False(File.Exists(path + ".bad"));
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    private sealed class RecordingRunner : IProcessRunner
    {
        private readonly List<string> _calls = new();
        public IReadOnlyList<string> Calls { get { lock (_calls) return _calls.ToArray(); } }

        public ProcessResult Run(string fileName, IReadOnlyList<string> arguments, int timeoutMs)
        {
            lock (_calls) _calls.Add(string.Join(' ', arguments));
            return arguments[0] == "devices"
                ? new ProcessResult(0, "List of devices attached\nSER1\tdevice\n\n", "", false)
                : new ProcessResult(0, "", "", false);
        }
    }

    [Fact]
    public void Runtime_reverses_the_tcp_port_it_actually_bound()
    {
        string dir = Path.Combine(Path.GetTempPath(), "slipstream-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, OperatingSystem.IsWindows() ? "adb.exe" : "adb"), "");
        try
        {
            var config = new HubConfig { PairingCode = "SLIPSTREAMTEST22", AdbFolder = dir, BeaconEnabled = false };
            config.Normalize();
            config.Ports.Udp = 0;
            config.Ports.Tcp = 0; // "any free port"
            var runner = new RecordingRunner();
            using var runtime = new HubRuntime(config, new NullOutput(), new HubRuntimeOptions
            {
                EnableBeacon = false,
                EnableAdb = true,
                UdpBind = IPAddress.Loopback,
                ProcessRunner = runner,
            });
            runtime.Start();
            int port = runtime.Tcp!.LocalPort;
            Assert.NotEqual(0, port);
            Assert.True(WaitFor(() => runner.Calls.Any(c => c.Contains("reverse tcp:"))));
            Assert.Contains($"-s SER1 reverse tcp:{port} tcp:{port}", runner.Calls);
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    [Fact]
    public void Runtime_without_its_own_tcp_listener_never_runs_adb_reverse()
    {
        string dir = Path.Combine(Path.GetTempPath(), "slipstream-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, OperatingSystem.IsWindows() ? "adb.exe" : "adb"), "");
        try
        {
            var config = new HubConfig { PairingCode = "SLIPSTREAMTEST22", AdbFolder = dir, BeaconEnabled = false };
            config.Normalize();
            config.Ports.Udp = 0;
            var runner = new RecordingRunner();
            using var runtime = new HubRuntime(config, new NullOutput(), new HubRuntimeOptions
            {
                EnableBeacon = false,
                EnableTcp = false, // or its port was taken by another program
                EnableAdb = true,
                UdpBind = IPAddress.Loopback,
                ProcessRunner = runner,
            });
            runtime.Start();
            Thread.Sleep(300);
            Assert.Empty(runner.Calls);
            Assert.Equal(AdbState.Disabled, runtime.AdbStatus.State);
            Assert.Contains("not listening", runtime.AdbStatus.Message);
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }

    // --------------------------------------------------------------- QR hosts ---

    [Fact]
    public void Qr_lists_the_address_windows_replies_from_first_within_a_subnet()
    {
        // Ethernet and Wi-Fi on the same router. Windows sends from Ethernet (.10). The phone connects
        // its UDP socket to the first QR host of its subnet, so .10 must come first or every STATUS
        // (sent from .10) would be dropped by the phone.
        var mask = IPAddress.Parse("255.255.255.0");
        var bcast = IPAddress.Parse("192.168.1.255");
        var wifi = new Ipv4Interface("Wi-Fi", "wifi", System.Net.NetworkInformation.NetworkInterfaceType.Wireless80211,
            IPAddress.Parse("192.168.1.20"), mask, bcast, HasGateway: true);
        var eth = new Ipv4Interface("Ethernet", "eth", System.Net.NetworkInformation.NetworkInterfaceType.Ethernet,
            IPAddress.Parse("192.168.1.10"), mask, bcast, HasGateway: true);
        var hotspot = new Ipv4Interface("Hotspot", "usb", System.Net.NetworkInformation.NetworkInterfaceType.Ethernet,
            IPAddress.Parse("192.168.43.5"), mask, IPAddress.Parse("192.168.43.255"), HasGateway: false);
        var linkLocal = new Ipv4Interface("x", "x", System.Net.NetworkInformation.NetworkInterfaceType.Ethernet,
            IPAddress.Parse("169.254.3.4"), IPAddress.Parse("255.255.0.0"), IPAddress.Parse("169.254.255.255"), HasGateway: false);

        IPAddress? Route(Ipv4Interface nic) => nic.Address.ToString().StartsWith("192.168.1.", StringComparison.Ordinal)
            ? IPAddress.Parse("192.168.1.10") : nic.Address;

        IReadOnlyList<string> hosts = NetworkInfo.OrderHosts(new[] { wifi, eth, hotspot, linkLocal }, Route, 16);
        Assert.Equal(new[] { "192.168.1.10", "192.168.43.5", "192.168.1.20" }, hosts);

        // A probe that fails never hides an address.
        IReadOnlyList<string> unknown = NetworkInfo.OrderHosts(new[] { wifi, eth }, _ => throw new SocketException(), 16);
        Assert.Equal(2, unknown.Count);
    }

    // ---------------------------------------------------------------- pulses ---

    [Fact]
    public void Pulse_queue_saturates_instead_of_wrapping_negative()
    {
        var p = new PulseScheduler(60_000, 40_000);
        p.Enqueue(0, int.MaxValue, 0);
        p.Enqueue(0, int.MaxValue, 0);
        Assert.True(p.Pending(0) > 0);
        Assert.Equal(int.MaxValue, p.Pending(0));
    }

    // ------------------------------------------------------- Windows natives ---

    [Fact]
    public void VJoy_report_struct_matches_the_sdk_layout_and_covers_the_v3_size()
    {
        Assert.Equal(JoystickPositionV2.Size, Marshal.SizeOf<JoystickPositionV2>());
        Assert.Equal(124, JoystickPositionV2.Size);
        Assert.Equal(0, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.bDevice)));
        Assert.Equal(4, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.Throttle)));
        Assert.Equal(16, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.AxisX)));
        Assert.Equal(20, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.AxisY)));
        Assert.Equal(24, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.AxisZ)));
        Assert.Equal(28, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.AxisXRot)));
        Assert.Equal(32, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.AxisYRot)));
        Assert.Equal(76, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.Buttons)));
        Assert.Equal(80, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.bHats)));
        Assert.Equal(96, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.ButtonsEx1)));
        Assert.Equal(JoystickPositionV2.V2Size, (int)Marshal.OffsetOf<JoystickPositionV2>(nameof(JoystickPositionV2.V3Tail0)));
        Assert.Equal("2.2.1", VJoyNative.FormatVersion(0x0221));
    }

    [Fact]
    public void Windows_scheduling_hints_are_harmless_where_the_apis_do_not_exist()
    {
        if (OperatingSystem.IsWindows()) return; // on Windows they really register; covered by the hub
        Assert.Null(Mmcss.JoinGamesTask());
        Assert.Equal((false, false), PowerThrottling.OptOut());
    }
}
