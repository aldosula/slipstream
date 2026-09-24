using System.Diagnostics;
using System.IO;
using System.Runtime;
using System.Windows;
using System.Windows.Threading;
using Slipstream.Core.Config;
using Slipstream.Hub.Native;
using Slipstream.Hub.Ui;

namespace Slipstream.Hub;

/// <summary>
/// Slipstream Hub. Single instance, AboveNormal process priority, 1 ms timer resolution while running,
/// closes to the tray. Start with --tray to start hidden (for sign-in autostart).
/// </summary>
public partial class App : Application
{
    private const string MutexName = @"Local\SlipstreamHub.SingleInstance";
    private const string ShowEventName = @"Local\SlipstreamHub.Show";

    private Mutex? _mutex;
    private EventWaitHandle? _showEvent;
    private RegisteredWaitHandle? _showWait;
    private HubHost? _host;
    private MainWindow? _window;
    private TrayIcon? _tray;
    private bool _timerPeriodSet;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        bool first;
        try
        {
            _mutex = new Mutex(initiallyOwned: true, MutexName, out first);
        }
        catch (UnauthorizedAccessException)
        {
            // The mutex exists but belongs to a hub started as administrator: that one is running.
            first = false;
        }
        if (!first)
        {
            // Another hub is running: bring it to the front and leave.
            try { EventWaitHandle.OpenExisting(ShowEventName).Set(); }
            catch (Exception ex) when (ex is WaitHandleCannotBeOpenedException or UnauthorizedAccessException) { }
            _mutex?.Dispose();
            _mutex = null;
            Shutdown(0);
            return;
        }
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        _showWait = ThreadPool.RegisterWaitForSingleObject(_showEvent,
            (_, _) => Dispatcher.BeginInvoke(ShowMainWindow), null, Timeout.Infinite, executeOnlyOnce: false);

        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += (_, args) => LogCrash(args.ExceptionObject as Exception);

        try { Process.GetCurrentProcess().PriorityClass = ProcessPriorityClass.AboveNormal; }
        catch (Exception) { /* not fatal: the hot threads still run at Highest */ }
        // Hidden in the tray the hub is a background process: keep full CPU speed and keep the 1 ms timer.
        PowerThrottling.OptOut();
        _timerPeriodSet = WinMm.timeBeginPeriod(1) == 0;
        // The receive path does not allocate; this keeps the UI's small garbage from ever causing a
        // blocking full collection while driving.
        GCSettings.LatencyMode = GCLatencyMode.SustainedLowLatency;

        _host = new HubHost(HubConfig.DefaultPath);
        _host.Start();

        _window = new MainWindow(_host);
        _tray = new TrayIcon(_host, ShowMainWindow, Quit);
        _window.StatusLine += text => _tray?.SetStatus(text);
        _window.HiddenToTray += (_, _) => _tray?.ShowBalloon("Slipstream Hub is still running",
            "The wheel keeps working. Right-click the tray icon to quit.");

        bool startHidden = e.Args.Any(a => string.Equals(a, "--tray", StringComparison.OrdinalIgnoreCase));
        if (!startHidden) _window.Show();
    }

    public void ShowMainWindow()
    {
        if (_window is null) return;
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
        _window.Topmost = true;  // bring to the front even when another app has focus
        _window.Topmost = false;
        _window.Focus();
    }

    private void Quit()
    {
        _window?.AllowClose();
        _window?.Close();
        Shutdown(0);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _showWait?.Unregister(null);
        _tray?.Dispose();
        _host?.Dispose();
        if (_timerPeriodSet) WinMm.timeEndPeriod(1);
        _showEvent?.Dispose();
        if (_mutex is not null)
        {
            try { _mutex.ReleaseMutex(); } catch (ApplicationException) { }
            _mutex.Dispose();
        }
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        LogCrash(e.Exception);
        MessageBox.Show($"Something went wrong in the Slipstream Hub window:\n\n{e.Exception.Message}\n\nThe link keeps running. Details are in {CrashLogPath}.",
            "Slipstream Hub", MessageBoxButton.OK, MessageBoxImage.Warning);
        e.Handled = true;
    }

    private static string CrashLogPath => Path.Combine(Path.GetDirectoryName(HubConfig.DefaultPath)!, "hub-errors.log");

    private static void LogCrash(Exception? ex)
    {
        if (ex is null) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CrashLogPath)!);
            File.AppendAllText(CrashLogPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {ex}{Environment.NewLine}{Environment.NewLine}");
        }
        catch (Exception) { /* nowhere left to report */ }
    }
}
