using System.Text.Json;

namespace Slipstream.Core.Tests;

/// <summary>Loads docs/test-vectors.json (copied next to the test assembly).</summary>
internal static class Vectors
{
    private static readonly Lazy<JsonDocument> Doc = new(() =>
    {
        string path = Path.Combine(AppContext.BaseDirectory, "test-vectors.json");
        if (!File.Exists(path)) throw new FileNotFoundException("test-vectors.json was not copied to the test output.", path);
        return JsonDocument.Parse(File.ReadAllText(path));
    });

    public static JsonElement Root => Doc.Value.RootElement;

    public static byte[] Hex(string hex) => Convert.FromHexString(hex);

    public static string ToHex(ReadOnlySpan<byte> bytes) => Convert.ToHexString(bytes).ToLowerInvariant();

    public static IEnumerable<JsonElement> Array(string name) => Root.GetProperty(name).EnumerateArray();

    public static uint U32(this JsonElement e, string name) => e.GetProperty(name).GetUInt32();
    public static int I32(this JsonElement e, string name) => e.GetProperty(name).GetInt32();
}
