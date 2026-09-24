using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using Slipstream.Core.Output;

namespace Slipstream.Hub.Output;

/// <summary>
/// A virtual Xbox 360 controller through ViGEmBus. AutoSubmitReport is off and each Apply ends in
/// exactly one SubmitReport. Rumble from the game is reported back to the phone in STATUS
/// (LargeMotor and SmallMotor bytes scaled by 257 to 0..65535).
/// </summary>
public sealed class ViGEmX360Output : IOutputDevice, IRumbleSource, IRetryableOutput
{
    // Each XUSB bit of the Core mapping, paired with the library's named button. Setting buttons by
    // name keeps this correct whatever bit layout the library uses internally.
    private static readonly (X360Buttons Bit, Xbox360Button Button)[] ButtonMap =
    {
        (X360Buttons.DPadUp, Xbox360Button.Up),
        (X360Buttons.DPadDown, Xbox360Button.Down),
        (X360Buttons.DPadLeft, Xbox360Button.Left),
        (X360Buttons.DPadRight, Xbox360Button.Right),
        (X360Buttons.Start, Xbox360Button.Start),
        (X360Buttons.Back, Xbox360Button.Back),
        (X360Buttons.LeftThumb, Xbox360Button.LeftThumb),
        (X360Buttons.RightThumb, Xbox360Button.RightThumb),
        (X360Buttons.LeftShoulder, Xbox360Button.LeftShoulder),
        (X360Buttons.RightShoulder, Xbox360Button.RightShoulder),
        (X360Buttons.Guide, Xbox360Button.Guide),
        (X360Buttons.A, Xbox360Button.A),
        (X360Buttons.B, Xbox360Button.B),
        (X360Buttons.X, Xbox360Button.X),
        (X360Buttons.Y, Xbox360Button.Y),
    };

    private readonly object _gate = new();
    private ViGEmClient? _client;
    private IXbox360Controller? _pad;
    private volatile OutputState _state = OutputState.Unavailable;
    private volatile string _detail = "Starting ViGEmBus.";
    private int _strong, _weak;

    public ViGEmX360Output() => Connect();

    public string Name => "Xbox 360 (ViGEm)";
    public OutputKind Kind => OutputKind.Xbox360;
    public OutputState State => _state;
    public string StateDetail => _detail;
    public ushort RumbleStrong => (ushort)Volatile.Read(ref _strong);
    public ushort RumbleWeak => (ushort)Volatile.Read(ref _weak);

    public void Retry()
    {
        lock (_gate)
        {
            DisconnectLocked();
        }
        Connect();
    }

    private void Connect()
    {
        lock (_gate)
        {
            if (_pad is not null) return;
            try
            {
                _client = new ViGEmClient();
                IXbox360Controller pad = _client.CreateXbox360Controller();
                pad.AutoSubmitReport = false;
                pad.FeedbackReceived += OnFeedback;
                pad.Connect();
                _pad = pad;
                _state = OutputState.Ready;
                _detail = "Virtual Xbox 360 controller connected. Games see it as a normal wired pad.";
            }
            catch (Exception ex)
            {
                DisconnectLocked();
                _state = OutputState.Unavailable;
                _detail = Explain(ex);
            }
        }
    }

    private static string Explain(Exception ex) => ex switch
    {
        VigemBusNotFoundException =>
            "ViGEmBus is not installed. Install it from github.com/nefarius/ViGEmBus/releases, then press Retry.",
        VigemBusVersionMismatchException =>
            "The installed ViGEmBus is too old for this hub. Install the latest ViGEmBus release, then press Retry.",
        VigemBusAccessFailedException =>
            "ViGEmBus refused access. Restart the PC after installing ViGEmBus, then press Retry.",
        VigemNoFreeSlotException or VigemAllocFailedException =>
            "No free virtual controller slot. Close other programs that create ViGEm controllers, then press Retry.",
        TypeInitializationException { InnerException: { } inner } => Explain(inner),
        _ => $"ViGEm could not create the controller ({ex.GetType().Name}: {ex.Message}). Press Retry.",
    };

    private void OnFeedback(object sender, Xbox360FeedbackReceivedEventArgs e)
    {
        Volatile.Write(ref _strong, e.LargeMotor * 257);
        Volatile.Write(ref _weak, e.SmallMotor * 257);
    }

    public void Apply(in OutputFrame frame)
    {
        lock (_gate)
        {
            IXbox360Controller? pad = _pad;
            if (pad is null) return;
            try
            {
                pad.SetAxisValue(Xbox360Axis.LeftThumbX, frame.X360LeftThumbX);
                pad.SetAxisValue(Xbox360Axis.RightThumbY, frame.X360RightThumbY);
                pad.SetSliderValue(Xbox360Slider.LeftTrigger, frame.X360LeftTrigger);
                pad.SetSliderValue(Xbox360Slider.RightTrigger, frame.X360RightTrigger);
                X360Buttons b = frame.X360Buttons;
                foreach ((X360Buttons bit, Xbox360Button button) in ButtonMap)
                    pad.SetButtonState(button, (b & bit) != 0);
                pad.SubmitReport();
            }
            catch (Exception ex)
            {
                DisconnectLocked();
                _state = OutputState.Faulted;
                _detail = $"The virtual Xbox 360 controller stopped working ({ex.GetType().Name}). Press Retry.";
            }
        }
    }

    public void Neutral()
    {
        lock (_gate)
        {
            if (_pad is null) return;
            try
            {
                _pad.ResetReport();
                _pad.SubmitReport();
            }
            catch (Exception) { /* device already gone */ }
        }
    }

    private void DisconnectLocked()
    {
        if (_pad is not null)
        {
            try { _pad.FeedbackReceived -= OnFeedback; } catch { }
            try { _pad.Disconnect(); } catch { }
        }
        _pad = null;
        try { _client?.Dispose(); } catch { }
        _client = null;
        Volatile.Write(ref _strong, 0);
        Volatile.Write(ref _weak, 0);
    }

    public void Dispose()
    {
        Neutral();
        lock (_gate) DisconnectLocked();
    }
}
