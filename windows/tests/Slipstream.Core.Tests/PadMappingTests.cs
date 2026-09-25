using Slipstream.Core.Config;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>PROTOCOL.md 12.5 beyond the numeric vectors: button routing, hat, touch, motion, the DualShock 4 report.</summary>
public class PadMappingTests
{
    private static PadOutputFrame Map(uint buttons = 0, ushort l2 = 0, ushort r2 = 0) => Mapping.MapPad(new PadFrame { Buttons = buttons, L2 = l2, R2 = r2 });

    [Fact]
    public void X360_sticks_go_straight_and_triggers_are_shifted()
    {
        PadOutputFrame f = Mapping.MapPad(new PadFrame { Lx = -32767, Ly = 32767, Rx = 12345, Ry = -23456, L2 = 65535, R2 = 4096 });
        Assert.Equal(-32767, f.X360LeftThumbX);
        Assert.Equal(32767, f.X360LeftThumbY); // XInput +y is up, like PAD
        Assert.Equal(12345, f.X360RightThumbX);
        Assert.Equal(-23456, f.X360RightThumbY);
        Assert.Equal(255, f.X360LeftTrigger);
        Assert.Equal(16, f.X360RightTrigger);
    }

    [Fact]
    public void X360_buttons_follow_the_table_and_bits_11_16_17_are_not_sent()
    {
        X360Buttons[] expected =
        {
            X360Buttons.A, X360Buttons.B, X360Buttons.X, X360Buttons.Y, X360Buttons.LeftShoulder, X360Buttons.RightShoulder,
            X360Buttons.LeftThumb, X360Buttons.RightThumb, X360Buttons.Back, X360Buttons.Start, X360Buttons.Guide, X360Buttons.None,
            X360Buttons.DPadUp, X360Buttons.DPadDown, X360Buttons.DPadLeft, X360Buttons.DPadRight, X360Buttons.None, X360Buttons.None,
        };
        for (int b = 0; b < Wire.PadButtons; b++) Assert.Equal(expected[b], Map(1u << b).X360Buttons);
        Assert.Equal(X360Buttons.None, Map(0xFFFC_0000).X360Buttons); // reserved bits
    }

    [Fact]
    public void Ds4_buttons_follow_the_table()
    {
        ushort[] expected =
        {
            Mapping.Ds4Cross, Mapping.Ds4Circle, Mapping.Ds4Square, Mapping.Ds4Triangle, Mapping.Ds4L1, Mapping.Ds4R1,
            Mapping.Ds4L3, Mapping.Ds4R3, Mapping.Ds4Share, Mapping.Ds4Options,
        };
        for (int b = 0; b < expected.Length; b++)
        {
            PadOutputFrame f = Map(1u << b);
            Assert.Equal(expected[b] | Mapping.Ds4HatNone, f.Ds4Buttons);
            Assert.Equal(0, f.Ds4Special);
        }
        Assert.Equal(Mapping.Ds4Ps, Map(1u << 10).Ds4Special);
        Assert.Equal(Mapping.Ds4TouchpadClick, Map(1u << 11).Ds4Special);
        // Mute (16) and the Xbox Share (17) are not sent.
        Assert.Equal(Mapping.Ds4HatNone, Map(1u << 16 | 1u << 17).Ds4Buttons);
        Assert.Equal(0, Map(1u << 16 | 1u << 17).Ds4Special);
        // The values ViGEm expects (Nefarius.ViGEm.Client DualShock4Button, read from its metadata).
        Assert.Equal(0x0010, Mapping.Ds4Square);
        Assert.Equal(0x0020, Mapping.Ds4Cross);
        Assert.Equal(0x0040, Mapping.Ds4Circle);
        Assert.Equal(0x0080, Mapping.Ds4Triangle);
        Assert.Equal(0x8000, Mapping.Ds4R3);
    }

