using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using QRCoder;
using Slipstream.Core;
using Slipstream.Core.Config;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;

namespace Slipstream.Cli;

/// <summary>
/// "hub": runs the engine headless with a console output. Prints the code, an ASCII QR of the
/// pairing URI and a live one-line axis display at 10 Hz.
/// </summary>
internal static class HubCommand
{
    public const string Usage =
        "slipstream hub [--code CODE] [--name NAME] [--port 47800] [--tcp-port 47802] [--beacon-port 47801]\n" +
        "               [--no-beacon] [--no-adb] [--no-tcp] [--adb-folder DIR] [--failsafe-ms 200]\n" +
        "               [--seconds N] [--exit-idle-ms N] [--stats-json PATH] [--qr-light] [--no-qr]\n" +
        "               [--pad-output auto|x360|ds4] [--tap-ms 50] [--tap-gap-ms 40]\n" +
        "  --exit-idle-ms N  exit once a phone was seen and nothing was accepted for N ms (for scripted tests)\n" +
        "  --pad-output      controller mode pad: auto follows the phone's layout (the headless hub only counts frames)";

    private static readonly IReadOnlySet<string> Flags = new HashSet<string> { "no-beacon", "no-adb", "no-tcp", "qr-light", "no-qr" };

    public static int Run(string[] argv)
    {
        var a = new Args(argv, Flags);
        string? codeArg = a.String("code");
        string? name = a.String("name");
        int udpPort = a.Int("port", Wire.DefaultUdpPort, 0, 65535);
        int tcpPort = a.Int("tcp-port", Wire.DefaultTcpPort, 0, 65535);
        int beaconPort = a.Int("beacon-port", Wire.DefaultBeaconPort, 1, 65535);
        bool noBeacon = a.Flag("no-beacon");
        bool noAdb = a.Flag("no-adb");
        bool noTcp = a.Flag("no-tcp");
        string? adbFolder = a.String("adb-folder");
        int failsafe = a.Int("failsafe-ms", 200, 50, 2000);
        int seconds = a.Int("seconds", 0, 0, 86_400);
        int exitIdleMs = a.Int("exit-idle-ms", 0, 0, 600_000);
        string? statsPath = a.String("stats-json");
        bool qrLight = a.Flag("qr-light");
        bool noQr = a.Flag("no-qr");
        string padOutput = (a.String("pad-output") ?? HubConfig.PadOutputAuto).Trim().ToLowerInvariant();
        int tapMs = a.Int("tap-ms", 50, 10, 500);
        int tapGapMs = a.Int("tap-gap-ms", 40, 10, 500);
        a.RejectUnknown();
        if (padOutput is not (HubConfig.PadOutputAuto or HubConfig.PadOutputX360 or HubConfig.PadOutputDs4))
            throw new UsageException("--pad-output must be auto, x360 or ds4.");

        string code;
        if (codeArg is null) code = Pairing.GenerateCode();
        else code = Pairing.Normalize(codeArg) ?? throw new UsageException("--code is not a valid pairing code (16 characters of A-Z and 2-7).");

        var config = new HubConfig
        {
            PairingCode = code,
            HubName = name,
            FailsafeMs = failsafe,
            UsbEnabled = !noAdb,
            AdbFolder = adbFolder,
            BeaconEnabled = !noBeacon,
            Output = HubConfig.OutputNone,
            PadOutput = padOutput,
            TapMs = tapMs,
            TapGapMs = tapGapMs,
        };
        config.Normalize();
        config.Ports.Udp = udpPort;   // after Normalize: 0 means "any free port" for testing
        config.Ports.Tcp = tcpPort;
        config.Ports.Beacon = beaconPort;

        var output = new ConsoleOutput();
        using var runtime = new HubRuntime(config, output, new HubRuntimeOptions
        {
            EnableBeacon = !noBeacon,
            EnableAdb = !noAdb,
            EnableTcp = !noTcp,
            PadOutputFactory = kind => new ConsolePadOutput(kind),
        });
        using var timer = WindowsTimerResolution.Begin();
        runtime.Start();

        int boundUdp = runtime.Udp?.LocalPort ?? udpPort;
        int boundTcp = runtime.Tcp?.LocalPort ?? tcpPort;
        IReadOnlyList<string> hosts = NetworkInfo.GetUsableHosts();
        string uri = Pairing.BuildPairUri(code, config.HubName!, boundUdp, boundTcp, hosts);

        Console.WriteLine("Slipstream Hub (headless)");
        Console.WriteLine($"  pairing code  {Pairing.Display(code)}");
        Console.WriteLine($"  hub name      {config.HubName}");
        Console.WriteLine($"  udp           {(runtime.UdpError ?? $"listening on {boundUdp}")}");
        Console.WriteLine($"  usb (tcp)     {(noTcp ? "off" : runtime.TcpError ?? $"listening on 127.0.0.1:{boundTcp}")}");
        Console.WriteLine($"  beacon        {(noBeacon ? "off" : $"1 Hz to port {beaconPort}")}");
        Console.WriteLine($"  pad output    {padOutput}, replayed taps {tapMs} ms down, {tapGapMs} ms gap");
        Console.WriteLine($"  hosts         {(hosts.Count == 0 ? "none found" : string.Join(", ", hosts))}");
        Console.WriteLine($"  pair uri      {uri}");
        if (!noQr) PrintQr(uri, qrLight);
        if (runtime.UdpError is not null && (noTcp || runtime.TcpError is not null))
        {
            Console.Error.WriteLine("No transport could start. " + runtime.UdpError);
            return 3;
        }

        using var stop = new ManualResetEventSlim(false);
        ConsoleCancelEventHandler onCancel = (_, e) => { e.Cancel = true; stop.Set(); };
        Console.CancelKeyPress += onCancel;
        bool live = !Console.IsOutputRedirected;
        var sw = Stopwatch.StartNew();
        long lastPrintMs = -1000;
        string exitReason = "stopped";
        // A scripted run polls faster, so it exits close to the idle time it asked for.
        int pollMs = exitIdleMs > 0 ? 20 : 100;
        try
        {
            while (!stop.Wait(pollMs))
            {
                if (seconds > 0 && sw.Elapsed.TotalSeconds >= seconds)
                {
                    exitReason = "seconds";
                    break;
                }
                HubSnapshot s = runtime.Engine.GetSnapshot();
                if (exitIdleMs > 0 && s.SinceLastPacketMs is double idle && idle >= exitIdleMs)
                {
                    exitReason = "idle";
                    break;
                }
                string line = LiveLine(s, runtime.AdbStatus);
                if (live)
                {
                    int width = SafeWidth();
                    Console.Write("\r" + (line.Length >= width ? line[..(width - 1)] : line.PadRight(width - 1)));
                }
                else if (sw.ElapsedMilliseconds - lastPrintMs >= 1000)
                {
                    lastPrintMs = sw.ElapsedMilliseconds;
                    Console.WriteLine(line);
                }
            }
        }
        finally
        {
            Console.CancelKeyPress -= onCancel;
        }
        if (live) Console.WriteLine();

        HubSnapshot final = runtime.Engine.GetSnapshot();
        PrintSummary(final);
        if (statsPath is not null)
        {
            WriteStats(statsPath, final, exitReason);
            Console.WriteLine($"stats written to {statsPath}");
        }
        return 0;
    }

