using System.Security.Cryptography;
using System.Text;

namespace Slipstream.Core.Protocol;

/// <summary>Pairing code handling (PROTOCOL.md section 2).</summary>
public static class Pairing
{
    /// <summary>RFC 4648 base32 alphabet.</summary>
    public const string Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    public const int CodeLength = 16;
    public const int RawLength = 10;
    public const int KeyLength = 32;
    public const int FingerprintLength = 8;

    private static readonly byte[] KeyLabel = "slipstream/v1/key"u8.ToArray();
    private static readonly byte[] FingerprintLabel = "slipstream/v1/fp"u8.ToArray();

    /// <summary>
    /// Normalizes user input: uppercase; drop '-', space and tab; map 0 to O, 1 to I, 8 to B;
    /// any other character outside the alphabet makes the code invalid; result must be 16 chars.
    /// Returns null when invalid.
    /// </summary>
    public static string? Normalize(string? text)
    {
        if (text is null) return null;
        Span<char> buf = stackalloc char[CodeLength];
        int n = 0;
        foreach (char raw in text)
        {
            // The reference uses Python's str.upper(), a full case mapping. The only full
            // mappings that expand into this alphabet are sharp s and the Latin ligatures.
            string? expansion = raw switch
            {
                'ß' => "SS", 'ﬀ' => "FF", 'ﬁ' => "FI", 'ﬂ' => "FL",
                'ﬃ' => "FFI", 'ﬄ' => "FFL", 'ﬅ' => "ST", 'ﬆ' => "ST",
                _ => null,
            };
            if (expansion is null)
            {
                if (!Accept(char.ToUpperInvariant(raw), buf, ref n)) return null;
            }
            else
            {
                foreach (char ch in expansion)
                    if (!Accept(ch, buf, ref n)) return null;
            }
        }
        return n == CodeLength ? new string(buf) : null;

        static bool Accept(char ch, Span<char> buf, ref int n)
        {
            if (ch is '-' or ' ' or '\t') return true;
            ch = ch switch { '0' => 'O', '1' => 'I', '8' => 'B', _ => ch };
            if (!IsAlphabet(ch)) return false;
            if (n == CodeLength) return false; // longer than 16, invalid either way
            buf[n++] = ch;
            return true;
        }
    }

    private static bool IsAlphabet(char ch) => ch is >= 'A' and <= 'Z' or >= '2' and <= '7';

    private static int ValueOf(char ch) => ch >= 'A' && ch <= 'Z' ? ch - 'A' : ch >= '2' && ch <= '7' ? ch - '2' + 26 : -1;

    /// <summary>Decodes a normalized 16 character code into its 10 raw bytes.</summary>
    public static byte[] Base32Decode(string code)
    {
        if (code is null || code.Length != CodeLength) throw new ArgumentException("A pairing code has exactly 16 base32 characters.", nameof(code));
        var raw = new byte[RawLength];
        int bitBuffer = 0, bits = 0, o = 0;
        foreach (char ch in code)
        {
            int v = ValueOf(ch);
            if (v < 0) throw new ArgumentException($"'{ch}' is not a base32 character.", nameof(code));
            bitBuffer = (bitBuffer << 5) | v;
            bits += 5;
            if (bits >= 8)
            {
                bits -= 8;
                raw[o++] = (byte)(bitBuffer >> bits);
                bitBuffer &= (1 << bits) - 1;
            }
        }
        return raw;
    }

    /// <summary>Encodes exactly 10 bytes as 16 base32 characters (no padding needed).</summary>
    public static string Base32Encode(ReadOnlySpan<byte> raw)
    {
        if (raw.Length != RawLength) throw new ArgumentException("Exactly 10 bytes are required.", nameof(raw));
        Span<char> chars = stackalloc char[CodeLength];
        int bitBuffer = 0, bits = 0, o = 0;
        foreach (byte b in raw)
        {
            bitBuffer = (bitBuffer << 8) | b;
            bits += 8;
            while (bits >= 5)
            {
                bits -= 5;
                chars[o++] = Alphabet[(bitBuffer >> bits) & 31];
            }
            bitBuffer &= (1 << bits) - 1;
        }
        return new string(chars);
    }

    /// <summary>K = SHA256("slipstream/v1/key" || raw).</summary>
    public static byte[] DeriveKey(string code)
    {
        byte[] raw = Base32Decode(code);
        Span<byte> input = stackalloc byte[KeyLabel.Length + RawLength];
        KeyLabel.CopyTo(input);
        raw.CopyTo(input[KeyLabel.Length..]);
        return SHA256.HashData(input);
    }