    [Fact]
    public void Ds4_hat_has_eight_directions_and_opposite_directions_cancel()
    {
        const uint U = 1u << 12, D = 1u << 13, L = 1u << 14, R = 1u << 15;
        Assert.Equal(8, Mapping.Ds4Hat(0));
        Assert.Equal(0, Mapping.Ds4Hat(U));
        Assert.Equal(1, Mapping.Ds4Hat(U | R));
        Assert.Equal(2, Mapping.Ds4Hat(R));
        Assert.Equal(3, Mapping.Ds4Hat(D | R));
        Assert.Equal(4, Mapping.Ds4Hat(D));
        Assert.Equal(5, Mapping.Ds4Hat(D | L));
        Assert.Equal(6, Mapping.Ds4Hat(L));
        Assert.Equal(7, Mapping.Ds4Hat(U | L));
        Assert.Equal(8, Mapping.Ds4Hat(U | D));
        Assert.Equal(8, Mapping.Ds4Hat(L | R));
        Assert.Equal(8, Mapping.Ds4Hat(U | D | L | R));
        Assert.Equal(2, Mapping.Ds4Hat(U | D | R)); // up and down cancel, right remains
        Assert.Equal(0, Mapping.Ds4Hat(U | L | R));
        Assert.Equal(6, Map(L | (1u << 0)).Ds4Buttons & 0x0F);
    }

    [Fact]
    public void Ds4_sticks_invert_y_and_triggers_set_the_digital_bits()
    {
        PadOutputFrame f = Mapping.MapPad(new PadFrame { Lx = -32767, Ly = 32767, Rx = 32767, Ry = -32767, L2 = 2047, R2 = 2048 });
        Assert.Equal(0, f.Ds4LeftX);
        Assert.Equal(0, f.Ds4LeftY);    // up is 0 on the DualShock 4
        Assert.Equal(255, f.Ds4RightX);
        Assert.Equal(255, f.Ds4RightY);
        Assert.Equal(7, f.Ds4LeftTrigger);
        Assert.Equal(8, f.Ds4RightTrigger);
        Assert.Equal(0, f.Ds4Buttons & Mapping.Ds4L2);
        Assert.Equal(Mapping.Ds4R2, f.Ds4Buttons & Mapping.Ds4R2);
        PadOutputFrame n = Mapping.MapPad(PadFrame.Neutral);
        Assert.Equal(128, n.Ds4LeftX);
        Assert.Equal(128, n.Ds4LeftY);
        Assert.Equal(Mapping.Ds4HatNone, n.Ds4Buttons);
        Assert.Equal(255, Mapping.Ds4AxisY(short.MinValue)); // -32768 is clamped to -32767, never overflows
        Assert.Equal(0, Mapping.Ds4Axis(short.MinValue));
    }

    [Fact]
    public void Ds4_touch_is_scaled_to_1919_by_942_with_an_active_low_finger_bit()
    {
        PadOutputFrame f = Mapping.MapPad(new PadFrame
        {
            Touch0X = 65535, Touch0Y = 0, Touch0Id = 0x80 | 5,
            Touch1X = 32768, Touch1Y = 65535, Touch1Id = 127,
        });
        Assert.Equal(1919, f.Ds4Touch0X);
        Assert.Equal(0, f.Ds4Touch0Y);
        Assert.Equal(959, f.Ds4Touch1X);   // 32768 * 1919 div 65535
        Assert.Equal(942, f.Ds4Touch1Y);
        Assert.Equal(5, f.Ds4Touch0Id);    // down: bit 7 clear on the DualShock 4
        Assert.Equal(0x80 | 127, f.Ds4Touch1Id);
        Assert.Equal(0, Mapping.Ds4TouchX(0));
        Assert.Equal(471, Mapping.Ds4TouchY(32768));
    }