    private static void PrintQr(string uri, bool lightBackground)
    {
        using var gen = new QRCodeGenerator();
        using QRCodeData data = gen.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var ascii = new AsciiQRCode(data);
        Encoding previous = Console.OutputEncoding;
        try
        {
            Console.OutputEncoding = Encoding.UTF8;
            // Half-block rendering; the default draws light modules as blocks, right for dark terminals.
            Console.WriteLine(ascii.GetGraphicSmall(drawQuietZones: true, invert: lightBackground));
        }
        finally
        {
            try { Console.OutputEncoding = previous; } catch (IOException) { }
        }
    }

    private static int SafeWidth()
    {
        try { return Math.Clamp(Console.WindowWidth, 40, 240); }
        catch (IOException) { return 120; }
    }

    internal static string LiveLine(HubSnapshot s, AdbStatus adb)
    {
        var inv = CultureInfo.InvariantCulture;
        ControllerFrame f = s.Frame;
        string state = s.State switch
        {
            LinkState.Waiting => "WAIT",
            LinkState.Live => "LIVE",
            LinkState.Paused => "PAUS",
            LinkState.Failsafe => "SAFE",
            _ => "LOST",
        };
        var sb = new StringBuilder(160);
        sb.Append(state).Append(' ');
        if (s.Mode == LinkMode.Controller)
        {
            PadFrame p = s.PadFrame;
            sb.Append(s.Style == PadStyle.Xbox ? "pad XB " : "pad PS ");
            sb.Append(string.Format(inv, "L({0,5:+0.00;-0.00; 0.00},{1,5:+0.00;-0.00; 0.00}) R({2,5:+0.00;-0.00; 0.00},{3,5:+0.00;-0.00; 0.00}) ",
                p.Lx / 32767.0, p.Ly / 32767.0, p.Rx / 32767.0, p.Ry / 32767.0));
            sb.Append(string.Format(inv, "l2 {0,3:0}% r2 {1,3:0}% btn ", p.L2 / 655.35, p.R2 / 655.35));
            for (int b = 0; b < Wire.PadButtons; b++) sb.Append((p.Buttons & (1u << b)) != 0 ? '#' : '.');
            long taps = 0, replayed = 0;
            foreach (long n in s.TapsEmitted) taps += n;
            foreach (long n in s.TapsReplayed) replayed += n;
            sb.Append(string.Format(inv, " presses {0} replayed {1}", taps, replayed));
            AppendLink(sb, s, adb);
            return sb.ToString();
        }
        sb.Append(SteerBar(f.Steer)).Append(' ');
        sb.Append(string.Format(inv, "thr {0,3:0}% brk {1,3:0}% clu {2,3:0}% hb {3,3:0}% ",
            f.Throttle / 655.35, f.Brake / 655.35, f.Clutch / 655.35, f.Handbrake / 655.35));
        sb.Append("sh ");
        for (int ch = 0; ch < 8; ch++) sb.Append((f.PulseMask & (1 << ch)) != 0 ? '*' : '.');
        sb.Append(" btn ");
        for (int i = 0; i < 8; i++) sb.Append((f.Held & (1u << i)) != 0 ? '#' : '.');
        AppendLink(sb, s, adb);
        return sb.ToString();
    }

