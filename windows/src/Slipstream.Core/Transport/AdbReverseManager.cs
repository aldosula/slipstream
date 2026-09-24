using System.Diagnostics;
using System.Text;

namespace Slipstream.Core.Transport;

/// <summary>Result of one external process run.</summary>
public sealed record ProcessResult(int ExitCode, string StdOut, string StdErr, bool TimedOut)
{
    public bool Ok => !TimedOut && ExitCode == 0;
}

/// <summary>Runs external programs. Behind an interface so the adb logic is testable.</summary>
public interface IProcessRunner
{
    ProcessResult Run(string fileName, IReadOnlyList<string> arguments, int timeoutMs);
}

/// <summary>Real process runner: no window, output captured asynchronously, hard timeout.</summary>
public sealed class SystemProcessRunner : IProcessRunner
{
    public ProcessResult Run(string fileName, IReadOnlyList<string> arguments, int timeoutMs)
    {
        var psi = new ProcessStartInfo(fileName)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (string a in arguments) psi.ArgumentList.Add(a);

        var stdout = new StringBuilder();
        var stderr = new StringBuilder();
        using var p = new Process { StartInfo = psi };
        p.OutputDataReceived += (_, e) => { if (e.Data is not null) lock (stdout) stdout.AppendLine(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data is not null) lock (stderr) stderr.AppendLine(e.Data); };
        p.Start();
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        // Timed wait only: when adb starts its server daemon, the daemon inherits the pipes, and an
        // untimed wait for end-of-stream would hang until the daemon exits.
        if (!p.WaitForExit(timeoutMs))
        {
            try { p.Kill(entireProcessTree: false); } catch { }
            return new ProcessResult(-1, Snapshot(stdout), Snapshot(stderr), TimedOut: true);
        }
        Thread.Sleep(20); // let the async readers drain the last lines
        return new ProcessResult(p.ExitCode, Snapshot(stdout), Snapshot(stderr), TimedOut: false);

        static string Snapshot(StringBuilder sb) { lock (sb) return sb.ToString(); }
    }
}

public enum AdbState
{
    /// <summary>USB mode switched off in settings.</summary>
    Disabled,
    /// <summary>Not polled yet.</summary>
    Starting,
    /// <summary>adb was not found. A reported state, not an error: Wi-Fi keeps working.</summary>
    NotFound,
    /// <summary>adb runs, no phone attached.</summary>
    NoDevice,
    /// <summary>A phone is attached but has not accepted this PC's USB debugging key.</summary>
    Unauthorized,
    /// <summary>At least one phone has the reverse tunnel.</summary>
    Ready,
    /// <summary>adb failed (timeout, reverse refused).</summary>
    Error,
}

public sealed record AdbDevice(string Serial, string State, bool Reversed);

public sealed record AdbStatus(AdbState State, string Message, string? AdbPath, IReadOnlyList<AdbDevice> Devices)
{
    public static AdbStatus Initial { get; } = new(AdbState.Starting, "Looking for adb.", null, Array.Empty<AdbDevice>());
}

/// <summary>
/// Every 2 s: <c>adb devices</c>, then <c>adb -s SERIAL reverse tcp:47802 tcp:47802</c> for each
/// newly attached phone. Reverse mappings are re-checked every 10 s because they vanish when the
/// phone's adbd restarts. adb is searched in the configured folder, then PATH, then
/// %LOCALAPPDATA%\Android\Sdk\platform-tools.
/// </summary>
public sealed class AdbReverseManager : IDisposable
{
    private const int PollMs = 2000;
    private const int VerifyEveryPolls = 5;
    private const int CommandTimeoutMs = 8000;

    private readonly IProcessRunner _runner;
    private readonly Func<string?> _configuredFolder;
    private readonly int _tcpPort;
    private readonly Func<string, bool> _fileExists;
    private readonly Func<string, string?> _getEnv;
    private readonly HashSet<string> _reversed = new(StringComparer.Ordinal);
    private readonly ManualResetEventSlim _stop = new(false);
    private Thread? _thread;
    private int _pollCount;
    private volatile AdbStatus _status = AdbStatus.Initial;

    public AdbReverseManager(IProcessRunner runner, Func<string?> configuredFolder, int tcpPort = Protocol.Wire.DefaultTcpPort,
        Func<string, bool>? fileExists = null, Func<string, string?>? getEnvironmentVariable = null)
    {
        _runner = runner ?? throw new ArgumentNullException(nameof(runner));
        _configuredFolder = configuredFolder ?? (() => null);
        _tcpPort = tcpPort;
        _fileExists = fileExists ?? File.Exists;
        _getEnv = getEnvironmentVariable ?? Environment.GetEnvironmentVariable;
    }

    public AdbStatus Status => _status;

    public void Start()
    {
        if (_thread is not null) return;
        _thread = new Thread(Loop) { IsBackground = true, Name = "slipstream-adb", Priority = ThreadPriority.BelowNormal };
        _thread.Start();
    }

