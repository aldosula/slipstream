using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;

namespace Slipstream.Core.Tests;

/// <summary>End to end over real sockets on ephemeral loopback ports, with the real clock and threads.</summary>
public class LoopbackTests
{
    private static readonly TimeSpan Deadline = TimeSpan.FromSeconds(5);

    [Fact]
    public void Udp_loopback_applies_input_and_returns_a_valid_status()
    {
        var output = new RecordingOutput();
        using var engine = new HubEngine(TestKeys.Main, output);
        engine.Start();
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        server.Start();

        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        phone.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        phone.ReceiveTimeout = 100;
        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var sim = new FakePhone(TestKeys.Main, epoch: 0xBEEF);
        sim.State.Steer = 16384;
        sim.State.Throttle = 65535;

        var sw = Stopwatch.StartNew();
        StatusPacket? status = null;
        var rx = new byte[128];
        uint lastSent = 0;
        while (status is null && sw.Elapsed < Deadline)
        {
            for (int i = 0; i < 5; i++)
            {
                byte[] p = sim.Next(sw.ElapsedTicks);
                lastSent = sim.Seq;
                phone.SendTo(p, hub);
                Thread.Sleep(2);
            }
            try
            {
                int n = phone.Receive(rx);
                if (StatusPacket.TryDecode(rx.AsSpan(0, n), TestKeys.Main.Auth, out StatusPacket s) == DecodeResult.Ok) status = s;
            }
            catch (SocketException) { }
        }

        Assert.NotNull(status);
        Assert.Equal(0xBEEFu, status!.Value.Epoch);
        Assert.InRange(status.Value.LastSeq, 1u, lastSent);
        Assert.True(status.Value.Accepted >= 1);
        Assert.Equal(0u, status.Value.Missing);
        Assert.InRange(status.Value.HoldUs, 0u, 1_000_000u);

        OutputFrame last = output.Last!.Value;
        Assert.Equal(24577, last.VJoyX);
        Assert.Equal(32768, last.VJoyY);
        HubSnapshot snap = engine.GetSnapshot();
        Assert.Equal(LinkState.Live, snap.State);
        Assert.True(snap.Transport(TransportKind.Udp).FirstArrivals >= 1);
        Assert.Contains(snap.StatusEndpoints, e => e.StartsWith("udp 127.0.0.1:", StringComparison.Ordinal));
    }

    [Fact]
    public void Udp_server_keeps_receiving_after_idle_receive_timeouts()
    {
        // Regression: a timed-out receive shrank the reused SocketAddress and the next receive threw,
        // killing the thread. Idle past two timeouts, then expect packets from two different sources.
        var output = new RecordingOutput();
        using var engine = new HubEngine(TestKeys.Main, output);
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        server.Start();
        Thread.Sleep(1200);
        Assert.Null(server.LastError);

        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var sim = new FakePhone(TestKeys.Main, epoch: 5);
        using var a = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        using var b = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        a.SendTo(sim.Next(0), hub);
        var sw = Stopwatch.StartNew();
        while (engine.GetSnapshot().Accepted < 1 && sw.Elapsed < Deadline) Thread.Sleep(5);
        Thread.Sleep(700); // another timeout after a successful receive
        b.SendTo(sim.Next(0), hub);
        while (engine.GetSnapshot().Accepted < 2 && sw.Elapsed < Deadline) Thread.Sleep(5);
        Assert.Equal(2u, engine.GetSnapshot().Accepted);
        Assert.Null(server.LastError);
    }

    [Fact]
    public void Udp_receive_path_from_socket_to_device_does_not_allocate()
    {
        // Runs the server's own receive step on this thread: socket receive, validation, peer lookup,
        // session rules and the device write.
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var sim = new FakePhone(TestKeys.Main, epoch: 42);
        var ctx = new UdpInputServer.ReceiveContext();

        void Batch(int count)
        {
            for (int i = 0; i < count; i++)
            {
                if (i % 25 == 0) sim.Press(0);
                phone.SendTo(sim.Next(i), hub);
            }
        }

        Batch(200);
        for (int i = 0; i < 200; i++) Assert.True(server.ReceiveOnce(ctx)); // warm up: JIT, first peer
        Batch(1000);
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int i = 0; i < 1000; i++) server.ReceiveOnce(ctx);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;

