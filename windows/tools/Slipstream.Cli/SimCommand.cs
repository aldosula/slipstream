using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using Slipstream.Core.Protocol;

namespace Slipstream.Cli;

/// <summary>
/// "sim": a fake phone. Sends INPUT (wheel) or, with --pad, PAD (controller mode, PROTOCOL.md section 12)
/// at a fixed rate with simulated loss over UDP, framed TCP, or both (multipath). The wheel sends N
/// shift-ups spread over the run; the pad moves both sticks, ramps both triggers and makes N short taps on
/// Cross (A in the Xbox layout). RTT and loss come from STATUS exactly as the phone computes them (section 6).
/// </summary>
internal static class SimCommand
{
    public const string Usage =
        "slipstream sim --code CODE [--host 127.0.0.1] [--port 47800] [--tcp] [--tcp-port 47802] [--multipath]\n" +
        "               [--rate 500] [--loss 0..1] [--seconds 5] [--pulses N] [--stats-json PATH]\n" +
        "               [--pad] [--style ps|xbox] [--taps N] [--tap-ms 20]\n" +
        "  --pad         controller mode: PAD packets with moving sticks, trigger ramps and --taps N short taps\n" +
        "                on Cross (A), each held for --tap-ms, spread over the run";

    private static readonly IReadOnlySet<string> Flags = new HashSet<string> { "tcp", "multipath", "pad" };

