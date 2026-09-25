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

/// <summary>Records every applied pad frame with its timestamp, and counts presses per canonical button. For tests.</summary>
public sealed class RecordingPadOutput : IPadOutputDevice
{
    private readonly object _gate = new();
    private readonly List<(long Timestamp, PadOutputFrame Frame)> _frames = new();
    private readonly IClock _clock;
    private readonly long[] _risingEdges = new long[32];
    private uint _lastButtons;

    public RecordingPadOutput(OutputKind kind, IClock? clock = null)
    {
        Kind = kind;
        _clock = clock ?? StopwatchClock.Instance;
    }

    public string Name => "Recording pad";
    public OutputKind Kind { get; }
    public OutputState State { get; set; } = OutputState.Ready;
    public string StateDetail { get; set; } = "Recording pad output for tests.";
    public int NeutralCalls { get; private set; }
    public bool Disposed { get; private set; }

    public void Apply(in PadOutputFrame frame)
    {
        lock (_gate)
        {
            _frames.Add((_clock.GetTimestamp(), frame));
            uint b = frame.Source.Buttons;
            uint rising = b & ~_lastButtons;
            for (int i = 0; i < 32; i++)
                if ((rising & (1u << i)) != 0) _risingEdges[i]++;
            _lastButtons = b;
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

    public IReadOnlyList<(long Timestamp, PadOutputFrame Frame)> Frames
    {
        get { lock (_gate) return _frames.ToArray(); }
    }

    public PadOutputFrame? Last
    {
        get { lock (_gate) return _frames.Count == 0 ? null : _frames[^1].Frame; }
    }

    /// <summary>Number of times canonical button <paramref name="button"/> went from released to pressed.</summary>
    public long RisingEdges(int button)
    {
        lock (_gate) return _risingEdges[button];
    }

    public void Dispose() => Disposed = true;
}
