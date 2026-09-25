namespace Slipstream.Core.Output;

/// <summary>Output mapping, normative defaults of PROTOCOL.md section 10 (wheel) and 12.5 (controller). Pure functions.</summary>
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

    // ------------------------------------------------------------ controller mode (12.5) ---

    /// <summary>DualShock 4 stick byte: ((v + 32767) * 255 + 32767) div 65534, 0 left, 128 centre, 255 right.</summary>
    public static byte Ds4Axis(short v)
    {
        long s = Math.Max((int)v, -32767); // -32768 is never sent; clamp so the formula stays in 0..255
        return (byte)(((s + 32767) * 255 + 32767) / 65534);
    }

    /// <summary>DualShock 4 stick Y byte from PAD Y (+y up): the formula on -v, since DualShock 4 Y is 0 at the top.</summary>
    public static byte Ds4AxisY(short v) => Ds4Axis((short)-Math.Max((int)v, -32767));

    /// <summary>DualShock 4 analog trigger: p &gt;&gt; 8.</summary>
    public static byte Ds4Trigger(ushort p) => (byte)(p >> 8);

    /// <summary>The digital L2 / R2 bit is set while (p &gt;&gt; 8) &gt;= 8.</summary>
    public static bool Ds4TriggerDigital(ushort p) => (p >> 8) >= 8;

    /// <summary>DualShock 4 hat value when no D-pad direction is held.</summary>
    public const byte Ds4HatNone = 8;

    /// <summary>
    /// The D-pad bits (12 up, 13 down, 14 left, 15 right) as the DualShock 4 hat: 0 north, clockwise to
    /// 7 north-west, 8 released. Opposite directions cancel (up with down is neither).
    /// </summary>
    public static byte Ds4Hat(uint buttons)
    {
        bool up = (buttons & (1u << (int)PadButton.DPadUp)) != 0;
        bool down = (buttons & (1u << (int)PadButton.DPadDown)) != 0;
        bool left = (buttons & (1u << (int)PadButton.DPadLeft)) != 0;
        bool right = (buttons & (1u << (int)PadButton.DPadRight)) != 0;
        int v = up == down ? 0 : up ? 1 : -1;       // +1 north, -1 south
        int h = left == right ? 0 : right ? 1 : -1; // +1 east, -1 west
        return (v, h) switch
        {
            (1, 0) => 0,
            (1, 1) => 1,
            (0, 1) => 2,
            (-1, 1) => 3,
            (-1, 0) => 4,
            (-1, -1) => 5,
            (0, -1) => 6,
            (1, -1) => 7,
            _ => Ds4HatNone,
        };
    }

    // DualShock 4 wButtons bits (same values as Nefarius.ViGEm.Client DualShock4Button).
    public const ushort Ds4Square = 0x0010, Ds4Cross = 0x0020, Ds4Circle = 0x0040, Ds4Triangle = 0x0080;
    public const ushort Ds4L1 = 0x0100, Ds4R1 = 0x0200, Ds4L2 = 0x0400, Ds4R2 = 0x0800;
    public const ushort Ds4Share = 0x1000, Ds4Options = 0x2000, Ds4L3 = 0x4000, Ds4R3 = 0x8000;
    // bSpecial bits (DualShock4SpecialButton).
    public const byte Ds4Ps = 0x01, Ds4TouchpadClick = 0x02;

    /// <summary>Canonical bits 0..9 to their DualShock 4 wButtons bit (12.5: Cross, Circle, Square, Triangle, L1, R1, L3, R3, Share, Options).</summary>
    private static readonly ushort[] Ds4ButtonTable =
    {
        Ds4Cross, Ds4Circle, Ds4Square, Ds4Triangle, Ds4L1, Ds4R1, Ds4L3, Ds4R3, Ds4Share, Ds4Options,
    };

    /// <summary>The wButtons word: canonical bits 0..9, the hat from 12..15, and the digital L2 / R2 bits.</summary>
    public static ushort Ds4ButtonsOf(uint buttons, ushort l2, ushort r2)
    {
        uint w = Ds4Hat(buttons);
        for (int i = 0; i < Ds4ButtonTable.Length; i++)
            if ((buttons & (1u << i)) != 0) w |= Ds4ButtonTable[i];
        if (Ds4TriggerDigital(l2)) w |= Ds4L2;
        if (Ds4TriggerDigital(r2)) w |= Ds4R2;
        return (ushort)w;
    }

    /// <summary>bSpecial: canonical bit 10 is PS, bit 11 the touchpad click. Bit 16 (Mute) and 17 (Xbox Share) are not sent.</summary>
    public static byte Ds4SpecialOf(uint buttons)
    {
        int b = 0;
        if ((buttons & (1u << (int)PadButton.Home)) != 0) b |= Ds4Ps;
        if ((buttons & (1u << (int)PadButton.Touchpad)) != 0) b |= Ds4TouchpadClick;
        return (byte)b;
    }

    /// <summary>Touch X to the DualShock 4 pad: x * 1919 div 65535 (0..1919).</summary>
    public static ushort Ds4TouchX(ushort x) => (ushort)((uint)x * 1919 / 65535);

    /// <summary>Touch Y to the DualShock 4 pad: y * 942 div 65535 (0..942).</summary>
    public static ushort Ds4TouchY(ushort y) => (ushort)((uint)y * 942 / 65535);

    /// <summary>
    /// PAD touch id (bit 7 = finger down) to the DualShock 4 byte, whose bit 7 is active low (set while the
    /// finger is up). The tracking id in bits 0..6 is kept.
    /// </summary>
    public static byte Ds4TouchId(byte padId) => (byte)((padId & 0x7F) | ((padId & 0x80) != 0 ? 0 : 0x80));

    /// <summary>
    /// Gyro resolution of the DualShock 4 report: 16 counts per degree per second (the BMI055 at its
    /// +-2000 dps range reads about 16.4; DS4Windows and the DSU tools use 16). PAD sends 1/16 dps, the same
    /// unit, so the gyro passes through unchanged.
    /// </summary>
    public const int Ds4GyroLsbPerDps = 16;
    /// <summary>PAD gyro resolution, counts per degree per second (PROTOCOL.md 12.1).</summary>
    public const int PadGyroLsbPerDps = 16;
    /// <summary>
    /// Accelerometer resolution of the DualShock 4 report: 8192 counts per g (range +-4 g). PAD sends
    /// 1/4096 g (range +-8 g), so accel is doubled and saturates at +-4 g.
    /// </summary>
    public const int Ds4AccelLsbPerG = 8192;
    /// <summary>PAD accel resolution, counts per g (PROTOCOL.md 12.1).</summary>
    public const int PadAccelLsbPerG = 4096;

    /// <summary>PAD gyro (1/16 dps) to DualShock 4 units, saturating.</summary>
    public static short Ds4Gyro(short v) => Saturate((long)v * Ds4GyroLsbPerDps / PadGyroLsbPerDps);

    /// <summary>PAD accel (1/4096 g) to DualShock 4 units (1/8192 g), saturating at +-4 g.</summary>
    public static short Ds4Accel(short v) => Saturate((long)v * Ds4AccelLsbPerG / PadAccelLsbPerG);

    /// <summary>Phone clock (us) to the DualShock 4 report timestamp, units of 16/3 us, wrapping at 16 bits.</summary>
    public static ushort Ds4Timestamp(uint timeUs) => (ushort)((ulong)timeUs * 3 / 16);

    private static short Saturate(long v) => (short)Math.Clamp(v, -32767, 32767);

    /// <summary>Canonical bits 0..10 and 12..15 to XUSB buttons (12.5). Bit 11 (touchpad), 16 and 17 have no Xbox 360 equivalent.</summary>
    private static readonly ushort[] X360PadTable =
    {
        (ushort)X360Buttons.A, (ushort)X360Buttons.B, (ushort)X360Buttons.X, (ushort)X360Buttons.Y,
        (ushort)X360Buttons.LeftShoulder, (ushort)X360Buttons.RightShoulder, (ushort)X360Buttons.LeftThumb, (ushort)X360Buttons.RightThumb,
        (ushort)X360Buttons.Back, (ushort)X360Buttons.Start, (ushort)X360Buttons.Guide, 0,
        (ushort)X360Buttons.DPadUp, (ushort)X360Buttons.DPadDown, (ushort)X360Buttons.DPadLeft, (ushort)X360Buttons.DPadRight,
    };

    public static X360Buttons X360PadButtonsOf(uint buttons)
    {
        uint b = 0;
        for (int i = 0; i < X360PadTable.Length; i++)
            if ((buttons & (1u << i)) != 0) b |= X360PadTable[i];
        return (X360Buttons)b;
    }

    /// <summary>Computes both pad mappings (Xbox 360 and DualShock 4) for one logical frame. PROTOCOL.md 12.5.</summary>
    public static PadOutputFrame MapPad(in PadFrame f)
    {
        bool motion = f.Motion;
        return new PadOutputFrame
        {
            Source = f,
            X360LeftThumbX = f.Lx,
            X360LeftThumbY = f.Ly,
            X360RightThumbX = f.Rx,
            X360RightThumbY = f.Ry,
            X360LeftTrigger = X360Trigger(f.L2),
            X360RightTrigger = X360Trigger(f.R2),
            X360Buttons = X360PadButtonsOf(f.Buttons),
            Ds4LeftX = Ds4Axis(f.Lx),
            Ds4LeftY = Ds4AxisY(f.Ly),
            Ds4RightX = Ds4Axis(f.Rx),
            Ds4RightY = Ds4AxisY(f.Ry),
            Ds4LeftTrigger = Ds4Trigger(f.L2),
            Ds4RightTrigger = Ds4Trigger(f.R2),
            Ds4Buttons = Ds4ButtonsOf(f.Buttons, f.L2, f.R2),
            Ds4Special = Ds4SpecialOf(f.Buttons),
            Ds4Touch0X = Ds4TouchX(f.Touch0X),
            Ds4Touch0Y = Ds4TouchY(f.Touch0Y),
            Ds4Touch1X = Ds4TouchX(f.Touch1X),
            Ds4Touch1Y = Ds4TouchY(f.Touch1Y),
            Ds4Touch0Id = Ds4TouchId(f.Touch0Id),
            Ds4Touch1Id = Ds4TouchId(f.Touch1Id),
            Ds4GyroX = motion ? Ds4Gyro(f.GyroX) : (short)0,
            Ds4GyroY = motion ? Ds4Gyro(f.GyroY) : (short)0,
            Ds4GyroZ = motion ? Ds4Gyro(f.GyroZ) : (short)0,
            Ds4AccelX = motion ? Ds4Accel(f.AccelX) : (short)0,
            Ds4AccelY = motion ? Ds4Accel(f.AccelY) : (short)0,
            Ds4AccelZ = motion ? Ds4Accel(f.AccelZ) : (short)0,
            Ds4Timestamp = Ds4Timestamp(f.TimeUs),
        };
    }
}