    public static int Run(string[] argv)
    {
        var a = new Args(argv, Flags);
        string? codeArg = a.String("code");
        string host = a.String("host") ?? "127.0.0.1";
        int port = a.Int("port", Wire.DefaultUdpPort, 1, 65535);
        bool tcpOnly = a.Flag("tcp");
        int tcpPort = a.Int("tcp-port", Wire.DefaultTcpPort, 1, 65535);
        bool multipath = a.Flag("multipath");
        int rate = a.Int("rate", 500, 1, 2000);
        double loss = a.Double("loss", 0, 0, 1);
        double seconds = a.Double("seconds", 5, 0.1, 86_400);
        int pulses = a.Int("pulses", 0, 0, 100_000);
        string? statsPath = a.String("stats-json");
        bool pad = a.Flag("pad");
        string style = (a.String("style") ?? "ps").Trim().ToLowerInvariant();
        int taps = a.Int("taps", 0, 0, 100_000);
        int tapMs = a.Int("tap-ms", 20, 1, 1000);
        a.RejectUnknown();
        if (style is not ("ps" or "xbox")) throw new UsageException("--style must be ps or xbox.");
        if (!pad && (taps > 0 || a.String("style") is not null)) throw new UsageException("--taps and --style need --pad.");
        if (pad && pulses > 0) throw new UsageException("--pulses is for the wheel; use --taps with --pad.");
        bool playStation = style == "ps";

        if (codeArg is null) throw new UsageException("sim needs --code, the pairing code shown by the hub.");
        PairingKey key = PairingKey.TryFromCode(codeArg, out PairingKey? k) ? k! : throw new UsageException("--code is not a valid pairing code.");
        if (!IPAddress.TryParse(host, out IPAddress? hubAddress))
        {
            hubAddress = Dns.GetHostAddresses(host).FirstOrDefault(x => x.AddressFamily == AddressFamily.InterNetwork)
                         ?? throw new UsageException($"Cannot resolve --host {host} to an IPv4 address.");
        }

        bool useUdp = !tcpOnly || multipath;
        bool useTcp = tcpOnly || multipath;
        var stats = new SimStats();
        using var sim = new Simulator(key, hubAddress, port, tcpPort, useUdp, useTcp, stats);
        sim.Connect();
        string what = pad
            ? $"controller ({(playStation ? "PlayStation" : "Xbox")} layout), {taps} taps on {(playStation ? "Cross" : "A")} of {tapMs} ms"
            : $"{pulses} shift-ups";
        Console.WriteLine($"Slipstream sim: epoch {sim.Epoch:x8}, {rate} Hz, loss {loss.ToString("0.###", CultureInfo.InvariantCulture)}, " +
                          $"{(useUdp ? $"udp {hubAddress}:{port}" : "")}{(useUdp && useTcp ? " + " : "")}{(useTcp ? $"tcp {hubAddress}:{tcpPort}" : "")}, " +
                          $"{what} over {seconds.ToString(CultureInfo.InvariantCulture)} s");

        if (pad) sim.RunPad(rate, loss, seconds, taps, tapMs, playStation);
        else sim.Run(rate, loss, seconds, pulses);

        StatusView v = stats.View();
        string sent = pad
            ? string.Format(CultureInfo.InvariantCulture, "taps sent {0}", stats.TapsSent)
            : string.Format(CultureInfo.InvariantCulture, "shift-ups sent {0}", stats.PulsesSent);
        Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
            "done: sent {0} built, {1} dropped by --loss | {2} | STATUS received {3} | rtt avg {4} min {5} max {6} ms | hub accepted {7} missing {8} loss {9:0.00}% | output 0x{10:x2}",
            stats.Built, stats.Dropped, sent, v.Count, Ms(v.EwmaUs), Ms(v.MinUs), Ms(v.MaxUs), v.Accepted, v.Missing, v.LossPercent, v.Output));
        if (statsPath is not null) WriteStats(statsPath, stats, v, sim.Epoch, pad, playStation);
        if (v.Count == 0)
        {
            Console.Error.WriteLine("No STATUS came back: check the code, the host and port, and the PC firewall (UDP 47800).");
            return 2;
        }
        return 0;
    }

    private static string Ms(double? us) => us is double x ? (x / 1000).ToString("0.000", CultureInfo.InvariantCulture) : "-";

    private static void WriteStats(string path, SimStats s, StatusView v, uint epoch, bool pad, bool playStation)
    {
        using FileStream fs = File.Create(path);
        using var w = new Utf8JsonWriter(fs, new JsonWriterOptions { Indented = true });
        w.WriteStartObject();
        w.WriteNumber("epoch", epoch);
        w.WriteString("mode", pad ? "controller" : "wheel");
        w.WriteString("style", pad ? (playStation ? "ps" : "xbox") : "none");
        w.WriteNumber("built", s.Built);
        w.WriteNumber("dropped", s.Dropped);
        w.WriteNumber("pulses_sent", s.PulsesSent);
        w.WriteNumber("taps_sent", s.TapsSent);
        w.WriteNumber("status_output", v.Output);
        w.WriteNumber("status_received", v.Count);
        w.WriteNumber("status_bad", s.BadStatus);
        w.WriteNumber("hub_accepted", v.Accepted);
        w.WriteNumber("hub_missing", v.Missing);
        w.WriteNumber("loss_percent", Math.Round(v.LossPercent, 3));
        if (v.EwmaUs is double e) w.WriteNumber("rtt_ewma_ms", Math.Round(e / 1000, 3)); else w.WriteNull("rtt_ewma_ms");
        if (v.MinUs is double mn) w.WriteNumber("rtt_min_ms", Math.Round(mn / 1000, 3)); else w.WriteNull("rtt_min_ms");
        if (v.MaxUs is double mx) w.WriteNumber("rtt_max_ms", Math.Round(mx / 1000, 3)); else w.WriteNull("rtt_max_ms");
        w.WriteEndObject();
    }

    private readonly record struct StatusView(int Count, double? EwmaUs, double? MinUs, double? MaxUs, uint Accepted, uint Missing, double LossPercent, double RecentLossPercent, int Output);

    /// <summary>Phone-side link statistics, fed by the STATUS receivers.</summary>
    private sealed class SimStats
    {
        private readonly object _gate = new();
        private readonly Queue<(long Ms, double Us)> _window = new();
        private int _count;
        private double? _ewma;
        private uint _accepted, _missing, _prevAccepted, _prevMissing;
        private double _recentLoss;
        private int _output;

        public long Built, Dropped, PulsesSent, TapsSent, BadStatus;

        public void OnStatus(in StatusPacket st, uint rttUs, long nowMs)
        {
            lock (_gate)
            {
                _count++;
                _ewma = _ewma is double e ? e + (rttUs - e) / 8.0 : rttUs; // alpha = 1/8
                _window.Enqueue((nowMs, rttUs));
                while (_window.Count > 0 && nowMs - _window.Peek().Ms > 5000) _window.Dequeue(); // min/max over 5 s
                uint dA = st.Accepted - _prevAccepted, dM = st.Missing - _prevMissing;
                if (dA + (double)dM > 0 && st.Accepted >= _prevAccepted) _recentLoss = 100.0 * dM / (dA + (double)dM);
                _prevAccepted = st.Accepted;
                _prevMissing = st.Missing;
                _accepted = st.Accepted;
                _missing = st.Missing;
                _output = st.Output;
            }
        }

        public StatusView View()
        {
            lock (_gate)
            {
                double? min = _window.Count == 0 ? null : _window.Min(x => x.Us);
                double? max = _window.Count == 0 ? null : _window.Max(x => x.Us);
                double loss = _accepted + (double)_missing > 0 ? 100.0 * _missing / (_accepted + (double)_missing) : 0;
                return new StatusView(_count, _ewma, min, max, _accepted, _missing, loss, _recentLoss, _output);
            }
        }
    }

    private sealed class Simulator : IDisposable
    {
        private readonly PairingKey _key;
        private readonly IPEndPoint _udpHub;
        private readonly IPEndPoint _tcpHub;
        private readonly bool _useUdp, _useTcp;
        private readonly SimStats _stats;
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private readonly Random _random = new();
        private Socket? _udp, _tcp;
        private volatile bool _stopping;
        private readonly List<Thread> _receivers = new();

        public Simulator(PairingKey key, IPAddress hub, int udpPort, int tcpPort, bool useUdp, bool useTcp, SimStats stats)
        {
            _key = key;
            _udpHub = new IPEndPoint(hub, udpPort);
            _tcpHub = new IPEndPoint(hub, tcpPort);
            _useUdp = useUdp;
            _useTcp = useTcp;
            _stats = stats;
            Span<byte> e = stackalloc byte[4];
            do { RandomNumberGenerator.Fill(e); Epoch = BitConverter.ToUInt32(e); } while (Epoch == 0);
        }

        public uint Epoch { get; }

        private uint NowUs => unchecked((uint)Slipstream.Core.Link.ClockMath.ToMicroseconds(_clock.ElapsedTicks, Stopwatch.Frequency));

        public void Connect()
        {
            if (_useUdp)
            {
                _udp = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { ReceiveTimeout = 250 };
                if (OperatingSystem.IsWindows()) _udp.IOControl(unchecked((int)0x9800000C), new byte[4], null);
                try { _udp.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.TypeOfService, 0xB8); } catch (SocketException) { } // DSCP EF
                _udp.Bind(new IPEndPoint(IPAddress.Any, 0));
                StartReceiver("sim-udp-rx", UdpReceive);
            }
            if (_useTcp)
            {
                _tcp = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
                _tcp.Connect(_tcpHub);
                StartReceiver("sim-tcp-rx", TcpReceive);
            }
        }

        private void StartReceiver(string name, ThreadStart body)
        {
            var t = new Thread(body) { IsBackground = true, Name = name };
            _receivers.Add(t);
            t.Start();
        }

        public void Run(int rate, double loss, double seconds, int pulses)
        {
            var state = new InputPacket { Epoch = Epoch, Seq = 0 };
            if (_useUdp && _useTcp) state.Flags = Wire.FlagMultipath;
            var buf = new byte[Wire.InputLength];
            var frame = new byte[Wire.FrameHeaderLength + Wire.InputLength];
            double periodTicks = Stopwatch.Frequency / (double)rate;
            long start = _clock.ElapsedTicks;
            long endTicks = start + (long)(seconds * Stopwatch.Frequency);
            int pulsesSent = 0;
            byte shiftCounter = 0;
            long nextPrintMs = 1000;
            long n = 0;

            while (true)
            {
                long due = start + (long)(n * periodTicks);
                if (due >= endTicks) break;
                WaitUntil(due);
                n++;
                double t = (_clock.ElapsedTicks - start) / (double)Stopwatch.Frequency;

                // Shift-up number i happens at (i + 0.5) * seconds / pulses.
                while (pulsesSent < pulses && t >= (pulsesSent + 0.5) * seconds / pulses)
                {
                    shiftCounter++;
                    pulsesSent++;
                }
                state.SetPulse(0, shiftCounter);
                state.Seq = unchecked(state.Seq + 1);
                state.TimeUs = NowUs;
                state.Steer = (short)Math.Round(Math.Sin(t * 2 * Math.PI / 3) * 26000);
                state.Throttle = (ushort)Math.Round(Math.Abs(((t % 4) / 2) - 1) * 65535);
                state.Brake = (ushort)(t % 5 > 4.5 ? 50000 : 0);
                StatusView v = _stats.View();
                state.Rtt100us = v.EwmaUs is double e ? (ushort)Math.Clamp(Math.Round(e / 100), 1, 65535) : (ushort)0;
                state.Encode(buf, _key.Auth);
                Interlocked.Increment(ref _stats.Built);

                bool sentAny = false;
                if (_udp is not null)
                {
                    if (_random.NextDouble() >= loss) { SafeSend(() => _udp.SendTo(buf, _udpHub)); sentAny = true; }
                }
                if (_tcp is not null)
                {
                    if (_random.NextDouble() >= loss)
                    {
                        int len = Framing.WriteFrame(frame, buf);
                        SafeSend(() => _tcp.Send(frame, 0, len, SocketFlags.None));
                        sentAny = true;
                    }
                }
                if (!sentAny) Interlocked.Increment(ref _stats.Dropped);

                long ms = _clock.ElapsedMilliseconds;
                if (ms >= nextPrintMs)
                {
                    nextPrintMs += 1000;
                    Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                        "t={0,3:0}s built {1} dropped {2} | STATUS {3} | rtt {4} ms (min {5} max {6}) | hub accepted {7} missing {8} loss {9:0.0}% (recent {10:0.0}%) | output 0x{11:x2}",
                        t, _stats.Built, _stats.Dropped, v.Count, Ms(v.EwmaUs), Ms(v.MinUs), Ms(v.MaxUs), v.Accepted, v.Missing, v.LossPercent, v.RecentLossPercent, v.Output));
                }
            }
            Interlocked.Exchange(ref _stats.PulsesSent, pulsesSent);
            // Give the last STATUS time to arrive before the numbers are read.
            Thread.Sleep(150);
        }

        /// <summary>
        /// Controller mode: PAD packets at a fixed rate. Both sticks circle, L2 ramps up, R2 goes up and down,
        /// and tap i on Cross (canonical button 0) is pressed at (i + 0.5) * seconds / taps for tapMs. The tap
        /// counter moves in the same packet that first carries the held bit (PROTOCOL.md 12.3). The PlayStation
        /// layout also moves a touchpad finger now and then and sends motion.
        /// </summary>
        public void RunPad(int rate, double loss, double seconds, int taps, int tapMs, bool playStation)
        {
            var state = new PadPacket { Epoch = Epoch, Seq = 0 };
            if (playStation) state.Flags |= Wire.FlagStylePs | Wire.FlagMotion;
            if (_useUdp && _useTcp) state.Flags |= Wire.FlagMultipath;
            var buf = new byte[Wire.PadLength];
            var frame = new byte[Wire.FrameHeaderLength + Wire.PadLength];
            double periodTicks = Stopwatch.Frequency / (double)rate;
            long start = _clock.ElapsedTicks;
            long endTicks = start + (long)(seconds * Stopwatch.Frequency);
            int tapsSent = 0;
            double releaseAt = -1;
            byte touchId = 0;
            bool touching = false;
            long nextPrintMs = 1000;
            long n = 0;

            while (true)
            {
                long due = start + (long)(n * periodTicks);
                if (due >= endTicks) break;
                WaitUntil(due);
                n++;
                double t = (_clock.ElapsedTicks - start) / (double)Stopwatch.Frequency;

                if (releaseAt >= 0 && t >= releaseAt)
                {
                    state.Buttons &= ~1u;
                    releaseAt = -1;
                }
                if (releaseAt < 0 && tapsSent < taps && t >= (tapsSent + 0.5) * seconds / taps)
                {
                    state.Buttons |= 1u;
                    state.SetTap(0, state.GetTap(0) + 1);
                    tapsSent++;
                    releaseAt = t + tapMs / 1000.0;
                }

                state.Seq = unchecked(state.Seq + 1);
                state.TimeUs = NowUs;
                state.Lx = Axis(Math.Sin(t * 2 * Math.PI / 2) * 30000);
                state.Ly = Axis(Math.Cos(t * 2 * Math.PI / 2) * 30000);
                state.Rx = Axis(Math.Sin(t * 2 * Math.PI / 3 + 1) * 22000);
                state.Ry = Axis(Math.Cos(t * 2 * Math.PI / 3 + 1) * 22000);
                state.L2 = (ushort)Math.Round((t % 1.5) / 1.5 * 65535);
                state.R2 = (ushort)Math.Round(Math.Abs(((t % 2) / 1) - 1) * 65535);
                if (playStation)
                {
                    bool touch = t % 3 >= 1 && t % 3 < 1.5;
                    if (touch && !touching) touchId = (byte)((touchId + 1) & 0x7F);
                    touching = touch;
                    state.Touch0Id = (byte)(touchId | (touch ? PadPacket.TouchActive : 0));
                    state.Touch0X = (ushort)Math.Round((t % 3 - 1) / 0.5 * 65535 * (touch ? 1 : 0));
                    state.Touch0Y = 32768;
                    state.GyroY = (short)Math.Round(Math.Sin(t * 2 * Math.PI) * 1600); // 100 dps
                    state.AccelY = 4096;                                              // 1 g up
                }
                StatusView v = _stats.View();
                state.Rtt100us = v.EwmaUs is double e ? (ushort)Math.Clamp(Math.Round(e / 100), 1, 65535) : (ushort)0;
                state.Encode(buf, _key.Auth);
                Interlocked.Increment(ref _stats.Built);
                SendOnPaths(buf, frame, loss);

                long ms = _clock.ElapsedMilliseconds;
                if (ms >= nextPrintMs)
                {
                    nextPrintMs += 1000;
                    Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
                        "t={0,3:0}s built {1} dropped {2} taps {3} | STATUS {4} | rtt {5} ms (min {6} max {7}) | hub accepted {8} missing {9} loss {10:0.0}% (recent {11:0.0}%) | output 0x{12:x2}",
                        t, _stats.Built, _stats.Dropped, tapsSent, v.Count, Ms(v.EwmaUs), Ms(v.MinUs), Ms(v.MaxUs), v.Accepted, v.Missing, v.LossPercent, v.RecentLossPercent, v.Output));
                }
            }
            // Never end on a held button: the last packet releases it (the tap still counts once).
            if (releaseAt >= 0)
            {
                state.Buttons &= ~1u;
                state.Seq = unchecked(state.Seq + 1);
                state.TimeUs = NowUs;
                state.Encode(buf, _key.Auth);
                Interlocked.Increment(ref _stats.Built);
                SendOnPaths(buf, frame, loss);
            }
            Interlocked.Exchange(ref _stats.TapsSent, tapsSent);
            Thread.Sleep(150);
        }

        private static short Axis(double v) => (short)Math.Clamp(Math.Round(v), -32767, 32767);

        /// <summary>Sends one packet on every path, each copy dropped with probability <paramref name="loss"/>.</summary>
        private void SendOnPaths(byte[] packet, byte[] frame, double loss)
        {
            bool sentAny = false;
            if (_udp is not null && _random.NextDouble() >= loss)
            {
                SafeSend(() => _udp.SendTo(packet, _udpHub));
                sentAny = true;
            }
            if (_tcp is not null && _random.NextDouble() >= loss)
            {
                int len = Framing.WriteFrame(frame, packet);
                SafeSend(() => _tcp.Send(frame, 0, len, SocketFlags.None));
                sentAny = true;
            }
            if (!sentAny) Interlocked.Increment(ref _stats.Dropped);
        }

        private void WaitUntil(long dueTicks)
        {
            long msTicks = Stopwatch.Frequency / 1000;
            while (true)
            {
                long left = dueTicks - _clock.ElapsedTicks;
                if (left <= 0) return;
                if (left > 2 * msTicks) Thread.Sleep(1);
                else Thread.SpinWait(40);
            }
        }

        private static void SafeSend(Action send)
        {
            try { send(); }
            catch (SocketException) { /* hub not up yet or port closed: keep going like a phone would */ }
        }

        private void UdpReceive()
        {
            var buf = new byte[256];
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            while (!_stopping)
            {
                int n;
                try { n = _udp!.ReceiveFrom(buf, ref from); }
                catch (SocketException) { continue; }
                catch (ObjectDisposedException) { return; }
                OnStatusBytes(buf.AsSpan(0, n));
            }
        }

        private void TcpReceive()
        {
            var buf = new byte[Wire.StatusLength];
            while (!_stopping)
            {
                Framing.ReadResult r;
                try { r = Framing.ReadFrame(_tcp!, buf, Wire.StatusLength); }
                catch (SocketException) { return; }
                catch (ObjectDisposedException) { return; }
                if (r != Framing.ReadResult.Ok) return;
                OnStatusBytes(buf);
            }
        }

        private void OnStatusBytes(ReadOnlySpan<byte> data)
        {
            uint now = NowUs;
            if (StatusPacket.TryDecode(data, _key.Auth, out StatusPacket st) != DecodeResult.Ok)
            {
                Interlocked.Increment(ref _stats.BadStatus);
                return;
            }
            if (st.Epoch != Epoch) return; // RTT is only valid for our own epoch
            _stats.OnStatus(in st, st.RoundTripUs(now), _clock.ElapsedMilliseconds);
        }

        public void Dispose()
        {
            _stopping = true;
            try { _tcp?.Shutdown(SocketShutdown.Both); } catch { }
            _udp?.Dispose();
            _tcp?.Dispose();
            foreach (Thread t in _receivers) t.Join(500);
        }
    }
}