    private static void AppendLink(StringBuilder sb, HubSnapshot s, AdbStatus adb)
    {
        var inv = CultureInfo.InvariantCulture;
        sb.Append(string.Format(inv, " | {0,4:0} Hz", s.RateHz));
        sb.Append(s.PhoneRttMs is double rtt ? string.Format(inv, " rtt {0:0.0} ms", rtt) : " rtt  -  ");
        sb.Append(string.Format(inv, " loss {0:0.0}%", s.RecentLossPercent));
        sb.Append(string.Format(inv, " udp {0:0}/s usb {1:0}/s", s.Transport(TransportKind.Udp).PacketsPerSecond, s.Transport(TransportKind.Tcp).PacketsPerSecond));
        long bad = s.Transport(TransportKind.Udp).BadTags + s.Transport(TransportKind.Tcp).BadTags;
        if (bad > 0) sb.Append(" badtag ").Append(bad);
        sb.Append(adb.State switch { AdbState.Ready => " adb ok", AdbState.Unauthorized => " adb auth?", _ => "" });
    }

    private static string SteerBar(short steer)
    {
        const int half = 10;
        int pos = (int)Math.Round(Math.Clamp(steer / 32767.0, -1, 1) * half);
        var bar = new char[half * 2 + 1];
        for (int i = 0; i < bar.Length; i++) bar[i] = '-';
        bar[half] = '|';
        int from = Math.Min(half, half + pos), to = Math.Max(half, half + pos);
        for (int i = from; i <= to; i++) if (i != half) bar[i] = '=';
        return "[" + new string(bar) + "]" + string.Format(CultureInfo.InvariantCulture, "{0,6:+0.00;-0.00; 0.00}", steer / 32767.0);
    }

