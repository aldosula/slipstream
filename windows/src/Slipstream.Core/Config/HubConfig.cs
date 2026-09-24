using System.Text.Json;
using System.Text.Json.Serialization;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Config;

/// <summary>Axis inversion switches as stored in hub.json.</summary>
public sealed class InvertConfig
{
    public bool Steer { get; set; }
    public bool Throttle { get; set; }
    public bool Brake { get; set; }
    public bool Clutch { get; set; }
    public bool Handbrake { get; set; }

    [JsonIgnore]
    public AxisInvert Flags
    {
        get => (Steer ? AxisInvert.Steer : 0) | (Throttle ? AxisInvert.Throttle : 0) | (Brake ? AxisInvert.Brake : 0)
               | (Clutch ? AxisInvert.Clutch : 0) | (Handbrake ? AxisInvert.Handbrake : 0);
        set
        {
            Steer = (value & AxisInvert.Steer) != 0;
            Throttle = (value & AxisInvert.Throttle) != 0;
            Brake = (value & AxisInvert.Brake) != 0;
            Clutch = (value & AxisInvert.Clutch) != 0;
            Handbrake = (value & AxisInvert.Handbrake) != 0;
        }
    }
}

public sealed class PortsConfig
{
    public int Udp { get; set; } = Wire.DefaultUdpPort;
    public int Beacon { get; set; } = Wire.DefaultBeaconPort;
    public int Tcp { get; set; } = Wire.DefaultTcpPort;
}

/// <summary>
/// Hub settings, stored as JSON in %APPDATA%\Slipstream\hub.json (on other systems the
/// ApplicationData folder equivalent). Property names are snake_case on disk.
/// </summary>
public sealed class HubConfig
{
    public const string OutputVJoy = "vjoy";
    public const string OutputX360 = "x360";
    public const string OutputNone = "none";

    public string? PairingCode { get; set; }
    public string? HubName { get; set; }
    /// <summary>"vjoy", "x360" or "none".</summary>
    public string Output { get; set; } = OutputVJoy;
    public int FailsafeMs { get; set; } = 200;
    public int PulseMs { get; set; } = 60;
    public int GapMs { get; set; } = 40;
    public InvertConfig Invert { get; set; } = new();
    public bool UsbEnabled { get; set; } = true;
    public string? AdbFolder { get; set; }
    public bool BeaconEnabled { get; set; } = true;
    public PortsConfig Ports { get; set; } = new();

    [JsonIgnore]
    public OutputKind OutputKind
    {
        get => Output switch { OutputX360 => OutputKind.Xbox360, OutputNone => OutputKind.None, _ => OutputKind.VJoy };
        set => Output = value switch { OutputKind.Xbox360 => OutputX360, OutputKind.None => OutputNone, _ => OutputVJoy };
    }

