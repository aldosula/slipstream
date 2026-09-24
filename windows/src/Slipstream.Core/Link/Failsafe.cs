using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// PROTOCOL.md section 9 rule 6. If no packet is accepted for failsafe_ms, or the packet has PAUSED:
/// throttle, brake, clutch, handbrake = 0, all held buttons released, queued pulses finish normally.
/// Steering holds its last value on failsafe and centers on PAUSED.
/// </summary>
public static class Failsafe
{
    public static bool IsTripped(long nowUs, long lastAcceptUs, long failsafeUs) => nowUs - lastAcceptUs >= failsafeUs;

    /// <summary>The frame to show, from the last applied packet and the pulse lamps currently down.</summary>
    public static ControllerFrame Frame(bool hasEpoch, in InputPacket last, bool tripped, byte pulseMask)
    {
        if (!hasEpoch || last.Paused) return new ControllerFrame(0, 0, 0, 0, 0, 0, pulseMask);
        if (tripped) return new ControllerFrame(last.Steer, 0, 0, 0, 0, 0, pulseMask);
        return new ControllerFrame(last.Steer, last.Throttle, last.Brake, last.Clutch, last.Handbrake,
            last.Buttons & ControllerFrame.HeldMask, pulseMask);
    }
}
