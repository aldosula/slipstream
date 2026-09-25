namespace Slipstream.Core.Output;

/// <summary>
/// A virtual game controller. <see cref="Apply"/> is called on the receive thread under the
/// engine lock, so it must be fast, must not block and must not throw for routine failures:
/// report them through <see cref="State"/> and <see cref="StateDetail"/> instead.
/// </summary>
public interface IOutputDevice : IDisposable
{
    string Name { get; }

    OutputKind Kind { get; }

    OutputState State { get; }

    /// <summary>One precise sentence: what is wrong and how to fix it, or what is working.</summary>
    string StateDetail { get; }

    /// <summary>Writes one frame to the device (one driver call).</summary>
    void Apply(in OutputFrame frame);

    /// <summary>Hard reset to the uninverted neutral state (centred steer, released everything).</summary>
    void Neutral();
}

/// <summary>Optional: a device that receives force feedback or rumble from the game.</summary>
public interface IRumbleSource
{
    ushort RumbleStrong { get; }
    ushort RumbleWeak { get; }
}

/// <summary>No virtual controller. The link runs, games see nothing.</summary>
public sealed class NullOutput : IOutputDevice
{
    public string Name => "None";
    public OutputKind Kind => OutputKind.None;
    public OutputState State => OutputState.Ready;
    public string StateDetail => "No virtual controller selected. The link runs, games see nothing.";
    public void Apply(in OutputFrame frame) { }
    public void Neutral() { }
    public void Dispose() { }
}

/// <summary>
/// A virtual gamepad for controller mode (PROTOCOL.md 12.4 rule 5): a DualShock 4 or an Xbox 360 pad.
/// Same contract as <see cref="IOutputDevice"/>: <see cref="Apply"/> runs on the receive thread under the
/// engine lock, must be fast, must not block and must not throw for routine failures.
/// </summary>
public interface IPadOutputDevice : IDisposable
{
    string Name { get; }

    /// <summary><see cref="OutputKind.Xbox360"/> or <see cref="OutputKind.DualShock4"/>.</summary>
    OutputKind Kind { get; }

    OutputState State { get; }

    /// <summary>One precise sentence: what is wrong and how to fix it, or what is working.</summary>
    string StateDetail { get; }

    /// <summary>Writes one frame to the device (one report submitted).</summary>
    void Apply(in PadOutputFrame frame);

    /// <summary>Hard reset to neutral: sticks centred, triggers and buttons released, no touch.</summary>
    void Neutral();
}

/// <summary>A pad that goes nowhere, of a given kind (the headless hub, tests).</summary>
public sealed class NullPadOutput : IPadOutputDevice
{
    public NullPadOutput(OutputKind kind = OutputKind.None) => Kind = kind;

    public string Name => "None";
    public OutputKind Kind { get; }
    public OutputState State => OutputState.Ready;
    public string StateDetail => "No virtual pad. The link runs, games see nothing.";
    public void Apply(in PadOutputFrame frame) { }
    public void Neutral() { }
    public void Dispose() { }
}

/// <summary>Placeholder for a pad device whose construction failed: a reported state, never a crash.</summary>
public sealed class FailedPadOutput : IPadOutputDevice
{
    public FailedPadOutput(OutputKind kind, string detail)
    {
        Kind = kind;
        StateDetail = detail;
    }

    public string Name => Kind == OutputKind.DualShock4 ? "DualShock 4" : "Xbox 360";
    public OutputKind Kind { get; }
    public OutputState State => OutputState.Faulted;
    public string StateDetail { get; }
    public void Apply(in PadOutputFrame frame) { }
    public void Neutral() { }
    public void Dispose() { }
}
