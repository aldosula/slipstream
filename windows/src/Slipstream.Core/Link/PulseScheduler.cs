using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// Per-channel press queue (PROTOCOL.md section 9 rule 4): a press is button down for
/// pulse_ms, then up for gap_ms before the next queued press on that channel.
/// Not thread-safe; the engine calls it under its lock. Allocation-free.
/// </summary>
public sealed class PulseScheduler
{
    private const byte Idle = 0, Down = 1, Gap = 2;

    private readonly int[] _pending = new int[Wire.PulseChannels];
    private readonly byte[] _phase = new byte[Wire.PulseChannels];
    private readonly long[] _phaseEndUs = new long[Wire.PulseChannels];
    private readonly long[] _emitted = new long[Wire.PulseChannels];

    public PulseScheduler(long pulseUs, long gapUs)
    {
        PulseUs = pulseUs;
        GapUs = gapUs;
    }

    public long PulseUs { get; set; }
    public long GapUs { get; set; }

    /// <summary>Bit j set while channel j is in its button-down phase.</summary>
    public byte Mask { get; private set; }

    /// <summary>Presses started since creation, per channel.</summary>
    public long Emitted(int channel) => _emitted[channel];

    /// <summary>Presses queued but not started yet.</summary>
    public int Pending(int channel) => _pending[channel];

    public bool IsIdle(int channel) => _phase[channel] == Idle && _pending[channel] == 0;

    /// <summary>Queues presses. An idle channel starts its first press immediately.</summary>
    public void Enqueue(int channel, int presses, long nowUs)
    {
        if (presses <= 0) return;
        // Saturate: a phone that keeps bumping a counter can never wrap the queue into a negative count.
        _pending[channel] = (int)Math.Min((long)_pending[channel] + presses, int.MaxValue);
        if (_phase[channel] == Idle) StartPress(channel, nowUs);
    }

    /// <summary>Advances every channel to <paramref name="nowUs"/>. True when <see cref="Mask"/> changed.</summary>
    public bool Update(long nowUs)
    {
        byte before = Mask;
        for (int ch = 0; ch < Wire.PulseChannels; ch++)
        {
            switch (_phase[ch])
            {
                case Down when nowUs >= _phaseEndUs[ch]:
                    _phase[ch] = Gap;
                    // Timed from now, not from the planned end: a late tick lengthens the gap
                    // instead of producing a press the game never sees.
                    _phaseEndUs[ch] = nowUs + GapUs;
                    Mask &= (byte)~(1 << ch);
                    break;
                case Gap when nowUs >= _phaseEndUs[ch]:
                    if (_pending[ch] > 0) StartPress(ch, nowUs);
                    else _phase[ch] = Idle;
                    break;
            }
        }
        return Mask != before;
    }

    /// <summary>Drops queued presses and releases everything. Emitted counts are kept.</summary>
    public void Clear()
    {
        Array.Clear(_pending);
        Array.Clear(_phase);
        Array.Clear(_phaseEndUs);
        Mask = 0;
    }

    private void StartPress(int ch, long nowUs)
    {
        _pending[ch]--;
        _emitted[ch]++;
        _phase[ch] = Down;
        _phaseEndUs[ch] = nowUs + PulseUs;
        Mask |= (byte)(1 << ch);
    }
}
