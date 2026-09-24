using System.ComponentModel;
using System.Globalization;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Transport;
using Slipstream.Hub.Ui;

namespace Slipstream.Hub;

public partial class MainWindow : Window
{
    private static readonly CultureInfo Inv = CultureInfo.InvariantCulture;
    private static readonly string[] PulseLabels = { "Up", "Down", "3", "4", "5", "6", "7", "8" };

    private readonly HubHost _host;
    private readonly DispatcherTimer _timer;
    private readonly Lamp[] _pulseLamps = new Lamp[8];
    private readonly Lamp[] _buttonLamps = new Lamp[24];
    private string _qrPayload = "";
    private long _nextHostCheckMs;
    private bool _loading;
    private bool _allowClose;
    private bool _trayHintShown;

    public MainWindow(HubHost host)
    {
        _host = host;
        InitializeComponent();

        LogoImage.Source = LoadLogo();
        for (int i = 0; i < 8; i++)
        {
            _pulseLamps[i] = new Lamp { Label = PulseLabels[i], Width = 46, Height = 28, Margin = new Thickness(0, 0, 6, 6), ToolTip = $"Pulse channel {i} (vJoy button {i + 1})" };
            PulseLamps.Children.Add(_pulseLamps[i]);
        }
        for (int i = 0; i < 24; i++)
        {
            _buttonLamps[i] = new Lamp { Label = (i + 1).ToString(Inv), Width = 34, Height = 28, Margin = new Thickness(0, 0, 6, 6), ToolTip = $"Held button {i + 1} (vJoy button {i + 9})" };
            ButtonLamps.Children.Add(_buttonLamps[i]);
        }

        LoadSettings();
        RefreshPairing(force: true);
        RefreshOutput();
        _host.Changed += (_, _) => Dispatcher.BeginInvoke(() => { RefreshPairing(force: true); RefreshOutput(); LoadSettings(); });
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;

        // UI refresh at 30 Hz from a snapshot. Never from the hot path.
        _timer = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
        _timer.Tick += (_, _) => Refresh();
        _timer.Start();

        SourceInitialized += (_, _) => UseDarkTitleBar();
    }

    /// <summary>Raised once per second with a short status line for the tray tooltip.</summary>
    public event Action<string>? StatusLine;

    public void AllowClose() => _allowClose = true;

    public event EventHandler? HiddenToTray;

    protected override void OnClosing(CancelEventArgs e)
    {
        if (!_allowClose)
        {
            // Close to tray: the hub keeps driving the controller.
            e.Cancel = true;
            Hide();
            if (!_trayHintShown)
            {
                _trayHintShown = true;
                HiddenToTray?.Invoke(this, EventArgs.Empty);
            }
            return;
        }
        _timer.Stop();
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        base.OnClosing(e);
    }

    // ------------------------------------------------------------------ refresh ---

    private long _lastStatusLineMs;