        Assert.Equal(0, allocated);
        Assert.Equal(1200u, engine.GetSnapshot().Accepted);
        Assert.Null(server.LastError);
    }

    [Fact]
    public void Udp_server_counts_bad_tags_and_ignores_garbage()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        server.Start();
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var stranger = new FakePhone(TestKeys.Other, epoch: 1);
        phone.SendTo(stranger.Next(0), hub);
        phone.SendTo(new byte[] { 1, 2, 3 }, hub);

        var sw = Stopwatch.StartNew();
        while (sw.Elapsed < Deadline && server.Datagrams < 2) Thread.Sleep(5);
        HubSnapshot s = engine.GetSnapshot();
        Assert.Equal(1, s.Transport(TransportKind.Udp).BadTags);
        Assert.Equal(1, s.Transport(TransportKind.Udp).Malformed);
        Assert.False(s.HasEpoch);
    }

    [Fact]
    public void Tcp_loopback_applies_framed_input_and_returns_a_framed_status()
    {
        var output = new RecordingOutput();
        using var engine = new HubEngine(TestKeys.Main, output);
        engine.Start();
        using var server = new TcpInputServer(engine, port: 0);
        server.Start();

        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
        var sim = new FakePhone(TestKeys.Main, epoch: 0xF00D);
        sim.State.Brake = 65535;
        sim.Press(0);

        var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
        var sw = Stopwatch.StartNew();
        using var stream = new NetworkStream(phone, ownsSocket: false);
        StatusPacket? status = null;
        var statusThread = new Thread(() =>
        {
            var buf = new byte[Wire.StatusLength];
            if (Framing.ReadFrame(stream, buf, Wire.StatusLength) == Framing.ReadResult.Ok
                && StatusPacket.TryDecode(buf, TestKeys.Main.Auth, out StatusPacket s) == DecodeResult.Ok)
                status = s;
        }) { IsBackground = true };
        statusThread.Start();

        while (statusThread.IsAlive && sw.Elapsed < Deadline)
        {
            int n = Framing.WriteFrame(frame, sim.Next(sw.ElapsedTicks));
            phone.Send(frame, 0, n, SocketFlags.None);
            Thread.Sleep(2);
        }
        statusThread.Join(Deadline);

        Assert.NotNull(status);
        Assert.Equal(0xF00Du, status!.Value.Epoch);
        Assert.True(status.Value.Accepted >= 1);
        HubSnapshot snap = engine.GetSnapshot();
        Assert.Equal(32768, snap.Output.VJoyZ);
        Assert.True(snap.Transport(TransportKind.Tcp).FirstArrivals >= 1);
        Assert.Equal(0, snap.Transport(TransportKind.Udp).Packets);
        Assert.Contains(snap.StatusEndpoints, e => e.StartsWith("usb ", StringComparison.Ordinal));
        Assert.Equal(1, server.ConnectionCount);
    }

    [Fact]
    public void Tcp_connection_is_closed_on_a_bad_frame_length()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        using var server = new TcpInputServer(engine, port: 0);
        server.Start();
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
        phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
        phone.ReceiveTimeout = 3000;

        var bad = new byte[2 + 44];
        BinaryPrimitives.WriteUInt16LittleEndian(bad, 44); // a STATUS-sized frame is not allowed towards the hub
        phone.Send(bad);

        int n;
        try { n = phone.Receive(new byte[16]); }
        catch (SocketException ex) when (ex.SocketErrorCode == SocketError.ConnectionReset) { n = 0; }
        Assert.Equal(0, n); // orderly close from the hub
        var sw = Stopwatch.StartNew();
        while (server.ConnectionCount > 0 && sw.Elapsed < Deadline) Thread.Sleep(5);
        Assert.Equal(0, server.ConnectionCount);
        Assert.Equal(1, server.ClosedForBadLength);
        Assert.Equal(1, engine.GetSnapshot().Transport(TransportKind.Tcp).Malformed);
    }

    [Fact]
    public void Multipath_over_real_udp_and_tcp_applies_each_seq_once()
    {
        var output = new RecordingOutput();
        using var engine = new HubEngine(TestKeys.Main, output);
        engine.Start();
        using var udp = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        udp.Start();
        using var tcp = new TcpInputServer(engine, port: 0);
        tcp.Start();

        using var u = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        using var t = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        t.Connect(new IPEndPoint(IPAddress.Loopback, tcp.LocalPort));
        var hub = new IPEndPoint(IPAddress.Loopback, udp.LocalPort);
        var sim = new FakePhone(TestKeys.Main, epoch: 0xAB);
        sim.State.Flags = Wire.FlagMultipath;
        var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
        const int presses = 5;
        for (int i = 0; i < 400; i++)
        {
            if (i % 70 == 10 && i < 400 - 60) sim.Press(0);
            byte[] p = sim.Next(i);
            u.SendTo(p, hub);
            int n = Framing.WriteFrame(frame, p);
            t.Send(frame, 0, n, SocketFlags.None);
            Thread.Sleep(1);
        }

        var sw = Stopwatch.StartNew();
        HubSnapshot s;
        do
        {
            Thread.Sleep(20);
            s = engine.GetSnapshot();
        }
        while (sw.Elapsed < Deadline && (s.Transport(TransportKind.Udp).Packets + s.Transport(TransportKind.Tcp).Packets < 800 || output.RisingEdges(0) < presses));

        Assert.Equal(400u, s.LastSeq);
        Assert.Equal(s.Accepted, (uint)(s.Transport(TransportKind.Udp).FirstArrivals + s.Transport(TransportKind.Tcp).FirstArrivals));
        Assert.Equal(s.Transport(TransportKind.Udp).Packets + s.Transport(TransportKind.Tcp).Packets - s.Accepted,
            s.Transport(TransportKind.Udp).Duplicates + s.Transport(TransportKind.Tcp).Duplicates);
        Assert.Equal(presses, output.RisingEdges(0));
        Assert.Equal(presses, s.PulsesEmitted[0]);
    }

    [Fact]
    public void Beacon_reaches_a_listener_with_the_current_fingerprint_and_name()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput());
        using var listener = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        listener.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        listener.ReceiveTimeout = 3000;
        int port = ((IPEndPoint)listener.LocalEndPoint!).Port;
        using var beacon = new BeaconBroadcaster(engine, () => "RACING-PC", 47800, 47802, port,
            includeBroadcast: false, extraTargets: new[] { new IPEndPoint(IPAddress.Loopback, port) });
        beacon.SendOnce();

        var buf = new byte[64];
        int n = listener.Receive(buf);
        Assert.Equal(Vectors.Root.GetProperty("beacon").GetProperty("hex").GetString(), Vectors.ToHex(buf.AsSpan(0, n)));

        engine.SetPairing(TestKeys.Other);
        beacon.SendOnce();
        n = listener.Receive(buf);
        Assert.True(Beacon.TryDecode(buf.AsSpan(0, n), out BeaconInfo? info));
        Assert.Equal(TestKeys.Other.FingerprintHex, info!.FingerprintHex);
    }

    [Fact]
    public void Directed_broadcast_is_address_or_not_mask()
    {
        Assert.Equal(IPAddress.Parse("192.168.1.255"), NetworkInfo.DirectedBroadcast(IPAddress.Parse("192.168.1.20"), IPAddress.Parse("255.255.255.0")));
        Assert.Equal(IPAddress.Parse("10.255.255.255"), NetworkInfo.DirectedBroadcast(IPAddress.Parse("10.0.0.4"), IPAddress.Parse("255.0.0.0")));
        Assert.Equal(IPAddress.Parse("192.168.43.63"), NetworkInfo.DirectedBroadcast(IPAddress.Parse("192.168.43.10"), IPAddress.Parse("255.255.255.192")));
        Assert.Null(NetworkInfo.DirectedBroadcast(IPAddress.Parse("10.1.1.1"), IPAddress.Parse("255.255.255.255")));
        Assert.Null(NetworkInfo.DirectedBroadcast(IPAddress.Parse("10.1.1.1"), IPAddress.Any));
    }
}