    [Fact]
    public void Ds4_motion_is_rescaled_to_its_own_units_and_only_sent_with_motion()
    {
        var src = new PadFrame { GyroX = -32767, GyroY = 1600, GyroZ = 32767, AccelX = 0, AccelY = -4096, AccelZ = 20000, Motion = true };
        PadOutputFrame f = Mapping.MapPad(src);
        Assert.Equal(-32767, f.Ds4GyroX);  // 16 counts per dps on both sides
        Assert.Equal(1600, f.Ds4GyroY);    // 100 dps
        Assert.Equal(32767, f.Ds4GyroZ);
        Assert.Equal(0, f.Ds4AccelX);
        Assert.Equal(-8192, f.Ds4AccelY);  // -1 g: 4096 per g in, 8192 per g out
        Assert.Equal(32767, f.Ds4AccelZ);  // saturates at +4 g
        Assert.Equal(Mapping.Ds4GyroLsbPerDps, Mapping.PadGyroLsbPerDps);
        Assert.Equal(2 * Mapping.PadAccelLsbPerG, Mapping.Ds4AccelLsbPerG);

        PadOutputFrame none = Mapping.MapPad(src with { Motion = false });
        Assert.Equal(0, none.Ds4GyroY);
        Assert.Equal(0, none.Ds4AccelY);
    }

    [Fact]
    public void Ds4_timestamp_counts_in_16_thirds_of_a_microsecond_and_wraps_cleanly()
    {
        Assert.Equal(0, Mapping.Ds4Timestamp(0));
        Assert.Equal(187, Mapping.Ds4Timestamp(1000));       // 1 ms is 187.5 units
        Assert.Equal(375, Mapping.Ds4Timestamp(2000));
        // The u32 phone clock wraps at a whole number of 16 bit periods: no jump at the wrap.
        ushort before = Mapping.Ds4Timestamp(uint.MaxValue - 15);
        ushort after = Mapping.Ds4Timestamp(0);
        Assert.Equal(3, (ushort)(after - before));
    }

    [Fact]
    public void Ds4_extended_report_has_the_documented_layout()
    {
        var src = new PadFrame
        {
            Lx = 32767, Ly = 32767, Rx = -32767, Ry = 0, L2 = 65535, R2 = 1000,
            Buttons = (1u << 0) | (1u << 3) | (1u << 4) | (1u << 10) | (1u << 11) | (1u << 12) | (1u << 15),
            Touch0X = 65535, Touch0Y = 65535, Touch0Id = 0x80 | 9, Touch1X = 0, Touch1Y = 0, Touch1Id = 3,
            GyroX = 16, GyroY = -16, GyroZ = 1000, AccelX = 4096, AccelY = -1, AccelZ = 0, Motion = true, TimeUs = 1000,
        };
        PadOutputFrame f = Mapping.MapPad(src);
        var r = new byte[Ds4Report.ExtendedLength];
        r.AsSpan().Fill(0xEE);
        Ds4Report.WriteExtended(f, r, reportCounter: 0x3F + 5, touchCounter: 77);

        Assert.Equal(63, r.Length);
        Assert.Equal(255, r[0]);
        Assert.Equal(0, r[1]);
        Assert.Equal(0, r[2]);
        Assert.Equal(128, r[3]);
        ushort w = (ushort)(r[4] | r[5] << 8);
        Assert.Equal(1, w & 0x0F);                                  // up + right: north-east
        Assert.Equal(Mapping.Ds4Cross | Mapping.Ds4Triangle | Mapping.Ds4L1 | Mapping.Ds4L2, w & 0xFFF0);
        Assert.Equal(Mapping.Ds4Ps | Mapping.Ds4TouchpadClick | (4 << 2), r[6]); // counter 68 & 0x3F = 4
        Assert.Equal(255, r[7]);
        Assert.Equal(3, r[8]);
        Assert.Equal(187, r[9] | r[10] << 8);
        Assert.Equal(0, r[11]);
        Assert.Equal(16, (short)(r[12] | r[13] << 8));
        Assert.Equal(-16, (short)(r[14] | r[15] << 8));
        Assert.Equal(1000, (short)(r[16] | r[17] << 8));
        Assert.Equal(8192, (short)(r[18] | r[19] << 8));
        Assert.Equal(-2, (short)(r[20] | r[21] << 8));
        Assert.Equal(0, (short)(r[22] | r[23] << 8));
        Assert.All(r[24..29], b => Assert.Equal(0, b));
        Assert.Equal(Ds4Report.BatteryWired, r[29]);
        Assert.Equal(1, r[32]);
        Assert.Equal(77, r[33]);
        Assert.Equal(9, r[34]);                                     // finger 0 down
        Assert.Equal((1919, 942), Ds4Report.ReadTouch(r.AsSpan(35)));
        Assert.Equal(0x80 | 3, r[38]);                              // finger 1 up
        Assert.Equal((0, 0), Ds4Report.ReadTouch(r.AsSpan(39)));
        Assert.All(r[42..], b => Assert.Equal(0, b));
    }

