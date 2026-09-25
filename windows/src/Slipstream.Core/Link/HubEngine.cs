using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// The hub core. One lock. <see cref="Accept(TransportKind, in InputPacket, IStatusSink?, long, PairingKey)"/>
/// and its PAD twin run on the calling receive thread and write the virtual device before returning
/// (rule 5: no frame timer, no queue). A housekeeping thread with a 1 ms tick releases pulse presses and
/// replayed taps on time, fires the failsafe and sends STATUS at 20 Hz to every endpoint that delivered an
/// accepted packet in the last second.
/// <para>
/// Wheel mode (INPUT) drives the wheel output (vJoy, Xbox 360 or none). Controller mode (PAD) drives a pad
/// device (DualShock 4 or Xbox 360), plugged in on the first accepted PAD packet and kept until the hub quits
/// or the output changes. When the packet type changes, the previous mode's device goes to neutral
/// (PROTOCOL.md 12.4 rule 2). When the wheel output is itself the pad kind wanted (Xbox 360 in both), that one
/// device serves both modes, so games never see a second pad take player one.
/// </para>
/// </summary>
public sealed class HubEngine : IDisposable
{
    private const int MaxSinks = 32;
    private const long RateWindowUs = 500_000;
    /// <summary>
    /// A duplicate at most this many seqs behind the newest applied one still proves its path is alive
    /// (a multipath copy is a few seqs behind at most). Older ones are stale or replayed, and never make
    /// the hub answer an address.
    /// </summary>
    internal const uint RecentDuplicateWindow = 256;

    private readonly object _gate = new();
    private readonly IClock _clock;
    private readonly LinkSession _session;
    private readonly Counters[] _transport = { new(), new() };
    private readonly Sink[] _sinks = new Sink[MaxSinks];
    private readonly IStatusSink?[] _sendList = new IStatusSink?[MaxSinks];
    private readonly byte[] _statusBuffer = new byte[Wire.StatusLength];

    private volatile PairingKey _pairing;
    private IOutputDevice _output;
    private HubEngineOptions _options;
    private OutputFrame _lastFrame;
    /// <summary>The frame written to the device when the newest packet was applied (before any later failsafe).</summary>
    private OutputFrame _acceptedFrame;
    private int _sinkCount;
    private long _totalAccepted, _totalMissing, _epochChanges, _outputErrors, _statusSent;
    private string? _lastOutputError;
    private long _nextStatusUs;

    // Controller mode: the pad device and how it is chosen.
    private readonly object _padCreateGate = new();
    private Func<OutputKind, IPadOutputDevice>? _padFactory;
    private IPadOutputDevice? _pad;
    /// <summary>True when the engine created <see cref="_pad"/> and disposes it; false when it is the shared wheel output.</summary>
    private bool _padOwned;
    private OutputKind _padWanted = OutputKind.Xbox360;
    private bool _padStylePs;
    /// <summary>The pad device is not the one wanted: <see cref="EnsurePadDevice"/> replaces it, outside the lock.</summary>
    private volatile bool _padStale;
    /// <summary>While above 0 no pad device is created (the host is swapping the wheel output).</summary>
    private int _padHold;
    private PadOutputFrame _lastPadFrame;
    private PadOutputFrame _acceptedPad;
    private bool _disposed;

    // Rate window.
    private long _rateStartUs;
    private long _rateAccepted, _rateMissing;
    private readonly long[] _ratePackets = new long[2], _rateFirst = new long[2];
    private double _rateHz, _recentLoss;
    private readonly double[] _packetsPerSec = new double[2], _firstPerSec = new double[2];

    private Thread? _housekeeping;
    private volatile bool _stopping;

    /// <param name="padFactory">Creates the controller-mode pad device of a kind (DualShock 4 or Xbox 360). Called
    /// outside the lock, at most once per needed device; it must not throw (a missing driver is a device in an
    /// error state). Null: no pad device is created unless the wheel output can serve as one.</param>
    public HubEngine(PairingKey pairing, IOutputDevice output, HubEngineOptions? options = null, IClock? clock = null,
        Func<OutputKind, IPadOutputDevice>? padFactory = null)
    {
        _pairing = pairing ?? throw new ArgumentNullException(nameof(pairing));
        _output = output ?? throw new ArgumentNullException(nameof(output));
        _options = options ?? new HubEngineOptions();
        _clock = clock ?? StopwatchClock.Instance;
        _padFactory = padFactory;
        _session = new LinkSession(_options.Timing);
        long now = _clock.NowUs();
        _nextStatusUs = now;
        _rateStartUs = now;
        lock (_gate)
        {
            _padWanted = ResolvePadKind(_options.PadOutput, false);
            ApplyCurrentLocked();
        }
    }

    public IClock Clock => _clock;

    public PairingKey Pairing => _pairing;

