using Slipstream.Core.Output;

namespace Slipstream.Hub.Output;

public static class OutputFactory
{
    /// <summary>Creates the device for a kind. Never throws: a missing driver is a reported state.</summary>
    public static IOutputDevice Create(OutputKind kind)
    {
        try
        {
            return kind switch
            {
                OutputKind.VJoy => new VJoyOutput(),
                OutputKind.Xbox360 => new ViGEmX360Output(),
                _ => new NullOutput(),
            };
        }
        catch (Exception ex)
        {
            return new FailedOutput(kind, $"Could not start the {Label(kind)} output: {ex.Message}");
        }
    }

    /// <summary>
    /// Creates the controller-mode pad of a kind (the engine calls it on the first PAD packet). Never throws:
    /// a missing ViGEmBus is a device in the Unavailable state with the fix in its detail text.
    /// </summary>
    public static IPadOutputDevice CreatePad(OutputKind kind)
    {
        try
        {
            return kind == OutputKind.DualShock4 ? new ViGEmDs4Output() : new ViGEmX360Output();
        }
        catch (Exception ex)
        {
            return new FailedPadOutput(kind, $"Could not start the virtual {Label(kind)} pad: {ex.Message}");
        }
    }

    public static string Label(OutputKind kind) => kind switch
    {
        OutputKind.VJoy => "vJoy",
        OutputKind.Xbox360 => "Xbox 360",
        OutputKind.DualShock4 => "DualShock 4",
        _ => "None",
    };

    /// <summary>Placeholder when a device could not even be constructed.</summary>
    private sealed class FailedOutput : IOutputDevice
    {
        public FailedOutput(OutputKind kind, string detail)
        {
            Kind = kind;
            StateDetail = detail;
        }

        public string Name => Label(Kind);
        public OutputKind Kind { get; }
        public OutputState State => OutputState.Faulted;
        public string StateDetail { get; }
        public void Apply(in OutputFrame frame) { }
        public void Neutral() { }
        public void Dispose() { }
    }
}
