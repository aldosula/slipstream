using System.Text.Json;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>Every entry of docs/test-vectors.json, reproduced byte for byte.</summary>
public class ProtocolVectorTests
{
    private static JsonElement PairingV => Vectors.Root.GetProperty("pairing");
    private static PairingKey Key => PairingKey.FromCode(PairingV.GetProperty("code").GetString()!);

    [Fact]
    public void Protocol_name_is_SLP1() => Assert.Equal("SLP/1", Vectors.Root.GetProperty("protocol").GetString());

    // ---------------------------------------------------------------- pairing ---

    [Fact]
    public void Pairing_raw_key_fingerprint_and_display_match()
    {
        string code = PairingV.GetProperty("code").GetString()!;
        Assert.Equal(PairingV.GetProperty("raw_hex").GetString(), Vectors.ToHex(Pairing.Base32Decode(code)));
        byte[] key = Pairing.DeriveKey(code);
        Assert.Equal(PairingV.GetProperty("key_hex").GetString(), Vectors.ToHex(key));
        Assert.Equal(PairingV.GetProperty("fingerprint_hex").GetString(), Vectors.ToHex(Pairing.Fingerprint(key)));
        Assert.Equal(PairingV.GetProperty("fingerprint_hex").GetString(), Key.FingerprintHex);
        Assert.Equal(PairingV.GetProperty("display").GetString(), Pairing.Display(code));
        Assert.Equal(PairingV.GetProperty("display").GetString(), Key.DisplayCode);
    }

    public static IEnumerable<object?[]> NormalizeCases()
        => PairingV.GetProperty("normalize").EnumerateArray()
            .Select(e => new object?[] { e.GetProperty("in").GetString(), e.GetProperty("out").ValueKind == JsonValueKind.Null ? null : e.GetProperty("out").GetString() });

    [Theory]
    [MemberData(nameof(NormalizeCases))]
    public void Normalize_matches_vector(string input, string? expected)
        => Assert.Equal(expected, Pairing.Normalize(input));

    [Fact]
    public void Normalize_rejects_null_and_matches_python_full_case_mapping()
    {
        Assert.Null(Pairing.Normalize(null));
        Assert.Null(Pairing.Normalize(""));
        // Python's str.upper() expands sharp s to "SS"; the reference therefore accepts it.
        Assert.Equal("SSLIPSTREAMTEST2", Pairing.Normalize("ßLIPSTREAMTEST2"));
        Assert.Equal("FIIPSTREAMTEST22", Pairing.Normalize("ﬁIPSTREAMTEST22"));
        Assert.Null(Pairing.Normalize("SLIPSTREAMTEST2é"));
    }

    [Fact]
    public void Generated_codes_are_valid_distinct_and_round_trip()
    {
        var seen = new HashSet<string>();
        for (int i = 0; i < 200; i++)
        {
            string code = Pairing.GenerateCode();
            Assert.Equal(16, code.Length);
            Assert.Equal(code, Pairing.Normalize(code));
            Assert.Equal(code, Pairing.Base32Encode(Pairing.Base32Decode(code)));
            Assert.True(seen.Add(code));
        }
    }

    [Fact]
    public void Pair_uri_follows_section_2()
    {
        string uri = Pairing.BuildPairUri("abcd-efgh-ijkl-mnop", "RACING-PC", 47800, 47802, new[] { "192.168.1.20", "10.0.0.4" });
        Assert.Equal("slipstream://pair?v=1&code=ABCDEFGHIJKLMNOP&name=RACING-PC&port=47800&tcp=47802&host=192.168.1.20&host=10.0.0.4", uri);
        Assert.Contains("name=My%20PC", Pairing.BuildPairUri("ABCDEFGHIJKLMNOP", "My PC", 1, 2, Array.Empty<string>()));
    }

