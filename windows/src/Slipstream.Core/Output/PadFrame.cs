namespace Slipstream.Core.Output;

/// <summary>Canonical pad buttons, PROTOCOL.md section 12.2. The value is the bit index in the PAD buttons field.</summary>
public enum PadButton
{
    /// <summary>Cross (PlayStation) or A (Xbox).</summary>
    South = 0,
    /// <summary>Circle or B.</summary>
    East = 1,
    /// <summary>Square or X.</summary>
    West = 2,
    /// <summary>Triangle or Y.</summary>
    North = 3,
    L1 = 4,
    R1 = 5,
    L3 = 6,
    R3 = 7,
    /// <summary>Create or View.</summary>
    Create = 8,
    /// <summary>Options or Menu.</summary>
    Options = 9,
    /// <summary>PS or Xbox.</summary>
    Home = 10,
    /// <summary>Touchpad click (PlayStation only).</summary>
    Touchpad = 11,
    DPadUp = 12,
    DPadDown = 13,
    DPadLeft = 14,
    DPadRight = 15,
    /// <summary>Mute (PlayStation only).</summary>
    Mute = 16,
    /// <summary>Share (Xbox only).</summary>
    Share = 17,
}

/// <summary>Which layout the phone is showing (the STYLE_PS flag of the newest PAD packet).</summary>
public enum PadStyle
{
    None = 0,
    PlayStation = 1,
    Xbox = 2,
}

/// <summary>Names of the canonical buttons for each layout (PROTOCOL.md 12.2), for the UI and tools.</summary>
public static class PadButtonNames
{
    private static readonly string[] Ps =
    {
        "Cross", "Circle", "Square", "Triangle", "L1", "R1", "L3", "R3",
        "Create", "Options", "PS", "Touchpad", "Up", "Down", "Left", "Right", "Mute", "",
    };

    private static readonly string[] Xbox =
    {
        "A", "B", "X", "Y", "LB", "RB", "LS", "RS",
        "View", "Menu", "Xbox", "", "Up", "Down", "Left", "Right", "", "Share",
    };

    /// <summary>The button's name in <paramref name="style"/>, or "" when that layout has no such button.</summary>
    public static string Name(int button, PadStyle style) => style == PadStyle.Xbox ? Xbox[button] : Ps[button];

    /// <summary>True when the layout has the button (bit 11 and 16 are PlayStation only, 17 is Xbox only).</summary>
    public static bool Exists(int button, PadStyle style) => Name(button, style).Length > 0;
}

/// <summary>
/// The logical controller-mode state after the link rules (tap scheduler, failsafe, PAUSED): what the
/// virtual pad should show right now. Units as on the wire (PROTOCOL.md 12.1).
/// </summary>
public readonly record struct PadFrame
{
    public short Lx { get; init; }
    public short Ly { get; init; }
    public short Rx { get; init; }
    public short Ry { get; init; }
    public ushort L2 { get; init; }
    public ushort R2 { get; init; }
    /// <summary>Output state of the canonical buttons (bits 0..17): held bits, or a running tap schedule.</summary>
    public uint Buttons { get; init; }
    public ushort Touch0X { get; init; }
    public ushort Touch0Y { get; init; }
    public ushort Touch1X { get; init; }
    public ushort Touch1Y { get; init; }
    /// <summary>PAD encoding: bit 7 active, bits 0..6 tracking id.</summary>
    public byte Touch0Id { get; init; }
    public byte Touch1Id { get; init; }
    /// <summary>1/16 degree per second.</summary>
    public short GyroX { get; init; }
    public short GyroY { get; init; }
    public short GyroZ { get; init; }
    /// <summary>1/4096 g.</summary>
    public short AccelX { get; init; }
    public short AccelY { get; init; }
    public short AccelZ { get; init; }
    /// <summary>The gyro and accel fields carry data (MOTION flag of the applied packet).</summary>
    public bool Motion { get; init; }
    public bool StylePs { get; init; }
    /// <summary>Phone clock of the applied packet, microseconds (the DualShock 4 report timestamp is made from it).</summary>
    public uint TimeUs { get; init; }

    public bool IsDown(PadButton b) => (Buttons & (1u << (int)b)) != 0;
    public bool Touch0Active => (Touch0Id & 0x80) != 0;
    public bool Touch1Active => (Touch1Id & 0x80) != 0;

    public static PadFrame Neutral => default;
}

/// <summary>
/// Everything a virtual pad needs for one update, computed once by <see cref="Mapping.MapPad"/>
/// (PROTOCOL.md 12.5). The Xbox 360 device reads the X360 half, the DualShock 4 device the Ds4 half.
/// </summary>
public readonly record struct PadOutputFrame
{
    public PadFrame Source { get; init; }

    // Xbox 360 (XUSB), XInput +y is up.
    public short X360LeftThumbX { get; init; }
    public short X360LeftThumbY { get; init; }
    public short X360RightThumbX { get; init; }
    public short X360RightThumbY { get; init; }
    public byte X360LeftTrigger { get; init; }
    public byte X360RightTrigger { get; init; }
    public X360Buttons X360Buttons { get; init; }

    // DualShock 4, report units: sticks 0..255 with 0 left and top, centre 128.
    public byte Ds4LeftX { get; init; }
    public byte Ds4LeftY { get; init; }
    public byte Ds4RightX { get; init; }
    public byte Ds4RightY { get; init; }
    public byte Ds4LeftTrigger { get; init; }
    public byte Ds4RightTrigger { get; init; }
    /// <summary>The DualShock 4 wButtons word: hat in bits 0..3, then Square, Cross, Circle, Triangle, L1, R1, L2, R2, Share, Options, L3, R3.</summary>
    public ushort Ds4Buttons { get; init; }
    /// <summary>Bit 0 PS, bit 1 touchpad click.</summary>
    public byte Ds4Special { get; init; }
    /// <summary>Touch finger 0, 0..1919.</summary>
    public ushort Ds4Touch0X { get; init; }
    /// <summary>Touch finger 0, 0..942.</summary>
    public ushort Ds4Touch0Y { get; init; }
    public ushort Ds4Touch1X { get; init; }
    public ushort Ds4Touch1Y { get; init; }
    /// <summary>DualShock 4 encoding: bit 7 set while the finger is NOT on the pad, bits 0..6 tracking id.</summary>
    public byte Ds4Touch0Id { get; init; }
    public byte Ds4Touch1Id { get; init; }
    /// <summary>DualShock 4 units (see <see cref="Mapping.Ds4GyroLsbPerDps"/>).</summary>
    public short Ds4GyroX { get; init; }
    public short Ds4GyroY { get; init; }
    public short Ds4GyroZ { get; init; }
    /// <summary>DualShock 4 units (see <see cref="Mapping.Ds4AccelLsbPerG"/>).</summary>
    public short Ds4AccelX { get; init; }
    public short Ds4AccelY { get; init; }
    public short Ds4AccelZ { get; init; }
    /// <summary>Report timestamp in units of 16/3 microseconds, wrapping.</summary>
    public ushort Ds4Timestamp { get; init; }
}
