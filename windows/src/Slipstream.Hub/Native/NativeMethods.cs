using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace Slipstream.Hub.Native;

/// <summary>Multimedia timer resolution: 1 ms scheduler ticks for the housekeeping thread.</summary>
internal static class WinMm
{
    [DllImport("winmm.dll")]
    public static extern uint timeBeginPeriod(uint uPeriod);

    [DllImport("winmm.dll")]
    public static extern uint timeEndPeriod(uint uPeriod);
}

/// <summary>
/// Opts the hub out of Windows power throttling. The hub spends a race hidden in the tray, and for a
/// process without a visible window Windows 11 may (a) run its threads under EcoQoS on efficiency cores
/// at low clock, and (b) ignore its timeBeginPeriod(1) request, which turns the 1 ms housekeeping tick
/// into 15.6 ms (late pulse releases and a coarse failsafe). ControlMask with StateMask 0 means "never
/// throttle this" for each bit.
/// </summary>
internal static class PowerThrottling
{
    private const int ProcessPowerThrottling = 4; // PROCESS_INFORMATION_CLASS
    private const uint CurrentVersion = 1;         // PROCESS_POWER_THROTTLING_CURRENT_VERSION
    private const uint ExecutionSpeed = 0x1;       // PROCESS_POWER_THROTTLING_EXECUTION_SPEED
    private const uint IgnoreTimerResolution = 0x4; // PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_POWER_THROTTLING_STATE
    {
        public uint Version;
        public uint ControlMask;
        public uint StateMask;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetProcessInformation(IntPtr hProcess, int processInformationClass,
        ref PROCESS_POWER_THROTTLING_STATE processInformation, uint processInformationSize);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    /// <summary>Returns which opt-outs Windows accepted (older builds reject the timer bit).</summary>
    public static (bool ExecutionSpeed, bool TimerResolution) OptOut()
    {
        try
        {
            IntPtr self = GetCurrentProcess();
            bool speed = Set(self, ExecutionSpeed);
            bool timer = Set(self, IgnoreTimerResolution); // Windows 11 only; fails harmlessly elsewhere
            return (speed, timer);
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return (false, false);
        }

        static bool Set(IntPtr process, uint bit)
        {
            var state = new PROCESS_POWER_THROTTLING_STATE { Version = CurrentVersion, ControlMask = bit, StateMask = 0 };
            return SetProcessInformation(process, ProcessPowerThrottling, ref state, (uint)Marshal.SizeOf<PROCESS_POWER_THROTTLING_STATE>());
        }
    }
}

/// <summary>
/// Multimedia Class Scheduler: the input threads join the built-in "Games" task, which runs them in
/// the High scheduling category (above every normal-priority game thread) while MMCSS still keeps
/// 20 % of the CPU for everything else. A registration is per thread and must be reverted on the
/// thread that made it, which the Core transports do when the thread ends.
/// </summary>
internal static class Mmcss
{
    [DllImport("avrt.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr AvSetMmThreadCharacteristicsW(string taskName, ref uint taskIndex);

    [DllImport("avrt.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AvRevertMmThreadCharacteristics(IntPtr avrtHandle);

    /// <summary>Registers the calling thread. Null when MMCSS is unavailable (the thread keeps its normal priority).</summary>
    public static IDisposable? JoinGamesTask()
    {
        try
        {
            uint index = 0;
            IntPtr handle = AvSetMmThreadCharacteristicsW("Games", ref index);
            return handle == IntPtr.Zero ? null : new Registration(handle);
        }
        catch (Exception e) when (e is DllNotFoundException or EntryPointNotFoundException)
        {
            return null;
        }
    }

    private sealed class Registration : IDisposable
    {
        private IntPtr _handle;

        public Registration(IntPtr handle) => _handle = handle;

        public void Dispose()
        {
            IntPtr h = Interlocked.Exchange(ref _handle, IntPtr.Zero);
            if (h != IntPtr.Zero) AvRevertMmThreadCharacteristics(h);
        }
    }
}

/// <summary>
/// Finds vJoyInterface.dll: the app folder first, then %ProgramFiles%\vJoy\x64. Installed once for
/// the whole assembly (a resolver can only be set once per assembly).
/// </summary>
internal static class NativeResolver
{
    private static int _installed;

    public const string VJoyLibrary = "vJoyInterface.dll";

    /// <summary>Where vJoyInterface.dll was loaded from, or null when it has not been found.</summary>
    public static string? VJoyPath { get; private set; }

    public static IReadOnlyList<string> VJoyCandidates()
    {
        var list = new List<string> { Path.Combine(AppContext.BaseDirectory, VJoyLibrary) };
        foreach (string? root in new[]
                 {
                     Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                     Environment.GetEnvironmentVariable("ProgramW6432"),
                 })
        {
            if (string.IsNullOrEmpty(root)) continue;
            string p = Path.Combine(root, "vJoy", "x64", VJoyLibrary);
            if (!list.Contains(p, StringComparer.OrdinalIgnoreCase)) list.Add(p);
        }
        return list;
    }

    public static void EnsureInstalled()
    {
        if (Interlocked.Exchange(ref _installed, 1) == 1) return;
        NativeLibrary.SetDllImportResolver(typeof(NativeResolver).Assembly, Resolve);
    }

    private static IntPtr Resolve(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (!libraryName.StartsWith("vJoyInterface", StringComparison.OrdinalIgnoreCase)) return IntPtr.Zero; // default rules
        foreach (string candidate in VJoyCandidates())
        {
            if (File.Exists(candidate) && NativeLibrary.TryLoad(candidate, out IntPtr handle))
            {
                VJoyPath = candidate;
                return handle;
            }
        }
        return IntPtr.Zero;
    }
}