    public HubEngineOptions Options
    {
        get { lock (_gate) return _options; }
    }

    /// <summary>The pad kind a setting resolves to: Auto follows STYLE_PS (12.4 rule 5).</summary>
    public static OutputKind ResolvePadKind(PadOutputSelection selection, bool stylePs) => selection switch
    {
        PadOutputSelection.Xbox360 => OutputKind.Xbox360,
        PadOutputSelection.DualShock4 => OutputKind.DualShock4,
        _ => stylePs ? OutputKind.DualShock4 : OutputKind.Xbox360,
    };

    // ------------------------------------------------------------------ hot path ---

    /// <summary>
    /// Rule 1 for one INPUT datagram or frame: header, exact length, tag. Counts rejects. Runs outside the
    /// lock. On success returns the key that verified it, to hand to <see cref="Accept(TransportKind, in InputPacket, IStatusSink?, long, PairingKey)"/>.
    /// </summary>
    public ReceiveOutcome Validate(TransportKind transport, ReadOnlySpan<byte> data, out InputPacket packet, out PairingKey verifiedWith)
    {
        verifiedWith = _pairing;
        return Count(transport, InputPacket.TryDecode(data, verifiedWith.Auth, out packet));
    }

    /// <summary>Rule 1 for one PAD datagram or frame (12.4 rule 1). Same contract as <see cref="Validate"/>.</summary>
    public ReceiveOutcome ValidatePad(TransportKind transport, ReadOnlySpan<byte> data, out PadPacket packet, out PairingKey verifiedWith)
    {
        verifiedWith = _pairing;
        return Count(transport, PadPacket.TryDecode(data, verifiedWith.Auth, out packet));
    }

    private ReceiveOutcome Count(TransportKind transport, DecodeResult r)
    {
        switch (r)
        {
            case DecodeResult.Ok:
                return ReceiveOutcome.Accepted;
            case DecodeResult.BadTag:
                Interlocked.Increment(ref _transport[(int)transport].BadTags);
                return ReceiveOutcome.BadTag;
            default:
                Interlocked.Increment(ref _transport[(int)transport].Malformed);
                return ReceiveOutcome.Malformed;
        }
    }

    /// <summary>Counts a packet rejected by the transport itself (for example a TCP frame of the wrong length).</summary>
    public void CountMalformed(TransportKind transport) => Interlocked.Increment(ref _transport[(int)transport].Malformed);

    /// <summary>
    /// Validate then accept one INPUT (52 bytes) or PAD (76 bytes) packet. <paramref name="rxTimestamp"/> is
    /// the clock tick when the bytes arrived. Any other length is malformed.
    /// </summary>
    public ReceiveOutcome Receive(TransportKind transport, ReadOnlySpan<byte> data, IStatusSink? sink, long rxTimestamp)
    {
        if (data.Length == Wire.PadLength)
        {
            ReceiveOutcome p = ValidatePad(transport, data, out PadPacket pad, out PairingKey padKey);
            return p == ReceiveOutcome.Accepted ? Accept(transport, in pad, sink, rxTimestamp, padKey) : p;
        }
        ReceiveOutcome v = Validate(transport, data, out InputPacket packet, out PairingKey key);
        return v == ReceiveOutcome.Accepted ? Accept(transport, in packet, sink, rxTimestamp, key) : v;
    }

    /// <summary>
    /// Rules 2 to 5 for an INPUT packet that passed <see cref="Validate"/>: session update and, when accepted,
    /// the device write, all under the lock on the calling thread.
    /// </summary>
    public ReceiveOutcome Accept(TransportKind transport, in InputPacket packet, IStatusSink? sink, long rxTimestamp, PairingKey verifiedWith)
    {
        long nowUs = ClockMath.ToMicroseconds(rxTimestamp, _clock.Frequency);
        lock (_gate)
        {
            if (!ReferenceEquals(verifiedWith, _pairing)) return ReceiveOutcome.StaleKey;
            Counters c = _transport[(int)transport];
            c.Packets++;
            uint missingBefore = _session.Missing;
            LinkMode before = _session.Mode;
            AcceptResult r = _session.Accept(in packet, nowUs);
            if (r is AcceptResult.Duplicate or AcceptResult.EpochRejected) return RejectedLocked(r, c, sink, packet.Seq, nowUs);
            if (before == LinkMode.Controller) NeutralizePadLocked(); // 12.4 rule 2: back to the wheel
            ReceiveOutcome outcome = CountAcceptedLocked(r, c, missingBefore, sink, nowUs);
            ApplyCurrentLocked();
            _acceptedFrame = _lastFrame;
            return outcome;
        }
    }

