using System.Buffers.Binary;

namespace Slipstream.Core.Output;

/// <summary>
/// Builds the 63 byte extended DualShock 4 report that ViGEmBus takes through
/// <c>vigem_target_ds4_update_ex</c> (Nefarius.ViGEm.Client <c>IDualShock4Controller.SubmitRawReport</c>, which
/// requires exactly 63 bytes). The bytes are the DualShock 4 USB input report 0x01 without its report id,
/// so byte i here is byte i + 1 of the controller's own report:
/// <code>
///  0      left stick X     (0 left, 128 centre, 255 right)
///  1      left stick Y     (0 top)
///  2      right stick X
///  3      right stick Y
///  4..5   wButtons, u16 LE: bits 0..3 hat (0 N .. 7 NW, 8 released), 4 Square, 5 Cross, 6 Circle,
///         7 Triangle, 8 L1, 9 R1, 10 L2, 11 R2, 12 Share, 13 Options, 14 L3, 15 R3
///  6      bit 0 PS, bit 1 touchpad click, bits 2..7 report counter
///  7      L2 analog        8  R2 analog
///  9..10  timestamp, u16 LE, units of 16/3 us
/// 11      sensor temperature, 0
/// 12..17  gyro x, y, z, i16 LE, 16 counts per degree per second
/// 18..23  accel x, y, z, i16 LE, 8192 counts per g
/// 24..28  0
/// 29      0x1B: cable connected (bit 4), battery level 11 = full
/// 30..31  0
/// 32      touch packets in this report: 1
/// 33      touch packet counter
/// 34      finger 0: bit 7 set while NOT touching, bits 0..6 tracking id
/// 35..37  finger 0 position: x (12 bits, 0..1919) and y (12 bits, 0..942) packed as
///         [x &amp; 0xFF] [(x &gt;&gt; 8) | (y &amp; 0xF) &lt;&lt; 4] [y &gt;&gt; 4]
/// 38      finger 1 id byte  39..41 finger 1 position
/// 42..62  0 (older touch packets, not used)
/// </code>
/// Axes follow the pad frame of PROTOCOL.md 12.1 (x right, y up, z toward the player), which is how the
/// DualShock 4 report is read by SDL and DS4Windows, so no axis is swapped or negated.
/// </summary>
public static class Ds4Report
{
    public const int ExtendedLength = 63;
    /// <summary>Battery byte: cable connected, fully charged, so games do not warn about a low battery.</summary>
    public const byte BatteryWired = 0x1B;

    /// <summary>Writes the extended report for <paramref name="f"/> into <paramref name="dest"/> (63 bytes). Allocation-free.</summary>
    public static void WriteExtended(in PadOutputFrame f, Span<byte> dest, byte reportCounter, byte touchCounter)
    {
        if (dest.Length < ExtendedLength) throw new ArgumentException("The extended DualShock 4 report needs 63 bytes.", nameof(dest));
        dest[..ExtendedLength].Clear();
        dest[0] = f.Ds4LeftX;
        dest[1] = f.Ds4LeftY;
        dest[2] = f.Ds4RightX;
        dest[3] = f.Ds4RightY;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[4..], f.Ds4Buttons);
        dest[6] = (byte)((f.Ds4Special & 0x03) | ((reportCounter & 0x3F) << 2));
        dest[7] = f.Ds4LeftTrigger;
        dest[8] = f.Ds4RightTrigger;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[9..], f.Ds4Timestamp);
        BinaryPrimitives.WriteInt16LittleEndian(dest[12..], f.Ds4GyroX);
        BinaryPrimitives.WriteInt16LittleEndian(dest[14..], f.Ds4GyroY);
        BinaryPrimitives.WriteInt16LittleEndian(dest[16..], f.Ds4GyroZ);
        BinaryPrimitives.WriteInt16LittleEndian(dest[18..], f.Ds4AccelX);
        BinaryPrimitives.WriteInt16LittleEndian(dest[20..], f.Ds4AccelY);
        BinaryPrimitives.WriteInt16LittleEndian(dest[22..], f.Ds4AccelZ);
        dest[29] = BatteryWired;
        dest[32] = 1;
        dest[33] = touchCounter;
        dest[34] = f.Ds4Touch0Id;
        WriteTouch(dest[35..], f.Ds4Touch0X, f.Ds4Touch0Y);
        dest[38] = f.Ds4Touch1Id;
        WriteTouch(dest[39..], f.Ds4Touch1X, f.Ds4Touch1Y);
    }

    private static void WriteTouch(Span<byte> d, ushort x, ushort y)
    {
        d[0] = (byte)(x & 0xFF);
        d[1] = (byte)(((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
        d[2] = (byte)((y >> 4) & 0xFF);
    }

    /// <summary>Reads a finger position back (tests and tools).</summary>
    public static (int X, int Y) ReadTouch(ReadOnlySpan<byte> d)
        => (d[0] | ((d[1] & 0x0F) << 8), (d[1] >> 4) | (d[2] << 4));
}
