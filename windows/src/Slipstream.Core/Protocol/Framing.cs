using System.Buffers.Binary;
using System.Net.Sockets;

namespace Slipstream.Core.Protocol;

/// <summary>TCP framing for the USB link (PROTOCOL.md section 8): u16 length, then that many bytes.</summary>
public static class Framing
{
    /// <summary>Writes u16 length plus the packet. Returns bytes written.</summary>
    public static int WriteFrame(Span<byte> dest, ReadOnlySpan<byte> packet)
    {
        if (packet.Length > ushort.MaxValue) throw new ArgumentException("Packet too large for a frame.", nameof(packet));
        int total = Wire.FrameHeaderLength + packet.Length;
        if (dest.Length < total) throw new ArgumentException("Destination too small for the frame.", nameof(dest));
        BinaryPrimitives.WriteUInt16LittleEndian(dest, (ushort)packet.Length);
        packet.CopyTo(dest[Wire.FrameHeaderLength..]);
        return total;
    }

    /// <summary>Outcome of reading one frame.</summary>
    public enum ReadResult { Ok, Closed, BadLength }

    /// <summary>
    /// Reads one frame whose length must equal <paramref name="expectedLength"/> into
    /// <paramref name="buffer"/>. Blocks. A different length is <see cref="ReadResult.BadLength"/>:
    /// the caller must close the connection.
    /// </summary>
    public static ReadResult ReadFrame(Socket socket, Span<byte> buffer, int expectedLength)
    {
        Span<byte> header = stackalloc byte[Wire.FrameHeaderLength];
        if (!ReadExactly(socket, header)) return ReadResult.Closed;
        int length = BinaryPrimitives.ReadUInt16LittleEndian(header);
        if (length != expectedLength) return ReadResult.BadLength;
        return ReadExactly(socket, buffer[..length]) ? ReadResult.Ok : ReadResult.Closed;
    }

    /// <summary>Same as <see cref="ReadFrame(Socket, Span{byte}, int)"/> for an in-memory stream (tests, tools).</summary>
    public static ReadResult ReadFrame(Stream stream, Span<byte> buffer, int expectedLength)
    {
        Span<byte> header = stackalloc byte[Wire.FrameHeaderLength];
        if (stream.ReadAtLeast(header, header.Length, throwOnEndOfStream: false) < header.Length) return ReadResult.Closed;
        int length = BinaryPrimitives.ReadUInt16LittleEndian(header);
        if (length != expectedLength) return ReadResult.BadLength;
        return stream.ReadAtLeast(buffer[..length], length, throwOnEndOfStream: false) == length ? ReadResult.Ok : ReadResult.Closed;
    }

    /// <summary>True for a frame length the hub accepts: one INPUT (52) or one PAD (76) packet.</summary>
    public static bool IsHubFrameLength(int length) => length is Wire.InputLength or Wire.PadLength;

    /// <summary>
    /// Hub side of section 8: reads one frame of 52 (INPUT) or 76 (PAD) bytes into <paramref name="buffer"/>
    /// (at least 76 bytes). Blocks. Any other length is <see cref="ReadResult.BadLength"/>: the caller
    /// must close the connection.
    /// </summary>
    public static ReadResult ReadHubFrame(Socket socket, Span<byte> buffer, out int length)
    {
        length = 0;
        Span<byte> header = stackalloc byte[Wire.FrameHeaderLength];
        if (!ReadExactly(socket, header)) return ReadResult.Closed;
        int n = BinaryPrimitives.ReadUInt16LittleEndian(header);
        if (!IsHubFrameLength(n)) return ReadResult.BadLength;
        if (!ReadExactly(socket, buffer[..n])) return ReadResult.Closed;
        length = n;
        return ReadResult.Ok;
    }

    /// <summary>Same as <see cref="ReadHubFrame(Socket, Span{byte}, out int)"/> for an in-memory stream (tests, tools).</summary>
    public static ReadResult ReadHubFrame(Stream stream, Span<byte> buffer, out int length)
    {
        length = 0;
        Span<byte> header = stackalloc byte[Wire.FrameHeaderLength];
        if (stream.ReadAtLeast(header, header.Length, throwOnEndOfStream: false) < header.Length) return ReadResult.Closed;
        int n = BinaryPrimitives.ReadUInt16LittleEndian(header);
        if (!IsHubFrameLength(n)) return ReadResult.BadLength;
        if (stream.ReadAtLeast(buffer[..n], n, throwOnEndOfStream: false) != n) return ReadResult.Closed;
        length = n;
        return ReadResult.Ok;
    }

    private static bool ReadExactly(Socket socket, Span<byte> dest)
    {
        int got = 0;
        while (got < dest.Length)
        {
            int n = socket.Receive(dest[got..], SocketFlags.None);
            if (n <= 0) return false;
            got += n;
        }
        return true;
    }
}