    /// <summary>
    /// Rules 2 to 5 and 12.4 for a PAD packet that passed <see cref="ValidatePad"/>. A pad device that has to
    /// be plugged in first (the first PAD packet, or a changed style under Auto) is created after the lock is
    /// released, and gets the newest frame as soon as it exists.
    /// </summary>
    public ReceiveOutcome Accept(TransportKind transport, in PadPacket packet, IStatusSink? sink, long rxTimestamp, PairingKey verifiedWith)
    {
        long nowUs = ClockMath.ToMicroseconds(rxTimestamp, _clock.Frequency);
        ReceiveOutcome outcome;
        lock (_gate)
        {
            if (!ReferenceEquals(verifiedWith, _pairing)) return ReceiveOutcome.StaleKey;
            Counters c = _transport[(int)transport];
            c.Packets++;
            uint missingBefore = _session.Missing;
            LinkMode before = _session.Mode;
            AcceptResult r = _session.Accept(in packet, nowUs);
            if (r is AcceptResult.Duplicate or AcceptResult.EpochRejected) return RejectedLocked(r, c, sink, packet.Seq, nowUs);
            if (before == LinkMode.Wheel) NeutralizeWheelLocked(); // 12.4 rule 2: into controller mode
            outcome = CountAcceptedLocked(r, c, missingBefore, sink, nowUs);
            // Also on every entry into controller mode: the wheel output may have changed in wheel mode (an
            // Xbox 360 wheel output then serves as the Xbox pad instead of a second, engine-owned one).
            if (packet.StylePs != _padStylePs || _pad is null || _padStale || before != LinkMode.Controller)
            {
                _padStylePs = packet.StylePs;
                CheckPadDeviceLocked();
            }
            ApplyCurrentLocked();
            _acceptedPad = _lastPadFrame;
        }
        if (_padStale) EnsurePadDevice();
        return outcome;
    }

    private ReceiveOutcome CountAcceptedLocked(AcceptResult r, Counters c, uint missingBefore, IStatusSink? sink, long nowUs)
    {
        c.FirstArrivals++;
        _totalAccepted++;
        if (sink is not null) TouchSinkLocked(sink, nowUs);
        if (r == AcceptResult.AcceptedNewEpoch)
        {
            _epochChanges++;
            return ReceiveOutcome.AcceptedNewEpoch;
        }
        _totalMissing += _session.Missing - missingBefore;
        return ReceiveOutcome.Accepted;
    }

    private ReceiveOutcome RejectedLocked(AcceptResult r, Counters c, IStatusSink? sink, uint seq, long nowUs)
    {
        if (r == AcceptResult.Duplicate)
        {
            c.Duplicates++;
            // The copy that lost the multipath race still came from a live path. Section 6 sends STATUS to
            // each endpoint that delivered the accepted packet, and the phone judges each path by the STATUS
            // it gets back on it: without this, the slower path (usually Wi-Fi next to USB) would never hear
            // from the hub and would look dead to the phone.
            if (sink is not null && unchecked(_session.LastSeq - seq) < RecentDuplicateWindow)
                TouchSinkLocked(sink, nowUs);
            return ReceiveOutcome.Duplicate;
        }
        c.ForeignEpoch++;
        return ReceiveOutcome.EpochRejected;
    }

    // --------------------------------------------------------------- housekeeping ---

    /// <summary>Starts the 1 ms housekeeping thread.</summary>
    public void Start()
    {
        if (_housekeeping is not null) return;
        _stopping = false;
        _housekeeping = new Thread(HousekeepingLoop)
        {
            IsBackground = true,
            Name = "slipstream-housekeeping",
            Priority = ThreadPriority.AboveNormal,
        };
        _housekeeping.Start();
    }

    public void Stop()
    {
        _stopping = true;
        _housekeeping?.Join(1000);
        _housekeeping = null;
    }

    private void HousekeepingLoop()
    {
        while (!_stopping)
        {
            try { Tick(); }
            catch (Exception ex) { _lastOutputError = "Housekeeping: " + ex.Message; }
            Thread.Sleep(1);
        }
    }

    /// <summary>
    /// One housekeeping step: pulse releases, replayed taps, failsafe, rate window, STATUS. Called by the
    /// housekeeping thread every millisecond, or directly by tests with a manual clock.
    /// Not reentrant: only one thread may call it.
    /// </summary>
    public void Tick()
    {
        long nowUs = _clock.NowUs();
        int toSend = 0;
        lock (_gate)
        {
            if (_session.Update(nowUs)) ApplyCurrentLocked();

            if (nowUs - _rateStartUs >= RateWindowUs) UpdateRatesLocked(nowUs);

            if (nowUs >= _nextStatusUs)
            {
                long interval = _options.StatusIntervalMs * 1000L;
                _nextStatusUs = nowUs - _nextStatusUs > interval ? nowUs + interval : _nextStatusUs + interval;
                if (_session.HasEpoch) toSend = PrepareStatusLocked(nowUs);
            }
        }

        if (toSend == 0) return;
        for (int i = 0; i < toSend; i++)
        {
            IStatusSink sink = _sendList[i]!;
            _sendList[i] = null;
            try { sink.SendStatus(_statusBuffer); }
            catch { /* a sink that fails is pruned once it stops delivering */ }
        }
        Interlocked.Add(ref _statusSent, toSend);
    }

