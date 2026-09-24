using System.Security.Cryptography;

namespace Slipstream.Core.Protocol;

/// <summary>
/// Truncated HMAC-SHA256 tags: tag(body) = HMAC-SHA256(K, body)[0..8).
/// Thread-safe and allocation-free: the static one-shot HMAC writes into a stack buffer,
/// and tags are compared in constant time.
/// </summary>
public sealed class PacketAuth
{
    private readonly byte[] _key;

    public PacketAuth(ReadOnlySpan<byte> key)
    {
        if (key.Length != Pairing.KeyLength) throw new ArgumentException("The pairing key is 32 bytes.", nameof(key));
        _key = key.ToArray();
    }

    /// <summary>Writes the 8 byte tag of <paramref name="body"/> into <paramref name="tag"/>.</summary>
    public void ComputeTag(ReadOnlySpan<byte> body, Span<byte> tag)
    {
        Span<byte> mac = stackalloc byte[32];
        HMACSHA256.HashData(_key, body, mac);
        mac[..Wire.TagLength].CopyTo(tag);
    }

    /// <summary>Constant-time check of an 8 byte tag.</summary>
    public bool Verify(ReadOnlySpan<byte> body, ReadOnlySpan<byte> tag)
    {
        if (tag.Length != Wire.TagLength) return false;
        Span<byte> mac = stackalloc byte[32];
        HMACSHA256.HashData(_key, body, mac);
        return CryptographicOperations.FixedTimeEquals(mac[..Wire.TagLength], tag);
    }
}
