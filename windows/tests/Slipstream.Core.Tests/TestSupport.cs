using Slipstream.Core.Link;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>A scriptable phone: holds the controller state and builds signed INPUT packets.</summary>
internal sealed class FakePhone
{
    private readonly PairingKey _key;
    private readonly byte[] _pulses = new byte[8];
    public InputPacket State;

    public FakePhone(PairingKey key, uint epoch, uint firstSeq = 1)
    {
        _key = key;
        State.Epoch = epoch;
        State.Seq = firstSeq - 1;
    }

    public uint Seq => State.Seq;

    public void Press(int channel, int times = 1)
    {
        _pulses[channel] = unchecked((byte)(_pulses[channel] + times));
        State.Pulses = InputPacket.PackPulses(_pulses);
    }

    /// <summary>Builds the next packet (seq + 1) with the phone clock taken from <paramref name="nowUs"/>.</summary>
    public byte[] Next(long nowUs)
    {
        State.Seq = unchecked(State.Seq + 1);
        State.TimeUs = unchecked((uint)nowUs);
        var buf = new byte[Wire.InputLength];
        State.Encode(buf, _key.Auth);
        return buf;
    }
}

/// <summary>Collects STATUS packets sent to it.</summary>
internal sealed class FakeSink : IStatusSink
{
    private readonly List<byte[]> _received = new();

    public FakeSink(TransportKind transport, string endpoint = "fake")
    {
        Transport = transport;
        Endpoint = endpoint;
    }

    public TransportKind Transport { get; }
    public string Endpoint { get; }
    public bool IsOpen { get; set; } = true;

    public void SendStatus(ReadOnlySpan<byte> status)
    {
        lock (_received) _received.Add(status.ToArray());
    }

    public IReadOnlyList<byte[]> Received
    {
        get { lock (_received) return _received.ToArray(); }
    }
}

internal static class TestKeys
{
    public static PairingKey Main { get; } = PairingKey.FromCode("SLIPSTREAMTEST22");
    public static PairingKey Other { get; } = PairingKey.FromCode("ABCDEFGHIJKLMNOP");
}

/// <summary>A scriptable phone in controller mode: holds the pad state and builds signed PAD packets.</summary>
internal sealed class FakePadPhone
{
    private readonly PairingKey _key;
    public PadPacket State;

    public FakePadPhone(PairingKey key, uint epoch, uint firstSeq = 1, bool playStation = true)
    {
        _key = key;
        State.Epoch = epoch;
        State.Seq = firstSeq - 1;
        if (playStation) State.Flags |= Wire.FlagStylePs;
    }

    public uint Seq
    {
        get => State.Seq;
        set => State.Seq = value;
    }

    /// <summary>Press-down: the held bit and the tap counter change in the same snapshot (PROTOCOL.md 12.3).</summary>
    public void Press(int button)
    {
        State.Buttons |= 1u << button;
        State.SetTap(button, State.GetTap(button) + 1);
    }

    public void Release(int button) => State.Buttons &= ~(1u << button);

    /// <summary>Whole taps (press and release) that happen between two packets.</summary>
    public void Tap(int button, int times = 1) => State.SetTap(button, State.GetTap(button) + times);

    /// <summary>Builds the next packet (seq + 1) with the phone clock taken from <paramref name="nowUs"/>.</summary>
    public byte[] Next(long nowUs)
    {
        State.Seq = unchecked(State.Seq + 1);
        State.TimeUs = unchecked((uint)nowUs);
        var buf = new byte[Wire.PadLength];
        State.Encode(buf, _key.Auth);
        return buf;
    }
}
