namespace Slipstream.Core.Output;

/// <summary>Kind of virtual controller, as carried in the STATUS output byte (low 7 bits).</summary>
public enum OutputKind : byte
{
    None = 0,
    VJoy = 1,
    Xbox360 = 2,
}

/// <summary>Health of an output device. Anything but Ready sets bit 7 of the STATUS output byte.</summary>
public enum OutputState
{
    /// <summary>Working, fully configured.</summary>
    Ready,
    /// <summary>Working, but part of the mapping cannot reach the game (for example missing vJoy axes).</summary>
    Degraded,
    /// <summary>Driver missing, device disabled or busy. Nothing reaches the game.</summary>
    Unavailable,
    /// <summary>Was working and then failed (device removed, driver error).</summary>
    Faulted,
}

/// <summary>Axes that can be inverted in hub settings.</summary>
[Flags]
public enum AxisInvert
{
    None = 0,
    Steer = 1,
    Throttle = 2,
    Brake = 4,
    Clutch = 8,
    Handbrake = 16,
}

/// <summary>
/// The logical controller state after the link rules (failsafe, PAUSED, pulse scheduling):
/// what the virtual device should show right now.
/// </summary>
public readonly record struct ControllerFrame(
    short Steer,
    ushort Throttle,
    ushort Brake,
    ushort Clutch,
    ushort Handbrake,
    uint Held,
    byte PulseMask)
{
    /// <summary>Held button bits 0..23 are used, 24..31 are reserved and ignored.</summary>
    public const uint HeldMask = 0x00FF_FFFF;

    public static ControllerFrame Neutral => default;
}

/// <summary>Xbox 360 (XUSB) button bits, same values as the XUSB report wButtons.</summary>
[Flags]
public enum X360Buttons : ushort
{
    None = 0,
    DPadUp = 0x0001,
    DPadDown = 0x0002,
    DPadLeft = 0x0004,
    DPadRight = 0x0008,
    Start = 0x0010,
    Back = 0x0020,
    LeftThumb = 0x0040,
    RightThumb = 0x0080,
    LeftShoulder = 0x0100,
    RightShoulder = 0x0200,
    Guide = 0x0400,
    A = 0x1000,
    B = 0x2000,
    X = 0x4000,
    Y = 0x8000,
}

/// <summary>
/// Everything an output device needs for one update, computed once by <see cref="Mapping"/>
/// (PROTOCOL.md section 10). Devices pick the half they drive.
/// </summary>
public readonly record struct OutputFrame
{
    public ControllerFrame Source { get; init; }

    // vJoy device 1, axes 1..32768, button 1 is bit 0.
    public int VJoyX { get; init; }
    public int VJoyY { get; init; }
    public int VJoyZ { get; init; }
    public int VJoyRx { get; init; }
    public int VJoyRy { get; init; }
    public uint VJoyButtons { get; init; }

    // Xbox 360 through ViGEm.
    public short X360LeftThumbX { get; init; }
    public short X360RightThumbY { get; init; }
    public byte X360LeftTrigger { get; init; }
    public byte X360RightTrigger { get; init; }
    public X360Buttons X360Buttons { get; init; }
}
