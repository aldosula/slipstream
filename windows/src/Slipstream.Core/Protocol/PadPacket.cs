using System.Buffers.Binary;

namespace Slipstream.Core.Protocol;

/// <summary>
/// PAD (type 4, phone to hub, exactly 76 bytes), controller mode. PROTOCOL.md section 12.
/// Same epoch, sequence space and tag rules as INPUT; the tag covers bytes [0..68).
/// A plain value type: decoding reads straight from a span and never allocates.
/// </summary>
public struct PadPacket
{
    public uint Epoch;
    public uint Seq;
    public uint TimeUs;
    /// <summary>Left stick X, -32767 left .. +32767 right.</summary>
    public short Lx;
    /// <summary>Left stick Y, -32767 down .. +32767 up.</summary>
    public short Ly;
    public short Rx;
    /// <summary>Right stick Y, +up.</summary>
    public short Ry;
    /// <summary>Left trigger 0..65535 (L2 / LT).</summary>
    public ushort L2;
    /// <summary>Right trigger 0..65535 (R2 / RT).</summary>
    public ushort R2;
    /// <summary>Held state of the canonical buttons (section 12.2), bits 0..17; 18..31 reserved.</summary>
    public uint Buttons;
    /// <summary>
    /// Tap counters of buttons 0..15, the wire bytes 32..39 read little-endian: button b is the nibble
    /// at bits 4b..4b+3 (button 2k in the low nibble of byte k, 2k+1 in the high nibble).
    /// </summary>
    public ulong TapsLow;
    /// <summary>Wire byte 40: button 16 in the low nibble, button 17 in the high nibble.</summary>
    public byte TapsHigh;
    public byte Flags;
    public ushort Rtt100us;
    /// <summary>Touchpad finger 0, 0 left .. 65535 right.</summary>
    public ushort Touch0X;
    /// <summary>0 top .. 65535 bottom.</summary>
    public ushort Touch0Y;
    public ushort Touch1X;
    public ushort Touch1Y;
    /// <summary>Bit 7 active, bits 0..6 tracking id.</summary>
    public byte Touch0Id;
    public byte Touch1Id;
    /// <summary>Angular rate in 1/16 degree per second, controller frame (x right, y up, z toward the player).</summary>
    public short GyroX, GyroY, GyroZ;
    /// <summary>Acceleration in 1/4096 g, controller frame.</summary>
    public short AccelX, AccelY, AccelZ;
    public ushort Reserved;

    /// <summary>Touch id byte: bit 7 set while the finger is down.</summary>
    public const byte TouchActive = 0x80;

    public readonly bool Paused => (Flags & Wire.FlagPaused) != 0;
    public readonly bool Multipath => (Flags & Wire.FlagMultipath) != 0;
    public readonly bool Motion => (Flags & Wire.FlagMotion) != 0;
    public readonly bool StylePs => (Flags & Wire.FlagStylePs) != 0;

    /// <summary>The 4-bit tap counter of canonical button <paramref name="button"/> (0..17).</summary>
    public readonly byte GetTap(int button)
        => button < 16 ? (byte)((TapsLow >> (button * 4)) & 0xF) : (byte)((TapsHigh >> ((button - 16) * 4)) & 0xF);

    public void SetTap(int button, int value)
    {
        ulong v = (ulong)(value & 0xF);
        if (button < 16)
        {
            int shift = button * 4;
            TapsLow = (TapsLow & ~(0xFUL << shift)) | (v << shift);
        }
        else
        {
            int shift = (button - 16) * 4;
            TapsHigh = (byte)((TapsHigh & ~(0xF << shift)) | ((int)v << shift));
        }
    }

    /// <summary>Sets all 18 tap counters (each 0..15, only the low 4 bits are used).</summary>
    public void SetTaps(ReadOnlySpan<byte> taps)
    {
        if (taps.Length != Wire.PadButtons) throw new ArgumentException("Eighteen tap counters are required.", nameof(taps));
        for (int b = 0; b < Wire.PadButtons; b++) SetTap(b, taps[b]);
    }