    private static void PrintSummary(HubSnapshot s)
    {
        TransportStats u = s.Transport(TransportKind.Udp), t = s.Transport(TransportKind.Tcp);
        Console.WriteLine(string.Format(CultureInfo.InvariantCulture,
            "summary: epoch {0:x8} accepted {1} missing {2} loss {3:0.00}% | udp packets {4} first {5} dup {6} badtag {7} | usb packets {8} first {9} dup {10} badtag {11}",
            s.Epoch, s.Accepted, s.Missing, s.LossPercent, u.Packets, u.FirstArrivals, u.Duplicates, u.BadTags,
            t.Packets, t.FirstArrivals, t.Duplicates, t.BadTags));
        Console.WriteLine("pulse presses emitted per channel: " + string.Join(' ', s.PulsesEmitted));
        if (s.LastPad.Seq != 0 || s.Mode == LinkMode.Controller)
        {
            PadStyle style = NameStyle(s);
            var parts = new List<string>();
            for (int b = 0; b < Wire.PadButtons; b++)
                if (s.TapsEmitted[b] > 0 || s.TapsReplayed[b] > 0)
                    parts.Add(string.Format(CultureInfo.InvariantCulture, "{0} {1} ({2} replayed)", ButtonName(b, style), s.TapsEmitted[b], s.TapsReplayed[b]));
            Console.WriteLine($"pad presses emitted ({(style == PadStyle.Xbox ? "Xbox" : "PlayStation")} names): " + (parts.Count == 0 ? "none" : string.Join(", ", parts)));
            Console.WriteLine($"pad output: {PadOutputName(s.PadOutputSelection)} selected, {Kind(s.PadOutputKind)} {(s.PadPlugged ? "plugged in" : "not plugged in")}");
        }
    }

    /// <summary>Button names follow the layout of the newest PAD packet.</summary>
    private static PadStyle NameStyle(HubSnapshot s)
        => s.Style != PadStyle.None ? s.Style : s.LastPad.StylePs ? PadStyle.PlayStation : PadStyle.Xbox;

    private static string ButtonName(int b, PadStyle style)
    {
        string name = PadButtonNames.Name(b, style);
        return name.Length > 0 ? name : "button_" + b.ToString(CultureInfo.InvariantCulture);
    }

    private static string PadOutputName(PadOutputSelection s) => s switch
    {
        PadOutputSelection.Xbox360 => HubConfig.PadOutputX360,
        PadOutputSelection.DualShock4 => HubConfig.PadOutputDs4,
        _ => HubConfig.PadOutputAuto,
    };

    private static string Kind(OutputKind k) => k switch
    {
        OutputKind.DualShock4 => "ds4",
        OutputKind.Xbox360 => "x360",
        OutputKind.VJoy => "vjoy",
        _ => "none",
    };

