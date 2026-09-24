using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// The hub core. One lock. <see cref="Accept"/> runs on the calling receive thread and writes the
/// virtual device before returning (rule 5: no frame timer, no queue). A housekeeping thread with a
/// 1 ms tick releases pulse presses on time, fires the failsafe and sends STATUS at 20 Hz to every
/// endpoint that delivered an accepted packet in the last second.
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

    // Rate window.
    private long _rateStartUs;
    private long _rateAccepted, _rateMissing;
    private readonly long[] _ratePackets = new long[2], _rateFirst = new long[2];
    private double _rateHz, _recentLoss;
    private readonly double[] _packetsPerSec = new double[2], _firstPerSec = new double[2];

    private Thread? _housekeeping;
    private volatile bool _stopping;

    public HubEngine(PairingKey pairing, IOutputDevice output, HubEngineOptions? options = null, IClock? clock = null)
    {
        _pairing = pairing ?? throw new ArgumentNullException(nameof(pairing));
        _output = output ?? throw new ArgumentNullException(nameof(output));
        _options = options ?? new HubEngineOptions();
        _clock = clock ?? StopwatchClock.Instance;
        _session = new LinkSession(_options.Timing);
        long now = _clock.NowUs();
        _nextStatusUs = now;
        _rateStartUs = now;
        lock (_gate) ApplyCurrentLocked();
    }

    public IClock Clock => _clock;

    public PairingKey Pairing => _pairing;

    public HubEngineOptions Options
    {
        get { lock (_gate) return _options; }
    }

    // ------------------------------------------------------------------ hot path ---

    /// <summary>
    /// Rule 1 for one datagram or frame: header, exact length, tag. Counts rejects. Runs outside the
    /// lock. On success returns the key that verified it, to hand to <see cref="Accept"/>.
    /// </summary>
    public ReceiveOutcome Validate(TransportKind transport, ReadOnlySpan<byte> data, out InputPacket packet, out PairingKey verifiedWith)
    {
        verifiedWith = _pairing;
        DecodeResult r = InputPacket.TryDecode(data, verifiedWith.Auth, out packet);
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

    /// <summary>Validate then accept. <paramref name="rxTimestamp"/> is the clock tick when the bytes arrived.</summary>
    public ReceiveOutcome Receive(TransportKind transport, ReadOnlySpan<byte> data, IStatusSink? sink, long rxTimestamp)
    {
        ReceiveOutcome v = Validate(transport, data, out InputPacket packet, out PairingKey key);
        return v == ReceiveOutcome.Accepted ? Accept(transport, in packet, sink, rxTimestamp, key) : v;
    }

    /// <summary>
    /// Rules 2 to 5 for a packet that passed <see cref="Validate"/>: session update and, when accepted,
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
            switch (_session.Accept(in packet, nowUs))
            {
                case AcceptResult.Accepted:
                    c.FirstArrivals++;
                    _totalAccepted++;
                    _totalMissing += _session.Missing - missingBefore;
                    if (sink is not null) TouchSinkLocked(sink, nowUs);
                    ApplyCurrentLocked();
                    _acceptedFrame = _lastFrame;
                    return ReceiveOutcome.Accepted;
                case AcceptResult.AcceptedNewEpoch:
                    c.FirstArrivals++;
                    _totalAccepted++;
                    _epochChanges++;
                    if (sink is not null) TouchSinkLocked(sink, nowUs);
                    ApplyCurrentLocked();
                    _acceptedFrame = _lastFrame;
                    return ReceiveOutcome.AcceptedNewEpoch;
                case AcceptResult.Duplicate:
                    c.Duplicates++;
                    // The copy that lost the multipath race still came from a live path. Section 6 sends
                    // STATUS to each endpoint that delivered the accepted INPUT, and the phone judges each
                    // path by the STATUS it gets back on it: without this, the slower path (usually Wi-Fi
                    // next to USB) would never hear from the hub and would look dead to the phone.
                    if (sink is not null && unchecked(_session.LastSeq - packet.Seq) < RecentDuplicateWindow)
                        TouchSinkLocked(sink, nowUs);
                    return ReceiveOutcome.Duplicate;
                default:
                    c.ForeignEpoch++;
                    return ReceiveOutcome.EpochRejected;
            }
        }
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
    /// One housekeeping step: pulse releases, failsafe, rate window, STATUS. Called by the
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
        var rumble = _output as IRumbleSource;
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
        byte b = (byte)((byte)_output.Kind & 0x7F);
        if (_output.State != OutputState.Ready) b |= Wire.OutputErrorBit;
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

    private void ApplyCurrentLocked()
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

    // ------------------------------------------------------------- configuration ---

    /// <summary>Switches to a new pairing key. The session forgets its epoch and queued presses.</summary>
    public void SetPairing(PairingKey pairing)
    {
        ArgumentNullException.ThrowIfNull(pairing);
        lock (_gate)
        {
            _pairing = pairing;
            _session.Reset();
            Array.Clear(_sinks);
            _sinkCount = 0;
            ApplyCurrentLocked();
            _acceptedFrame = default;
        }
    }

    /// <summary>Swaps the output device. Returns the previous one, already set to neutral; the caller disposes it.</summary>
    public IOutputDevice SetOutput(IOutputDevice output)
    {
        ArgumentNullException.ThrowIfNull(output);
        lock (_gate)
        {
            IOutputDevice old = _output;
            if (ReferenceEquals(old, output)) return old;
            try { old.Neutral(); } catch { /* the old device may already be gone */ }
            _output = output;
            ApplyCurrentLocked();
            return old;
        }
    }

    public IOutputDevice Output
    {
        get { lock (_gate) return _output; }
    }

    /// <summary>Applies new timing and inversion settings. Takes effect on the next packet or tick.</summary>
    public void UpdateOptions(HubEngineOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);
        lock (_gate)
        {
            _options = options;
            _session.Timing = options.Timing;
            ApplyCurrentLocked();
        }
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
        var sinks = new IStatusSink?[MaxSinks];
        int sinkCount = 0;

        long nowUs;
        bool has;
        InputPacket last;
        OutputFrame frame, acceptedFrame;
        uint epoch, lastSeq, acc, miss;
        long lastAcceptUs, totalAccepted, totalMissing, epochChanges, outputErrors;
        double recentLoss, rateHz;
        HubEngineOptions options;
        IOutputDevice output;
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
            long timeoutUs = _options.StatusSinkTimeoutMs * 1000L;
            for (int i = 0; i < _sinkCount; i++)
                if (nowUs - _sinks[i].LastAcceptUs <= timeoutUs) sinks[sinkCount++] = _sinks[i].Target;

            has = _session.HasEpoch;
            last = _session.Last;
            frame = _lastFrame;
            acceptedFrame = _acceptedFrame;
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

        double? since = has ? (nowUs - lastAcceptUs) / 1000.0 : null;
        return new HubSnapshot
        {
            // PAUSED before the failsafe: a phone that leaves its drive screen sends PAUSED and then stops
            // its link. The device keeps the PAUSED output (steering centred, rule 6), so the label must say
            // paused, not "signal lost, steering held", until the phone counts as gone.
            State = !has ? LinkState.Waiting
                : since >= options.LostAfterMs ? LinkState.Lost
                : last.Paused ? LinkState.Paused
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
            PhoneRttMs = has && last.Rtt100us != 0 ? last.Rtt100us / 10.0 : null,
            SinceLastPacketMs = since,
            Paused = has && last.Paused,
            Calibrating = has && last.Calibrating,
            Multipath = has && last.Multipath,
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
        };
    }

    public void Dispose()
    {
        Stop();
        lock (_gate)
        {
            try { _output.Neutral(); } catch { /* shutting down */ }
            // A receive thread that is still finishing its last packet must not drive the real device
            // after it was set to neutral (the owner disposes it next).
            _output = new NullOutput();
        }
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
