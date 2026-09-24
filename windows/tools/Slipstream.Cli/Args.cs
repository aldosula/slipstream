using System.Globalization;

namespace Slipstream.Cli;

/// <summary>Minimal "--name value" and "--flag" parser. Unknown options are errors.</summary>
internal sealed class Args
{
    private readonly Dictionary<string, string?> _values = new(StringComparer.Ordinal);
    private readonly HashSet<string> _used = new(StringComparer.Ordinal);

    public Args(IEnumerable<string> args, IReadOnlySet<string> flags)
    {
        using IEnumerator<string> e = args.GetEnumerator();
        while (e.MoveNext())
        {
            string a = e.Current;
            if (!a.StartsWith("--", StringComparison.Ordinal)) throw new UsageException($"Unexpected argument '{a}'.");
            string name = a[2..];
            string? value = null;
            int eq = name.IndexOf('=');
            if (eq >= 0)
            {
                value = name[(eq + 1)..];
                name = name[..eq];
            }
            else if (!flags.Contains(name))
            {
                if (!e.MoveNext()) throw new UsageException($"Option --{name} needs a value.");
                value = e.Current;
            }
            _values[name] = value;
        }
    }

    public bool Flag(string name)
    {
        _used.Add(name);
        return _values.ContainsKey(name);
    }

    public string? String(string name)
    {
        _used.Add(name);
        return _values.TryGetValue(name, out string? v) ? v : null;
    }

    public int Int(string name, int fallback, int min, int max)
    {
        string? s = String(name);
        if (s is null) return fallback;
        if (!int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out int v) || v < min || v > max)
            throw new UsageException($"--{name} must be a whole number from {min} to {max}.");
        return v;
    }

    public double Double(string name, double fallback, double min, double max)
    {
        string? s = String(name);
        if (s is null) return fallback;
        if (!double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out double v) || v < min || v > max)
            throw new UsageException($"--{name} must be a number from {min.ToString(CultureInfo.InvariantCulture)} to {max.ToString(CultureInfo.InvariantCulture)}.");
        return v;
    }

    /// <summary>Throws for any option that was given but never read.</summary>
    public void RejectUnknown()
    {
        foreach (string k in _values.Keys)
            if (!_used.Contains(k)) throw new UsageException($"Unknown option --{k}.");
    }
}

internal sealed class UsageException : Exception
{
    public UsageException(string message) : base(message) { }
}
