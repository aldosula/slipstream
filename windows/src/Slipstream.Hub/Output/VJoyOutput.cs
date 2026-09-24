using Slipstream.Core.Output;
using Slipstream.Hub.Native;

namespace Slipstream.Hub.Output;

/// <summary>
/// vJoy device 1 (axes X Y Z Rx Ry, 32 buttons). One UpdateVJD call per Apply. On start it checks
/// the driver and the device configuration and reports exactly what is missing.
/// </summary>
public sealed class VJoyOutput : IOutputDevice, IRetryableOutput
{
    private const uint Device = 1;
    private const int RequiredButtons = 32;
    private const long AutoRetryMs = 2000;

    private readonly object _gate = new();
    private JoystickPositionV2 _report;
    private volatile bool _acquired;
    private volatile bool _disposed;
    private bool _autoRetry;
    private long _nextRetryMs;
    private volatile OutputState _state = OutputState.Unavailable;
    private volatile string _detail = "Starting vJoy.";
    private volatile string _name = "vJoy";

    public VJoyOutput()
    {
        NativeResolver.EnsureInstalled();
        Probe();
    }

    public string Name => _name;
    public OutputKind Kind => OutputKind.VJoy;
    public OutputState State => _state;
    public string StateDetail => _detail;

    /// <summary>Releases the device and checks everything again (after the user fixed vJoy's configuration).</summary>
    public void Retry()
    {
        lock (_gate)
        {
            if (_acquired)
            {
                try { VJoyNative.RelinquishVJD(Device); } catch (DllNotFoundException) { }
                _acquired = false;
            }
        }
        Probe();
    }

    private void Probe()
    {
        lock (_gate)
        {
            if (_acquired || _disposed) return;
            _autoRetry = false;
            try
            {
                if (!VJoyNative.vJoyEnabled())
                {
                    Report(OutputState.Unavailable,
                        "The vJoy driver is installed but disabled. Open 'Configure vJoy', tick 'Enable vJoy', then press Retry.");
                    return;
                }
                string version = VJoyNative.FormatVersion(VJoyNative.GetvJoyVersion());
                _name = $"vJoy {version}, device {Device}";

                VjdStat status = VJoyNative.GetVJDStatus(Device);
                switch (status)
                {
                    case VjdStat.Miss:
                        Report(OutputState.Unavailable,
                            $"vJoy device {Device} does not exist. Open 'Configure vJoy', select tab {Device}, enable axes X, Y, Z, Rx, Ry, set 32 buttons, press Apply, then Retry.");
                        return;
                    case VjdStat.Busy:
                        _autoRetry = true;
                        _nextRetryMs = Environment.TickCount64 + AutoRetryMs;
                        Report(OutputState.Unavailable,
                            $"vJoy device {Device} is used by another program (another feeder, another wheel server or a second hub). Close it; Slipstream retries every 2 s.");
                        return;
                    case VjdStat.Unknown:
                        Report(OutputState.Unavailable,
                            $"vJoy reports an unknown state for device {Device}. Reinstall vJoy 2.2.x, restart the PC, then press Retry.");
                        return;
                }

                var missing = new List<string>();
                if (!VJoyNative.GetVJDAxisExist(Device, VJoyAxis.X)) missing.Add("X (steering)");
                if (!VJoyNative.GetVJDAxisExist(Device, VJoyAxis.Y)) missing.Add("Y (throttle)");
                if (!VJoyNative.GetVJDAxisExist(Device, VJoyAxis.Z)) missing.Add("Z (brake)");
                if (!VJoyNative.GetVJDAxisExist(Device, VJoyAxis.Rx)) missing.Add("Rx (clutch)");
                if (!VJoyNative.GetVJDAxisExist(Device, VJoyAxis.Ry)) missing.Add("Ry (handbrake)");
                int buttons = VJoyNative.GetVJDButtonNumber(Device);

                if (status == VjdStat.Free && !VJoyNative.AcquireVJD(Device))
                {
                    _autoRetry = true;
                    _nextRetryMs = Environment.TickCount64 + AutoRetryMs;
                    Report(OutputState.Unavailable,
                        $"vJoy device {Device} could not be acquired. Another program may have just taken it; Slipstream retries every 2 s.");
                    return;
                }

                _acquired = true;
                WriteNeutralLocked();

                var problems = new List<string>();
                if (missing.Count > 0) problems.Add((missing.Count == 1 ? "axis " : "axes ") + string.Join(", ", missing));
                if (buttons < RequiredButtons) problems.Add($"{RequiredButtons - buttons} buttons (it has {buttons}, needs {RequiredButtons})");
                if (problems.Count > 0)
                {
                    Report(OutputState.Degraded,
                        $"vJoy device {Device} works but is missing {string.Join(" and ", problems)}. Open 'Configure vJoy', tab {Device}, add them, press Apply, then Retry.");
                }
                else
                {
                    Report(OutputState.Ready, $"vJoy {version} ready: device {Device} with axes X Y Z Rx Ry and {buttons} buttons.");
                }
            }
            catch (DllNotFoundException)
            {
                Report(OutputState.Unavailable,
                    "vJoy is not installed: vJoyInterface.dll is not in the app folder or in C:\\Program Files\\vJoy\\x64. Install vJoy 2.2.x (github.com/BrunnerInnovation/vJoy), then press Retry.");
            }
            catch (BadImageFormatException)
            {
                Report(OutputState.Unavailable,
                    "The vJoyInterface.dll found is not the 64-bit library. Reinstall vJoy 2.2.x (x64), then press Retry.");
            }
            catch (EntryPointNotFoundException ex)
            {
                Report(OutputState.Unavailable,
                    $"This vJoyInterface.dll is too old ({ex.Message}). Install vJoy 2.2.x, then press Retry.");
            }
        }
    }

