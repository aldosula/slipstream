using System.Buffers.Binary;

namespace Slipstream.Core.Protocol;

/// <summary>STATUS (type 2, hub to phone, exactly 44 bytes). PROTOCOL.md section 6.</summary>
public struct StatusPacket
{
    public uint Epoch;
    public uint LastSeq;
    public uint EchoTimeUs;
    public uint HoldUs;
    public uint Accepted;
    public uint Missing;
    public ushort RumbleStrong;
    public ushort RumbleWeak;
    /// <summary>Low 7 bits: 0 none, 1 vJoy, 2 Xbox 360. Bit 7: output device error.</summary>
    public byte Output;
    public byte HubFlags;

    public readonly void Encode(Span<byte> dest, PacketAuth auth)
    {
        if (dest.Length < Wire.StatusLength) throw new ArgumentException("STATUS needs 44 bytes.", nameof(dest));
        Wire.WriteHeader(dest, Wire.TypeStatus);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[4..], Epoch);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[8..], LastSeq);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[12..], EchoTimeUs);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[16..], HoldUs);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[20..], Accepted);
        BinaryPrimitives.WriteUInt32LittleEndian(dest[24..], Missing);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[28..], RumbleStrong);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[30..], RumbleWeak);
        dest[32] = Output;
        dest[33] = HubFlags;
        BinaryPrimitives.WriteUInt16LittleEndian(dest[34..], 0); // reserved
        auth.ComputeTag(dest[..Wire.StatusBodyLength], dest.Slice(Wire.StatusBodyLength, Wire.TagLength));
    }

    public static DecodeResult TryDecode(ReadOnlySpan<byte> data, PacketAuth auth, out StatusPacket packet)
    {
        packet = default;
        if (data.Length != Wire.StatusLength || !Wire.HeaderIs(data, Wire.TypeStatus)) return DecodeResult.Malformed;
        if (!auth.Verify(data[..Wire.StatusBodyLength], data.Slice(Wire.StatusBodyLength, Wire.TagLength))) return DecodeResult.BadTag;
        packet = new StatusPacket
        {
            Epoch = BinaryPrimitives.ReadUInt32LittleEndian(data[4..]),
            LastSeq = BinaryPrimitives.ReadUInt32LittleEndian(data[8..]),
            EchoTimeUs = BinaryPrimitives.ReadUInt32LittleEndian(data[12..]),
            HoldUs = BinaryPrimitives.ReadUInt32LittleEndian(data[16..]),
            Accepted = BinaryPrimitives.ReadUInt32LittleEndian(data[20..]),
            Missing = BinaryPrimitives.ReadUInt32LittleEndian(data[24..]),
            RumbleStrong = BinaryPrimitives.ReadUInt16LittleEndian(data[28..]),
            RumbleWeak = BinaryPrimitives.ReadUInt16LittleEndian(data[30..]),
            Output = data[32],
            HubFlags = data[33],
        };
        return DecodeResult.Ok;
    }

    /// <summary>
    /// Phone-side RTT: rtt_us = (now_us - echo_t_us) - hold_us with u32 wrapping subtraction.
    /// </summary>
    public readonly uint RoundTripUs(uint nowUs) => unchecked(nowUs - EchoTimeUs - HoldUs);
}
