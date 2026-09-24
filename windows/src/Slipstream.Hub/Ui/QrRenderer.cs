using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace Slipstream.Hub.Ui;

internal static class QrRenderer
{
    // Near-black modules on white: the polarity every phone scanner reads.
    private static readonly byte[] Dark = { 0x0F, 0x12, 0x17, 0xFF };
    private static readonly byte[] Light = { 0xFF, 0xFF, 0xFF, 0xFF };

    public static BitmapSource Render(string payload, int pixelsPerModule = 8)
    {
        using var generator = new QRCodeGenerator();
        using QRCodeData data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        byte[] png = new PngByteQRCode(data).GetGraphic(pixelsPerModule, Dark, Light, drawQuietZones: true);
        var image = new BitmapImage();
        using (var stream = new MemoryStream(png))
        {
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
        }
        image.Freeze();
        return image;
    }
}
