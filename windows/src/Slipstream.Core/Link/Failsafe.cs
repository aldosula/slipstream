using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Link;

/// <summary>
/// PROTOCOL.md section 9 rule 6. If no packet is accepted for failsafe_ms, or the packet has PAUSED:
/// throttle, brake, clutch, handbrake = 0, all held buttons released, queued pulses finish normally.
/// Steering holds its last value on failsafe and centers on PAUSED. Section 12.4 rule 4 is the same rule
/// for PAD: sticks centre, triggers 0, held buttons released, touch inactive, motion zero.
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

    /// <summary>
    /// The pad frame to show (12.4 rule 4), from the last applied PAD packet and the button output bits
    /// (held bits, already released on failsafe or PAUSED, with running tap schedules on top).
    /// </summary>
    public static PadFrame PadFrame(bool live, in PadPacket last, bool tripped, uint buttonsOut)
    {
        if (!live || tripped || last.Paused)
        {
            // Fingers lifted where they were (a DualShock 4 keeps the last position of a lifted finger).
            return new PadFrame
            {
                Buttons = buttonsOut,
                Touch0X = last.Touch0X,
                Touch0Y = last.Touch0Y,
                Touch1X = last.Touch1X,
                Touch1Y = last.Touch1Y,
                Touch0Id = (byte)(last.Touch0Id & 0x7F),
                Touch1Id = (byte)(last.Touch1Id & 0x7F),
                StylePs = last.StylePs,
                TimeUs = last.TimeUs,
            };
        }
        bool motion = last.Motion;
        return new PadFrame
        {
            Lx = last.Lx,
            Ly = last.Ly,
            Rx = last.Rx,
            Ry = last.Ry,
            L2 = last.L2,
            R2 = last.R2,
            Buttons = buttonsOut,
            Touch0X = last.Touch0X,
            Touch0Y = last.Touch0Y,
            Touch1X = last.Touch1X,
            Touch1Y = last.Touch1Y,
            Touch0Id = last.Touch0Id,
            Touch1Id = last.Touch1Id,
            GyroX = motion ? last.GyroX : (short)0,
            GyroY = motion ? last.GyroY : (short)0,
            GyroZ = motion ? last.GyroZ : (short)0,
            AccelX = motion ? last.AccelX : (short)0,
            AccelY = motion ? last.AccelY : (short)0,
            AccelZ = motion ? last.AccelZ : (short)0,
            Motion = motion,
            StylePs = last.StylePs,
            TimeUs = last.TimeUs,
        };
    }
}
