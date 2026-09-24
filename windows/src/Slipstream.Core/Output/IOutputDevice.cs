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
