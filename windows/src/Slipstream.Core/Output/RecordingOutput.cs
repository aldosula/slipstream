using Slipstream.Core.Link;

namespace Slipstream.Core.Output;

/// <summary>Records every applied frame with its timestamp. For tests and diagnostics.</summary>
public sealed class RecordingOutput : IOutputDevice
{
    private readonly object _gate = new();
    private readonly List<(long Timestamp, OutputFrame Frame)> _frames = new();
    private readonly IClock _clock;
    private readonly long[] _risingEdges = new long[32];
    private uint _lastButtons;

    public RecordingOutput(IClock? clock = null) => _clock = clock ?? StopwatchClock.Instance;

    public string Name => "Recording";
    public OutputKind Kind { get; init; } = OutputKind.None;
    public OutputState State { get; set; } = OutputState.Ready;
    public string StateDetail { get; set; } = "Recording output for tests.";
    public int NeutralCalls { get; private set; }

    public void Apply(in OutputFrame frame)
    {
        lock (_gate)
        {
            _frames.Add((_clock.GetTimestamp(), frame));
            uint rising = frame.VJoyButtons & ~_lastButtons;
            for (int b = 0; b < 32; b++)
                if ((rising & (1u << b)) != 0) _risingEdges[b]++;
            _lastButtons = frame.VJoyButtons;
        }
    }

    public void Neutral()
    {
        lock (_gate)
        {
            NeutralCalls++;
            _lastButtons = 0;
        }
    }

    public IReadOnlyList<(long Timestamp, OutputFrame Frame)> Frames
    {
        get { lock (_gate) return _frames.ToArray(); }
    }

    public OutputFrame? Last
    {
        get { lock (_gate) return _frames.Count == 0 ? null : _frames[^1].Frame; }
    }

    /// <summary>Number of times vJoy button bit <paramref name="bit"/> went from released to pressed.</summary>
    public long RisingEdges(int bit)
    {
        lock (_gate) return _risingEdges[bit];
    }

    public void Dispose() { }
}
