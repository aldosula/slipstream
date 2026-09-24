using Slipstream.Core.Config;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Core.Transport;

namespace Slipstream.Core.Tests;

public class AdbReverseManagerTests
{
    private sealed class FakeRunner : IProcessRunner
    {
        public readonly List<string> Calls = new();
        public Func<IReadOnlyList<string>, ProcessResult> Respond = _ => new ProcessResult(0, "", "", false);

        public ProcessResult Run(string fileName, IReadOnlyList<string> arguments, int timeoutMs)
        {
            Calls.Add(string.Join(' ', arguments));
            return Respond(arguments);
        }
    }

    private static readonly string AdbExe = OperatingSystem.IsWindows() ? "adb.exe" : "adb";

    private static AdbReverseManager Manager(FakeRunner runner, string? folder = null, string? path = null, string? local = null, params string[] existing)
    {
        var files = new HashSet<string>(existing);
        return new AdbReverseManager(runner, () => folder, 47802, f => files.Contains(f),
            name => name switch { "PATH" => path, "LOCALAPPDATA" => local, _ => null });
    }

    private static ProcessResult Devices(params string[] lines)
        => new(0, "List of devices attached\n" + string.Join("\n", lines) + "\n\n", "", false);

    [Fact]
    public void Missing_adb_is_a_reported_state_and_runs_nothing()
    {
        var runner = new FakeRunner();
        AdbStatus s = Manager(runner).PollOnce();
        Assert.Equal(AdbState.NotFound, s.State);
        Assert.Contains("platform-tools", s.Message);
        Assert.Empty(runner.Calls);
    }

    [Fact]
    public void Adb_is_searched_in_folder_then_path_then_localappdata()
    {
        var runner = new FakeRunner();
        string inFolder = Path.Combine("cfg", AdbExe);
        string inPath = Path.Combine("p2", AdbExe);
        string inLocal = Path.Combine("local", "Android", "Sdk", "platform-tools", AdbExe);
        string pathVar = string.Join(Path.PathSeparator, "p1", "p2");

        Assert.Equal(inFolder, Manager(runner, "cfg", pathVar, "local", inFolder, inPath, inLocal).LocateAdb());
        Assert.Equal(inPath, Manager(runner, "cfg", pathVar, "local", inPath, inLocal).LocateAdb());
        Assert.Equal(inLocal, Manager(runner, "cfg", pathVar, "local", inLocal).LocateAdb());
        Assert.Null(Manager(runner, "cfg", pathVar, "local").LocateAdb());
    }

    [Fact]
    public void Reverse_runs_once_per_new_device_and_again_after_replug()
    {
        var runner = new FakeRunner();
        bool plugged = true;
        runner.Respond = args => args[0] == "devices"
            ? (plugged ? Devices("R58M123ABC\tdevice") : Devices())
            : new ProcessResult(0, "", "", false);
        string adb = Path.Combine("pt", AdbExe);
        AdbReverseManager m = Manager(runner, "pt", null, null, adb);

        AdbStatus s = m.PollOnce();
        Assert.Equal(AdbState.Ready, s.State);
        Assert.True(s.Devices.Single().Reversed);
        Assert.Equal(new[] { "devices", "-s R58M123ABC reverse tcp:47802 tcp:47802" }, runner.Calls);

        m.PollOnce();
        Assert.Equal(1, runner.Calls.Count(c => c.Contains("reverse tcp:")));

        plugged = false;
        Assert.Equal(AdbState.NoDevice, m.PollOnce().State);
        plugged = true;
        Assert.Equal(AdbState.Ready, m.PollOnce().State);
        Assert.Equal(2, runner.Calls.Count(c => c.Contains("reverse tcp:")));
    }

    [Fact]
    public void Lost_reverse_mapping_is_restored_by_the_periodic_check()
    {
        var runner = new FakeRunner();
        runner.Respond = args => args[0] == "devices" ? Devices("SER1\tdevice")
            : args.Contains("--list") ? new ProcessResult(0, "", "", false) // mapping gone
            : new ProcessResult(0, "", "", false);
        string adb = Path.Combine("pt", AdbExe);
        AdbReverseManager m = Manager(runner, "pt", null, null, adb);
        for (int i = 0; i < 5; i++) m.PollOnce();
        Assert.Contains(runner.Calls, c => c.EndsWith("reverse --list", StringComparison.Ordinal));
        Assert.Equal(2, runner.Calls.Count(c => c.Contains("reverse tcp:")));
    }

    [Fact]
    public void Unauthorized_and_failed_reverse_are_reported_precisely()
    {
        var runner = new FakeRunner();
        runner.Respond = args => args[0] == "devices" ? Devices("SER1\tunauthorized") : new ProcessResult(0, "", "", false);
        string adb = Path.Combine("pt", AdbExe);
        AdbStatus s = Manager(runner, "pt", null, null, adb).PollOnce();
        Assert.Equal(AdbState.Unauthorized, s.State);
        Assert.Contains("Allow USB debugging", s.Message);
        Assert.DoesNotContain(runner.Calls, c => c.Contains("reverse"));

        var runner2 = new FakeRunner();
        runner2.Respond = args => args[0] == "devices" ? Devices("SER2\tdevice") : new ProcessResult(1, "", "error: device offline", false);
        AdbStatus s2 = Manager(runner2, "pt", null, null, adb).PollOnce();
        Assert.Equal(AdbState.Error, s2.State);
        Assert.Contains("device offline", s2.Message);
    }