    internal static void WriteStats(string path, HubSnapshot s, string exitReason = "stopped")
    {
        string? dir = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        using FileStream fs = File.Create(path);
        using var w = new Utf8JsonWriter(fs, new JsonWriterOptions { Indented = true });
        w.WriteStartObject();
        w.WriteString("exit_reason", exitReason);
        w.WriteString("state", s.State.ToString().ToLowerInvariant());
        w.WriteNumber("epoch", s.Epoch);
        w.WriteNumber("last_seq", s.LastSeq);
        w.WriteNumber("accepted", s.Accepted);
        w.WriteNumber("missing", s.Missing);
        w.WriteNumber("total_accepted", s.TotalAccepted);
        w.WriteNumber("total_missing", s.TotalMissing);
        w.WriteNumber("epoch_changes", s.EpochChanges);
        w.WriteNumber("loss_percent", Math.Round(s.LossPercent, 3));
        w.WriteNumber("status_sent", s.StatusSent);

        w.WriteStartObject("duplicates");
        foreach (TransportStats t in s.Transports) w.WriteNumber(Name(t.Kind), t.Duplicates);
        w.WriteEndObject();

        w.WriteStartObject("transports");
        foreach (TransportStats t in s.Transports)
        {
            w.WriteStartObject(Name(t.Kind));
            w.WriteNumber("packets", t.Packets);
            w.WriteNumber("first_arrivals", t.FirstArrivals);
            w.WriteNumber("duplicates", t.Duplicates);
            w.WriteNumber("bad_tags", t.BadTags);
            w.WriteNumber("malformed", t.Malformed);
            w.WriteNumber("foreign_epoch", t.ForeignEpoch);
            w.WriteEndObject();
        }
        w.WriteEndObject();

        w.WriteStartArray("pulse_presses");
        foreach (long n in s.PulsesEmitted) w.WriteNumberValue(n);
        w.WriteEndArray();
        w.WriteStartArray("pulse_pending");
        foreach (int n in s.PulsesPending) w.WriteNumberValue(n);
        w.WriteEndArray();

        // The newest INPUT as the hub decoded it from the wire.
        InputPacket p = s.LastInput;
        w.WriteStartObject("last_input");
        w.WriteNumber("epoch", p.Epoch);
        w.WriteNumber("seq", p.Seq);
        w.WriteNumber("t_us", p.TimeUs);
        w.WriteNumber("steer", p.Steer);
        w.WriteNumber("throttle", p.Throttle);
        w.WriteNumber("brake", p.Brake);
        w.WriteNumber("clutch", p.Clutch);
        w.WriteNumber("handbrake", p.Handbrake);
        w.WriteNumber("aux", p.Aux);
        w.WriteNumber("buttons", p.Buttons);
        w.WriteStartArray("pulses");
        for (int ch = 0; ch < Wire.PulseChannels; ch++) w.WriteNumberValue(p.GetPulse(ch));
        w.WriteEndArray();
        w.WriteNumber("flags", p.Flags);
        w.WriteNumber("rtt_100us", p.Rtt100us);
        w.WriteEndObject();

        // What the device shows now (after any failsafe or PAUSED), and what it got when that INPUT was applied.
        WriteFrame(w, "last_frame", s.Output);
        WriteFrame(w, "applied_frame", s.AcceptedOutput);

        // Controller mode (PROTOCOL.md section 12).
        w.WriteString("mode", s.Mode switch { LinkMode.Wheel => "wheel", LinkMode.Controller => "controller", _ => "none" });
        w.WriteString("style", s.Style switch { PadStyle.PlayStation => "ps", PadStyle.Xbox => "xbox", _ => "none" });
        w.WriteStartObject("pad_output");
        w.WriteString("selected", PadOutputName(s.PadOutputSelection));
        w.WriteString("wanted", Kind(s.PadOutputWanted));
        w.WriteString("kind", Kind(s.PadOutputKind));
        w.WriteBoolean("plugged", s.PadPlugged);
        w.WriteString("name", s.PadOutputName);
        w.WriteString("state", s.PadOutputState.ToString().ToLowerInvariant());
        w.WriteEndObject();

        // Presses the pad showed per canonical button (bit order of PROTOCOL.md 12.2), passed through and
        // replayed together, then the replayed ones alone and what is still queued.
        w.WriteStartArray("taps_emitted");
        foreach (long n in s.TapsEmitted) w.WriteNumberValue(n);
        w.WriteEndArray();
        PadStyle style = NameStyle(s);
        w.WriteStartObject("taps_emitted_by_name");
        for (int b = 0; b < Wire.PadButtons; b++) w.WriteNumber(ButtonName(b, style), s.TapsEmitted[b]);
        w.WriteEndObject();
        w.WriteStartArray("taps_replayed");
        foreach (long n in s.TapsReplayed) w.WriteNumberValue(n);
        w.WriteEndArray();
        w.WriteStartArray("taps_pending");
        foreach (int n in s.TapsPending) w.WriteNumberValue(n);
        w.WriteEndArray();

        PadPacket q = s.LastPad;
        w.WriteStartObject("last_pad");
        w.WriteNumber("epoch", q.Epoch);
        w.WriteNumber("seq", q.Seq);
        w.WriteNumber("t_us", q.TimeUs);
        w.WriteNumber("lx", q.Lx);
        w.WriteNumber("ly", q.Ly);
        w.WriteNumber("rx", q.Rx);
        w.WriteNumber("ry", q.Ry);
        w.WriteNumber("l2", q.L2);
        w.WriteNumber("r2", q.R2);
        w.WriteNumber("buttons", q.Buttons);
        w.WriteStartArray("taps");
        for (int b = 0; b < Wire.PadButtons; b++) w.WriteNumberValue(q.GetTap(b));
        w.WriteEndArray();
        w.WriteNumber("flags", q.Flags);
        w.WriteNumber("rtt_100us", q.Rtt100us);
        w.WriteStartArray("touch");
        WriteTouch(w, q.Touch0X, q.Touch0Y, q.Touch0Id);
        WriteTouch(w, q.Touch1X, q.Touch1Y, q.Touch1Id);
        w.WriteEndArray();
        WriteVector(w, "gyro", q.GyroX, q.GyroY, q.GyroZ);
        WriteVector(w, "accel", q.AccelX, q.AccelY, q.AccelZ);
        w.WriteEndObject();

        WritePadFrame(w, "last_pad_frame", s.PadOutput);
        WritePadFrame(w, "applied_pad_frame", s.AcceptedPadOutput);

        w.WriteEndObject();

        static string Name(TransportKind k) => k == TransportKind.Udp ? "udp" : "tcp";
    }

