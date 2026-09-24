using System.Diagnostics;

namespace Slipstream.Core.Link;

/// <summary>Monotonic time source in ticks. Injected so tests can control time.</summary>
public interface IClock
{
    long GetTimestamp();
    /// <summary>Ticks per second.</summary>
    long Frequency { get; }
}

/// <summary>The real clock: <see cref="Stopwatch"/> ticks (QueryPerformanceCounter on Windows).</summary>
public sealed class StopwatchClock : IClock
{
    public static readonly StopwatchClock Instance = new();
    public long GetTimestamp() => Stopwatch.GetTimestamp();
    public long Frequency => Stopwatch.Frequency;
}

/// <summary>A clock that only moves when told to. Frequency is 1 MHz, so one tick is one microsecond.</summary>
public sealed class ManualClock : IClock
{
    private long _now;
    public ManualClock(long startUs = 1_000_000) => _now = startUs;
    public long GetTimestamp() => Interlocked.Read(ref _now);
    public long Frequency => 1_000_000;
    public void AdvanceUs(long us) => Interlocked.Add(ref _now, us);
    public void AdvanceMs(double ms) => AdvanceUs((long)Math.Round(ms * 1000));
    public long NowUs => Interlocked.Read(ref _now);
}

public static class ClockMath
{
    /// <summary>Ticks to microseconds without overflow for any realistic uptime.</summary>
    public static long ToMicroseconds(long ticks, long frequency)
    {
        if (frequency == 1_000_000) return ticks;
        long whole = ticks / frequency;
        long rest = ticks % frequency;
        return whole * 1_000_000 + rest * 1_000_000 / frequency;
    }

    public static long NowUs(this IClock clock) => ToMicroseconds(clock.GetTimestamp(), clock.Frequency);
}
