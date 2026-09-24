using System.Buffers.Binary;

namespace Slipstream.Core.Protocol;

/// <summary>
/// INPUT (type 1, phone to hub, exactly 52 bytes). PROTOCOL.md section 5.
/// A plain value type: decoding reads straight from a span and never allocates.
/// </summary>
public struct InputPacket
{
    public uint Epoch;
    public uint Seq;
    public uint TimeUs;
    public short Steer;
    public ushort Throttle;
    public ushort Brake;
    public ushort Clutch;
    public ushort Handbrake;
    public ushort Aux;
    public uint Buttons;
    /// <summary>Eight wrapping u8 press counters packed little-endian: byte j is channel j.</summary>
    public ulong Pulses;
    public byte Flags;
    public byte Reserved;
    public ushort Rtt100us;

    public readonly bool Paused => (Flags & Wire.FlagPaused) != 0;
    public readonly bool Calibrating => (Flags & Wire.FlagCalibrating) != 0;
    public readonly bool Multipath => (Flags & Wire.FlagMultipath) != 0;

    public readonly byte GetPulse(int channel) => (byte)(Pulses >> (channel * 8));

    public void SetPulse(int channel, byte value)
    {
        int shift = channel * 8;
        Pulses = (Pulses & ~(0xFFUL << shift)) | ((ulong)value << shift);
    }

    public static ulong PackPulses(ReadOnlySpan<byte> counters)
    {
        if (counters.Length != Wire.PulseChannels) throw new ArgumentException("Eight pulse counters are required.", nameof(counters));
        return BinaryPrimitives.ReadUInt64LittleEndian(counters);
    }

    /// <summary>Writes the 44 byte body and its tag into <paramref name="dest"/> (at least 52 bytes).</summary>
    public readonly void Encode(Span<byte> dest, PacketAuth auth)
    {
        if (dest.Length < Wire.InputLength) throw new ArgumentException("INPUT needs 52 bytes.", nameof(dest));
        Wire.WriteHeader(dest, Wire.TypeInput);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[4..], Epoch);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[8..], Seq);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[12..], TimeUs);
        BinaryPrimitives.WriteInt16LittleEndian(dest[16..], Steer);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[18..], Throttle);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[20..], Brake);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[22..], Clutch);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[24..], Handbrake);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[26..], Aux);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[28..], Buttons);
        BinaryPrimitives.WriteUInt64LittleEndian(dest[32..], Pulses);
        dest[40] = Flags;
        dest[41] = 0; // reserved, always sent as 0
        BinaryPrimitives.WriteUInt16LittleEndian(dest[42..], Rtt100us);
        auth.ComputeTag(dest[..Wire.InputBodyLength], dest.Slice(Wire.InputBodyLength, Wire.TagLength));
    }

    /// <summary>
    /// Receiver rule 1: validate header and exact length, then the tag. Nothing is written to
    /// <paramref name="packet"/> unless the result is <see cref="DecodeResult.Ok"/>.
    /// </summary>
    public static DecodeResult TryDecode(ReadOnlySpan<byte> data, PacketAuth auth, out InputPacket packet)
    {
        packet = default;
        if (data.Length != Wire.InputLength || !Wire.HeaderIs(data, Wire.TypeInput)) return DecodeResult.Malformed;
        if (!auth.Verify(data[..Wire.InputBodyLength], data.Slice(Wire.InputBodyLength, Wire.TagLength))) return DecodeResult.BadTag;
        packet = ReadFields(data);
        return DecodeResult.Ok;
    }

    /// <summary>Reads the fields without checking the tag. For tests and tools only.</summary>
    public static InputPacket ReadFields(ReadOnlySpan<byte> data)
    {
        if (data.Length < Wire.InputBodyLength) throw new ArgumentException("Too short for INPUT.", nameof(data));
        return new InputPacket
        {
            Epoch = BinaryPrimitives.ReadUInt32LittleEndian(data[4..]),
            Seq = BinaryPrimitives.ReadUInt32LittleEndian(data[8..]),
            TimeUs = BinaryPrimitives.ReadUInt32LittleEndian(data[12..]),
            Steer = BinaryPrimitives.ReadInt16LittleEndian(data[16..]),
            Throttle = BinaryPrimitives.ReadUInt16LittleEndian(data[18..]),
            Brake = BinaryPrimitives.ReadUInt16LittleEndian(data[20..]),
            Clutch = BinaryPrimitives.ReadUInt16LittleEndian(data[22..]),
            Handbrake = BinaryPrimitives.ReadUInt16LittleEndian(data[24..]),
            Aux = BinaryPrimitives.ReadUInt16LittleEndian(data[26..]),
            Buttons = BinaryPrimitives.ReadUInt32LittleEndian(data[28..]),
            Pulses = BinaryPrimitives.ReadUInt64LittleEndian(data[32..]),
            Flags = data[40],
            Reserved = data[41],
            Rtt100us = BinaryPrimitives.ReadUInt16LittleEndian(data[42..]),
        };
    }
}