    private static void WriteTouch(Utf8JsonWriter w, ushort x, ushort y, byte id)
    {
        w.WriteStartObject();
        w.WriteNumber("x", x);
        w.WriteNumber("y", y);
        w.WriteNumber("id", id & 0x7F);
        w.WriteBoolean("active", (id & 0x80) != 0);
        w.WriteEndObject();
    }

    private static void WriteVector(Utf8JsonWriter w, string name, short x, short y, short z)
    {
        w.WriteStartArray(name);
        w.WriteNumberValue(x);
        w.WriteNumberValue(y);
        w.WriteNumberValue(z);
        w.WriteEndArray();
    }

    private static void WritePadFrame(Utf8JsonWriter w, string name, in PadOutputFrame o)
    {
        PadFrame f = o.Source;
        w.WriteStartObject(name);
        w.WriteNumber("lx", f.Lx);
        w.WriteNumber("ly", f.Ly);
        w.WriteNumber("rx", f.Rx);
        w.WriteNumber("ry", f.Ry);
        w.WriteNumber("l2", f.L2);
        w.WriteNumber("r2", f.R2);
        w.WriteNumber("buttons", f.Buttons);
        w.WriteStartArray("touch");
        WriteTouch(w, f.Touch0X, f.Touch0Y, f.Touch0Id);
        WriteTouch(w, f.Touch1X, f.Touch1Y, f.Touch1Id);
        w.WriteEndArray();
        w.WriteBoolean("motion", f.Motion);
        WriteVector(w, "gyro", f.GyroX, f.GyroY, f.GyroZ);
        WriteVector(w, "accel", f.AccelX, f.AccelY, f.AccelZ);
        w.WriteStartObject("x360");
        w.WriteNumber("left_thumb_x", o.X360LeftThumbX);
        w.WriteNumber("left_thumb_y", o.X360LeftThumbY);
        w.WriteNumber("right_thumb_x", o.X360RightThumbX);
        w.WriteNumber("right_thumb_y", o.X360RightThumbY);
        w.WriteNumber("left_trigger", o.X360LeftTrigger);
        w.WriteNumber("right_trigger", o.X360RightTrigger);
        w.WriteNumber("buttons", (ushort)o.X360Buttons);
        w.WriteEndObject();
        w.WriteStartObject("ds4");
        w.WriteNumber("left_x", o.Ds4LeftX);
        w.WriteNumber("left_y", o.Ds4LeftY);
        w.WriteNumber("right_x", o.Ds4RightX);
        w.WriteNumber("right_y", o.Ds4RightY);
        w.WriteNumber("l2", o.Ds4LeftTrigger);
        w.WriteNumber("r2", o.Ds4RightTrigger);
        w.WriteNumber("buttons", o.Ds4Buttons);
        w.WriteNumber("hat", o.Ds4Buttons & 0x0F);
        w.WriteNumber("special", o.Ds4Special);
        w.WriteStartArray("touch");
        foreach ((ushort x, ushort y, byte id) in new[] { (o.Ds4Touch0X, o.Ds4Touch0Y, o.Ds4Touch0Id), (o.Ds4Touch1X, o.Ds4Touch1Y, o.Ds4Touch1Id) })
        {
            w.WriteStartObject();
            w.WriteNumber("x", x);
            w.WriteNumber("y", y);
            w.WriteNumber("id_byte", id);
            w.WriteEndObject();
        }
        w.WriteEndArray();
        WriteVector(w, "gyro", o.Ds4GyroX, o.Ds4GyroY, o.Ds4GyroZ);
        WriteVector(w, "accel", o.Ds4AccelX, o.Ds4AccelY, o.Ds4AccelZ);
        w.WriteNumber("timestamp", o.Ds4Timestamp);
        w.WriteEndObject();
        w.WriteEndObject();
    }

