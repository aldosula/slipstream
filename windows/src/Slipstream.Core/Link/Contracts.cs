using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

public enum TransportKind
{
    Udp = 0,
    Tcp = 1,
}

/// <summary>An endpoint STATUS can be sent back to: one UDP source address or one TCP connection.</summary>
public interface IStatusSink
{
    TransportKind Transport { get; }

    /// <summary>Human readable address, for the UI.</summary>
    string Endpoint { get; }

    bool IsOpen { get; }

    /// <summary>
    /// Sends one 44 byte STATUS packet. Called on the housekeeping thread, outside the engine lock.
    /// Must not block for long and must not throw.
    /// </summary>
    void SendStatus(ReadOnlySpan<byte> status);
}

/// <summary>What happened to one received datagram or frame.</summary>
public enum ReceiveOutcome
{
    Accepted,
    AcceptedNewEpoch,
    Duplicate,
    EpochRejected,
    Malformed,
    BadTag,
    /// <summary>Verified with a key that was replaced while the packet was in flight.</summary>
    StaleKey,
}

public enum LinkState
{
    /// <summary>No phone has sent a valid packet yet (or the pairing code changed).</summary>
    Waiting,
    /// <summary>Packets arriving, controls applied.</summary>
    Live,
    /// <summary>The phone is not on its drive screen: neutral output, steering centred.</summary>
    Paused,
    /// <summary>No packet for failsafe_ms: pedals released, steering held.</summary>
    Failsafe,
    /// <summary>No packet for 2 s: the phone is gone.</summary>
    Lost,
}

/// <summary>Engine settings. Milliseconds, as in hub.json.</summary>
public sealed record HubEngineOptions
{
    public int FailsafeMs { get; init; } = 200;
    public int PulseMs { get; init; } = 60;
    public int GapMs { get; init; } = 40;
    public int TakeoverMs { get; init; } = 300;
    public int StatusIntervalMs { get; init; } = 50;          // 20 Hz
    public int StatusSinkTimeoutMs { get; init; } = 1000;     // endpoints that delivered in the last second
    public int LostAfterMs { get; init; } = 2000;
    public AxisInvert Invert { get; init; } = AxisInvert.None;
    /// <summary>Controller mode: a replayed tap is down this long (12.4 rule 3, tap_ms).</summary>
    public int TapMs { get; init; } = 50;
    /// <summary>Controller mode: release between replayed taps (12.4 rule 3, gap_ms).</summary>
    public int TapGapMs { get; init; } = 40;
    /// <summary>Controller mode: which virtual pad to drive (12.4 rule 5).</summary>
    public PadOutputSelection PadOutput { get; init; } = PadOutputSelection.Auto;

    public LinkTiming Timing => LinkTiming.FromMilliseconds(FailsafeMs, PulseMs, GapMs, TakeoverMs, TapMs, TapGapMs);
}

/// <summary>Per-transport counters in a snapshot.</summary>
public sealed record TransportStats
{
    public TransportKind Kind { get; init; }
    /// <summary>Packets with a valid tag (first arrivals, duplicates and foreign epochs).</summary>
    public long Packets { get; init; }
    /// <summary>Packets from this transport that were applied (arrived first).</summary>
    public long FirstArrivals { get; init; }
    public long Duplicates { get; init; }
    public long BadTags { get; init; }
    public long Malformed { get; init; }
    public long ForeignEpoch { get; init; }
    public double PacketsPerSecond { get; init; }
    public double FirstArrivalsPerSecond { get; init; }
    /// <summary>0..1: this transport's share of the applied packets over the last rate window.</summary>
    public double FirstArrivalShare { get; init; }
}

