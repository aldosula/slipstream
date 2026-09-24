namespace Slipstream.Core.Protocol;

/// <summary>Serial-number and pulse counter arithmetic (PROTOCOL.md section 9, rules 3 and 4).</summary>
public static class SeqMath
{
    /// <summary>True when seq <paramref name="a"/> is newer than <paramref name="b"/>: d = (a - b) mod 2^32, newer iff 0 &lt; d &lt; 2^31.</summary>
    public static bool SeqNewer(uint a, uint b)
    {
        uint d = unchecked(a - b);
        return d != 0 && d < 0x8000_0000u;
    }

    /// <summary>Forward distance from b to a, mod 2^32.</summary>
    public static uint SeqDistance(uint a, uint b) => unchecked(a - b);

    /// <summary>Presses to emit for a wrapping u8 pulse counter: (new - old) mod 256 when in 1..127, else 0.</summary>
    public static int PulseDelta(byte newValue, byte oldValue)
    {
        int d = (newValue - oldValue) & 0xFF;
        return d is >= 1 and <= 127 ? d : 0;
    }
}