    private int PrepareStatusLocked(long nowUs)
    {
        long timeoutUs = _options.StatusSinkTimeoutMs * 1000L;
        int n = 0;
        for (int i = 0; i < _sinkCount;)
        {
            ref Sink s = ref _sinks[i];
            if (!s.Target!.IsOpen || nowUs - s.LastAcceptUs > timeoutUs)
            {
                // Remove by swapping in the last entry.
                _sinks[i] = _sinks[--_sinkCount];
                _sinks[_sinkCount] = default;
                continue;
            }
            _sendList[n++] = s.Target;
            i++;
        }
        if (n == 0) return 0;

        long hold = nowUs - _session.LastAcceptUs;
        // Rumble comes from the device the game is driving: the pad in controller mode, the wheel otherwise.
        var rumble = (_session.Mode == LinkMode.Controller ? _pad : (object)_output) as IRumbleSource;
        var status = new StatusPacket
        {
            Epoch = _session.Epoch,
            LastSeq = _session.LastSeq,
            EchoTimeUs = _session.LastTimeUs,
            HoldUs = (uint)Math.Clamp(hold, 0, uint.MaxValue),
            Accepted = _session.Accepted,
            Missing = _session.Missing,
            RumbleStrong = rumble?.RumbleStrong ?? 0,
            RumbleWeak = rumble?.RumbleWeak ?? 0,
            Output = OutputByteLocked(),
            HubFlags = 0,
        };
        status.Encode(_statusBuffer, _pairing.Auth);
        return n;
    }

    private byte OutputByteLocked()
    {
        OutputKind kind;
        OutputState state;
        if (_session.Mode == LinkMode.Controller)
        {
            IPadOutputDevice? pad = _pad;
            if (pad is null) return 0;
            kind = pad.Kind;
            state = pad.State;
        }
        else
        {
            kind = _output.Kind;
            state = _output.State;
        }
        byte b = (byte)((byte)kind & 0x7F);
        if (state != OutputState.Ready) b |= Wire.OutputErrorBit;
        return b;
    }

    private void UpdateRatesLocked(long nowUs)
    {
        double dt = (nowUs - _rateStartUs) / 1e6;
        long dAccepted = _totalAccepted - _rateAccepted;
        long dMissing = _totalMissing - _rateMissing;
        _rateHz = dAccepted / dt;
        _recentLoss = dAccepted + dMissing > 0 ? 100.0 * dMissing / (dAccepted + dMissing) : 0;
        for (int t = 0; t < 2; t++)
        {
            Counters c = _transport[t];
            _packetsPerSec[t] = (c.Packets - _ratePackets[t]) / dt;
            _firstPerSec[t] = (c.FirstArrivals - _rateFirst[t]) / dt;
            _ratePackets[t] = c.Packets;
            _rateFirst[t] = c.FirstArrivals;
        }
        _rateAccepted = _totalAccepted;
        _rateMissing = _totalMissing;
        _rateStartUs = nowUs;
    }

    private void TouchSinkLocked(IStatusSink sink, long nowUs)
    {
        for (int i = 0; i < _sinkCount; i++)
        {
            if (ReferenceEquals(_sinks[i].Target, sink))
            {
                _sinks[i].LastAcceptUs = nowUs;
                return;
            }
        }
        if (_sinkCount < MaxSinks)
        {
            _sinks[_sinkCount++] = new Sink { Target = sink, LastAcceptUs = nowUs };
            return;
        }
        int oldest = 0;
        for (int i = 1; i < _sinkCount; i++)
            if (_sinks[i].LastAcceptUs < _sinks[oldest].LastAcceptUs) oldest = i;
        _sinks[oldest] = new Sink { Target = sink, LastAcceptUs = nowUs };
    }

    // ------------------------------------------------------------------- devices ---

    private void ApplyCurrentLocked()
    {
        if (_session.Mode == LinkMode.Controller) ApplyPadLocked();
        else ApplyWheelLocked();
    }

    private void ApplyWheelLocked()
    {
        ControllerFrame frame = _session.CurrentFrame();
        _lastFrame = Mapping.Map(in frame, _options.Invert);
        try
        {
            _output.Apply(in _lastFrame);
        }
        catch (Exception ex)
        {
            _outputErrors++;
            _lastOutputError = ex.Message;
        }
    }

    private void ApplyPadLocked()
    {
        PadFrame frame = _session.CurrentPadFrame();
        _lastPadFrame = Mapping.MapPad(in frame);
        IPadOutputDevice? pad = _pad;
        if (pad is null) return; // being plugged in: it gets the newest frame once it exists
        try
        {
            pad.Apply(in _lastPadFrame);
        }
        catch (Exception ex)
        {
            _outputErrors++;
            _lastOutputError = ex.Message;
        }
    }

