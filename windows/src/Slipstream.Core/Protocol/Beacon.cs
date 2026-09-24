using System.Buffers.Binary;
using System.Text;

namespace Slipstream.Core.Protocol;

/// <summary>A decoded BEACON. <see cref="FingerprintHex"/> is lowercase hex of the 8 fingerprint bytes.</summary>
public sealed record BeaconInfo(ushort UdpPort, ushort TcpPort, string FingerprintHex, string Name);

/// <summary>BEACON (type 3, hub to UDP broadcast 47801, 17 + n bytes, unauthenticated). PROTOCOL.md section 7.</summary>
public static class Beacon
{
    /// <summary>Encodes a beacon. Returns the number of bytes written (17 + name bytes).</summary>
    public static int Encode(Span<byte> dest, ushort udpPort, ushort tcpPort, ReadOnlySpan<byte> fingerprint, string name)
    {
        if (fingerprint.Length != Pairing.FingerprintLength) throw new ArgumentException("The fingerprint is 8 bytes.", nameof(fingerprint));
        int nameBytes = Encoding.UTF8.GetByteCount(name);
        if (nameBytes > Wire.BeaconMaxNameBytes) throw new ArgumentException("The hub name is at most 32 UTF-8 bytes.", nameof(name));
        int total = Wire.BeaconFixedLength + nameBytes;
        if (dest.Length < total) throw new ArgumentException("Destination too small for the beacon.", nameof(dest));
        Wire.WriteHeader(dest, Wire.TypeBeacon);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[4..], udpPort);
        BinaryPrimitives.WriteUInt16LittleEndian(dest[6..], tcpPort);
        fingerprint.CopyTo(dest[8..16]);
        dest[16] = (byte)nameBytes;
        Encoding.UTF8.GetBytes(name, dest[17..total]);
        return total;
    }

    /// <summary>Validates header, name length and exact total length.</summary>
    public static bool TryDecode(ReadOnlySpan<byte> data, out BeaconInfo? beacon)
    {
        beacon = null;
        if (data.Length < Wire.BeaconFixedLength || !Wire.HeaderIs(data, Wire.TypeBeacon)) return false;
        int n = data[16];
        if (n > Wire.BeaconMaxNameBytes || data.Length != Wire.BeaconFixedLength + n) return false;
        beacon = new BeaconInfo(
            BinaryPrimitives.ReadUInt16LittleEndian(data[4..]),
            BinaryPrimitives.ReadUInt16LittleEndian(data[6..]),
            Convert.ToHexString(data[8..16]).ToLowerInvariant(),
            Encoding.UTF8.GetString(data.Slice(17, n)));
        return true;
    }
}