    [Fact]
    public void Button_names_follow_the_style_table()
    {
        Assert.Equal("Cross", PadButtonNames.Name(0, PadStyle.PlayStation));
        Assert.Equal("A", PadButtonNames.Name(0, PadStyle.Xbox));
        Assert.Equal("Create", PadButtonNames.Name(8, PadStyle.PlayStation));
        Assert.Equal("View", PadButtonNames.Name(8, PadStyle.Xbox));
        Assert.True(PadButtonNames.Exists(11, PadStyle.PlayStation));
        Assert.False(PadButtonNames.Exists(11, PadStyle.Xbox));
        Assert.False(PadButtonNames.Exists(17, PadStyle.PlayStation));
        Assert.Equal("Share", PadButtonNames.Name(17, PadStyle.Xbox));
    }

    [Fact]
    public void Pad_output_setting_resolves_by_style_and_is_stored_in_hub_json()
    {
        Assert.Equal(OutputKind.DualShock4, HubEngine.ResolvePadKind(PadOutputSelection.Auto, stylePs: true));
        Assert.Equal(OutputKind.Xbox360, HubEngine.ResolvePadKind(PadOutputSelection.Auto, stylePs: false));
        Assert.Equal(OutputKind.Xbox360, HubEngine.ResolvePadKind(PadOutputSelection.Xbox360, stylePs: true));
        Assert.Equal(OutputKind.DualShock4, HubEngine.ResolvePadKind(PadOutputSelection.DualShock4, stylePs: false));

        var c = new HubConfig { PairingCode = "SLIPSTREAMTEST22", PadOutput = "ds4", TapMs = 70, TapGapMs = 30 };
        c.Normalize();
        string json = c.ToJson();
        Assert.Contains("\"pad_output\": \"ds4\"", json);
        Assert.Contains("\"tap_ms\": 70", json);
        Assert.Contains("\"tap_gap_ms\": 30", json);
        HubConfig back = HubConfig.FromJson(json);
        Assert.Equal(PadOutputSelection.DualShock4, back.PadOutputSelection);
        HubEngineOptions o = back.ToEngineOptions();
        Assert.Equal(70, o.TapMs);
        Assert.Equal(30, o.TapGapMs);
        Assert.Equal(PadOutputSelection.DualShock4, o.PadOutput);
        Assert.Equal(70_000, o.Timing.TapUs);
        Assert.Equal(30_000, o.Timing.TapGapUs);

        // Defaults, and a 0.1.0 file without the new keys.
        var d = new HubConfig();
        Assert.Equal(PadOutputSelection.Auto, d.PadOutputSelection);
        Assert.Equal(50, d.TapMs);
        Assert.Equal(40, d.TapGapMs);
        HubConfig old = HubConfig.FromJson("{ \"pairing_code\": \"SLIPSTREAMTEST22\", \"output\": \"x360\" }");
        Assert.Equal("auto", old.PadOutput);
        Assert.Equal(50, old.TapMs);

        // Hand-edited values are repaired.
        var bad = new HubConfig { PairingCode = "SLIPSTREAMTEST22", PadOutput = "PS5", TapMs = 1, TapGapMs = 9000 };
        Assert.True(bad.Normalize());
        Assert.Equal("auto", bad.PadOutput);
        Assert.Equal(10, bad.TapMs);
        Assert.Equal(500, bad.TapGapMs);
        var upper = new HubConfig { PairingCode = "SLIPSTREAMTEST22", PadOutput = " X360 " };
        upper.Normalize();
        Assert.Equal(PadOutputSelection.Xbox360, upper.PadOutputSelection);
    }
}
