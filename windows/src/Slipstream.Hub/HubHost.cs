using System.IO;
using Slipstream.Core;
using Slipstream.Core.Config;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;
using Slipstream.Hub.Output;

namespace Slipstream.Hub;

/// <summary>
/// Everything the window and the tray act on: settings file, runtime (engine and transports) and the
/// current output device. All methods are called on the UI thread.
/// </summary>
public sealed class HubHost : IDisposable
{
    public HubHost(string configPath)
    {
        ConfigPath = configPath;
        Config = HubConfig.LoadOrCreate(configPath, out string? note);
        LoadNote = note;
        IOutputDevice output = OutputFactory.Create(Config.OutputKind);
        Runtime = new HubRuntime(Config, output, new HubRuntimeOptions { HotThreadInit = Native.Mmcss.JoinGamesTask });
    }

    public string ConfigPath { get; }
    public HubConfig Config { get; }
    public string? LoadNote { get; }
    public HubRuntime Runtime { get; }
    public HubEngine Engine => Runtime.Engine;
    public string? LastSaveError { get; private set; }

    /// <summary>Raised after the pairing code, the hub name or the output changed.</summary>
    public event EventHandler? Changed;

    public void Start() => Runtime.Start();

    public void SelectOutput(OutputKind kind) => SelectOutput(kind, force: false);

    private void SelectOutput(OutputKind kind, bool force)
    {
        if (!force && Config.OutputKind == kind && Engine.Output.Kind == kind) return;
        IOutputDevice next = OutputFactory.Create(kind);
        IOutputDevice old = Engine.SetOutput(next);
        try { old.Dispose(); } catch { /* a failing old driver must not block the switch */ }
        Config.OutputKind = kind;
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void RetryOutput()
    {
        if (Engine.Output is IRetryableOutput r) r.Retry();
        // A device that failed in its constructor has the right kind already: build it again.
        else SelectOutput(Config.OutputKind, force: true);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public PairingKey NewCode()
    {
        PairingKey key = Runtime.RegeneratePairing();
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
        return key;
    }

    /// <summary>Edits settings, applies them to the running engine and saves.</summary>
    public void UpdateSettings(Action<HubConfig> edit)
    {
        string? name = Config.HubName;
        edit(Config);
        Config.Normalize();
        Runtime.ApplySettings();
        Runtime.SetUsbEnabled(Config.UsbEnabled);
        Save();
        if (name != Config.HubName) Changed?.Invoke(this, EventArgs.Empty);
    }

    public bool Save()
    {
        try
        {
            Config.Save(ConfigPath);
            LastSaveError = null;
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            LastSaveError = $"Settings could not be saved to {ConfigPath}: {ex.Message}";
            return false;
        }
    }

    public string PairUri(IReadOnlyList<string> hosts) => Runtime.PairUri(hosts);

    public string DisplayCode => Pairing.Display(Engine.Pairing.Code);

    public void Dispose()
    {
        IOutputDevice output = Engine.Output;
        Runtime.Dispose();
        try { output.Dispose(); } catch { }
    }
}
