using System.ComponentModel;
using System.Diagnostics;

namespace Slipstream.Hub;

/// <summary>
/// Adds an inbound Windows Firewall rule for the INPUT port (UDP 47800) on the private and domain
/// profiles. netsh runs elevated through ProcessStartInfo Verb=runas, so only this click asks for
/// administrator rights.
/// </summary>
internal static class FirewallHelper
{
    public static string RuleName(int port) => $"Slipstream Hub (UDP {port})";

    public static async Task<(bool Ok, string Message)> AllowUdpAsync(int port)
    {
        if (await Task.Run(() => RuleExists(port)))
            return (true, $"Windows Firewall already allows UDP {port} on private and domain networks.");

        var psi = new ProcessStartInfo("netsh")
        {
            Arguments = $"advfirewall firewall add rule name=\"{RuleName(port)}\" dir=in action=allow protocol=UDP localport={port} profile=private,domain",
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        try
        {
            using Process? p = Process.Start(psi);
            if (p is null) return (false, "netsh could not be started.");
            bool exited = await Task.Run(() => p.WaitForExit(30_000));
            if (!exited) return (false, "netsh did not finish in 30 s.");
            if (p.ExitCode != 0) return (false, $"netsh failed with exit code {p.ExitCode}.");
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            return (false, "Cancelled: Windows asked for administrator rights and the request was declined.");
        }
        catch (Win32Exception ex)
        {
            return (false, $"netsh could not run: {ex.Message}");
        }

        bool ok = await Task.Run(() => RuleExists(port));
        return ok
            ? (true, $"Done: UDP {port} is allowed on private and domain networks. If Wi-Fi is set to Public, switch it to Private in Windows settings.")
            : (false, "netsh ran but the rule is not visible. Check Windows Defender Firewall, inbound rules.");
    }

    /// <summary>Reading rules needs no elevation.</summary>
    public static bool RuleExists(int port)
    {
        try
        {
            var psi = new ProcessStartInfo("netsh", $"advfirewall firewall show rule name=\"{RuleName(port)}\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using Process? p = Process.Start(psi);
            if (p is null) return false;
            Task<string> stdout = p.StandardOutput.ReadToEndAsync();
            _ = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(10_000)) return false;
            return p.ExitCode == 0 && stdout.Result.Contains(RuleName(port), StringComparison.OrdinalIgnoreCase);
        }
        catch (Win32Exception)
        {
            return false;
        }
    }
}