    private static void WriteFrame(Utf8JsonWriter w, string name, in OutputFrame o)
    {
        ControllerFrame f = o.Source;
        w.WriteStartObject(name);
        w.WriteNumber("steer", f.Steer);
        w.WriteNumber("throttle", f.Throttle);
        w.WriteNumber("brake", f.Brake);
        w.WriteNumber("clutch", f.Clutch);
        w.WriteNumber("handbrake", f.Handbrake);
        w.WriteNumber("held", f.Held);
        w.WriteNumber("pulse_mask", f.PulseMask);
        w.WriteStartObject("vjoy");
        w.WriteNumber("x", o.VJoyX);
        w.WriteNumber("y", o.VJoyY);
        w.WriteNumber("z", o.VJoyZ);
        w.WriteNumber("rx", o.VJoyRx);
        w.WriteNumber("ry", o.VJoyRy);
        w.WriteNumber("buttons", o.VJoyButtons);
        w.WriteEndObject();
        w.WriteStartObject("x360");
        w.WriteNumber("left_thumb_x", o.X360LeftThumbX);
        w.WriteNumber("right_thumb_y", o.X360RightThumbY);
        w.WriteNumber("left_trigger", o.X360LeftTrigger);
        w.WriteNumber("right_trigger", o.X360RightTrigger);
        w.WriteNumber("buttons", (ushort)o.X360Buttons);
        w.WriteEndObject();
        w.WriteEndObject();
    }
}

/// <summary>timeBeginPeriod(1) on Windows so the 1 ms housekeeping tick is really 1 ms. No-op elsewhere.</summary>
internal sealed class WindowsTimerResolution : IDisposable
{
    private readonly bool _active;

    private WindowsTimerResolution(bool active) => _active = active;

    public static WindowsTimerResolution Begin()
    {
        if (!OperatingSystem.IsWindows()) return new WindowsTimerResolution(false);
        try { return new WindowsTimerResolution(timeBeginPeriod(1) == 0); }
        catch (DllNotFoundException) { return new WindowsTimerResolution(false); }
    }

    public void Dispose()
    {
        if (_active) timeEndPeriod(1);
    }

    [DllImport("winmm.dll")]
    private static extern uint timeBeginPeriod(uint period);

    [DllImport("winmm.dll")]
    private static extern uint timeEndPeriod(uint period);
}
