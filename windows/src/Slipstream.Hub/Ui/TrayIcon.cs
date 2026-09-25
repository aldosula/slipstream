using System.IO;
using System.Windows;
using Slipstream.Core.Output;
using Drawing = System.Drawing;
using Forms = System.Windows.Forms;

namespace Slipstream.Hub.Ui;

/// <summary>Notification area icon: Show, Wheel output (vJoy, Xbox 360, None), Controller output (Auto, Xbox 360, DualShock 4), Quit. Dark menu.</summary>
internal sealed class TrayIcon : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    private readonly Forms.ContextMenuStrip _menu;
    private readonly Forms.ToolStripMenuItem _vjoy, _x360, _none;
    private readonly Forms.ToolStripMenuItem _padAuto, _padX360, _padDs4;
    private readonly HubHost _host;

    public TrayIcon(HubHost host, Action show, Action quit)
    {
        _host = host;
        _menu = new Forms.ContextMenuStrip { Renderer = new DarkMenuRenderer(), ShowImageMargin = false, ShowCheckMargin = true };
        var showItem = new Forms.ToolStripMenuItem("Show Slipstream Hub") { Font = new Drawing.Font(Forms.SystemInformation.MenuFont, Drawing.FontStyle.Bold) };
        showItem.Click += (_, _) => show();

        var output = new Forms.ToolStripMenuItem("Wheel output");
        _vjoy = new Forms.ToolStripMenuItem("vJoy");
        _x360 = new Forms.ToolStripMenuItem("Xbox 360 (ViGEm)");
        _none = new Forms.ToolStripMenuItem("None");
        _vjoy.Click += (_, _) => host.SelectOutput(OutputKind.VJoy);
        _x360.Click += (_, _) => host.SelectOutput(OutputKind.Xbox360);
        _none.Click += (_, _) => host.SelectOutput(OutputKind.None);
        output.DropDownItems.AddRange(new Forms.ToolStripItem[] { _vjoy, _x360, _none });
        if (output.DropDown is Forms.ToolStripDropDownMenu dd)
        {
            dd.Renderer = new DarkMenuRenderer();
            dd.ShowImageMargin = false;
            dd.ShowCheckMargin = true;
        }

        var padOutput = new Forms.ToolStripMenuItem("Controller output");
        _padAuto = new Forms.ToolStripMenuItem("Auto (by the phone's layout)");
        _padX360 = new Forms.ToolStripMenuItem("Xbox 360 (ViGEm)");
        _padDs4 = new Forms.ToolStripMenuItem("DualShock 4 (ViGEm)");
        _padAuto.Click += (_, _) => host.SelectPadOutput(PadOutputSelection.Auto);
        _padX360.Click += (_, _) => host.SelectPadOutput(PadOutputSelection.Xbox360);
        _padDs4.Click += (_, _) => host.SelectPadOutput(PadOutputSelection.DualShock4);
        padOutput.DropDownItems.AddRange(new Forms.ToolStripItem[] { _padAuto, _padX360, _padDs4 });
        if (padOutput.DropDown is Forms.ToolStripDropDownMenu pd)
        {
            pd.Renderer = new DarkMenuRenderer();
            pd.ShowImageMargin = false;
            pd.ShowCheckMargin = true;
        }

        var quitItem = new Forms.ToolStripMenuItem("Quit");
        quitItem.Click += (_, _) => quit();

        _menu.Items.AddRange(new Forms.ToolStripItem[] { showItem, output, padOutput, new Forms.ToolStripSeparator(), quitItem });
        _menu.Opening += (_, _) => UpdateChecks();

        _icon = new Forms.NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "Slipstream Hub",
            ContextMenuStrip = _menu,
            Visible = true,
        };
        _icon.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) show(); };
    }

    private void UpdateChecks()
    {
        OutputKind k = _host.Config.OutputKind;
        _vjoy.Checked = k == OutputKind.VJoy;
        _x360.Checked = k == OutputKind.Xbox360;
        _none.Checked = k == OutputKind.None;
        PadOutputSelection p = _host.Config.PadOutputSelection;
        _padAuto.Checked = p == PadOutputSelection.Auto;
        _padX360.Checked = p == PadOutputSelection.Xbox360;
        _padDs4.Checked = p == PadOutputSelection.DualShock4;
    }

    /// <summary>Tooltip text; Windows limits it to 63 characters.</summary>
    public void SetStatus(string text) => _icon.Text = text.Length > 63 ? text[..63] : text;

    public void ShowBalloon(string title, string text) => _icon.ShowBalloonTip(4000, title, text, Forms.ToolTipIcon.None);

    private static Drawing.Icon LoadIcon()
    {
        try
        {
            var info = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/slipstream.ico"));
            if (info is not null)
            {
                using Stream s = info.Stream;
                return new Drawing.Icon(s, Forms.SystemInformation.SmallIconSize);
            }
        }
        catch (Exception) { /* bad or unsupported icon data must not stop the hub from starting */ }
        return Drawing.SystemIcons.Application;
    }

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
        _menu.Dispose();
    }

    /// <summary>Tray menu colours matching the window theme.</summary>
    private sealed class DarkMenuRenderer : Forms.ToolStripProfessionalRenderer
    {
        private static readonly Drawing.Color Back = Drawing.Color.FromArgb(0x16, 0x1A, 0x21);
        private static readonly Drawing.Color Hover = Drawing.Color.FromArgb(0x25, 0x2B, 0x35);
        private static readonly Drawing.Color Line = Drawing.Color.FromArgb(0x2A, 0x30, 0x3B);
        private static readonly Drawing.Color Text = Drawing.Color.FromArgb(0xE9, 0xED, 0xF3);
        private static readonly Drawing.Color Accent = Drawing.Color.FromArgb(0xFF, 0x8A, 0x3D);

        public DarkMenuRenderer() : base(new DarkColors()) => RoundedEdges = false;

        protected override void OnRenderItemText(Forms.ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = Text;
            base.OnRenderItemText(e);
        }

        protected override void OnRenderArrow(Forms.ToolStripArrowRenderEventArgs e)
        {
            e.ArrowColor = Text;
            base.OnRenderArrow(e);
        }

        protected override void OnRenderItemCheck(Forms.ToolStripItemImageRenderEventArgs e)
        {
            Drawing.Rectangle r = e.ImageRectangle;
            using var brush = new Drawing.SolidBrush(Accent);
            int d = Math.Min(r.Width, r.Height) / 2;
            e.Graphics.SmoothingMode = Drawing.Drawing2D.SmoothingMode.AntiAlias;
            e.Graphics.FillEllipse(brush, r.X + (r.Width - d) / 2, r.Y + (r.Height - d) / 2, d, d);
        }

        private sealed class DarkColors : Forms.ProfessionalColorTable
        {
            public override Drawing.Color ToolStripDropDownBackground => Back;
            public override Drawing.Color ImageMarginGradientBegin => Back;
            public override Drawing.Color ImageMarginGradientMiddle => Back;
            public override Drawing.Color ImageMarginGradientEnd => Back;
            public override Drawing.Color MenuBorder => Line;
            public override Drawing.Color MenuItemBorder => Hover;
            public override Drawing.Color MenuItemSelected => Hover;
            public override Drawing.Color MenuItemSelectedGradientBegin => Hover;
            public override Drawing.Color MenuItemSelectedGradientEnd => Hover;
            public override Drawing.Color MenuItemPressedGradientBegin => Hover;
            public override Drawing.Color MenuItemPressedGradientEnd => Hover;
            public override Drawing.Color SeparatorDark => Line;
            public override Drawing.Color SeparatorLight => Line;
            public override Drawing.Color CheckBackground => Back;
            public override Drawing.Color CheckSelectedBackground => Hover;
            public override Drawing.Color CheckPressedBackground => Hover;
        }
    }
}
