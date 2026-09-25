using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// Per canonical button replay of presses that packet loss hid (PROTOCOL.md 12.4 rule 3). For one accepted
/// PAD packet and one button, with d = TapDelta(new, old): nothing when d = 0; otherwise replay = d - 1 when
/// the held bit is set, else d, and gap_first = the virtual button is down right now. The schedule releases
/// for gap_ms first when gap_first, then plays replay taps of tap_ms down and gap_ms up, then the button
/// follows its held bit again. While a schedule runs it drives the button; taps of later packets are
/// appended (gap_first ignored), at most 15 queued per button. Without loss nothing is ever scheduled, so
/// presses and releases pass straight through.
/// <para>
/// One addition to the literal rule, so no press is lost: while a schedule runs, the press that the held
/// bit stands for is hidden (the schedule drives the button). If that held bit clears before the schedule
/// ends, or a newer press proves it ended, the hidden press is appended as one more tap instead of
/// vanishing. If it is still held when the schedule ends, the button follows the held bit and shows it.
/// On epoch adoption (<see cref="Rebaseline"/>) the same holds for a press the new epoch's first packet
/// carries on a button whose schedule is still running.
/// </para>
/// Not thread-safe; the session calls it under the engine lock. Allocation-free.
/// </summary>
public sealed class TapScheduler
{
    /// <summary>At most this many taps wait per button (12.4 rule 3).</summary>
    public const int MaxQueued = 15;

    private const byte Idle = 0, Down = 1, Up = 2;

    private readonly byte[] _phase = new byte[Wire.PadButtons];
    private readonly long[] _phaseEndUs = new long[Wire.PadButtons];
    private readonly int[] _pending = new int[Wire.PadButtons];
    private readonly long[] _replayed = new long[Wire.PadButtons];
    /// <summary>Bit b: a held press on button b that the running schedule hides from the output.</summary>
    private uint _hidden;

    public TapScheduler(long tapUs, long gapUs)
    {
        TapUs = tapUs;
        GapUs = gapUs;
    }

    public long TapUs { get; set; }
    public long GapUs { get; set; }

    /// <summary>Bit b set while a schedule drives button b.</summary>
    public uint ActiveMask { get; private set; }

    /// <summary>Bit b set while button b is in the down phase of a replayed tap.</summary>
    public uint DownMask { get; private set; }

    /// <summary>
    /// The reference rule (tools/gen_test_vectors.py tap_schedule) for a button with no schedule running:
    /// (gap_first, replay taps) for the virtual button state, the held bit and d.
    /// </summary>
    public static (bool GapFirst, int ReplayTaps) Plan(bool outputDown, bool held, int d)
        => d == 0 ? (false, 0) : (outputDown, held ? d - 1 : d);

    /// <summary>Taps replayed (started by a schedule) since creation, per button.</summary>
    public long Replayed(int button) => _replayed[button];

    /// <summary>Taps queued but not started yet.</summary>
    public int Pending(int button) => _pending[button];

    public bool IsActive(int button) => (ActiveMask & (1u << button)) != 0;

    /// <summary>The output bits: a running schedule overrides the held bit.</summary>
    public uint Compose(uint held) => (held & ~ActiveMask) | DownMask;

    /// <summary>
    /// Rule 3 for one button of one accepted PAD packet. <paramref name="d"/> is the tap delta,
    /// <paramref name="held"/> the packet's held bit, <paramref name="outputDown"/> the virtual button before
    /// this packet. Call for every button whose d is not 0 and for every button with a running schedule.
    /// </summary>
    public void OnPacket(int button, int d, bool held, bool outputDown, long nowUs)
    {
        uint bit = 1u << button;
        if ((ActiveMask & bit) != 0)
        {
            // A schedule runs: append, gap_first is ignored.
            int add = 0;
            if (d > 0)
            {
                add = held ? d - 1 : d;
                if ((_hidden & bit) != 0) add++; // a newer press means the hidden one was released
            }
            else if (!held && (_hidden & bit) != 0)
            {
                add = 1; // the hidden press ended before the schedule did
            }
            if (d > 0 || !held) _hidden = held ? _hidden | bit : _hidden & ~bit;
            Append(button, add);
            return;
        }

        (bool gapFirst, int replay) = Plan(outputDown, held, d);
        if (!gapFirst && replay == 0) return; // pass-through: nothing scheduled
        _pending[button] = Math.Min(replay, MaxQueued);
        ActiveMask |= bit;
        _hidden = held ? _hidden | bit : _hidden & ~bit;
        if (gapFirst)
        {
            _phase[button] = Up;
            _phaseEndUs[button] = nowUs + GapUs;
            DownMask &= ~bit;
        }
        else
        {
            StartTap(button, nowUs);
        }
    }

    /// <summary>Advances every running schedule to <paramref name="nowUs"/>. True when a mask changed.</summary>
    public bool Update(long nowUs)
    {
        uint activeBefore = ActiveMask, downBefore = DownMask;
        uint active = ActiveMask;
        while (active != 0)
        {
            int b = System.Numerics.BitOperations.TrailingZeroCount(active);
            active &= active - 1;
            if (nowUs < _phaseEndUs[b]) continue;
            uint bit = 1u << b;
            if (_phase[b] == Down)
            {
                // Timed from now, not from the planned end: a late tick lengthens the gap instead of
                // producing a release the game never sees.
                _phase[b] = Up;
                _phaseEndUs[b] = nowUs + GapUs;
                DownMask &= ~bit;
            }
            else if (_pending[b] > 0)
            {
                StartTap(b, nowUs);
            }
            else
            {
                // Done: the button follows its held bit again (which shows a still-hidden press).
                _phase[b] = Idle;
                ActiveMask &= ~bit;
                _hidden &= ~bit;
            }
        }
        return ActiveMask != activeBefore || DownMask != downBefore;
    }

    /// <summary>
    /// Epoch adoption while schedules from the previous epoch still run (section 9 rule 2: they finish). The
    /// adopted packet is a baseline and never reaches <see cref="OnPacket"/>, so the hidden presses are
    /// brought in line with it here. A press of the old epoch that its schedule was hiding ended with that
    /// epoch: it is appended as one tap, as when its held bit clears. A press the adopted packet holds on a
    /// button that a schedule drives becomes the hidden press, so it is shown once the schedule ends, or
    /// appended if it is released first, instead of vanishing.
    /// </summary>
    public void Rebaseline(uint held)
    {
        uint active = ActiveMask;
        uint ended = _hidden & active;
        while (ended != 0)
        {
            int b = System.Numerics.BitOperations.TrailingZeroCount(ended);
            ended &= ended - 1;
            Append(b, 1);
        }
        _hidden = held & active;
    }

    /// <summary>Drops every schedule (mode switch). Replayed counts are kept.</summary>
    public void Clear()
    {
        Array.Clear(_phase);
        Array.Clear(_phaseEndUs);
        Array.Clear(_pending);
        ActiveMask = 0;
        DownMask = 0;
        _hidden = 0;
    }

    private void Append(int button, int taps)
    {
        if (taps <= 0) return;
        _pending[button] = Math.Min(_pending[button] + taps, MaxQueued);
    }

    private void StartTap(int b, long nowUs)
    {
        _pending[b]--;
        _replayed[b]++;
        _phase[b] = Down;
        _phaseEndUs[b] = nowUs + TapUs;
        DownMask |= 1u << b;
    }
}
