using System.Numerics;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>Timing of the receiver rules, in microseconds.</summary>
public readonly record struct LinkTiming(long FailsafeUs, long PulseUs, long GapUs, long TakeoverUs)
{
    /// <summary>Controller mode: how long a replayed tap is held down (12.4 rule 3, tap_ms).</summary>
    public long TapUs { get; init; } = 50_000;

    /// <summary>Controller mode: release between replayed taps, and before the first one when gap_first (gap_ms).</summary>
    public long TapGapUs { get; init; } = 40_000;

    public static LinkTiming Default => FromMilliseconds(200, 60, 40);

    public static LinkTiming FromMilliseconds(int failsafeMs, int pulseMs, int gapMs, int takeoverMs = 300, int tapMs = 50, int tapGapMs = 40)
        => new(failsafeMs * 1000L, pulseMs * 1000L, gapMs * 1000L, takeoverMs * 1000L) { TapUs = tapMs * 1000L, TapGapUs = tapGapMs * 1000L };
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

/// <summary>Which packet type the link last applied (PROTOCOL.md 12.4 rule 2).</summary>
public enum LinkMode
{
    /// <summary>Nothing applied yet in this session.</summary>
    None = 0,
    /// <summary>INPUT packets: racing wheel.</summary>
    Wheel = 1,
    /// <summary>PAD packets: gamepad.</summary>
    Controller = 2,
}

/// <summary>
/// The hub's receiver rules, PROTOCOL.md section 9 rules 2, 3, 4 and 6 and section 12.4, applied literally.
/// INPUT and PAD share one epoch and one sequence space. Rule 1 (validation) happens before, in the codecs;
/// rule 5 (apply on the receive thread) is the engine's job. Not thread-safe: the engine serializes all
/// calls under one lock. Allocation-free.
/// </summary>
public sealed class LinkSession
{
    private LinkTiming _timing;
    private InputPacket _last;
    private ulong _pulseBaseline;
    private PadPacket _lastPad;
    private ulong _tapBaselineLow;
    private byte _tapBaselineHigh;
    /// <summary>What the pad's buttons show now: held bits (unless neutralized) with running tap schedules on top.</summary>
    private uint _padOut;
    private readonly long[] _padPresses = new long[Wire.PadButtons];

    public LinkSession(LinkTiming timing)
    {
        _timing = timing;
        Pulses = new PulseScheduler(timing.PulseUs, timing.GapUs);
        Taps = new TapScheduler(timing.TapUs, timing.TapGapUs);
    }

    public LinkTiming Timing
    {
        get => _timing;
        set
        {
            _timing = value;
            Pulses.PulseUs = value.PulseUs;
            Pulses.GapUs = value.GapUs;
            Taps.TapUs = value.TapUs;
            Taps.GapUs = value.TapGapUs;
        }
    }

    public PulseScheduler Pulses { get; }

    public TapScheduler Taps { get; }

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

    /// <summary>The type of the last applied packet: wheel (INPUT) or controller (PAD).</summary>
    public LinkMode Mode { get; private set; }

    /// <summary>The last applied INPUT packet (valid when <see cref="Mode"/> is wheel).</summary>
    public ref readonly InputPacket Last => ref _last;

    /// <summary>The last applied PAD packet (valid when <see cref="Mode"/> is controller).</summary>
    public ref readonly PadPacket LastPad => ref _lastPad;

    /// <summary>PAUSED flag of the last applied packet, whatever its type.</summary>
    public bool LastPaused => Mode == LinkMode.Controller ? _lastPad.Paused : _last.Paused;

    /// <summary>MULTIPATH flag of the last applied packet.</summary>
    public bool LastMultipath => Mode == LinkMode.Controller ? _lastPad.Multipath : _last.Multipath;

    /// <summary>rtt_100us of the last applied packet.</summary>
    public ushort LastRtt100us => Mode == LinkMode.Controller ? _lastPad.Rtt100us : _last.Rtt100us;

    /// <summary>Presses the pad's buttons showed (released to pressed transitions), per canonical button.</summary>
    public long PadPresses(int button) => _padPresses[button];

    /// <summary>Current output bits of the canonical pad buttons.</summary>
    public uint PadButtonsOut => _padOut;

    public AcceptResult Accept(in InputPacket p, long nowUs)
    {
        AcceptResult r = Admit(p.Epoch, p.Seq, nowUs);
        switch (r)
        {
            case AcceptResult.AcceptedNewEpoch:
                // Rule 2: baseline without emitting presses. Presses already queued from the previous epoch finish.
                EnterMode(LinkMode.Wheel);
                _pulseBaseline = p.Pulses;
                break;
            case AcceptResult.Accepted when Mode != LinkMode.Wheel:
                // 12.4 rule 2: the packet type changed. Counters are the baseline, exactly as on adoption.
                EnterMode(LinkMode.Wheel);
                _pulseBaseline = p.Pulses;
                break;
            case AcceptResult.Accepted:
                // Rule 4: pulse counters.
                for (int ch = 0; ch < Wire.PulseChannels; ch++)
                {
                    int presses = SeqMath.PulseDelta(p.GetPulse(ch), (byte)(_pulseBaseline >> (ch * 8)));
                    if (presses > 0) Pulses.Enqueue(ch, presses, nowUs);
                }
                _pulseBaseline = p.Pulses;
                break;
            default:
                return r;
        }
        _last = p;
        Store(p.Seq, p.TimeUs, nowUs);
        return r;
    }

    public AcceptResult Accept(in PadPacket p, long nowUs)
    {
        AcceptResult r = Admit(p.Epoch, p.Seq, nowUs);
        switch (r)
        {
            case AcceptResult.AcceptedNewEpoch:
                // Rule 2 and 12.4 rule 2: taps baselined without presses; running schedules finish. A schedule
                // that goes on drives its button, so the adopted packet's held bits are handed to the scheduler
                // as its hidden presses (otherwise a press held while it runs would never show).
                EnterMode(LinkMode.Controller);
                Taps.Rebaseline(p.Buttons & Wire.PadButtonMask);
                break;
            case AcceptResult.Accepted when Mode != LinkMode.Controller:
                EnterMode(LinkMode.Controller);
                break;
            case AcceptResult.Accepted:
                // 12.4 rule 3, per button, against the output as it is right now.
                uint heldNew = p.Buttons & Wire.PadButtonMask;
                for (int b = 0; b < Wire.PadButtons; b++)
                {
                    int d = SeqMath.TapDelta(p.GetTap(b), BaselineTap(b));
                    if (d == 0 && !Taps.IsActive(b)) continue;
                    Taps.OnPacket(b, d, (heldNew & (1u << b)) != 0, (_padOut & (1u << b)) != 0, nowUs);
                }
                break;
            default:
                return r;
        }
        _tapBaselineLow = p.TapsLow;
        _tapBaselineHigh = p.TapsHigh;
        _lastPad = p;
        Store(p.Seq, p.TimeUs, nowUs);
        RefreshPadButtons();
        return r;
    }

    /// <summary>
    /// Advances pulse and tap timing and evaluates the failsafe (rule 6, 12.4 rule 4). Returns true when the
    /// frame the device should show changed.
    /// </summary>
    public bool Update(long nowUs)
    {
        bool changed = Pulses.Update(nowUs);
        changed |= Taps.Update(nowUs);
        bool failsafe = HasEpoch && Failsafe.IsTripped(nowUs, LastAcceptUs, _timing.FailsafeUs);
        if (failsafe != InFailsafe)
        {
            InFailsafe = failsafe;
            changed = true;
        }
        if (changed && Mode == LinkMode.Controller) RefreshPadButtons();
        return changed;
    }

    /// <summary>
    /// The wheel frame to write to the device now. Rule 6: on failsafe or PAUSED the pedals and held
    /// buttons are released and queued pulses finish; steering holds on failsafe and centers on PAUSED.
    /// Neutral outside wheel mode.
    /// </summary>
    public ControllerFrame CurrentFrame()
        => Mode == LinkMode.Controller
            ? new ControllerFrame(0, 0, 0, 0, 0, 0, Pulses.Mask)
            : Failsafe.Frame(HasEpoch, in _last, InFailsafe, Pulses.Mask);

    /// <summary>
    /// The pad frame to write to the device now. 12.4 rule 4: on failsafe or PAUSED the sticks centre,
    /// triggers are 0, held buttons are released, touch fingers are inactive and motion is zero; running
    /// tap schedules finish.
    /// </summary>
    public PadFrame CurrentPadFrame() => Failsafe.PadFrame(HasEpoch && Mode == LinkMode.Controller, in _lastPad, InFailsafe, _padOut);

    /// <summary>Forgets the epoch, the mode and every queued press (used when the pairing key changes).</summary>
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
        Mode = LinkMode.None;
        _last = default;
        _pulseBaseline = 0;
        _lastPad = default;
        _tapBaselineLow = 0;
        _tapBaselineHigh = 0;
        _padOut = 0;
        Pulses.Clear();
        Taps.Clear();
    }

    /// <summary>Rules 2 and 3 on the fields every packet type shares. Updates the epoch counters.</summary>
    private AcceptResult Admit(uint epoch, uint seq, long nowUs)
    {
        // Rule 2: epoch.
        if (!HasEpoch || (epoch != Epoch && nowUs - LastAcceptUs >= _timing.TakeoverUs))
        {
            HasEpoch = true;
            Epoch = epoch;
            Accepted = 1;
            Missing = 0;
            return AcceptResult.AcceptedNewEpoch;
        }
        if (epoch != Epoch) return AcceptResult.EpochRejected;

        // Rule 3: serial-number sequence.
        uint d = unchecked(seq - LastSeq);
        if (d == 0 || d >= 0x8000_0000u) return AcceptResult.Duplicate;
        if (d > 1) Missing = unchecked(Missing + (d - 1));
        Accepted = unchecked(Accepted + 1);
        return AcceptResult.Accepted;
    }

    /// <summary>
    /// 12.4 rule 2: when the packet type changes the previous mode's presses are dropped (its device goes to
    /// neutral, which the engine does) and the new mode starts from the packet's own counters.
    /// </summary>
    private void EnterMode(LinkMode mode)
    {
        if (Mode == mode) return;
        if (Mode == LinkMode.Controller)
        {
            Taps.Clear();
            _padOut = 0;
        }
        else if (Mode == LinkMode.Wheel)
        {
            Pulses.Clear();
        }
        Mode = mode;
    }

    private int BaselineTap(int b)
        => b < 16 ? (int)((_tapBaselineLow >> (b * 4)) & 0xF) : (_tapBaselineHigh >> ((b - 16) * 4)) & 0xF;

    /// <summary>Recomputes the pad's button output and counts every released to pressed transition.</summary>
    private void RefreshPadButtons()
    {
        bool neutral = !HasEpoch || Mode != LinkMode.Controller || _lastPad.Paused || InFailsafe;
        uint held = neutral ? 0 : _lastPad.Buttons & Wire.PadButtonMask;
        uint next = Taps.Compose(held);
        uint rising = next & ~_padOut;
        while (rising != 0)
        {
            int b = BitOperations.TrailingZeroCount(rising);
            rising &= rising - 1;
            _padPresses[b]++;
        }
        _padOut = next;
    }

    private void Store(uint seq, uint timeUs, long nowUs)
    {
        LastSeq = seq;
        LastTimeUs = timeUs;
        LastAcceptUs = nowUs;
        InFailsafe = false;
    }
}