    private void Refresh()
    {
        if (!IsVisible || WindowState == WindowState.Minimized)
        {
            // Hidden in the tray or minimized: only the tooltip line is needed, once per second. Do not take the engine
            // lock (and allocate a snapshot) 30 times a second while the game is running.
            if (Environment.TickCount64 - _lastStatusLineMs >= 1000) EmitStatusLine(_host.Engine.GetSnapshot());
            return;
        }
        HubSnapshot s = _host.Engine.GetSnapshot();

        // State pill and hints.
        (string label, Brush dot, string hint) = s.State switch
        {
            LinkState.Live => ("Live", Res("Brush.Ok"), s.Multipath ? "Live over Wi-Fi and USB together: the first copy of each packet wins." : "Live."),
            LinkState.Paused => ("Paused on the phone", Res("Brush.Warn"), "The phone left its drive screen: steering centred, pedals and buttons released."),
            LinkState.Failsafe => ("Signal lost", Res("Brush.Warn"), $"No packet for {s.SinceLastPacketMs:0} ms: pedals and buttons released, steering held."),
            LinkState.Lost => ("Phone disconnected", Res("Brush.Error"), "No packets for over 2 s. Check that Slipstream Wheel is open and on the same network, or plug in the USB cable."),
            _ => ("Waiting for the phone", Res("Brush.TextFaint"), "Open Slipstream Wheel and scan the code. If the phone sees the hub but nothing arrives, press Allow through firewall below."),
        };
        SetText(StateText, label);
        if (!ReferenceEquals(StateDot.Fill, dot)) StateDot.Fill = dot;
        SetText(LinkHintText, hint);

        // Headline numbers.
        SetText(RateText, s.RateHz.ToString("0", Inv));
        SetText(RttText, s.PhoneRttMs is double rtt ? rtt.ToString(rtt < 10 ? "0.0" : "0", Inv) : "-");
        SetText(LossText, s.RecentLossPercent.ToString("0.0", Inv));
        SetText(EpochLossText, s.HasEpoch ? string.Format(Inv, "{0:0.00} % since the phone connected", s.LossPercent) : "");
        TransportStats udp = s.Transport(TransportKind.Udp), tcp = s.Transport(TransportKind.Tcp);
        long bad = udp.BadTags + tcp.BadTags;
        long malformed = udp.Malformed + tcp.Malformed;
        SetText(BadText, (bad + malformed).ToString(Inv));
        SetText(BadDetailText, bad + malformed == 0 ? "none" : string.Format(Inv, "{0} bad tag, {1} malformed", bad, malformed));

        // Per transport.
        SetText(UdpRateText, udp.PacketsPerSecond.ToString("0", Inv));
        SetText(TcpRateText, tcp.PacketsPerSecond.ToString("0", Inv));
        UdpShareBar.Value = udp.FirstArrivalShare;
        TcpShareBar.Value = tcp.FirstArrivalShare;
        SetText(UdpShareText, (udp.FirstArrivalShare * 100).ToString("0", Inv) + " %");
        SetText(TcpShareText, (tcp.FirstArrivalShare * 100).ToString("0", Inv) + " %");
        SetText(UdpDupText, udp.Duplicates.ToString(Inv));
        SetText(TcpDupText, tcp.Duplicates.ToString(Inv));
        SetText(UdpBadText, udp.BadTags.ToString(Inv));
        SetText(TcpBadText, tcp.BadTags.ToString(Inv));

        string? portError = _host.Runtime.UdpError ?? _host.Runtime.TcpError;
        SetText(PortErrorText, portError ?? "");
        PortErrorText.Visibility = portError is null ? Visibility.Collapsed : Visibility.Visible;
        AdbStatus adb = _host.Runtime.AdbStatus;
        SetText(UsbStatusText, "USB: " + adb.Message);
        SetText(EndpointsText, s.StatusEndpoints.Count == 0 ? "STATUS: no phone to answer yet." : "STATUS 20 Hz to " + string.Join(", ", s.StatusEndpoints));
        BeaconBroadcaster? beacon = _host.Runtime.Beacon;
        SetText(BeaconText, beacon is null
            ? "Discovery beacon off."
            : string.Format(Inv, "Discovery beacon: 1 Hz to {0} broadcast addresses.", beacon.Targets.Count));

        // Controls.
        ControllerFrame f = s.Frame;
        SteerBar.Value = f.Steer / 32767.0;
        SetText(SteerText, (f.Steer / 32767.0).ToString("+0.00;-0.00;0.00", Inv));
        SetPedal(ThrottleBar, ThrottleText, f.Throttle);
        SetPedal(BrakeBar, BrakeText, f.Brake);
        SetPedal(ClutchBar, ClutchText, f.Clutch);
        SetPedal(HandbrakeBar, HandbrakeText, f.Handbrake);
        for (int i = 0; i < 8; i++) _pulseLamps[i].IsOn = (f.PulseMask & (1 << i)) != 0;
        for (int i = 0; i < 24; i++) _buttonLamps[i].IsOn = (f.Held & (1u << i)) != 0;
        var flags = new List<string>(3);
        if (s.HasEpoch) flags.Add("epoch " + s.Epoch.ToString("x8", Inv));
        if (s.Calibrating) flags.Add("calibrating");
        if (s.Multipath) flags.Add("multipath");
        SetText(FlagsText, string.Join("  ", flags));

        // Output health can change on its own (driver removed, another feeder took vJoy).
        RefreshOutputState(s);

        long now = Environment.TickCount64;
        if (now >= _nextHostCheckMs)
        {
            _nextHostCheckMs = now + 5000;
            RefreshPairing(force: false);
        }
        EmitStatusLine(s);
    }

