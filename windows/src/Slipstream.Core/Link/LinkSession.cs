using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>Timing of the receiver rules, in microseconds.</summary>
public readonly record struct LinkTiming(long FailsafeUs, long PulseUs, long GapUs, long TakeoverUs)
{
    public static LinkTiming Default => FromMilliseconds(200, 60, 40);

    public static LinkTiming FromMilliseconds(int failsafeMs, int pulseMs, int gapMs, int takeoverMs = 300)
        => new(failsafeMs * 1000L, pulseMs * 1000L, gapMs * 1000L, takeoverMs * 1000L);
}

public enum AcceptResult
{
    /// <summary>Newer packet in the current epoch: applied.</summary>
    Accepted,
    /// <summary>First packet of a newly adopted epoch: applied, counters reset, pulses baselined.</summary>
    AcceptedNewEpoch,
    /// <summary>Same or older seq than the last applied one (a multipath copy, or late): dropped.</summary>
    Duplicate,
    /// <summary>A different epoch while the current one is still alive (under 300 ms of silence): dropped.</summary>
    EpochRejected,
}

/// <summary>
/// The hub's receiver rules, PROTOCOL.md section 9 rules 2, 3, 4 and 6, applied literally.
/// Rule 1 (validation) happens before, in the codec; rule 5 (apply on the receive thread) is the
/// engine's job. Not thread-safe: the engine serializes all calls under one lock. Allocation-free.
/// </summary>
public sealed class LinkSession
{
    private LinkTiming _timing;
    private InputPacket _last;
    private ulong _pulseBaseline;

    public LinkSession(LinkTiming timing)
    {
        _timing = timing;
        Pulses = new PulseScheduler(timing.PulseUs, timing.GapUs);
    }

    public LinkTiming Timing
    {
        get => _timing;
        set
        {
            _timing = value;
            Pulses.PulseUs = value.PulseUs;
            Pulses.GapUs = value.GapUs;
        }
    }

    public PulseScheduler Pulses { get; }

    public bool HasEpoch { get; private set; }
    public uint Epoch { get; private set; }
    /// <summary>Highest seq applied in this epoch.</summary>
    public uint LastSeq { get; private set; }
    /// <summary>t_us of the packet <see cref="LastSeq"/>.</summary>
    public uint LastTimeUs { get; private set; }
    /// <summary>Hub receive time of <see cref="LastSeq"/>, microseconds.</summary>
    public long LastAcceptUs { get; private set; }
    /// <summary>Distinct packets applied in this epoch.</summary>
    public uint Accepted { get; private set; }
    /// <summary>Seq numbers skipped in this epoch.</summary>
    public uint Missing { get; private set; }
    /// <summary>True while no packet has been accepted for failsafe_ms (evaluated by <see cref="Update"/>).</summary>
    public bool InFailsafe { get; private set; }

    /// <summary>The last applied packet (valid when <see cref="HasEpoch"/>).</summary>
    public ref readonly InputPacket Last => ref _last;

    public AcceptResult Accept(in InputPacket p, long nowUs)
    {
        // Rule 2: epoch.
        if (!HasEpoch)
        {
            Adopt(in p, nowUs);
            return AcceptResult.AcceptedNewEpoch;
        }
        if (p.Epoch != Epoch)
        {
            if (nowUs - LastAcceptUs >= _timing.TakeoverUs)
            {
                Adopt(in p, nowUs);
                return AcceptResult.AcceptedNewEpoch;
            }
            return AcceptResult.EpochRejected;
        }

        // Rule 3: serial-number sequence.
        uint d = unchecked(p.Seq - LastSeq);
        if (d == 0 || d >= 0x8000_0000u) return AcceptResult.Duplicate;
        if (d > 1) Missing = unchecked(Missing + (d - 1));
        Accepted = unchecked(Accepted + 1);

        // Rule 4: pulse counters.
        for (int ch = 0; ch < Wire.PulseChannels; ch++)
        {
            int presses = SeqMath.PulseDelta(p.GetPulse(ch), (byte)(_pulseBaseline >> (ch * 8)));
            if (presses > 0) Pulses.Enqueue(ch, presses, nowUs);
        }
        _pulseBaseline = p.Pulses;

        Store(in p, nowUs);
        return AcceptResult.Accepted;
    }

    /// <summary>
    /// Advances pulse timing and evaluates the failsafe (rule 6). Returns true when the frame the
    /// device should show changed.
    /// </summary>
    public bool Update(long nowUs)
    {
        bool changed = Pulses.Update(nowUs);
        bool failsafe = HasEpoch && Failsafe.IsTripped(nowUs, LastAcceptUs, _timing.FailsafeUs);
        if (failsafe != InFailsafe)
        {
            InFailsafe = failsafe;
            changed = true;
        }
        return changed;
    }

    /// <summary>
    /// The frame to write to the device now. Rule 6: on failsafe or PAUSED the pedals and held
    /// buttons are released and queued pulses finish; steering holds on failsafe and centers on PAUSED.
    /// </summary>
    public ControllerFrame CurrentFrame() => Failsafe.Frame(HasEpoch, in _last, InFailsafe, Pulses.Mask);

    /// <summary>Forgets the epoch and every queued press (used when the pairing key changes).</summary>
    public void Reset()
    {
        HasEpoch = false;
        Epoch = 0;
        LastSeq = 0;
        LastTimeUs = 0;
        LastAcceptUs = 0;
        Accepted = 0;
        Missing = 0;
        InFailsafe = false;
        _last = default;
        _pulseBaseline = 0;
        Pulses.Clear();
    }

    private void Adopt(in InputPacket p, long nowUs)
    {
        HasEpoch = true;
        Epoch = p.Epoch;
        Accepted = 1;
        Missing = 0;
        // Baseline without emitting presses. Presses already queued from the previous epoch finish.
        _pulseBaseline = p.Pulses;
        Store(in p, nowUs);
    }

    private void Store(in InputPacket p, long nowUs)
    {
        _last = p;
        LastSeq = p.Seq;
        LastTimeUs = p.TimeUs;
        LastAcceptUs = nowUs;
        InFailsafe = false;
    }
}