    public static string DefaultPath
        => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData, Environment.SpecialFolderOption.Create),
            "Slipstream", "hub.json");

    /// <summary>Default hub name: the machine name, at most 32 UTF-8 bytes.</summary>
    public static string DefaultHubName()
    {
        string name;
        try { name = Environment.MachineName; }
        catch (InvalidOperationException) { name = "Slipstream Hub"; }
        name = Pairing.TruncateName(name);
        return name.Length == 0 ? "Slipstream Hub" : name;
    }

    /// <summary>
    /// Fixes everything a hand-edited file could break: invalid or missing code (a new one is
    /// generated), name length, out-of-range timings and ports, unknown output kind.
    /// Returns true when something was changed and the file should be saved.
    /// </summary>
    public bool Normalize()
    {
        bool changed = false;
        string? code = Pairing.Normalize(PairingCode);
        if (code is null)
        {
            code = Pairing.GenerateCode();
            changed = true;
        }
        if (code != PairingCode) { PairingCode = code; changed = true; }

        string name = string.IsNullOrWhiteSpace(HubName) ? DefaultHubName() : Pairing.TruncateName(HubName);
        if (name != HubName) { HubName = name; changed = true; }

        string output = (Output ?? "").Trim().ToLowerInvariant();
        if (output is not (OutputVJoy or OutputX360 or OutputNone)) output = OutputVJoy;
        if (output != Output) { Output = output; changed = true; }

        changed |= Clamp(FailsafeMs, 50, 2000, v => FailsafeMs = v);
        changed |= Clamp(PulseMs, 10, 500, v => PulseMs = v);
        changed |= Clamp(GapMs, 10, 500, v => GapMs = v);

        Invert ??= new InvertConfig();
        Ports ??= new PortsConfig();
        // 0 for the UDP or TCP port means "any free port" (tests and side-by-side runs only).
        changed |= Clamp(Ports.Udp, 0, 65535, v => Ports.Udp = v);
        changed |= Clamp(Ports.Beacon, 1, 65535, v => Ports.Beacon = v);
        changed |= Clamp(Ports.Tcp, 0, 65535, v => Ports.Tcp = v);

        if (AdbFolder is not null && string.IsNullOrWhiteSpace(AdbFolder)) { AdbFolder = null; changed = true; }
        return changed;

        static bool Clamp(int value, int min, int max, Action<int> set)
        {
            int c = Math.Clamp(value, min, max);
            if (c == value) return false;
            set(c);
            return true;
        }
    }

    public HubEngineOptions ToEngineOptions() => new()
    {
        FailsafeMs = FailsafeMs,
        PulseMs = PulseMs,
        GapMs = GapMs,
        Invert = Invert.Flags,
    };

    /// <summary>
    /// Loads the file, or creates a default one. A corrupt file is kept as hub.json.bad and replaced.
    /// <paramref name="note"/> says what happened, for the UI; null when the file loaded cleanly.
    /// </summary>
    public static HubConfig LoadOrCreate(string path, out string? note)
    {
        note = null;
        HubConfig? cfg = null;
        bool readFailed = false;
        if (File.Exists(path))
        {
            try
            {
                cfg = JsonSerializer.Deserialize(File.ReadAllText(path), HubConfigJson.Default.HubConfig);
                if (cfg is null) note = "Settings file was empty; defaults restored.";
            }
            catch (JsonException ex)
            {
                try { File.Copy(path, path + ".bad", overwrite: true); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
                note = $"Settings file was unreadable ({ex.Message}); saved as hub.json.bad, defaults restored.";
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // Locked by another program or no permission: start with defaults, and do not overwrite a
                // file that may be perfectly good (a crash here would stop the hub from starting at all).
                readFailed = true;
                note = $"Settings file could not be read ({ex.Message}); running with defaults, the file was left untouched.";
            }
        }
        cfg ??= new HubConfig();
        bool changed = cfg.Normalize();
        if (!readFailed && (changed || !File.Exists(path) || note is not null))
        {
            try { cfg.Save(path); }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                note = (note is null ? "" : note + " ") + $"Settings cannot be saved: {ex.Message}";
            }
        }
        return cfg;
    }

    /// <summary>Atomic save: write a temporary file, then replace.</summary>
    public void Save(string path)
    {
        string? dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        string tmp = path + ".tmp";
        File.WriteAllText(tmp, ToJson());
        File.Move(tmp, path, overwrite: true);
    }

    public string ToJson() => JsonSerializer.Serialize(this, HubConfigJson.Default.HubConfig);

    public static HubConfig FromJson(string json)
        => JsonSerializer.Deserialize(json, HubConfigJson.Default.HubConfig) ?? new HubConfig();

    public HubConfig Clone() => FromJson(ToJson());
}

[JsonSourceGenerationOptions(
    WriteIndented = true,
    PropertyNamingPolicy = JsonKnownNamingPolicy.SnakeCaseLower,
    ReadCommentHandling = JsonCommentHandling.Skip,
    AllowTrailingCommas = true)]
[JsonSerializable(typeof(HubConfig))]
internal sealed partial class HubConfigJson : JsonSerializerContext
{
}