    private void EmitStatusLine(HubSnapshot s)
    {
        long now = Environment.TickCount64;
        if (now - _lastStatusLineMs < 1000) return;
        _lastStatusLineMs = now;
        string state = s.State switch
        {
            LinkState.Live => string.Format(Inv, "live {0:0} Hz", s.RateHz),
            LinkState.Paused => "paused",
            LinkState.Failsafe => "signal lost",
            LinkState.Lost => "phone disconnected",
            _ => "waiting for the phone",
        };
        StatusLine?.Invoke("Slipstream Hub: " + state);
    }

    private static void SetPedal(LevelBar bar, TextBlock text, ushort value)
    {
        double v = value / 65535.0;
        bar.Value = v;
        SetText(text, (v * 100).ToString("0", Inv) + " %");
    }

    private static void SetText(TextBlock block, string text)
    {
        if (!string.Equals(block.Text, text, StringComparison.Ordinal)) block.Text = text;
    }

    private static Brush Res(string key) => (Brush)Application.Current.FindResource(key);

    // ------------------------------------------------------------------ pairing ---

    private void OnNetworkChanged(object? sender, EventArgs e)
        => Dispatcher.BeginInvoke(() => RefreshPairing(force: true));

    private void RefreshPairing(bool force)
    {
        IReadOnlyList<string> hosts = NetworkInfo.GetUsableHosts();
        string payload = _host.PairUri(hosts);
        SetText(CodeText, _host.DisplayCode);
        SetText(HubNameText, _host.Config.HubName ?? "");
        int udpPort = _host.Runtime.Udp?.LocalPort ?? _host.Config.Ports.Udp;
        int tcpPort = _host.Runtime.Tcp?.LocalPort ?? _host.Config.Ports.Tcp;
        SetText(HostsText, hosts.Count == 0
            ? "No network address found. Connect this PC to Wi-Fi or Ethernet, or use USB."
            : $"This PC: {string.Join(", ", hosts)}. Wi-Fi UDP {udpPort}, USB TCP {tcpPort}.");
        if (!force && payload == _qrPayload) return;
        _qrPayload = payload;
        try { QrImage.Source = QrRenderer.Render(payload); }
        catch (Exception ex) { SetText(HostsText, "QR code could not be drawn: " + ex.Message); }
    }

    private void NewCode_Click(object sender, RoutedEventArgs e)
    {
        MessageBoxResult answer = MessageBox.Show(this,
            "Make a new pairing code?\n\nPhones paired with the current code will stop driving until they scan the new one.",
            "New pairing code", MessageBoxButton.YesNo, MessageBoxImage.Question, MessageBoxResult.No);
        if (answer != MessageBoxResult.Yes) return;
        _host.NewCode();
        RefreshPairing(force: true);
    }

    // ------------------------------------------------------------------- output ---

    private OutputKind _shownKind = (OutputKind)255;
    private OutputState _shownState = (OutputState)255;

