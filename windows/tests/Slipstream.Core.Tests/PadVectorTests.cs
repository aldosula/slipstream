using System.Buffers.Binary;
using System.Text.Json;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>The controller-mode entries of docs/test-vectors.json (PROTOCOL.md section 12), byte for byte.</summary>
public class PadVectorTests
{
    private static PairingKey Key => PairingKey.FromCode(Vectors.Root.GetProperty("pairing").GetProperty("code").GetString()!);

    private static PadPacket PadFrom(JsonElement f)
    {
        JsonElement[] touch = f.GetProperty("touch").EnumerateArray().ToArray();
        short[] gyro = f.GetProperty("gyro").EnumerateArray().Select(x => (short)x.GetInt32()).ToArray();
        short[] accel = f.GetProperty("accel").EnumerateArray().Select(x => (short)x.GetInt32()).ToArray();
        var p = new PadPacket
        {
            Epoch = f.U32("epoch"),
            Seq = f.U32("seq"),
            TimeUs = f.U32("t_us"),
            Lx = (short)f.I32("lx"),
            Ly = (short)f.I32("ly"),
            Rx = (short)f.I32("rx"),
            Ry = (short)f.I32("ry"),
            L2 = (ushort)f.I32("l2"),
            R2 = (ushort)f.I32("r2"),
            Buttons = f.U32("buttons"),
            Flags = (byte)f.I32("flags"),
            Rtt100us = (ushort)f.I32("rtt_100us"),
            Touch0X = (ushort)touch[0].I32("x"),
            Touch0Y = (ushort)touch[0].I32("y"),
            Touch1X = (ushort)touch[1].I32("x"),
            Touch1Y = (ushort)touch[1].I32("y"),
            Touch0Id = TouchId(touch[0]),
            Touch1Id = TouchId(touch[1]),
            GyroX = gyro[0],
            GyroY = gyro[1],
            GyroZ = gyro[2],
            AccelX = accel[0],
            AccelY = accel[1],
            AccelZ = accel[2],
        };
        p.SetTaps(f.GetProperty("taps").EnumerateArray().Select(x => (byte)x.GetInt32()).ToArray());
        return p;
    }

    private static byte TouchId(JsonElement t)
        => (byte)((t.GetProperty("active").GetBoolean() ? 0x80 : 0) | (t.I32("id") & 0x7F));

    public static IEnumerable<object[]> PadIndexes() => Vectors.Array("pad").Select((_, i) => new object[] { i });