    private void Report(OutputState state, string detail)
    {
        _state = state;
        _detail = detail;
    }

    public void Apply(in OutputFrame frame)
    {
        if (!_acquired)
        {
            if (_disposed) return;
            if (!_autoRetry || Environment.TickCount64 < _nextRetryMs) return;
            Probe();
            if (!_acquired) return;
        }
        // Uncontended almost always (the engine already serializes Apply); it guards against a
        // Retry from the UI thread rewriting the report at the same moment.
        lock (_gate)
        {
            if (_acquired) UpdateLocked(in frame);
        }
    }

    private void UpdateLocked(in OutputFrame frame)
    {
        _report.bDevice = (byte)Device;
        _report.AxisX = frame.VJoyX;
        _report.AxisY = frame.VJoyY;
        _report.AxisZ = frame.VJoyZ;
        _report.AxisXRot = frame.VJoyRx;
        _report.AxisYRot = frame.VJoyRy;
        _report.Buttons = frame.VJoyButtons;
        _report.bHats = _report.bHatsEx1 = _report.bHatsEx2 = _report.bHatsEx3 = 0xFFFF_FFFF; // POV neutral
        if (!VJoyNative.UpdateVJD(Device, ref _report))
        {
            _acquired = false;
            _autoRetry = true;
            _nextRetryMs = Environment.TickCount64 + AutoRetryMs;
            Report(OutputState.Faulted,
                $"vJoy device {Device} stopped accepting updates (another program took it, or the driver was reset). Retrying every 2 s.");
        }
    }

    public void Neutral()
    {
        lock (_gate)
        {
            if (_acquired) WriteNeutralLocked();
        }
    }

    private void WriteNeutralLocked()
    {
        OutputFrame n = Mapping.Map(ControllerFrame.Neutral, AxisInvert.None);
        _report = new JoystickPositionV2
        {
            bDevice = (byte)Device,
            AxisX = n.VJoyX,
            AxisY = n.VJoyY,
            AxisZ = n.VJoyZ,
            AxisXRot = n.VJoyRx,
            AxisYRot = n.VJoyRy,
            bHats = 0xFFFF_FFFF,
            bHatsEx1 = 0xFFFF_FFFF,
            bHatsEx2 = 0xFFFF_FFFF,
            bHatsEx3 = 0xFFFF_FFFF,
        };
        VJoyNative.UpdateVJD(Device, ref _report);
    }

    public void Dispose()
    {
        lock (_gate)
        {
            // After Dispose no path (auto-retry included) may take device 1 again.
            _disposed = true;
            _autoRetry = false;
            if (!_acquired) return;
            try
            {
                WriteNeutralLocked();
                VJoyNative.RelinquishVJD(Device);
            }
            catch (DllNotFoundException) { }
            _acquired = false;
        }
    }
}