    private void RefreshOutput()
    {
        _loading = true;
        try
        {
            OutputKind kind = _host.Config.OutputKind;
            VJoyRadio.IsChecked = kind == OutputKind.VJoy;
            X360Radio.IsChecked = kind == OutputKind.Xbox360;
            NoneRadio.IsChecked = kind == OutputKind.None;
        }
        finally
        {
            _loading = false;
        }
        _shownKind = (OutputKind)255;
        RefreshOutputState(_host.Engine.GetSnapshot());
    }

    private void RefreshOutputState(HubSnapshot s)
    {
        if (s.OutputKind == _shownKind && s.OutputState == _shownState && OutputDetailText.Text == s.OutputDetail) return;
        _shownKind = s.OutputKind;
        _shownState = s.OutputState;
        (string text, string brush) = s.OutputState switch
        {
            OutputState.Ready => ($"{s.OutputName}: ready", "Brush.Ok"),
            OutputState.Degraded => ($"{s.OutputName}: works, setup incomplete", "Brush.Warn"),
            OutputState.Faulted => ($"{s.OutputName}: stopped", "Brush.Error"),
            _ => ($"{s.OutputName}: not available", "Brush.Error"),
        };
        if (s.OutputKind == OutputKind.None) (text, brush) = ("No virtual controller", "Brush.TextFaint");
        SetText(OutputStateText, text);
        OutputDot.Fill = Res(brush);
        SetText(OutputDetailText, s.OutputDetail);
        RetryButton.Visibility = s.OutputKind != OutputKind.None && s.OutputState != OutputState.Ready ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Output_Checked(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        OutputKind kind = sender == X360Radio ? OutputKind.Xbox360 : sender == NoneRadio ? OutputKind.None : OutputKind.VJoy;
        Mouse.OverrideCursor = Cursors.Wait;
        try { _host.SelectOutput(kind); }
        finally { Mouse.OverrideCursor = null; }
        _shownKind = (OutputKind)255;
    }

    private void Retry_Click(object sender, RoutedEventArgs e)
    {
        Mouse.OverrideCursor = Cursors.Wait;
        try { _host.RetryOutput(); }
        finally { Mouse.OverrideCursor = null; }
        _shownKind = (OutputKind)255;
    }

    // ----------------------------------------------------------------- settings ---

    private void LoadSettings()
    {
        _loading = true;
        try
        {
            var c = _host.Config;
            HubNameBox.Text = c.HubName ?? "";
            FailsafeBox.Text = c.FailsafeMs.ToString(Inv);
            PulseBox.Text = c.PulseMs.ToString(Inv);
            GapBox.Text = c.GapMs.ToString(Inv);
            InvertSteer.IsChecked = c.Invert.Steer;
            InvertThrottle.IsChecked = c.Invert.Throttle;
            InvertBrake.IsChecked = c.Invert.Brake;
            InvertClutch.IsChecked = c.Invert.Clutch;
            InvertHandbrake.IsChecked = c.Invert.Handbrake;
            UsbSwitch.IsChecked = c.UsbEnabled;
            AdbFolderBox.Text = c.AdbFolder ?? "";
            SetText(SettingsStatusText, _host.LastSaveError ?? _host.LoadNote ?? $"Saved in {_host.ConfigPath}");
        }
        finally
        {
            _loading = false;
        }
    }

    private void Settings_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) ApplyTextSettings();
    }

    private void Settings_LostFocus(object sender, RoutedEventArgs e) => ApplyTextSettings();

    private void ApplyTextSettings()
    {
        if (_loading) return;
        var errors = new List<string>();
        int failsafe = ParseMs(FailsafeBox.Text, 50, 2000, "Failsafe", errors, _host.Config.FailsafeMs);
        int pulse = ParseMs(PulseBox.Text, 10, 500, "Shift press", errors, _host.Config.PulseMs);
        int gap = ParseMs(GapBox.Text, 10, 500, "Shift gap", errors, _host.Config.GapMs);
        string name = HubNameBox.Text.Trim();
        string? adb = string.IsNullOrWhiteSpace(AdbFolderBox.Text) ? null : AdbFolderBox.Text.Trim();

        var c = _host.Config;
        bool changed = failsafe != c.FailsafeMs || pulse != c.PulseMs || gap != c.GapMs
                       || (name.Length > 0 && name != c.HubName) || adb != c.AdbFolder;
        if (changed)
        {
            _host.UpdateSettings(cfg =>
            {
                cfg.FailsafeMs = failsafe;
                cfg.PulseMs = pulse;
                cfg.GapMs = gap;
                if (name.Length > 0) cfg.HubName = name;
                cfg.AdbFolder = adb;
            });
        }
        LoadSettings();
        if (errors.Count > 0) SetText(SettingsStatusText, string.Join(" ", errors));
    }

    private static int ParseMs(string text, int min, int max, string label, List<string> errors, int current)
    {
        if (int.TryParse(text.Trim(), NumberStyles.Integer, Inv, out int v) && v >= min && v <= max) return v;
        errors.Add($"{label} must be a whole number of milliseconds from {min} to {max}.");
        return current;
    }

    private void Invert_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _host.UpdateSettings(cfg =>
        {
            cfg.Invert.Steer = InvertSteer.IsChecked == true;
            cfg.Invert.Throttle = InvertThrottle.IsChecked == true;
            cfg.Invert.Brake = InvertBrake.IsChecked == true;
            cfg.Invert.Clutch = InvertClutch.IsChecked == true;
            cfg.Invert.Handbrake = InvertHandbrake.IsChecked == true;
        });
        LoadSettings();
    }

    private void Usb_Changed(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        _host.UpdateSettings(cfg => cfg.UsbEnabled = UsbSwitch.IsChecked == true);
        LoadSettings();
    }

    private void BrowseAdb_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "Folder that contains adb.exe (Android platform-tools)",
            Multiselect = false,
        };
        if (!string.IsNullOrWhiteSpace(AdbFolderBox.Text)) dialog.InitialDirectory = AdbFolderBox.Text.Trim();
        if (dialog.ShowDialog(this) != true) return;
        AdbFolderBox.Text = dialog.FolderName;
        ApplyTextSettings();
    }

    private async void Firewall_Click(object sender, RoutedEventArgs e)
    {
        FirewallButton.IsEnabled = false;
        SetText(FirewallText, "Waiting for Windows to confirm administrator rights.");
        try
        {
            (bool ok, string message) = await FirewallHelper.AllowUdpAsync(_host.Runtime.Udp?.LocalPort ?? _host.Config.Ports.Udp);
            SetText(FirewallText, message);
            FirewallText.Foreground = ok ? Res("Brush.TextMuted") : Res("Brush.Error");
        }
        finally
        {
            FirewallButton.IsEnabled = true;
        }
    }

    // --------------------------------------------------------------------- misc ---

    private static ImageSource? LoadLogo()
    {
        try
        {
            var decoder = new IconBitmapDecoder(new Uri("pack://application:,,,/Assets/slipstream.ico"),
                BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
            return decoder.Frames.OrderByDescending(fr => fr.PixelWidth).FirstOrDefault(fr => fr.PixelWidth <= 64) ?? decoder.Frames[0];
        }
        catch (Exception)
        {
            return null;
        }
    }

    private void UseDarkTitleBar()
    {
        try
        {
            IntPtr hwnd = new WindowInteropHelper(this).Handle;
            int on = 1;
            // DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Windows 10 20H1 and later, 19 on older builds.
            if (DwmSetWindowAttribute(hwnd, 20, ref on, sizeof(int)) != 0)
                DwmSetWindowAttribute(hwnd, 19, ref on, sizeof(int));
        }
        catch (DllNotFoundException) { }
        catch (EntryPointNotFoundException) { }
    }

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}