    [Theory]
    [MemberData(nameof(PadIndexes))]
    public void Pad_encode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("pad").ElementAt(index);
        var buf = new byte[Wire.PadLength];
        PadFrom(v.GetProperty("fields")).Encode(buf, Key.Auth);
        Assert.Equal(v.GetProperty("hex").GetString(), Vectors.ToHex(buf));
    }

    [Theory]
    [MemberData(nameof(PadIndexes))]
    public void Pad_decode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("pad").ElementAt(index);
        JsonElement f = v.GetProperty("fields");
        Assert.Equal(DecodeResult.Ok, PadPacket.TryDecode(Vectors.Hex(v.GetProperty("hex").GetString()!), Key.Auth, out PadPacket p));
        Assert.Equal(PadFrom(f), p);

        int[] taps = f.GetProperty("taps").EnumerateArray().Select(x => x.GetInt32()).ToArray();
        for (int b = 0; b < Wire.PadButtons; b++) Assert.Equal(taps[b], p.GetTap(b));
        JsonElement[] touch = f.GetProperty("touch").EnumerateArray().ToArray();
        Assert.Equal(touch[0].GetProperty("active").GetBoolean(), (p.Touch0Id & PadPacket.TouchActive) != 0);
        Assert.Equal(touch[1].GetProperty("id").GetInt32(), p.Touch1Id & 0x7F);
        int flags = f.I32("flags");
        Assert.Equal((flags & 0x01) != 0, p.Paused);
        Assert.Equal((flags & 0x04) != 0, p.Multipath);
        Assert.Equal((flags & 0x08) != 0, p.Motion);
        Assert.Equal((flags & 0x10) != 0, p.StylePs);
        Assert.Equal(0, p.Reserved);
    }

    [Fact]
    public void Pad_taps_are_nibbles_low_first_and_counters_wrap_at_16()
    {
        var p = new PadPacket();
        p.SetTap(0, 1);
        p.SetTap(1, 2);
        p.SetTap(16, 3);
        p.SetTap(17, 4);
        var buf = new byte[Wire.PadLength];
        p.Encode(buf, Key.Auth);
        Assert.Equal(0x21, buf[32]); // button 0 low nibble, button 1 high nibble
        Assert.Equal(0x43, buf[40]); // buttons 16 and 17
        p.SetTap(0, 15 + 1);         // 16 wraps to 0
        Assert.Equal(0, p.GetTap(0));
        Assert.Equal(2, p.GetTap(1));
    }

    [Fact]
    public void Pad_frame_matches_vector_and_the_hub_reads_both_packet_sizes()
    {
        JsonElement fr = Vectors.Root.GetProperty("pad_frame");
        byte[] packet = Vectors.Hex(fr.GetProperty("packet_hex").GetString()!);
        Assert.Equal(Wire.PadLength, packet.Length);
        var buf = new byte[Wire.FrameHeaderLength + packet.Length];
        int n = Framing.WriteFrame(buf, packet);
        Assert.Equal(fr.GetProperty("hex").GetString(), Vectors.ToHex(buf.AsSpan(0, n)));

        var read = new byte[Wire.PadLength];
        Assert.Equal(Framing.ReadResult.Ok, Framing.ReadHubFrame(new MemoryStream(buf), read, out int length));
        Assert.Equal(Wire.PadLength, length);
        Assert.Equal(packet, read);

        // An INPUT frame reads too, a STATUS-sized or odd frame does not (section 8, hub side).
        byte[] input = Vectors.Hex(Vectors.Root.GetProperty("frame").GetProperty("hex").GetString()!);
        Assert.Equal(Framing.ReadResult.Ok, Framing.ReadHubFrame(new MemoryStream(input), read, out length));
        Assert.Equal(Wire.InputLength, length);
        foreach (int bad in new[] { 44, 53, 75, 77, 0 })
        {
            var f = new byte[2 + 80];
            BinaryPrimitives.WriteUInt16LittleEndian(f, (ushort)bad);
            Assert.Equal(Framing.ReadResult.BadLength, Framing.ReadHubFrame(new MemoryStream(f), read, out _));
        }
        Assert.Equal(Framing.ReadResult.Closed, Framing.ReadHubFrame(new MemoryStream(buf, 0, 40), read, out _));
    }

    [Fact]
    public void Pad_tampered_packet_fails_the_tag()
    {
        byte[] tampered = Vectors.Hex(Vectors.Root.GetProperty("pad_tamper").GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.BadTag, PadPacket.TryDecode(tampered, Key.Auth, out PadPacket p));
        Assert.Equal(default, p);
    }

    [Fact]
    public void Pad_wrong_key_header_or_length_is_rejected()
    {
        byte[] good = Vectors.Hex(Vectors.Array("pad").First().GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.Ok, PadPacket.TryDecode(good, Key.Auth, out _));
        Assert.Equal(DecodeResult.BadTag, PadPacket.TryDecode(good, TestKeys.Other.Auth, out _));
        Assert.Equal(DecodeResult.Malformed, PadPacket.TryDecode(good.AsSpan(0, 75), Key.Auth, out _));
        Assert.Equal(DecodeResult.Malformed, PadPacket.TryDecode(good.Concat(new byte[] { 0 }).ToArray(), Key.Auth, out _));
        byte[] wrongType = (byte[])good.Clone();
        wrongType[3] = Wire.TypeInput;
        Assert.Equal(DecodeResult.Malformed, PadPacket.TryDecode(wrongType, Key.Auth, out _));
        byte[] input = Vectors.Hex(Vectors.Array("input").First().GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.Malformed, PadPacket.TryDecode(input, Key.Auth, out _));
        Assert.Equal(DecodeResult.Malformed, InputPacket.TryDecode(good, Key.Auth, out _));
    }

    public static IEnumerable<object[]> TapDeltaCases()
        => Vectors.Array("tap_delta").Select(e => new object[] { e.I32("new"), e.I32("old"), e.I32("taps") });

    [Theory]
    [MemberData(nameof(TapDeltaCases))]
    public void Tap_delta_matches_vector(int newValue, int oldValue, int taps)
        => Assert.Equal(taps, SeqMath.TapDelta(newValue, oldValue));

    public static IEnumerable<object[]> TapScheduleCases()
        => Vectors.Array("tap_schedule").Select(e => new object[]
        {
            e.GetProperty("output_down").GetBoolean(), e.GetProperty("held").GetBoolean(), e.I32("d"),
            e.GetProperty("gap_first").GetBoolean(), e.I32("replay_taps"),
        });

    [Theory]
    [MemberData(nameof(TapScheduleCases))]
    public void Tap_schedule_matches_vector(bool outputDown, bool held, int d, bool gapFirst, int replayTaps)
    {
        Assert.Equal((gapFirst, replayTaps), TapScheduler.Plan(outputDown, held, d));

        // And the scheduler itself: what it starts for one button on one packet.
        var s = new TapScheduler(50_000, 40_000);
        s.OnPacket(0, d, held, outputDown, nowUs: 1000);
        bool scheduled = gapFirst || replayTaps > 0;
        Assert.Equal(scheduled, s.IsActive(0));
        Assert.Equal(!gapFirst && replayTaps > 0, (s.DownMask & 1) != 0); // a tap starts at once unless the gap comes first
        Assert.Equal(replayTaps - (!gapFirst && replayTaps > 0 ? 1 : 0), s.Pending(0));
    }

    [Theory]
    [MemberData(nameof(ProtocolVectorTests.Map), "map_ds4_axis", MemberType = typeof(ProtocolVectorTests))]
    public void Map_ds4_axis_matches_vector(int input, int output) => Assert.Equal(output, Mapping.Ds4Axis((short)input));

    [Theory]
    [MemberData(nameof(ProtocolVectorTests.Map), "map_ds4_axis_y", MemberType = typeof(ProtocolVectorTests))]
    public void Map_ds4_axis_y_matches_vector(int input, int output) => Assert.Equal(output, Mapping.Ds4AxisY((short)input));

    public static IEnumerable<object[]> TriggerDigitalCases()
        => Vectors.Array("map_ds4_trigger_digital").Select(e => new object[] { e.I32("in"), e.GetProperty("out").GetBoolean() });

    [Theory]
    [MemberData(nameof(TriggerDigitalCases))]
    public void Map_ds4_trigger_digital_matches_vector(int input, bool output)
    {
        Assert.Equal(output, Mapping.Ds4TriggerDigital((ushort)input));
        // The same threshold lands in the wButtons word as L2 and R2.
        ushort w = Mapping.Ds4ButtonsOf(0, (ushort)input, (ushort)input);
        Assert.Equal(output, (w & Mapping.Ds4L2) != 0);
        Assert.Equal(output, (w & Mapping.Ds4R2) != 0);
    }
}
