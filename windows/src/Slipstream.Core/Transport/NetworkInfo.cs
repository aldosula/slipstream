using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Slipstream.Core.Transport;

/// <summary>One IPv4 address of an up, non-loopback interface.</summary>
public sealed record Ipv4Interface(
    string Name,
    string Description,
    NetworkInterfaceType Type,
    IPAddress Address,
    IPAddress Mask,
    IPAddress? DirectedBroadcast,
    bool HasGateway)
{
    public bool IsLinkLocal => Address.GetAddressBytes() is [169, 254, ..];
}

public static class NetworkInfo
{
    /// <summary>Every IPv4 address on every up, non-loopback, non-tunnel interface.</summary>
    public static IReadOnlyList<Ipv4Interface> GetIpv4Interfaces()
    {
        var list = new List<Ipv4Interface>();
        NetworkInterface[] nics;
        try { nics = NetworkInterface.GetAllNetworkInterfaces(); }
        catch (NetworkInformationException) { return list; }

        foreach (NetworkInterface nic in nics)
        {
            try
            {
                if (nic.OperationalStatus != OperationalStatus.Up) continue;
                if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                IPInterfaceProperties props = nic.GetIPProperties();
                bool gateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (UnicastIPAddressInformation ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || IPAddress.IsLoopback(ua.Address)) continue;
                    IPAddress mask = ua.IPv4Mask ?? IPAddress.Any;
                    list.Add(new Ipv4Interface(nic.Name, nic.Description, nic.NetworkInterfaceType, ua.Address, mask,
                        DirectedBroadcast(ua.Address, mask), gateway));
                }
            }
            catch (NetworkInformationException) { /* interface vanished while we looked */ }
            catch (PlatformNotSupportedException) { }
        }
        return list;
    }

    /// <summary>
    /// Every usable IPv4 address for the pairing QR: not loopback, not link-local. Within one subnet the
    /// address Windows itself sends from comes first, then interfaces with a gateway, then Wi-Fi and
    /// Ethernet. <paramref name="max"/> only guards against absurd adapter counts.
    /// </summary>
    /// <remarks>
    /// The phone connects its UDP socket to one hub address, so STATUS is only accepted when it comes
    /// back from exactly that address. The hub answers from a wildcard socket, and with two adapters in
    /// the same subnet (Ethernet and Wi-Fi to the same router) the OS picks the route-preferred one as
    /// the source. The phone takes the first listed host of its own subnet, so that one must be it.
    /// </remarks>
    public static IReadOnlyList<string> GetUsableHosts(int max = 16)
        => OrderHosts(GetIpv4Interfaces(), PreferredSourceFor, max);

    internal static IReadOnlyList<string> OrderHosts(IEnumerable<Ipv4Interface> interfaces, Func<Ipv4Interface, IPAddress?> preferredSource, int max)
        => interfaces
            .Where(i => !i.IsLinkLocal)
            .Select(i => (Nic: i, Preferred: SafePreferred(i, preferredSource)))
            .OrderByDescending(x => x.Preferred)
            .ThenByDescending(x => x.Nic.HasGateway)
            .ThenBy(x => x.Nic.Type is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet ? 0 : 1)
            .Select(x => x.Nic.Address.ToString())
            .Distinct()
            .Take(max)
            .ToList();

    private static bool SafePreferred(Ipv4Interface nic, Func<Ipv4Interface, IPAddress?> preferredSource)
    {
        try
        {
            IPAddress? source = preferredSource(nic);
            return source is null || source.Equals(nic.Address); // unknown counts as preferred
        }
        catch (Exception)
        {
            return true;
        }
    }

    /// <summary>
    /// The local address the OS would use to reach this interface's subnet: a UDP connect sends nothing,
    /// it only runs the route lookup. Null when it cannot be determined.
    /// </summary>
    public static IPAddress? PreferredSourceFor(Ipv4Interface nic)
    {
        IPAddress? target = nic.DirectedBroadcast;
        if (target is null) return null;
        using var probe = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { EnableBroadcast = true };
        probe.Connect(new IPEndPoint(target, 9));
        return (probe.LocalEndPoint as IPEndPoint)?.Address;
    }

    /// <summary>address | ~mask, or null for a point-to-point or unknown mask.</summary>
    public static IPAddress? DirectedBroadcast(IPAddress address, IPAddress mask)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork || mask.AddressFamily != AddressFamily.InterNetwork) return null;
        byte[] a = address.GetAddressBytes(), m = mask.GetAddressBytes();
        if (m.All(b => b == 0) || m.All(b => b == 255)) return null;
        var r = new byte[4];
        for (int i = 0; i < 4; i++) r[i] = (byte)(a[i] | ~m[i]);
        return new IPAddress(r);
    }
}