    /// <summary>Writes the 68 byte body and its tag into <paramref name="dest"/> (at least 76 bytes).</summary>
    public readonly void Encode(Span<byte> dest, PacketAuth auth)
    {
        if (dest.Length < Wire.PadLength) throw new ArgumentException("PAD needs 76 bytes.", nameof(dest));
        Wire.WriteHeader(dest, Wire.TypePad);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[4..], Epoch);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[8..], Seq);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[12..], TimeUs);
        BinaryPrimitives.WriteInt16LittleEndian(dest[16..], Lx);
        BinaryPrimitives.WriteInt16LittleEndian(dest[18..], Ly);
        BinaryPrimitives.WriteInt16LittleEndian(dest[20..], Rx);
        BinaryPrimitives.WriteInt16LittleEndian(dest[22..], Ry);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[24..], L2);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[26..], R2);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[28..], Buttons);
        BinaryPrimitives.WriteUInt64LittleEndian(dest[32..], TapsLow);
        dest[40] = TapsHigh;
        dest[41] = Flags;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[42..], Rtt100us);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[44..], Touch0X);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[46..], Touch0Y);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[48..], Touch1X);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[50..], Touch1Y);
        dest[52] = Touch0Id;
        dest[53] = Touch1Id;
        BinaryPrimitives.WriteInt16LittleEndian(dest[54..], GyroX);
        BinaryPrimitives.WriteInt16LittleEndian(dest[56..], GyroY);
        BinaryPrimitives.WriteInt16LittleEndian(dest[58..], GyroZ);
        BinaryPrimitives.WriteInt16LittleEndian(dest[60..], AccelX);
        BinaryPrimitives.WriteInt16LittleEndian(dest[62..], AccelY);
        BinaryPrimitives.WriteInt16LittleEndian(dest[64..], AccelZ);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[66..], 0); // reserved, always sent as 0
        auth.ComputeTag(dest[..Wire.PadBodyLength], dest.Slice(Wire.PadBodyLength, Wire.TagLength));
    }

    /// <summary>
    /// Receiver rule 1 for PAD: header and exact length, then the tag. Nothing is written to
    /// <paramref name="packet"/> unless the result is <see cref="DecodeResult.Ok"/>.
    /// </summary>
    public static DecodeResult TryDecode(ReadOnlySpan<byte> data, PacketAuth auth, out PadPacket packet)
    {
        packet = default;
        if (data.Length != Wire.PadLength || !Wire.HeaderIs(data, Wire.TypePad)) return DecodeResult.Malformed;
        if (!auth.Verify(data[..Wire.PadBodyLength], data.Slice(Wire.PadBodyLength, Wire.TagLength))) return DecodeResult.BadTag;
        packet = ReadFields(data);
        return DecodeResult.Ok;
    }

    /// <summary>Reads the fields without checking the tag. For tests and tools only.</summary>
    public static PadPacket ReadFields(ReadOnlySpan<byte> data)
    {
        if (data.Length < Wire.PadBodyLength) throw new ArgumentException("Too short for PAD.", nameof(data));
        return new PadPacket
        {
            Epoch = BinaryPrimitives.ReadUInt32LittleEndian(data[4..]),
            Seq = BinaryPrimitives.ReadUInt32LittleEndian(data[8..]),
            TimeUs = BinaryPrimitives.ReadUInt32LittleEndian(data[12..]),
            Lx = BinaryPrimitives.ReadInt16LittleEndian(data[16..]),
            Ly = BinaryPrimitives.ReadInt16LittleEndian(data[18..]),
            Rx = BinaryPrimitives.ReadInt16LittleEndian(data[20..]),
            Ry = BinaryPrimitives.ReadInt16LittleEndian(data[22..]),
            L2 = BinaryPrimitives.ReadUInt16LittleEndian(data[24..]),
            R2 = BinaryPrimitives.ReadUInt16LittleEndian(data[26..]),
            Buttons = BinaryPrimitives.ReadUInt32LittleEndian(data[28..]),
            TapsLow = BinaryPrimitives.ReadUInt64LittleEndian(data[32..]),
            TapsHigh = data[40],
            Flags = data[41],
            Rtt100us = BinaryPrimitives.ReadUInt16LittleEndian(data[42..]),
            Touch0X = BinaryPrimitives.ReadUInt16LittleEndian(data[44..]),
            Touch0Y = BinaryPrimitives.ReadUInt16LittleEndian(data[46..]),
            Touch1X = BinaryPrimitives.ReadUInt16LittleEndian(data[48..]),
            Touch1Y = BinaryPrimitives.ReadUInt16LittleEndian(data[50..]),
            Touch0Id = data[52],
            Touch1Id = data[53],
            GyroX = BinaryPrimitives.ReadInt16LittleEndian(data[54..]),
            GyroY = BinaryPrimitives.ReadInt16LittleEndian(data[56..]),
            GyroZ = BinaryPrimitives.ReadInt16LittleEndian(data[58..]),
            AccelX = BinaryPrimitives.ReadInt16LittleEndian(data[60..]),
            AccelY = BinaryPrimitives.ReadInt16LittleEndian(data[62..]),
            AccelZ = BinaryPrimitives.ReadInt16LittleEndian(data[64..]),
            Reserved = BinaryPrimitives.ReadUInt16LittleEndian(data[66..]),
        };
    }
}