    /// <summary>fingerprint = SHA256("slipstream/v1/fp" || K)[0..8).</summary>
    public static byte[] Fingerprint(ReadOnlySpan<byte> key)
    {
        Span<byte> input = stackalloc byte[FingerprintLabel.Length + key.Length];
        FingerprintLabel.CopyTo(input);
        key.CopyTo(input[FingerprintLabel.Length..]);
        Span<byte> digest = stackalloc byte[32];
        SHA256.HashData(input, digest);
        return digest[..FingerprintLength].ToArray();
    }

    /// <summary>A fresh 16 character code carrying 80 bits from the OS CSPRNG.</summary>
    public static string GenerateCode()
    {
        Span<byte> raw = stackalloc byte[RawLength];
        RandomNumberGenerator.Fill(raw);
        string code = Base32Encode(raw);
        CryptographicOperations.ZeroMemory(raw);
        return code;
    }

    /// <summary>Display form, four groups of four: ABCD-EFGH-IJKL-MNOP.</summary>
    public static string Display(string code)
    {
        if (code.Length != CodeLength) throw new ArgumentException("A pairing code has exactly 16 characters.", nameof(code));
        return string.Create(19, code, static (span, c) =>
        {
            int o = 0;
            for (int i = 0; i < CodeLength; i++)
            {
                if (i > 0 && i % 4 == 0) span[o++] = '-';
                span[o++] = c[i];
            }
        });
    }

    /// <summary>
    /// QR payload: slipstream://pair?v=1&amp;code=...&amp;name=...&amp;port=...&amp;tcp=...&amp;host=...(repeated).
    /// </summary>
    public static string BuildPairUri(string code, string hubName, int udpPort, int tcpPort, IEnumerable<string> hosts)
    {
        string normalized = Normalize(code) ?? throw new ArgumentException("Invalid pairing code.", nameof(code));
        var sb = new StringBuilder(160);
        sb.Append("slipstream://pair?v=1&code=").Append(normalized)
          .Append("&name=").Append(Uri.EscapeDataString(hubName))
          .Append("&port=").Append(udpPort)
          .Append("&tcp=").Append(tcpPort);
        foreach (string host in hosts) sb.Append("&host=").Append(Uri.EscapeDataString(host));
        return sb.ToString();
    }

    /// <summary>Truncates a hub name to at most 32 UTF-8 bytes without splitting a character.</summary>
    public static string TruncateName(string? name)
    {
        name = (name ?? string.Empty).Trim();
        if (Encoding.UTF8.GetByteCount(name) <= Wire.BeaconMaxNameBytes) return name;
        var sb = new StringBuilder();
        int bytes = 0;
        var e = System.Globalization.StringInfo.GetTextElementEnumerator(name);
        while (e.MoveNext())
        {
            string element = e.GetTextElement();
            int n = Encoding.UTF8.GetByteCount(element);
            if (bytes + n > Wire.BeaconMaxNameBytes) break;
            sb.Append(element);
            bytes += n;
        }
        return sb.ToString();
    }
}

/// <summary>A validated pairing code with its derived key, fingerprint and tag engine. Immutable.</summary>
public sealed class PairingKey
{
    private readonly byte[] _fingerprint;

    private PairingKey(string code)
    {
        Code = code;
        byte[] key = Pairing.DeriveKey(code);
        _fingerprint = Pairing.Fingerprint(key);
        Auth = new PacketAuth(key);
        CryptographicOperations.ZeroMemory(key);
    }

    /// <summary>Normalized 16 character code.</summary>
    public string Code { get; }

    /// <summary>ABCD-EFGH-IJKL-MNOP.</summary>
    public string DisplayCode => Pairing.Display(Code);

    /// <summary>8 byte public fingerprint used in beacons.</summary>
    public ReadOnlySpan<byte> Fingerprint => _fingerprint;

    public string FingerprintHex => Convert.ToHexString(_fingerprint).ToLowerInvariant();

    /// <summary>HMAC tag engine for this key.</summary>
    public PacketAuth Auth { get; }

    public static PairingKey FromCode(string text)
        => TryFromCode(text, out var key) ? key! : throw new ArgumentException("Invalid pairing code. It needs 16 characters from A-Z and 2-7.", nameof(text));

    public static bool TryFromCode(string? text, out PairingKey? key)
    {
        string? code = Pairing.Normalize(text);
        key = code is null ? null : new PairingKey(code);
        return key is not null;
    }

    public static PairingKey Generate() => new(Pairing.GenerateCode());
}
