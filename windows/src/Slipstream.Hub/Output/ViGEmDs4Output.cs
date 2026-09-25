using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Exceptions;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.DualShock4;
using Slipstream.Core.Output;

namespace Slipstream.Hub.Output;

/// <summary>
/// A virtual DualShock 4 through ViGEmBus, for controller mode. AutoSubmitReport is off and each Apply submits
/// exactly one report. Rumble from the game (LargeMotor, SmallMotor, scaled by 257 to 0..65535) goes back to
/// the phone in STATUS.
/// <para>
/// The report: Nefarius.ViGEm.Client 1.21.256 exposes <c>IDualShock4Controller.SubmitRawReport(byte[])</c>,
/// which takes exactly 63 bytes and calls <c>vigem_target_ds4_update_ex</c> (checked in the assembly metadata:
/// it compares the length with <c>Marshal.SizeOf</c> of its 63 byte <c>DS4_REPORT_EX</c> and throws
/// <c>VigemNotSupportedException</c> when the installed bus does not support extended reports). The 63 bytes
/// are built by <see cref="Ds4Report.WriteExtended"/> (layout documented there) and carry sticks, buttons,
/// hat, triggers, touchpad fingers and motion. On a bus without extended reports the device falls back to the
/// standard report (sticks, buttons, hat, triggers; no touch, no motion) and says so in its state.
/// </para>
/// </summary>
public sealed class ViGEmDs4Output : IPadOutputDevice, IRumbleSource, IRetryableOutput
{
    private readonly object _gate = new();
    private readonly byte[] _report = new byte[Ds4Report.ExtendedLength];
    private ViGEmClient? _client;
    private IDualShock4Controller? _pad;
    private bool _extended = true;
    private byte _counter, _touchCounter;
    private volatile OutputState _state = OutputState.Unavailable;
    private volatile string _detail = "Starting ViGEmBus.";
    private int _strong, _weak;

    public ViGEmDs4Output() => Connect();

    public string Name => "DualShock 4 (ViGEm)";
    public OutputKind Kind => OutputKind.DualShock4;
    public OutputState State => _state;
    public string StateDetail => _detail;
    public ushort RumbleStrong => (ushort)Volatile.Read(ref _strong);
    public ushort RumbleWeak => (ushort)Volatile.Read(ref _weak);

    public void Retry()
    {
        lock (_gate)
        {
            DisconnectLocked();
            _extended = true;
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
                IDualShock4Controller pad = _client.CreateDualShock4Controller();
                pad.AutoSubmitReport = false;
                // Nefarius.ViGEm.Client 1.21 marks the DualShock 4 FeedbackReceived event obsolete in favour of
                // AwaitRawOutputReport, which needs its own blocking thread and parses raw output reports. The
                // event still delivers the two motor bytes, which is all STATUS carries.
#pragma warning disable CS0618
                pad.FeedbackReceived += OnFeedback;
#pragma warning restore CS0618
                pad.Connect();
                _pad = pad;
                _state = OutputState.Ready;
                _detail = "Virtual DualShock 4 connected, with touchpad and motion. Games see it as a wired PlayStation 4 controller.";
                SubmitLocked(Mapping.MapPad(PadFrame.Neutral));
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
        DllNotFoundException or BadImageFormatException =>
            $"The ViGEm client library could not be loaded ({ex.GetType().Name}). Reinstall Slipstream Hub, then press Retry.",
        _ => $"ViGEm could not create the DualShock 4 ({ex.GetType().Name}: {ex.Message}). Press Retry.",
    };

    private void OnFeedback(object sender, DualShock4FeedbackReceivedEventArgs e)
    {
        Volatile.Write(ref _strong, e.LargeMotor * 257);
        Volatile.Write(ref _weak, e.SmallMotor * 257);
    }

    public void Apply(in PadOutputFrame frame)
    {
        lock (_gate)
        {
            if (_pad is null) return;
            SubmitLocked(in frame);
        }
    }

    public void Neutral()
    {
        lock (_gate)
        {
            if (_pad is null) return;
            SubmitLocked(Mapping.MapPad(PadFrame.Neutral));
        }
    }

    private void SubmitLocked(in PadOutputFrame f)
    {
        IDualShock4Controller pad = _pad!;
        if (_extended)
        {
            try
            {
                Ds4Report.WriteExtended(in f, _report, _counter++, _touchCounter++);
                pad.SubmitRawReport(_report);
                return;
            }
            catch (Exception ex) when (ex is VigemNotSupportedException or EntryPointNotFoundException)
            {
                // An older ViGEmBus: keep going with the standard report.
                _extended = false;
                _state = OutputState.Degraded;
                _detail = "Virtual DualShock 4 connected without touchpad and motion: this ViGEmBus is too old for them. Install the latest ViGEmBus release, then press Retry.";
            }
            catch (Exception ex)
            {
                Fault(ex);
                return;
            }
        }
        try
        {
            pad.LeftThumbX = f.Ds4LeftX;
            pad.LeftThumbY = f.Ds4LeftY;
            pad.RightThumbX = f.Ds4RightX;
            pad.RightThumbY = f.Ds4RightY;
            pad.LeftTrigger = f.Ds4LeftTrigger;
            pad.RightTrigger = f.Ds4RightTrigger;
            pad.SetButtonsFull(f.Ds4Buttons); // includes the hat in bits 0..3
            pad.SetSpecialButtonsFull(f.Ds4Special);
            pad.SubmitReport();
        }
        catch (Exception ex)
        {
            Fault(ex);
        }
    }

    private void Fault(Exception ex)
    {
        DisconnectLocked();
        _state = OutputState.Faulted;
        _detail = $"The virtual DualShock 4 stopped working ({ex.GetType().Name}). Press Retry.";
    }

    private void DisconnectLocked()
    {
        if (_pad is not null)
        {
#pragma warning disable CS0618 // see Connect
            try { _pad.FeedbackReceived -= OnFeedback; } catch { }
#pragma warning restore CS0618
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
