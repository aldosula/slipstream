namespace Slipstream.Core.Protocol;

/// <summary>Constants of the Slipstream Link Protocol v1 (docs/PROTOCOL.md). All integers little-endian.</summary>
public static class Wire
{
    public const byte Magic0 = 0x53; // 'S'
    public const byte Magic1 = 0x4C; // 'L'
    public const byte Version = 1;

    public const byte TypeInput = 1;
    public const byte TypeStatus = 2;
    public const byte TypeBeacon = 3;
    /// <summary>PAD, controller mode (section 12).</summary>
    public const byte TypePad = 4;

    public const int HeaderLength = 4;
    public const int TagLength = 8;
    public const int InputLength = 52;
    public const int StatusLength = 44;
    public const int InputBodyLength = InputLength - TagLength;   // 44
    public const int StatusBodyLength = StatusLength - TagLength; // 36
    public const int PadLength = 76;
    public const int PadBodyLength = PadLength - TagLength;       // 68
    public const int BeaconFixedLength = 17;
    public const int BeaconMaxNameBytes = 32;
    public const int BeaconMaxLength = BeaconFixedLength + BeaconMaxNameBytes;
    public const int FrameHeaderLength = 2;

    public const int DefaultUdpPort = 47800;
    public const int DefaultBeaconPort = 47801;
    public const int DefaultTcpPort = 47802;

    public const int PulseChannels = 8;

    /// <summary>Canonical pad buttons (section 12.2), bits 0..17 of the PAD buttons field.</summary>
    public const int PadButtons = 18;
    /// <summary>Mask of the canonical pad button bits; 18..31 are reserved.</summary>
    public const uint PadButtonMask = (1u << PadButtons) - 1;
    /// <summary>Bytes of packed 4-bit tap counters in PAD (offset 32).</summary>
    public const int PadTapBytes = PadButtons / 2;

    /// <summary>INPUT flags byte (offset 40).</summary>
    public const byte FlagPaused = 0x01;
    public const byte FlagCalibrating = 0x02;
    public const byte FlagMultipath = 0x04;
    /// <summary>PAD flags byte (offset 41): gyro and accel fields are valid.</summary>
    public const byte FlagMotion = 0x08;
    /// <summary>PAD flags byte: PlayStation layout (clear means Xbox layout).</summary>
    public const byte FlagStylePs = 0x10;

    /// <summary>STATUS output byte: bit 7 is the output device error flag.</summary>
    public const byte OutputErrorBit = 0x80;

    /// <summary>Writes the 4 byte common header.</summary>
    public static void WriteHeader(Span<byte> dest, byte type)
    {
        dest[0] = Magic0;
        dest[1] = Magic1;
        dest[2] = Version;
        dest[3] = type;
    }

    /// <summary>True when the 4 byte common header is valid for the given type.</summary>
    public static bool HeaderIs(ReadOnlySpan<byte> packet, byte type)
        => packet.Length >= HeaderLength
           && packet[0] == Magic0 && packet[1] == Magic1 && packet[2] == Version && packet[3] == type;
}

/// <summary>Why a received packet was rejected before it touched any state.</summary>
public enum DecodeResult
{
    Ok = 0,
    /// <summary>Wrong magic, version, type or exact length.</summary>
    Malformed = 1,
    /// <summary>Header and length fine, HMAC tag wrong.</summary>
    BadTag = 2,
}
