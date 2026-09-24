using System.Globalization;
using System.Windows;
using System.Windows.Media;

namespace Slipstream.Hub.Ui;

/// <summary>Shared drawing resources, resolved once from the theme.</summary>
internal static class MeterPalette
{
    private static readonly Lazy<Brush> TrackBrush = new(() => Get("Brush.Raised", Color.FromRgb(0x1E, 0x23, 0x2C)));
    private static readonly Lazy<Brush> AccentBrush = new(() => Get("Brush.Accent", Color.FromRgb(0xFF, 0x8A, 0x3D)));
    private static readonly Lazy<Brush> TickBrush = new(() => Get("Brush.StrokeStrong", Color.FromRgb(0x3A, 0x42, 0x50)));
    private static readonly Lazy<Brush> OnAccentBrush = new(() => Get("Brush.OnAccent", Color.FromRgb(0x1A, 0x0E, 0x05)));
    private static readonly Lazy<Brush> MutedBrush = new(() => Get("Brush.TextMuted", Color.FromRgb(0xA3, 0xAC, 0xBA)));
    public static readonly Typeface LabelFace = new(new FontFamily("Segoe UI Variable Text, Segoe UI"), FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal);

    public static Brush Track => TrackBrush.Value;
    public static Brush Accent => AccentBrush.Value;
    public static Brush Tick => TickBrush.Value;
    public static Brush OnAccent => OnAccentBrush.Value;
    public static Brush Muted => MutedBrush.Value;

    private static Brush Get(string key, Color fallback)
        => Application.Current?.TryFindResource(key) as Brush ?? Freeze(new SolidColorBrush(fallback));

    private static Brush Freeze(SolidColorBrush b)
    {
        b.Freeze();
        return b;
    }
}

/// <summary>A horizontal 0..1 level bar (pedals).</summary>
public sealed class LevelBar : FrameworkElement
{
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(double), typeof(LevelBar),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public double Value
    {
        get => (double)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight, r = Math.Min(h / 2, 6);
        dc.DrawRoundedRectangle(MeterPalette.Track, null, new Rect(0, 0, w, h), r, r);
        double v = Math.Clamp(Value, 0, 1);
        if (v > 0)
        {
            double fw = Math.Max(h, w * v);
            dc.DrawRoundedRectangle(MeterPalette.Accent, null, new Rect(0, 0, Math.Min(fw, w), h), r, r);
        }
    }
}

/// <summary>A horizontal -1..1 bar filled from the centre (steering).</summary>
public sealed class CenterBar : FrameworkElement
{
    public static readonly DependencyProperty ValueProperty = DependencyProperty.Register(
        nameof(Value), typeof(double), typeof(CenterBar),
        new FrameworkPropertyMetadata(0.0, FrameworkPropertyMetadataOptions.AffectsRender));

    public double Value
    {
        get => (double)GetValue(ValueProperty);
        set => SetValue(ValueProperty, value);
    }

    protected override void OnRender(DrawingContext dc)
    {
        double w = ActualWidth, h = ActualHeight, r = Math.Min(h / 2, 6), mid = w / 2;
        dc.DrawRoundedRectangle(MeterPalette.Track, null, new Rect(0, 0, w, h), r, r);
        double v = Math.Clamp(Value, -1, 1);
        double x = mid + v * mid;
        var fill = new Rect(Math.Min(mid, x), 0, Math.Abs(x - mid), h);
        if (fill.Width > 0.5) dc.DrawRectangle(MeterPalette.Accent, null, fill);
        // Centre line and a marker at the current position.
        dc.DrawRectangle(MeterPalette.Tick, null, new Rect(mid - 1, -3, 2, h + 6));
        dc.DrawRoundedRectangle(MeterPalette.Accent, null, new Rect(Math.Clamp(x - 3, 0, w - 6), -3, 6, h + 6), 3, 3);
    }
}

/// <summary>A small labelled lamp for buttons and pulse channels.</summary>
public sealed class Lamp : FrameworkElement
{
    public static readonly DependencyProperty IsOnProperty = DependencyProperty.Register(
        nameof(IsOn), typeof(bool), typeof(Lamp),
        new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender));

    public static readonly DependencyProperty LabelProperty = DependencyProperty.Register(
        nameof(Label), typeof(string), typeof(Lamp),
        new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender));

    public bool IsOn
    {
        get => (bool)GetValue(IsOnProperty);
        set => SetValue(IsOnProperty, value);
    }

    public string Label
    {
        get => (string)GetValue(LabelProperty);
        set => SetValue(LabelProperty, value);
    }

    protected override Size MeasureOverride(Size availableSize) => new(34, 28);

    protected override void OnRender(DrawingContext dc)
    {
        var rect = new Rect(0, 0, ActualWidth, ActualHeight);
        dc.DrawRoundedRectangle(IsOn ? MeterPalette.Accent : MeterPalette.Track, null, rect, 6, 6);
        if (string.IsNullOrEmpty(Label)) return;
        var text = new FormattedText(Label, CultureInfo.InvariantCulture, FlowDirection.LeftToRight,
            MeterPalette.LabelFace, 11, IsOn ? MeterPalette.OnAccent : MeterPalette.Muted, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        dc.DrawText(text, new Point((rect.Width - text.Width) / 2, (rect.Height - text.Height) / 2));
    }
}