    /// <summary>12.4 rule 2, INPUT to PAD: the wheel device goes to neutral and stays there in controller mode.</summary>
    private void NeutralizeWheelLocked()
    {
        try { _output.Neutral(); }
        catch (Exception ex)
        {
            _outputErrors++;
            _lastOutputError = ex.Message;
        }
        _lastFrame = Mapping.Map(ControllerFrame.Neutral, AxisInvert.None);
    }

    /// <summary>12.4 rule 2, PAD to INPUT: the pad goes to neutral and stays plugged in.</summary>
    private void NeutralizePadLocked()
    {
        IPadOutputDevice? pad = _pad;
        if (pad is not null)
        {
            try { pad.Neutral(); }
            catch (Exception ex)
            {
                _outputErrors++;
                _lastOutputError = ex.Message;
            }
        }
        _lastPadFrame = Mapping.MapPad(PadFrame.Neutral);
    }

    /// <summary>The wheel output, when it can also serve as the pad of <paramref name="kind"/> (Xbox 360 in both modes).</summary>
    private IPadOutputDevice? SharedPadLocked(OutputKind kind)
        => _output is IPadOutputDevice p && p.Kind == kind ? p : null;

    /// <summary>True when <see cref="_pad"/> is the device controller mode wants now, or when none can be made.</summary>
    private bool PadIsRightLocked()
    {
        IPadOutputDevice? shared = SharedPadLocked(_padWanted);
        if (shared is not null) return ReferenceEquals(_pad, shared);
        if (_padFactory is null) return _pad is null;
        return _pad is not null && _padOwned && _pad.Kind == _padWanted;
    }

    /// <summary>Resolves the wanted pad kind and flags a replacement when the current device is not it.</summary>
    private void CheckPadDeviceLocked()
    {
        _padWanted = ResolvePadKind(_options.PadOutput, _padStylePs);
        if (!_disposed && _session.Mode == LinkMode.Controller && !PadIsRightLocked()) _padStale = true;
    }

    /// <summary>
    /// Plugs in the pad device controller mode wants (or switches to the shared wheel output), replacing the
    /// previous one. Runs outside the engine lock: creating a virtual pad takes milliseconds, and the other
    /// receive thread must not wait for it. Only one thread creates at a time; the others go on.
    /// </summary>
    private void EnsurePadDevice()
    {
        if (!Monitor.TryEnter(_padCreateGate)) return;
        try
        {
            for (int attempt = 0; attempt < 4; attempt++)
            {
                OutputKind want;
                Func<OutputKind, IPadOutputDevice>? factory;
                IPadOutputDevice? retired = null;
                lock (_gate)
                {
                    if (!_padStale || _disposed || _padHold > 0) return;
                    if (_session.Mode != LinkMode.Controller || PadIsRightLocked())
                    {
                        _padStale = false;
                        return;
                    }
                    want = _padWanted;
                    factory = _padFactory;
                    IPadOutputDevice? shared = SharedPadLocked(want);
                    if (shared is not null || factory is null)
                    {
                        retired = InstallPadLocked(shared, owned: false);
                        factory = null;
                    }
                }
                if (factory is not null)
                {
                    IPadOutputDevice created = CreatePad(factory, want);
                    lock (_gate)
                    {
                        bool stillWanted = !_disposed && _padHold == 0 && _session.Mode == LinkMode.Controller
                                           && _padWanted == want && SharedPadLocked(want) is null;
                        retired = stillWanted ? InstallPadLocked(created, owned: true) : created;
                    }
                }
                DisposeQuietly(retired);
            }
        }
        finally
        {
            Monitor.Exit(_padCreateGate);
        }
    }

    private static IPadOutputDevice CreatePad(Func<OutputKind, IPadOutputDevice> factory, OutputKind kind)
    {
        try
        {
            return factory(kind) ?? new FailedPadOutput(kind, "No pad device was created.");
        }
        catch (Exception ex)
        {
            return new FailedPadOutput(kind, $"Could not start the virtual pad: {ex.Message}");
        }
    }

    /// <summary>Makes <paramref name="device"/> the pad and applies the current frame. Returns the owned device it replaced, to dispose outside the lock.</summary>
    private IPadOutputDevice? InstallPadLocked(IPadOutputDevice? device, bool owned)
    {
        IPadOutputDevice? retired = DetachPadLocked(device);
        _pad = device;
        _padOwned = owned && device is not null;
        _padStale = false;
        if (_session.Mode == LinkMode.Controller) ApplyPadLocked();
        return retired;
    }