    [Fact]
    public void Parse_devices_handles_daemon_banners_and_long_format()
    {
        var parsed = AdbReverseManager.ParseDevices(
            "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n" +
            "emulator-5554\tdevice\nR58M\tunauthorized\n0123 device usb:1-1 product:x model:Pixel_8 device:shiba transport_id:3\n\n");
        Assert.Equal(new[] { ("emulator-5554", "device"), ("R58M", "unauthorized"), ("0123", "device") }, parsed);
    }
}

public class HubConfigTests
{
    [Fact]
    public void Defaults_are_the_protocol_defaults_and_normalize_fills_code_and_name()
    {
        var c = new HubConfig();
        Assert.True(c.Normalize());
        Assert.NotNull(Pairing.Normalize(c.PairingCode));
        Assert.False(string.IsNullOrEmpty(c.HubName));
        Assert.True(System.Text.Encoding.UTF8.GetByteCount(c.HubName!) <= 32);
        Assert.Equal(200, c.FailsafeMs);
        Assert.Equal(60, c.PulseMs);
        Assert.Equal(40, c.GapMs);
        Assert.Equal(47800, c.Ports.Udp);
        Assert.Equal(47801, c.Ports.Beacon);
        Assert.Equal(47802, c.Ports.Tcp);
        Assert.Equal(OutputKind.VJoy, c.OutputKind);
        Assert.True(c.UsbEnabled);
        Assert.False(c.Normalize()); // stable
    }

    [Fact]
    public void Json_uses_snake_case_and_round_trips()
    {
        var c = new HubConfig { PairingCode = "SLIPSTREAMTEST22", HubName = "RACING-PC", Output = "x360", FailsafeMs = 250, AdbFolder = @"C:\tools" };
        c.Invert.Flags = AxisInvert.Steer | AxisInvert.Clutch;
        string json = c.ToJson();
        Assert.Contains("\"pairing_code\": \"SLIPSTREAMTEST22\"", json);
        Assert.Contains("\"failsafe_ms\": 250", json);
        Assert.Contains("\"usb_enabled\": true", json);
        Assert.Contains("\"adb_folder\"", json);
        HubConfig back = HubConfig.FromJson(json);
        Assert.Equal("RACING-PC", back.HubName);
        Assert.Equal(OutputKind.Xbox360, back.OutputKind);
        Assert.Equal(AxisInvert.Steer | AxisInvert.Clutch, back.Invert.Flags);
        Assert.Equal(@"C:\tools", back.AdbFolder);
    }

    [Fact]
    public void Normalize_repairs_hand_edited_values()
    {
        var c = new HubConfig
        {
            PairingCode = "slip-stre-amte-st22",
            HubName = new string('X', 50),
            Output = "gamepad",
            FailsafeMs = 5,
            PulseMs = 100000,
            AdbFolder = "  ",
        };
        c.Ports.Udp = 70000;
        c.Ports.Beacon = 0;
        Assert.True(c.Normalize());
        Assert.Equal("SLIPSTREAMTEST22", c.PairingCode);
        Assert.Equal(32, c.HubName!.Length);
        Assert.Equal("vjoy", c.Output);
        Assert.Equal(50, c.FailsafeMs);
        Assert.Equal(500, c.PulseMs);
        Assert.Null(c.AdbFolder);
        Assert.Equal(65535, c.Ports.Udp);
        Assert.Equal(1, c.Ports.Beacon);

        var bad = new HubConfig { PairingCode = "not a code" };
        bad.Normalize();
        Assert.NotEqual("not a code", bad.PairingCode);
        Assert.NotNull(Pairing.Normalize(bad.PairingCode));
    }

    [Fact]
    public void Load_creates_saves_and_recovers_from_a_corrupt_file()
    {
        string dir = Path.Combine(Path.GetTempPath(), "slipstream-tests-" + Guid.NewGuid().ToString("N"));
        string path = Path.Combine(dir, "hub.json");
        try
        {
            HubConfig first = HubConfig.LoadOrCreate(path, out string? note);
            Assert.Null(note);
            Assert.True(File.Exists(path));
            HubConfig again = HubConfig.LoadOrCreate(path, out note);
            Assert.Null(note);
            Assert.Equal(first.PairingCode, again.PairingCode);

            File.WriteAllText(path, "{ this is not json");
            HubConfig recovered = HubConfig.LoadOrCreate(path, out note);
            Assert.NotNull(note);
            Assert.True(File.Exists(path + ".bad"));
            Assert.NotNull(Pairing.Normalize(recovered.PairingCode));
        }
        finally
        {
            try { Directory.Delete(dir, recursive: true); } catch (IOException) { }
        }
    }
}