    private void Loop()
    {
        while (true)
        {
            try { PollOnce(); }
            catch (Exception ex) { _status = _status with { State = AdbState.Error, Message = "adb: " + ex.Message }; }
            try
            {
                if (_stop.Wait(PollMs)) return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
        }
    }

    /// <summary>One poll: locate adb, list devices, reverse new ones. Public for tests.</summary>
    public AdbStatus PollOnce()
    {
        string? adb = LocateAdb();
        if (adb is null)
        {
            _reversed.Clear();
            return _status = new AdbStatus(AdbState.NotFound,
                "adb not found. USB mode needs Android platform-tools: install them, or set the folder that contains adb in Settings. Wi-Fi works without it.",
                null, Array.Empty<AdbDevice>());
        }

        ProcessResult list = _runner.Run(adb, new[] { "devices" }, CommandTimeoutMs);
        if (!list.Ok)
        {
            string why = list.TimedOut ? "timed out" : $"exit code {list.ExitCode}: {FirstLine(list.StdErr)}";
            return _status = new AdbStatus(AdbState.Error, $"'adb devices' failed ({why}).", adb, Array.Empty<AdbDevice>());
        }

        List<(string Serial, string State)> attached = ParseDevices(list.StdOut);
        _reversed.RemoveWhere(s => !attached.Any(d => d.Serial == s && d.State == "device"));

        bool verify = ++_pollCount % VerifyEveryPolls == 0;
        string mapping = $"tcp:{_tcpPort}";
        var devices = new List<AdbDevice>();
        string? lastError = null;
        foreach ((string serial, string state) in attached)
        {
            if (state != "device")
            {
                devices.Add(new AdbDevice(serial, state, false));
                continue;
            }
            bool have = _reversed.Contains(serial);
            if (have && verify)
            {
                ProcessResult check = _runner.Run(adb, new[] { "-s", serial, "reverse", "--list" }, CommandTimeoutMs);
                if (check.Ok && !check.StdOut.Contains(mapping + " " + mapping, StringComparison.Ordinal))
                {
                    _reversed.Remove(serial);
                    have = false;
                }
            }
            if (!have)
            {
                ProcessResult rev = _runner.Run(adb, new[] { "-s", serial, "reverse", mapping, mapping }, CommandTimeoutMs);
                if (rev.Ok)
                {
                    _reversed.Add(serial);
                    have = true;
                }
                else
                {
                    lastError = $"'adb reverse' failed for {serial}: {(rev.TimedOut ? "timed out" : FirstLine(rev.StdErr + rev.StdOut))}";
                }
            }
            devices.Add(new AdbDevice(serial, state, have));
        }

        if (devices.Any(d => d.Reversed))
        {
            string names = string.Join(", ", devices.Where(d => d.Reversed).Select(d => d.Serial));
            return _status = new AdbStatus(AdbState.Ready, $"USB link ready ({names}).", adb, devices);
        }
        if (lastError is not null) return _status = new AdbStatus(AdbState.Error, lastError, adb, devices);
        if (devices.Any(d => d.State == "unauthorized"))
            return _status = new AdbStatus(AdbState.Unauthorized,
                "Phone attached but not authorized. Unlock it and accept the 'Allow USB debugging' prompt.", adb, devices);
        if (devices.Count > 0)
            return _status = new AdbStatus(AdbState.NoDevice,
                $"Phone attached but {devices[0].State}. Reconnect the cable or toggle USB debugging.", adb, devices);
        return _status = new AdbStatus(AdbState.NoDevice,
            "No phone on USB. Plug it in with USB debugging enabled (Developer options).", adb, devices);
    }

    /// <summary>Parses 'adb devices' output: lines of "SERIAL\tSTATE" after the header.</summary>
    public static List<(string Serial, string State)> ParseDevices(string output)
    {
        var result = new List<(string, string)>();
        foreach (string raw in output.Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices", StringComparison.Ordinal) || line.StartsWith('*')) continue;
            string[] parts = line.Split(new[] { '\t', ' ' }, 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length != 2) continue;
            result.Add((parts[0], parts[1].Trim().Split(' ')[0]));
        }
        return result;
    }

    /// <summary>Configured folder, then PATH, then %LOCALAPPDATA%\Android\Sdk\platform-tools.</summary>
    public string? LocateAdb()
    {
        string exe = OperatingSystem.IsWindows() ? "adb.exe" : "adb";

        string? folder = _configuredFolder();
        if (!string.IsNullOrWhiteSpace(folder))
        {
            string candidate = Path.Combine(folder.Trim().Trim('"'), exe);
            if (_fileExists(candidate)) return candidate;
        }

        string? path = _getEnv("PATH");
        if (!string.IsNullOrEmpty(path))
        {
            foreach (string dir in path.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                string candidate;
                try { candidate = Path.Combine(dir.Trim().Trim('"'), exe); }
                catch (ArgumentException) { continue; }
                if (_fileExists(candidate)) return candidate;
            }
        }

        string? local = _getEnv("LOCALAPPDATA");
        if (!string.IsNullOrEmpty(local))
        {
            string candidate = Path.Combine(local, "Android", "Sdk", "platform-tools", exe);
            if (_fileExists(candidate)) return candidate;
        }
        return null;
    }

    private static string FirstLine(string s)
    {
        s = s.Trim();
        int nl = s.IndexOf('\n');
        return nl < 0 ? s : s[..nl].Trim();
    }

    public void Dispose()
    {
        _stop.Set();
        // Background thread: never hold up the UI or shutdown for a slow adb (one call may take up to
        // 8 s, for example while adb starts its server). If the poll is still running, the event is left
        // to the finalizer: disposing it under that thread would make its next wait throw and end the process.
        Thread? t = _thread;
        if (t is null || t.Join(1000)) _stop.Dispose();
    }
}