    /// <summary>
    /// Takes the current pad out of service. An owned pad is set to neutral and returned for disposal; a
    /// shared one (the wheel output) is only let go, and set to neutral only in controller mode, where the
    /// wheel side is neutral anyway.
    /// </summary>
    private IPadOutputDevice? DetachPadLocked(IPadOutputDevice? keep = null)
    {
        IPadOutputDevice? old = _pad;
        bool owned = _padOwned;
        _pad = null;
        _padOwned = false;
        if (old is null || ReferenceEquals(old, keep)) return null;
        if (owned || _session.Mode == LinkMode.Controller)
        {
            try { old.Neutral(); } catch { /* the device may already be gone */ }
        }
        return owned ? old : null;
    }

    private static void DisposeQuietly(IDisposable? d)
    {
        if (d is null) return;
        try { d.Dispose(); } catch { /* a failing old driver must not stop the link */ }
    }

    // ------------------------------------------------------------- configuration ---

    /// <summary>Switches to a new pairing key. The session forgets its epoch, its mode and queued presses.</summary>
    public void SetPairing(PairingKey pairing)
    {
        ArgumentNullException.ThrowIfNull(pairing);
        lock (_gate)
        {
            _pairing = pairing;
            if (_session.Mode == LinkMode.Controller) NeutralizePadLocked();
            _session.Reset();
            Array.Clear(_sinks);
            _sinkCount = 0;
            _padStale = false;
            ApplyCurrentLocked();
            _acceptedFrame = default;
            _acceptedPad = default;
            _lastPadFrame = default;
        }
    }

    /// <summary>
    /// Swaps the wheel output device. Returns the previous one, already set to neutral; the caller disposes it.
    /// A pad that was sharing the old device is let go; its replacement is plugged in by the next PAD packet or
    /// by <see cref="EndOutputChange"/>.
    /// </summary>
    public IOutputDevice SetOutput(IOutputDevice output)
    {
        ArgumentNullException.ThrowIfNull(output);
        lock (_gate)
        {
            IOutputDevice old = _output;
            if (ReferenceEquals(old, output)) return old;
            try { old.Neutral(); } catch { /* the old device may already be gone */ }
            if (ReferenceEquals(_pad, old))
            {
                _pad = null;
                _padOwned = false;
            }
            _output = output;
            if (_session.Mode == LinkMode.Controller)
            {
                // The wheel side stays neutral in controller mode.
                try { output.Neutral(); } catch { /* reported through its state */ }
                _lastFrame = Mapping.Map(ControllerFrame.Neutral, AxisInvert.None);
                CheckPadDeviceLocked();
            }
            // No pad is created here: the caller still holds the old device, and a new virtual pad plugged in
            // before the old one is gone would take a later slot. The next PAD packet, or EndOutputChange,
            // plugs it in.
            ApplyCurrentLocked();
            return old;
        }
    }

    /// <summary>
    /// Called by the host before it creates a new wheel output of <paramref name="newWheelKind"/>. No pad device
    /// is created until <see cref="EndOutputChange"/>, and an engine-owned pad of the same kind is unplugged
    /// first, so the new wheel device takes the slot it frees (the first XInput slot, for Xbox 360) and then
    /// serves as the pad as well.
    /// </summary>
    public void BeginOutputChange(OutputKind newWheelKind)
    {
        IPadOutputDevice? retired = null;
        lock (_gate)
        {
            _padHold++;
            if (_pad is not null && _padOwned && _pad.Kind == newWheelKind)
            {
                retired = DetachPadLocked();
                _padStale = _session.Mode == LinkMode.Controller;
            }
        }
        DisposeQuietly(retired);
    }

    /// <summary>Ends <see cref="BeginOutputChange"/>: plugs in the pad controller mode needs now, if any.</summary>
    public void EndOutputChange()
    {
        lock (_gate)
        {
            if (_padHold > 0) _padHold--;
            CheckPadDeviceLocked();
        }
        if (_padStale) EnsurePadDevice();
    }

    /// <summary>
    /// Unplugs the engine-owned pad and, in controller mode, plugs in a fresh one of the kind wanted (a Retry
    /// for a pad that cannot retry by itself, such as one whose construction failed). In wheel mode the next
    /// PAD packet plugs it in. A shared pad (the wheel output) is left alone: its owner retries it.
    /// </summary>
    public void ReplugPad()
    {
        IPadOutputDevice? retired = null;
        lock (_gate)
        {
            if (_disposed || _pad is null || !_padOwned) return;
            retired = DetachPadLocked();
            _padStale = _session.Mode == LinkMode.Controller;
        }
        DisposeQuietly(retired);
        if (_padStale) EnsurePadDevice();
    }

    public IOutputDevice Output
    {
        get { lock (_gate) return _output; }
    }

    /// <summary>The controller-mode pad device, null until the first PAD packet plugs one in.</summary>
    public IPadOutputDevice? PadOutput
    {
        get { lock (_gate) return _pad; }
    }

