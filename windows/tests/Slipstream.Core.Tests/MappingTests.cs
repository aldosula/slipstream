using Slipstream.Core.Output;

namespace Slipstream.Core.Tests;

/// <summary>The PROTOCOL.md section 10 tables beyond the numeric vectors: button routing and inversion.</summary>
public class MappingTests
{
    [Fact]
    public void Vjoy_pulse_channel_j_is_button_j_plus_1_and_held_bit_i_is_button_i_plus_9()
    {
        for (int j = 0; j < 8; j++)
        {
            OutputFrame f = Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 0, (byte)(1 << j)), AxisInvert.None);
            Assert.Equal(1u << j, f.VJoyButtons); // button j+1 is bit j
        }
        for (int i = 0; i < 24; i++)
        {
            OutputFrame f = Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 1u << i, 0), AxisInvert.None);
            Assert.Equal(1u << (i + 8), f.VJoyButtons); // button i+9 is bit i+8
        }
        // Reserved held bits 24..31 are ignored.
        Assert.Equal(0u, Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 0xFF00_0000, 0), AxisInvert.None).VJoyButtons);
    }

    [Fact]
    public void Vjoy_axes_follow_the_formulas()
    {
        OutputFrame f = Mapping.Map(new ControllerFrame(-32767, 65535, 32768, 1, 0, 0, 0), AxisInvert.None);
        Assert.Equal(1, f.VJoyX);
        Assert.Equal(32768, f.VJoyY);
        Assert.Equal(16385, f.VJoyZ);
        Assert.Equal(1, f.VJoyRx);
        Assert.Equal(1, f.VJoyRy);
        OutputFrame neutral = Mapping.Map(ControllerFrame.Neutral, AxisInvert.None);
        Assert.Equal(16385, neutral.VJoyX);
    }

    [Fact]
    public void Vjoy_inversion_is_32769_minus_v()
    {
        var src = new ControllerFrame(-16384, 0, 65535, 32767, 1000, 0, 0);
        OutputFrame plain = Mapping.Map(src, AxisInvert.None);
        OutputFrame inv = Mapping.Map(src, AxisInvert.Steer | AxisInvert.Throttle | AxisInvert.Brake | AxisInvert.Clutch | AxisInvert.Handbrake);
        Assert.Equal(32769 - plain.VJoyX, inv.VJoyX);
        Assert.Equal(32769 - plain.VJoyY, inv.VJoyY);
        Assert.Equal(32769 - plain.VJoyZ, inv.VJoyZ);
        Assert.Equal(32769 - plain.VJoyRx, inv.VJoyRx);
        Assert.Equal(32769 - plain.VJoyRy, inv.VJoyRy);
        OutputFrame onlyBrake = Mapping.Map(src, AxisInvert.Brake);
        Assert.Equal(plain.VJoyX, onlyBrake.VJoyX);
        Assert.Equal(1, onlyBrake.VJoyZ);
    }

    [Fact]
    public void X360_axes_follow_the_table()
    {
        OutputFrame f = Mapping.Map(new ControllerFrame(-12345, 65535, 256, 40000, 0, 0, 0), AxisInvert.None);
        Assert.Equal(-12345, f.X360LeftThumbX);      // steer as i16
        Assert.Equal(255, f.X360RightTrigger);       // throttle >> 8
        Assert.Equal(1, f.X360LeftTrigger);          // brake >> 8
        Assert.Equal(20000, f.X360RightThumbY);      // clutch >> 1
        Assert.Equal(32767, Mapping.Map(new ControllerFrame(0, 0, 0, 65535, 0, 0, 0), AxisInvert.None).X360RightThumbY);
    }

    [Fact]
    public void X360_handbrake_is_A_while_at_least_32768()
    {
        Assert.False(Mapping.Map(new ControllerFrame(0, 0, 0, 0, 32767, 0, 0), AxisInvert.None).X360Buttons.HasFlag(X360Buttons.A));
        Assert.True(Mapping.Map(new ControllerFrame(0, 0, 0, 0, 32768, 0, 0), AxisInvert.None).X360Buttons.HasFlag(X360Buttons.A));
    }

    [Fact]
    public void X360_pulses_and_held_buttons_follow_the_table()
    {
        X360Buttons[] pulses = { X360Buttons.B, X360Buttons.X, X360Buttons.RightShoulder, X360Buttons.LeftShoulder,
                                 X360Buttons.Y, X360Buttons.Back, X360Buttons.Start, X360Buttons.RightThumb };
        for (int j = 0; j < 8; j++)
            Assert.Equal(pulses[j], Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 0, (byte)(1 << j)), AxisInvert.None).X360Buttons);

        X360Buttons[] held = { X360Buttons.DPadUp, X360Buttons.DPadDown, X360Buttons.DPadLeft, X360Buttons.DPadRight };
        for (int i = 0; i < 4; i++)
            Assert.Equal(held[i], Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 1u << i, 0), AxisInvert.None).X360Buttons);

        // Held bits 4..23 have no Xbox button.
        Assert.Equal(X360Buttons.None, Mapping.Map(new ControllerFrame(0, 0, 0, 0, 0, 0x00FF_FFF0, 0), AxisInvert.None).X360Buttons);
    }

    [Fact]
    public void X360_inversion_mirrors_steering_only_and_released_pedals_stay_released()
    {
        var src = new ControllerFrame(1000, 256, 0, 0, 0, 0, 0);
        OutputFrame inv = Mapping.Map(src, AxisInvert.Steer | AxisInvert.Throttle | AxisInvert.Brake | AxisInvert.Clutch | AxisInvert.Handbrake);
        Assert.Equal(-1000, inv.X360LeftThumbX);
        Assert.Equal(1, inv.X360RightTrigger);
        Assert.Equal(0, inv.X360LeftTrigger);
        Assert.Equal(0, inv.X360RightThumbY);
        Assert.False(inv.X360Buttons.HasFlag(X360Buttons.A)); // a released handbrake never holds A

        // Rule 6 for the Xbox output under every switch: the failsafe frame (pedals 0, no buttons)
        // presses nothing, while vJoy still follows v' = 32769 - v.
        OutputFrame failsafe = Mapping.Map(new ControllerFrame(500, 0, 0, 0, 0, 0, 0), (AxisInvert)31);
        Assert.Equal(X360Buttons.None, failsafe.X360Buttons);
        Assert.Equal(0, failsafe.X360RightTrigger);
        Assert.Equal(0, failsafe.X360LeftTrigger);
        Assert.Equal(32768, failsafe.VJoyY);
    }

    [Fact]
    public void Steer_minus_32768_is_clamped_not_overflowed()
    {
        OutputFrame f = Mapping.Map(new ControllerFrame(short.MinValue, 0, 0, 0, 0, 0, 0), AxisInvert.Steer);
        Assert.Equal(32768, f.VJoyX);
        Assert.Equal(32767, f.X360LeftThumbX);
        Assert.Equal(0, Mapping.HidSteer(short.MinValue));
    }
}
