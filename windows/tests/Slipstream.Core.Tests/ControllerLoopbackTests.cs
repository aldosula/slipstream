using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;

namespace Slipstream.Core.Tests;

/// <summary>Controller mode end to end over real sockets on ephemeral loopback ports, with the real clock and threads.</summary>
public class ControllerLoopbackTests
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

    [Fact]
    public void Udp_loopback_applies_pad_packets_to_the_pad_and_returns_status()
    {
        RecordingPadOutput? pad = null;
        using var engine = new HubEngine(TestKeys.Main, new RecordingOutput(), null, null, k => pad = new RecordingPadOutput(k));
        engine.Start();
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        server.Start();

        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        phone.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        phone.ReceiveTimeout = 100;
        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var sim = new FakePadPhone(TestKeys.Main, epoch: 0xCAB, playStation: true);
        sim.State.Lx = 16384;
        sim.State.R2 = 65535;

        var sw = Stopwatch.StartNew();
        StatusPacket? status = null;
        var rx = new byte[128];
        bool pressed = false;
        while (status is null && sw.Elapsed < Deadline)
        {
            for (int i = 0; i < 5; i++)
            {
                if (!pressed && sim.Seq > 3) { sim.Press(0); pressed = true; }
                phone.SendTo(sim.Next(sw.ElapsedTicks), hub);
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
        Assert.Equal(0xCABu, status!.Value.Epoch);
        Assert.Equal((byte)OutputKind.DualShock4, status.Value.Output);
        Assert.True(WaitFor(() => pad?.Last is not null && pad.Last.Value.Source.IsDown(PadButton.South)));
        PadOutputFrame last = pad!.Last!.Value;
        Assert.Equal(Mapping.Ds4Axis(16384), last.Ds4LeftX);
        Assert.Equal(255, last.Ds4RightTrigger);
        HubSnapshot snap = engine.GetSnapshot();
        Assert.Equal(LinkMode.Controller, snap.Mode);
        Assert.Equal(PadStyle.PlayStation, snap.Style);
        Assert.Equal(1, snap.TapsEmitted[0]);
        Assert.Equal(0, snap.Transport(TransportKind.Udp).Malformed);
    }

    [Fact]
    public void Tcp_loopback_applies_framed_pad_packets_and_returns_a_framed_status()
    {
        RecordingPadOutput? pad = null;
        using var engine = new HubEngine(TestKeys.Main, new RecordingOutput(), null, null, k => pad = new RecordingPadOutput(k));
        engine.Start();
        using var server = new TcpInputServer(engine, port: 0);
        server.Start();

        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
        var sim = new FakePadPhone(TestKeys.Main, epoch: 0xF00F, playStation: false);
        sim.State.Ly = -32767;
        sim.Press((int)PadButton.North);

        var frame = new byte[Wire.FrameHeaderLength + Wire.PadLength];
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
        Assert.Equal(0xF00Fu, status!.Value.Epoch);
        Assert.Equal((byte)OutputKind.Xbox360, status.Value.Output);
        Assert.True(WaitFor(() => pad?.Last is not null));
        PadOutputFrame last = pad!.Last!.Value;
        Assert.Equal(-32767, last.X360LeftThumbY);
        Assert.True(last.X360Buttons.HasFlag(X360Buttons.Y));
        HubSnapshot snap = engine.GetSnapshot();
        Assert.True(snap.Transport(TransportKind.Tcp).FirstArrivals >= 1);
        Assert.Equal(PadStyle.Xbox, snap.Style);
        Assert.Equal(1, server.ConnectionCount);
        Assert.Equal(0, server.ClosedForBadLength);
    }

    [Fact]
    public void Tcp_connection_carrying_both_packet_sizes_stays_open_and_an_odd_frame_closes_it()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, null, k => new NullPadOutput(k));
        engine.Start();
        using var server = new TcpInputServer(engine, port: 0);
        server.Start();
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        phone.Connect(new IPEndPoint(IPAddress.Loopback, server.LocalPort));
        phone.ReceiveTimeout = 3000;

        var wheel = new FakePhone(TestKeys.Main, epoch: 0x33);
        var pad = new FakePadPhone(TestKeys.Main, epoch: 0x33);
        var frame = new byte[2 + Wire.PadLength];
        for (int i = 0; i < 20; i++)
        {
            byte[] p;
            if (i % 2 == 0) { wheel.State.Seq = pad.Seq; p = wheel.Next(i); pad.Seq = wheel.Seq; }
            else p = pad.Next(i);
            int n = Framing.WriteFrame(frame, p);
            phone.Send(frame, 0, n, SocketFlags.None);
        }
        Assert.True(WaitFor(() => engine.GetSnapshot().Accepted == 20));
        Assert.Equal(1, server.ConnectionCount);

        var bad = new byte[2 + 53];
        BinaryPrimitives.WriteUInt16LittleEndian(bad, 53);
        phone.Send(bad);
        Assert.True(WaitFor(() => server.ConnectionCount == 0));
        Assert.Equal(1, server.ClosedForBadLength);
    }

    [Fact]
    public void Udp_pad_receive_path_from_socket_to_device_does_not_allocate()
    {
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, null, k => new NullPadOutput(k));
        using var server = new UdpInputServer(engine, port: 0, bindAddress: IPAddress.Loopback);
        using var phone = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        var hub = new IPEndPoint(IPAddress.Loopback, server.LocalPort);
        var sim = new FakePadPhone(TestKeys.Main, epoch: 43);
        var ctx = new UdpInputServer.ReceiveContext();

        void Batch(int count)
        {
            for (int i = 0; i < count; i++)
            {
                if (i % 25 == 0) sim.Press(i % 18);
                if (i % 25 == 3) sim.Release(i % 18 == 0 ? 17 : (i - 3) % 18);
                if (i % 50 == 7) sim.Tap(i % 18, 2);
                sim.State.Rx = (short)(i * 31);
                phone.SendTo(sim.Next(i), hub);
            }
        }

        Batch(200);
        for (int i = 0; i < 200; i++) Assert.True(server.ReceiveOnce(ctx)); // warm up: JIT, first peer, the pad device
        Batch(1000);
        long before = GC.GetAllocatedBytesForCurrentThread();
        for (int i = 0; i < 1000; i++) server.ReceiveOnce(ctx);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;

        Assert.Equal(0, allocated);
        Assert.Equal(1200u, engine.GetSnapshot().Accepted);
        Assert.Null(server.LastError);
    }
}