    /// <summary>
    /// Applies new timing, inversion and controller output settings. Takes effect on the next packet or tick.
    /// A changed controller output replaces the pad device at once in controller mode, and unplugs it in wheel
    /// mode (the next PAD packet plugs in the new kind).
    /// </summary>
    public void UpdateOptions(HubEngineOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        IPadOutputDevice? retired = null;
        lock (_gate)
        {
            bool padChanged = options.PadOutput != _options.PadOutput;
            _options = options;
            _session.Timing = options.Timing;
            if (padChanged)
            {
                _padWanted = ResolvePadKind(options.PadOutput, _padStylePs);
                if (_session.Mode == LinkMode.Controller) CheckPadDeviceLocked();
                else if (_pad is not null && _pad.Kind != _padWanted) retired = DetachPadLocked();
            }
            ApplyCurrentLocked();
        }
        DisposeQuietly(retired);
        if (_padStale) EnsurePadDevice();
    }

    // ------------------------------------------------------------------- snapshot ---

    /// <summary>
    /// A consistent copy for the UI and tools. The lock is held only for plain copies into buffers
    /// allocated beforehand: the UI thread runs at normal priority next to a game, and if it were
    /// preempted while allocating inside the lock, the receive thread would wait behind it.
    /// </summary>
    public HubSnapshot GetSnapshot()
    {
        var counters = new long[2 * 6];
        var rates = new double[2 * 2];
        var emitted = new long[Wire.PulseChannels];
        var pending = new int[Wire.PulseChannels];
        var tapsEmitted = new long[Wire.PadButtons];
        var tapsReplayed = new long[Wire.PadButtons];
        var tapsPending = new int[Wire.PadButtons];
        var sinks = new IStatusSink?[MaxSinks];
        int sinkCount = 0;

        long nowUs;
        bool has, paused, multipath;
        ushort rtt100us;
        LinkMode mode;
        InputPacket last;
        PadPacket lastPad;
        OutputFrame frame, acceptedFrame;
        PadOutputFrame padFrame, acceptedPad;
        uint epoch, lastSeq, acc, miss;
        long lastAcceptUs, totalAccepted, totalMissing, epochChanges, outputErrors;
        double recentLoss, rateHz;
        HubEngineOptions options;
        IOutputDevice output;
        IPadOutputDevice? pad;
        OutputKind padWanted;
        string? lastOutputError;
        PairingKey pairing;

        lock (_gate)
        {
            nowUs = _clock.NowUs();
            for (int t = 0; t < 2; t++)
            {
                Counters c = _transport[t];
                int o = t * 6;
                counters[o] = c.Packets;
                counters[o + 1] = c.FirstArrivals;
                counters[o + 2] = c.Duplicates;
                counters[o + 3] = Interlocked.Read(ref c.BadTags);
                counters[o + 4] = Interlocked.Read(ref c.Malformed);
                counters[o + 5] = c.ForeignEpoch;
                rates[t * 2] = _packetsPerSec[t];
                rates[t * 2 + 1] = _firstPerSec[t];
            }
            for (int ch = 0; ch < Wire.PulseChannels; ch++)
            {
                emitted[ch] = _session.Pulses.Emitted(ch);
                pending[ch] = _session.Pulses.Pending(ch);
            }
            for (int b = 0; b < Wire.PadButtons; b++)
            {
                tapsEmitted[b] = _session.PadPresses(b);
                tapsReplayed[b] = _session.Taps.Replayed(b);
                tapsPending[b] = _session.Taps.Pending(b);
            }
            long timeoutUs = _options.StatusSinkTimeoutMs * 1000L;
            for (int i = 0; i < _sinkCount; i++)
                if (nowUs - _sinks[i].LastAcceptUs <= timeoutUs) sinks[sinkCount++] = _sinks[i].Target;

            has = _session.HasEpoch;
            mode = _session.Mode;
            paused = _session.LastPaused;
            multipath = _session.LastMultipath;
            rtt100us = _session.LastRtt100us;
            last = _session.Last;
            lastPad = _session.LastPad;
            frame = _lastFrame;
            acceptedFrame = _acceptedFrame;
            padFrame = _lastPadFrame;
            acceptedPad = _acceptedPad;
            epoch = _session.Epoch;
            lastSeq = _session.LastSeq;
            acc = _session.Accepted;
            miss = _session.Missing;
            lastAcceptUs = _session.LastAcceptUs;
            totalAccepted = _totalAccepted;
            totalMissing = _totalMissing;
            epochChanges = _epochChanges;
            outputErrors = _outputErrors;
            recentLoss = _recentLoss;
            rateHz = _rateHz;
            options = _options;
            output = _output;
            pad = _pad;
            padWanted = _padWanted;
            lastOutputError = _lastOutputError;
            pairing = _pairing;
        }

        var transports = new TransportStats[2];
        double firstTotal = rates[1] + rates[3];
        for (int t = 0; t < 2; t++)
        {
            int o = t * 6;
            transports[t] = new TransportStats
            {
                Kind = (TransportKind)t,
                Packets = counters[o],
                FirstArrivals = counters[o + 1],
                Duplicates = counters[o + 2],
                BadTags = counters[o + 3],
                Malformed = counters[o + 4],
                ForeignEpoch = counters[o + 5],
                PacketsPerSecond = rates[t * 2],
                FirstArrivalsPerSecond = rates[t * 2 + 1],
                FirstArrivalShare = firstTotal > 0 ? rates[t * 2 + 1] / firstTotal : 0,
            };
        }

        var endpoints = new List<string>(sinkCount);
        for (int i = 0; i < sinkCount; i++)
            if (sinks[i]!.IsOpen) endpoints.Add(sinks[i]!.Endpoint);

        bool controller = has && mode == LinkMode.Controller;
        double? since = has ? (nowUs - lastAcceptUs) / 1000.0 : null;
        return new HubSnapshot
        {
            // PAUSED before the failsafe: a phone that leaves its drive screen sends PAUSED and then stops
            // its link. The device keeps the PAUSED output (steering centred, rule 6), so the label must say
            // paused, not "signal lost, steering held", until the phone counts as gone.
            State = !has ? LinkState.Waiting
                : since >= options.LostAfterMs ? LinkState.Lost
                : paused ? LinkState.Paused
                : since >= options.FailsafeMs ? LinkState.Failsafe
                : LinkState.Live,
            HasEpoch = has,
            Epoch = epoch,
            LastSeq = lastSeq,
            Accepted = acc,
            Missing = miss,
            TotalAccepted = totalAccepted,
            TotalMissing = totalMissing,
            EpochChanges = epochChanges,
            LossPercent = acc + (double)miss > 0 ? 100.0 * miss / (acc + (double)miss) : 0,
            RecentLossPercent = recentLoss,
            RateHz = rateHz,
            PhoneRttMs = has && rtt100us != 0 ? rtt100us / 10.0 : null,
            SinceLastPacketMs = since,
            Paused = has && paused,
            Calibrating = has && mode == LinkMode.Wheel && last.Calibrating,
            Multipath = has && multipath,
            LastInput = last,
            Frame = frame.Source,
            Output = frame,
            AcceptedOutput = acceptedFrame,
            Transports = transports,
            PulsesEmitted = emitted,
            PulsesPending = pending,
            OutputName = output.Name,
            OutputKind = output.Kind,
            OutputState = output.State,
            OutputDetail = output.StateDetail,
            OutputErrors = outputErrors,
            LastOutputError = lastOutputError,
            StatusSent = Interlocked.Read(ref _statusSent),
            StatusEndpoints = endpoints,
            PairingCode = pairing.Code,
            FingerprintHex = pairing.FingerprintHex,
            Mode = has ? mode : LinkMode.None,
            Style = controller ? (lastPad.StylePs ? PadStyle.PlayStation : PadStyle.Xbox) : PadStyle.None,
            LastPad = lastPad,
            PadFrame = padFrame.Source,
            PadOutput = padFrame,
            AcceptedPadOutput = acceptedPad,
            TapsEmitted = tapsEmitted,
            TapsReplayed = tapsReplayed,
            TapsPending = tapsPending,
            PadOutputSelection = options.PadOutput,
            PadOutputWanted = padWanted,
            PadPlugged = pad is not null,
            PadOutputName = pad?.Name ?? PadKindLabel(padWanted),
            PadOutputKind = pad?.Kind ?? padWanted,
            PadOutputState = pad?.State ?? OutputState.Ready,
            PadOutputDetail = pad?.StateDetail
                ?? $"Not plugged in yet. The virtual {PadKindLabel(padWanted)} pad appears when the phone switches to controller mode.",
        };
    }

    private static string PadKindLabel(OutputKind kind) => kind == OutputKind.DualShock4 ? "DualShock 4" : "Xbox 360";

    public void Dispose()
    {
        Stop();
        IPadOutputDevice? retired;
        lock (_gate)
        {
            _disposed = true;
            try { _output.Neutral(); } catch { /* shutting down */ }
            // A receive thread that is still finishing its last packet must not drive the real device
            // after it was set to neutral (the owner disposes it next).
            _output = new NullOutput();
            retired = DetachPadLocked(); // a shared pad is the wheel output: its owner disposes it
            _padFactory = null;
            _padStale = false;
        }
        DisposeQuietly(retired);
    }

    private sealed class Counters
    {
        public long Packets, FirstArrivals, Duplicates, ForeignEpoch;
        public long BadTags, Malformed; // written with Interlocked, outside the lock
    }

    private struct Sink
    {
        public IStatusSink? Target;
        public long LastAcceptUs;
    }
}