/// <summary>A consistent, immutable copy of the engine state for the UI and tools. Never used on the hot path.</summary>
public sealed record HubSnapshot
{
    public LinkState State { get; init; }
    public bool HasEpoch { get; init; }
    public uint Epoch { get; init; }
    public uint LastSeq { get; init; }
    public uint Accepted { get; init; }
    public uint Missing { get; init; }
    public long TotalAccepted { get; init; }
    public long TotalMissing { get; init; }
    public long EpochChanges { get; init; }
    /// <summary>missing / (accepted + missing) over the epoch, percent.</summary>
    public double LossPercent { get; init; }
    /// <summary>Same over the last rate window, percent.</summary>
    public double RecentLossPercent { get; init; }
    /// <summary>Applied packets per second, all transports together.</summary>
    public double RateHz { get; init; }
    /// <summary>The phone's smoothed RTT from rtt_100us, null when the phone does not know yet.</summary>
    public double? PhoneRttMs { get; init; }
    public double? SinceLastPacketMs { get; init; }
    public bool Paused { get; init; }
    public bool Calibrating { get; init; }
    public bool Multipath { get; init; }
    public InputPacket LastInput { get; init; }
    public ControllerFrame Frame { get; init; }
    public OutputFrame Output { get; init; }
    /// <summary>
    /// The frame written to the device when the newest packet was applied. Unlike <see cref="Output"/>
    /// it does not follow a later failsafe or pulse release, so tools can check what a packet produced.
    /// </summary>
    public OutputFrame AcceptedOutput { get; init; }
    public IReadOnlyList<TransportStats> Transports { get; init; } = Array.Empty<TransportStats>();
    public IReadOnlyList<long> PulsesEmitted { get; init; } = Array.Empty<long>();
    public IReadOnlyList<int> PulsesPending { get; init; } = Array.Empty<int>();
    public string OutputName { get; init; } = "";
    public OutputKind OutputKind { get; init; }
    public OutputState OutputState { get; init; }
    public string OutputDetail { get; init; } = "";
    public long OutputErrors { get; init; }
    public string? LastOutputError { get; init; }
    public long StatusSent { get; init; }
    public IReadOnlyList<string> StatusEndpoints { get; init; } = Array.Empty<string>();
    public string PairingCode { get; init; } = "";
    public string FingerprintHex { get; init; } = "";

    // ------------------------------------------------------------ controller mode ---

    /// <summary>Type of the last applied packet: wheel (INPUT) or controller (PAD). None before the first.</summary>
    public LinkMode Mode { get; init; }
    /// <summary>The phone's layout in controller mode, <see cref="PadStyle.None"/> in wheel mode.</summary>
    public PadStyle Style { get; init; }
    /// <summary>The newest PAD packet as decoded from the wire (default when none was applied).</summary>
    public PadPacket LastPad { get; init; }
    /// <summary>What the pad shows now, after the tap scheduler, failsafe and PAUSED.</summary>
    public PadFrame PadFrame { get; init; }
    /// <summary>The mapped pad frame last written (or computed, when no pad device is plugged in yet).</summary>
    public PadOutputFrame PadOutput { get; init; }
    /// <summary>The pad frame written when the newest PAD packet was applied (does not follow a later failsafe).</summary>
    public PadOutputFrame AcceptedPadOutput { get; init; }
    /// <summary>Presses the virtual pad showed per canonical button (passed through and replayed together).</summary>
    public IReadOnlyList<long> TapsEmitted { get; init; } = Array.Empty<long>();
    /// <summary>Of those, taps the scheduler replayed because packets were lost.</summary>
    public IReadOnlyList<long> TapsReplayed { get; init; } = Array.Empty<long>();
    /// <summary>Replay taps queued but not started, per canonical button.</summary>
    public IReadOnlyList<int> TapsPending { get; init; } = Array.Empty<int>();
    /// <summary>The "Controller output" setting.</summary>
    public PadOutputSelection PadOutputSelection { get; init; }
    /// <summary>The pad kind the setting resolves to for the current style (Auto follows STYLE_PS).</summary>
    public OutputKind PadOutputWanted { get; init; }
    /// <summary>True once a pad device is plugged in (it stays until the hub quits or the output changes).</summary>
    public bool PadPlugged { get; init; }
    public string PadOutputName { get; init; } = "";
    public OutputKind PadOutputKind { get; init; }
    public OutputState PadOutputState { get; init; }
    public string PadOutputDetail { get; init; } = "";

    public TransportStats Transport(TransportKind kind) => Transports[(int)kind];
}