    [Fact]
    public void Hub_name_is_truncated_to_32_utf8_bytes_on_a_character_boundary()
    {
        Assert.Equal("RACING-PC", Pairing.TruncateName("RACING-PC"));
        Assert.Equal(new string('A', 32), Pairing.TruncateName(new string('A', 40)));
        string accented = new string('é', 20); // 2 bytes each
        string t = Pairing.TruncateName(accented);
        Assert.Equal(16, t.Length);
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(t) <= 32);
    }

    // ---------------------------------------------------------------- packets ---

    private static InputPacket InputFrom(JsonElement f)
    {
        var p = new InputPacket
        {
            Epoch = f.U32("epoch"),
            Seq = f.U32("seq"),
            TimeUs = f.U32("t_us"),
            Steer = (short)f.I32("steer"),
            Throttle = (ushort)f.I32("throttle"),
            Brake = (ushort)f.I32("brake"),
            Clutch = (ushort)f.I32("clutch"),
            Handbrake = (ushort)f.I32("handbrake"),
            Aux = (ushort)f.I32("aux"),
            Buttons = f.U32("buttons"),
            Flags = (byte)f.I32("flags"),
            Rtt100us = (ushort)f.I32("rtt_100us"),
        };
        byte[] pulses = f.GetProperty("pulses").EnumerateArray().Select(x => (byte)x.GetInt32()).ToArray();
        p.Pulses = InputPacket.PackPulses(pulses);
        return p;
    }

    public static IEnumerable<object[]> InputIndexes() => Vectors.Array("input").Select((_, i) => new object[] { i });
    public static IEnumerable<object[]> StatusIndexes() => Vectors.Array("status").Select((_, i) => new object[] { i });

    [Theory]
    [MemberData(nameof(InputIndexes))]
    public void Input_encode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("input").ElementAt(index);
        var buf = new byte[Wire.InputLength];
        InputFrom(v.GetProperty("fields")).Encode(buf, Key.Auth);
        Assert.Equal(v.GetProperty("hex").GetString(), Vectors.ToHex(buf));
    }

    [Theory]
    [MemberData(nameof(InputIndexes))]
    public void Input_decode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("input").ElementAt(index);
        JsonElement f = v.GetProperty("fields");
        Assert.Equal(DecodeResult.Ok, InputPacket.TryDecode(Vectors.Hex(v.GetProperty("hex").GetString()!), Key.Auth, out InputPacket p));
        InputPacket expected = InputFrom(f);
        Assert.Equal(expected.Epoch, p.Epoch);
        Assert.Equal(expected.Seq, p.Seq);
        Assert.Equal(expected.TimeUs, p.TimeUs);
        Assert.Equal(expected.Steer, p.Steer);
        Assert.Equal(expected.Throttle, p.Throttle);
        Assert.Equal(expected.Brake, p.Brake);
        Assert.Equal(expected.Clutch, p.Clutch);
        Assert.Equal(expected.Handbrake, p.Handbrake);
        Assert.Equal(expected.Aux, p.Aux);
        Assert.Equal(expected.Buttons, p.Buttons);
        Assert.Equal(expected.Flags, p.Flags);
        Assert.Equal(expected.Rtt100us, p.Rtt100us);
        int[] pulses = f.GetProperty("pulses").EnumerateArray().Select(x => x.GetInt32()).ToArray();
        for (int ch = 0; ch < 8; ch++) Assert.Equal(pulses[ch], p.GetPulse(ch));
        Assert.Equal((f.I32("flags") & 1) != 0, p.Paused);
        Assert.Equal((f.I32("flags") & 2) != 0, p.Calibrating);
        Assert.Equal((f.I32("flags") & 4) != 0, p.Multipath);
    }

    private static StatusPacket StatusFrom(JsonElement f) => new()
    {
        Epoch = f.U32("epoch"),
        LastSeq = f.U32("last_seq"),
        EchoTimeUs = f.U32("echo_t_us"),
        HoldUs = f.U32("hold_us"),
        Accepted = f.U32("accepted"),
        Missing = f.U32("missing"),
        RumbleStrong = (ushort)f.I32("rumble_strong"),
        RumbleWeak = (ushort)f.I32("rumble_weak"),
        Output = (byte)f.I32("output"),
        HubFlags = (byte)f.I32("hub_flags"),
    };

    [Theory]
    [MemberData(nameof(StatusIndexes))]
    public void Status_encode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("status").ElementAt(index);
        var buf = new byte[Wire.StatusLength];
        StatusFrom(v.GetProperty("fields")).Encode(buf, Key.Auth);
        Assert.Equal(v.GetProperty("hex").GetString(), Vectors.ToHex(buf));
    }

    [Theory]
    [MemberData(nameof(StatusIndexes))]
    public void Status_decode_matches_vector(int index)
    {
        JsonElement v = Vectors.Array("status").ElementAt(index);
        Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(Vectors.Hex(v.GetProperty("hex").GetString()!), Key.Auth, out StatusPacket p));
        Assert.Equal(StatusFrom(v.GetProperty("fields")), p);
    }

    [Fact]
    public void Beacon_encode_and_decode_match_vector()
    {
        JsonElement b = Vectors.Root.GetProperty("beacon");
        JsonElement f = b.GetProperty("fields");
        var buf = new byte[Wire.BeaconMaxLength];
        int n = Beacon.Encode(buf, (ushort)f.I32("udp_port"), (ushort)f.I32("tcp_port"), Key.Fingerprint, f.GetProperty("name").GetString()!);
        Assert.Equal(b.GetProperty("hex").GetString(), Vectors.ToHex(buf.AsSpan(0, n)));

        Assert.True(Beacon.TryDecode(Vectors.Hex(b.GetProperty("hex").GetString()!), out BeaconInfo? info));
        Assert.Equal((ushort)f.I32("udp_port"), info!.UdpPort);
        Assert.Equal((ushort)f.I32("tcp_port"), info.TcpPort);
        Assert.Equal(f.GetProperty("name").GetString(), info.Name);
        Assert.Equal(PairingV.GetProperty("fingerprint_hex").GetString(), info.FingerprintHex);
    }

    [Fact]
    public void Beacon_decode_rejects_bad_lengths()
    {
        byte[] good = Vectors.Hex(Vectors.Root.GetProperty("beacon").GetProperty("hex").GetString()!);
        Assert.False(Beacon.TryDecode(good.AsSpan(0, good.Length - 1), out _));
        Assert.False(Beacon.TryDecode(good.Concat(new byte[] { 0 }).ToArray(), out _));
        byte[] wrongType = (byte[])good.Clone();
        wrongType[3] = Wire.TypeInput;
        Assert.False(Beacon.TryDecode(wrongType, out _));
    }

    [Fact]
    public void Frame_matches_vector_and_reads_back()
    {
        JsonElement fr = Vectors.Root.GetProperty("frame");
        byte[] packet = Vectors.Hex(fr.GetProperty("packet_hex").GetString()!);
        var buf = new byte[Wire.FrameHeaderLength + packet.Length];
        int n = Framing.WriteFrame(buf, packet);
        Assert.Equal(fr.GetProperty("hex").GetString(), Vectors.ToHex(buf.AsSpan(0, n)));

        var read = new byte[Wire.InputLength];
        Assert.Equal(Framing.ReadResult.Ok, Framing.ReadFrame(new MemoryStream(buf), read, Wire.InputLength));
        Assert.Equal(packet, read);
        Assert.Equal(Framing.ReadResult.BadLength, Framing.ReadFrame(new MemoryStream(buf), read, Wire.StatusLength));
        Assert.Equal(Framing.ReadResult.Closed, Framing.ReadFrame(new MemoryStream(buf, 0, 10), read, Wire.InputLength));
    }

    [Fact]
    public void Tampered_packet_fails_the_tag()
    {
        byte[] tampered = Vectors.Hex(Vectors.Root.GetProperty("tamper").GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.BadTag, InputPacket.TryDecode(tampered, Key.Auth, out InputPacket p));
        Assert.Equal(default, p); // nothing leaks out of a rejected packet
    }

    [Fact]
    public void Wrong_key_header_or_length_is_rejected()
    {
        byte[] good = Vectors.Hex(Vectors.Array("input").First().GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.Ok, InputPacket.TryDecode(good, Key.Auth, out _));
        Assert.Equal(DecodeResult.BadTag, InputPacket.TryDecode(good, PairingKey.FromCode("ABCDEFGHIJKLMNOP").Auth, out _));
        Assert.Equal(DecodeResult.Malformed, InputPacket.TryDecode(good.AsSpan(0, 51), Key.Auth, out _));
        Assert.Equal(DecodeResult.Malformed, InputPacket.TryDecode(good.Concat(new byte[] { 0 }).ToArray(), Key.Auth, out _));
        foreach (int offset in new[] { 0, 1, 2, 3 })
        {
            byte[] bad = (byte[])good.Clone();
            bad[offset] ^= 0xFF;
            Assert.Equal(DecodeResult.Malformed, InputPacket.TryDecode(bad, Key.Auth, out _));
        }
        byte[] status = Vectors.Hex(Vectors.Array("status").First().GetProperty("hex").GetString()!);
        Assert.Equal(DecodeResult.Malformed, InputPacket.TryDecode(status, Key.Auth, out _));
        Assert.Equal(DecodeResult.Malformed, StatusPacket.TryDecode(good, Key.Auth, out _));
    }

    // ---------------------------------------------------------------- helpers ---

    public static IEnumerable<object[]> SeqNewerCases()
        => Vectors.Array("seq_newer").Select(e => new object[] { e.U32("a"), e.U32("b"), e.GetProperty("newer").GetBoolean() });

    [Theory]
    [MemberData(nameof(SeqNewerCases))]
    public void Seq_newer_matches_vector(uint a, uint b, bool newer) => Assert.Equal(newer, SeqMath.SeqNewer(a, b));

    public static IEnumerable<object[]> PulseDeltaCases()
        => Vectors.Array("pulse_delta").Select(e => new object[] { e.I32("new"), e.I32("old"), e.I32("presses") });

    [Theory]
    [MemberData(nameof(PulseDeltaCases))]
    public void Pulse_delta_matches_vector(int newValue, int oldValue, int presses)
        => Assert.Equal(presses, SeqMath.PulseDelta((byte)newValue, (byte)oldValue));

    public static IEnumerable<object[]> Map(string name)
        => Vectors.Array(name).Select(e => new object[] { e.I32("in"), e.I32("out") });

    [Theory]
    [MemberData(nameof(Map), "map_vjoy_steer")]
    public void Map_vjoy_steer_matches_vector(int input, int output) => Assert.Equal(output, Mapping.VJoySteer((short)input));

    [Theory]
    [MemberData(nameof(Map), "map_vjoy_pedal")]
    public void Map_vjoy_pedal_matches_vector(int input, int output) => Assert.Equal(output, Mapping.VJoyPedal((ushort)input));

    [Theory]
    [MemberData(nameof(Map), "map_x360_trigger")]
    public void Map_x360_trigger_matches_vector(int input, int output) => Assert.Equal(output, Mapping.X360Trigger((ushort)input));

    [Theory]
    [MemberData(nameof(Map), "map_hid_steer")]
    public void Map_hid_steer_matches_vector(int input, int output) => Assert.Equal(output, Mapping.HidSteer((short)input));

    [Fact]
    public void Every_vector_section_is_covered_by_a_test()
    {
        var covered = new HashSet<string>
        {
            "protocol", "note", "pairing", "input", "status", "beacon", "frame", "tamper",
            "seq_newer", "pulse_delta", "map_vjoy_steer", "map_vjoy_pedal", "map_x360_trigger", "map_hid_steer",
            // Controller mode, read by PadVectorTests.
            "pad", "pad_frame", "pad_tamper", "tap_delta", "tap_schedule",
            "map_ds4_axis", "map_ds4_axis_y", "map_ds4_trigger_digital",
        };
        foreach (JsonProperty p in Vectors.Root.EnumerateObject())
            Assert.True(covered.Contains(p.Name), $"test-vectors.json has a section '{p.Name}' that no test reads.");
    }
}
