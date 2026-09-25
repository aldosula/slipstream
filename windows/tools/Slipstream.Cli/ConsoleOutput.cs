using Slipstream.Core.Output;

namespace Slipstream.Cli;

/// <summary>
/// The headless hub's "virtual controller": it only remembers the last frame. The live line on the
/// console reads the engine snapshot, never this object, so the hot path stays a struct copy.
/// </summary>
internal sealed class ConsoleOutput : IOutputDevice
{
    private long _applies;

    public string Name => "Console";
    public OutputKind Kind => OutputKind.None;
    public OutputState State => OutputState.Ready;
    public string StateDetail => "Headless hub: controls are shown on the console, no game controller is created.";

    public long Applies => Interlocked.Read(ref _applies);
    public OutputFrame LastFrame { get; private set; }

    public void Apply(in OutputFrame frame)
    {
        LastFrame = frame; // called under the engine lock
        Interlocked.Increment(ref _applies);
    }

    public void Neutral() => LastFrame = Mapping.Map(ControllerFrame.Neutral, AxisInvert.None);

    public void Dispose() { }
}

/// <summary>
/// The headless hub's controller-mode pad: it only counts frames. It reports the kind the engine asked for
/// (DualShock 4 or Xbox 360), so the stats show which pad a real hub would have plugged in.
/// </summary>
internal sealed class ConsolePadOutput : IPadOutputDevice
{
    private long _applies;

    public ConsolePadOutput(OutputKind kind) => Kind = kind;

    public string Name => Kind == OutputKind.DualShock4 ? "Console (DualShock 4)" : "Console (Xbox 360)";
    public OutputKind Kind { get; }
    public OutputState State => OutputState.Ready;
    public string StateDetail => "Headless hub: the pad is shown on the console, no virtual controller is created.";
    public long Applies => Interlocked.Read(ref _applies);

    public void Apply(in PadOutputFrame frame) => Interlocked.Increment(ref _applies);

    public void Neutral() { }

    public void Dispose() { }
}
