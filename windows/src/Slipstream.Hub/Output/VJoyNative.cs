using System.Runtime.InteropServices;
using Slipstream.Hub.Native;

namespace Slipstream.Hub.Output;

/// <summary>vJoyInterface.dll status codes (VjdStat).</summary>
internal enum VjdStat
{
    Own = 0,   // the device is owned by this feeder
    Free = 1,  // the device is free
    Busy = 2,  // the device is owned by another feeder
    Miss = 3,  // the device is missing or not configured
    Unknown = 4,
}

/// <summary>HID usages of the vJoy axes, as GetVJDAxisExist expects them.</summary>
internal static class VJoyAxis
{
    public const uint X = 0x30;
    public const uint Y = 0x31;
    public const uint Z = 0x32;
    public const uint Rx = 0x33;
    public const uint Ry = 0x34;
}

/// <summary>
/// JOYSTICK_POSITION_V2, laid out exactly as in the official vJoyInterfaceWrap C# wrapper:
/// sequential, default packing (so 3 bytes of padding after bDevice), 108 bytes, followed by the four
/// LONGs that the 2.2.x SDK appends in JOYSTICK_POSITION_V3 (124 bytes in total). UpdateVJD takes a
/// PVOID and copies the size its own build expects, so a 2.2.x DLL built for V3 would otherwise read
/// 16 bytes past the end of this struct. The first 108 bytes (X, Y, Z, Rx, Ry, buttons) sit at the same
/// offsets in both versions, and a V2 DLL simply ignores the tail.
/// </summary>
[StructLayout(LayoutKind.Sequential)]
internal struct JoystickPositionV2
{
    public const int V2Size = 108;
    public const int Size = 124;

    public byte bDevice;
    public int Throttle;
    public int Rudder;
    public int Aileron;
    public int AxisX;
    public int AxisY;
    public int AxisZ;
    public int AxisXRot;
    public int AxisYRot;
    public int AxisZRot;
    public int Slider;
    public int Dial;
    public int Wheel;
    public int AxisVX;
    public int AxisVY;
    public int AxisVZ;
    public int AxisVBRX;
    public int AxisVBRY;
    public int AxisVBRZ;
    public uint Buttons;
    public uint bHats;
    public uint bHatsEx1;
    public uint bHatsEx2;
    public uint bHatsEx3;
    public uint ButtonsEx1;
    public uint ButtonsEx2;
    public uint ButtonsEx3;
    // JOYSTICK_POSITION_V3 tail (vJoy 2.2.x), always 0 here.
    public int V3Tail0;
    public int V3Tail1;
    public int V3Tail2;
    public int V3Tail3;
}

/// <summary>P/Invoke into vJoyInterface.dll (resolved by <see cref="NativeResolver"/>).</summary>
internal static class VJoyNative
{
    private const string Lib = NativeResolver.VJoyLibrary;

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool vJoyEnabled();

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern short GetvJoyVersion();

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern VjdStat GetVJDStatus(uint rID);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool AcquireVJD(uint rID);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern void RelinquishVJD(uint rID);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool ResetVJD(uint rID);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool UpdateVJD(uint rID, ref JoystickPositionV2 pData);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool GetVJDAxisExist(uint rID, uint axis);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int GetVJDButtonNumber(uint rID);

    /// <summary>"2.2.1" from the BCD-like short vJoy reports (0x0221).</summary>
    public static string FormatVersion(short v)
    {
        int x = (ushort)v;
        return x == 0 ? "unknown" : $"{(x >> 8) & 0xF}.{(x >> 4) & 0xF}.{x & 0xF}";
    }
}
