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

    public static string Label(OutputKind kind) => kind switch
    {
        OutputKind.VJoy => "vJoy",
        OutputKind.Xbox360 => "Xbox 360",
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
