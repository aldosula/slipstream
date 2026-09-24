namespace Slipstream.Core.Output;

/// <summary>Output mapping, normative defaults of PROTOCOL.md section 10. Pure functions.</summary>
public static class Mapping
{
    public const int VJoyMin = 1;
    public const int VJoyMax = 32768;

    /// <summary>X: 1 + ((steer + 32767) * 32767 + 32767) div 65534.</summary>
    public static int VJoySteer(short steer)
    {
        long s = Math.Max((int)steer, -32767); // -32768 is never sent; clamp so the formula stays in range
        return (int)(1 + ((s + 32767) * 32767 + 32767) / 65534);
    }

    /// <summary>Y, Z, Rx, Ry: 1 + (p * 32767 + 32767) div 65535.</summary>
    public static int VJoyPedal(ushort p) => (int)(1 + ((long)p * 32767 + 32767) / 65535);

    /// <summary>Axis inversion for vJoy: v' = 32769 - v.</summary>
    public static int VJoyInvert(int v) => 32769 - v;

    /// <summary>Triggers: p &gt;&gt; 8.</summary>
    public static byte X360Trigger(ushort p) => (byte)(p >> 8);

    /// <summary>Clutch on right thumb Y: p &gt;&gt; 1 as i16.</summary>
    public static short X360Clutch(ushort p) => (short)(p >> 1);

    /// <summary>Handbrake on A: held while p &gt;= 32768.</summary>
    public static bool X360Handbrake(ushort p) => p >= 32768;

    /// <summary>Bluetooth HID steering report value: steer + 32767, 0..65534 (center 32767).</summary>
    public static int HidSteer(short steer) => Math.Max((int)steer, -32767) + 32767;

    /// <summary>Pulse channel j (0..7) to X360 button: B, X, RB, LB, Y, Back, Start, Right thumb.</summary>
    public static ReadOnlySpan<ushort> X360PulseButtons => PulseButtonTable;

    private static readonly ushort[] PulseButtonTable =
    {
        (ushort)X360Buttons.B, (ushort)X360Buttons.X, (ushort)X360Buttons.RightShoulder, (ushort)X360Buttons.LeftShoulder,
        (ushort)X360Buttons.Y, (ushort)X360Buttons.Back, (ushort)X360Buttons.Start, (ushort)X360Buttons.RightThumb,
    };

    /// <summary>Held bit 0..3 to D-pad up, down, left, right.</summary>
    public static ReadOnlySpan<ushort> X360HeldButtons => HeldButtonTable;

    private static readonly ushort[] HeldButtonTable =
    {
        (ushort)X360Buttons.DPadUp, (ushort)X360Buttons.DPadDown, (ushort)X360Buttons.DPadLeft, (ushort)X360Buttons.DPadRight,
    };

    /// <summary>vJoy buttons: pulse channel j is button j + 1 (bit j), held bit i is button i + 9 (bit i + 8).</summary>
    public static uint VJoyButtons(byte pulseMask, uint held) => pulseMask | ((held & ControllerFrame.HeldMask) << 8);

    public static X360Buttons X360ButtonsOf(byte pulseMask, uint held, ushort handbrake)
    {
        uint b = 0;
        var pulseMap = X360PulseButtons;
        for (int j = 0; j < 8; j++)
            if ((pulseMask & (1 << j)) != 0) b |= pulseMap[j];
        var heldMap = X360HeldButtons;
        for (int i = 0; i < 4; i++)
            if ((held & (1u << i)) != 0) b |= heldMap[i];
        if (X360Handbrake(handbrake)) b |= (ushort)X360Buttons.A;
        return (X360Buttons)b;
    }

    /// <summary>Computes both device mappings for one logical frame, with per-axis inversion.</summary>
    public static OutputFrame Map(in ControllerFrame f, AxisInvert invert)
    {
        short steer = Math.Max(f.Steer, (short)-32767);
        ushort throttle = f.Throttle, brake = f.Brake, clutch = f.Clutch, handbrake = f.Handbrake;

        int vx = VJoySteer(steer), vy = VJoyPedal(throttle), vz = VJoyPedal(brake), vrx = VJoyPedal(clutch), vry = VJoyPedal(handbrake);
        if ((invert & AxisInvert.Steer) != 0) vx = VJoyInvert(vx);
        if ((invert & AxisInvert.Throttle) != 0) vy = VJoyInvert(vy);
        if ((invert & AxisInvert.Brake) != 0) vz = VJoyInvert(vz);
        if ((invert & AxisInvert.Clutch) != 0) vrx = VJoyInvert(vrx);
        if ((invert & AxisInvert.Handbrake) != 0) vry = VJoyInvert(vry);

        // Xbox 360: only steering follows the inversion switch (mirrored, -steer). Section 10 defines
        // inversion for vJoy, where games calibrate DirectInput axes. XInput triggers and sticks have a
        // fixed meaning (0 is released), so an inverted pedal would read fully pressed at rest and during
        // the failsafe, and an inverted handbrake would hold A (the menu confirm button) whenever the
        // handbrake is released, breaking rule 6 "all held buttons released". The pedal switches, stored
        // once for both outputs, therefore apply to vJoy only.
        short xs = (invert & AxisInvert.Steer) != 0 ? (short)-steer : steer;
        ushort xt = throttle, xb = brake, xc = clutch, xh = handbrake;

        return new OutputFrame
        {
            Source = f,
            VJoyX = vx,
            VJoyY = vy,
            VJoyZ = vz,
            VJoyRx = vrx,
            VJoyRy = vry,
            VJoyButtons = VJoyButtons(f.PulseMask, f.Held),
            X360LeftThumbX = xs,
            X360RightTrigger = X360Trigger(xt),
            X360LeftTrigger = X360Trigger(xb),
            X360RightThumbY = X360Clutch(xc),
            X360Buttons = X360ButtonsOf(f.PulseMask, f.Held, xh),
        };
    }
}
