using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>PROTOCOL.md section 12.4 driven through the engine with a manual clock and a recording pad.</summary>
public class ControllerModeTests
{
    private const int Cross = (int)PadButton.South;
    private const int Circle = (int)PadButton.East;

    internal sealed class PadRig
    {
        public readonly ManualClock Clock = new();
        public readonly RecordingOutput Wheel;
        public readonly List<RecordingPadOutput> Pads = new();
        public readonly HubEngine Engine;

        public PadRig(HubEngineOptions? options = null, bool factory = true, IOutputDevice? wheel = null)
        {
            Wheel = new RecordingOutput(Clock);
            Engine = new HubEngine(TestKeys.Main, wheel ?? Wheel, options, Clock, factory ? CreatePad : null);
        }

        private IPadOutputDevice CreatePad(OutputKind kind)
        {
            var p = new RecordingPadOutput(kind, Clock);
            lock (Pads) Pads.Add(p);
            return p;
        }

        public RecordingPadOutput Pad => Pads[^1];

        public ReceiveOutcome Send(byte[] packet, TransportKind t = TransportKind.Udp, IStatusSink? sink = null)
            => Engine.Receive(t, packet, sink, Clock.GetTimestamp());

        public void AdvanceMs(double ms)
        {
            long total = (long)Math.Round(ms * 1000);
            while (total > 0)
            {
                long step = Math.Min(1000, total);
                Clock.AdvanceUs(step);
                total -= step;
                Engine.Tick();
            }
        }

        /// <summary>Sends one packet every 2 ms for <paramref name="ms"/>, ticking every millisecond.</summary>
        public void Stream(FakePadPhone phone, double ms, Func<bool>? lose = null)
        {
            for (int i = 0; i < ms; i += 2)
            {
                byte[] p = phone.Next(Clock.NowUs);
                if (lose is null || !lose()) Send(p);
                AdvanceMs(2);
            }
        }

        public HubSnapshot Snap => Engine.GetSnapshot();
    }

    private static List<(long Time, bool Down)> Transitions(IReadOnlyList<(long Timestamp, PadOutputFrame Frame)> frames, int button)
    {
        var list = new List<(long, bool)>();
        bool state = false;
        foreach ((long ts, PadOutputFrame f) in frames)
        {
            bool down = (f.Source.Buttons & (1u << button)) != 0;
            if (down != state) list.Add((ts, down));
            state = down;
        }
        return list;
    }

    private static (PadRig Rig, FakePadPhone Phone) Start(bool playStation = true, HubEngineOptions? options = null)
    {
        var rig = new PadRig(options);
        var phone = new FakePadPhone(TestKeys.Main, epoch: 0x5AD, playStation: playStation);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(phone.Next(0)));
        rig.AdvanceMs(10);
        return (rig, phone);
    }

    // ------------------------------------------------------------ tap scheduler ---

    [Fact]
    public void No_loss_presses_and_releases_pass_straight_through_with_zero_added_delay()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        var sentAt = new List<(long Time, bool Down)>();
        for (int tap = 0; tap < 5; tap++)
        {
            phone.Press(Cross);
            int before = rig.Pad.Frames.Count;
            long t = rig.Clock.NowUs;
            rig.Send(phone.Next(t));
            // Written inside the receive call: no tick, no timer.
            Assert.Equal(before + 1, rig.Pad.Frames.Count);
            Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
            sentAt.Add((t, true));
            rig.AdvanceMs(2);
            rig.Stream(phone, 60);

            phone.Release(Cross);
            t = rig.Clock.NowUs;
            rig.Send(phone.Next(t));
            Assert.False(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
            sentAt.Add((t, false));
            rig.AdvanceMs(2);
            rig.Stream(phone, 80);
        }

        Assert.Equal(sentAt, Transitions(rig.Pad.Frames, Cross));
        Assert.Equal(5, rig.Pad.RisingEdges(Cross));
        HubSnapshot s = rig.Snap;
        Assert.Equal(5, s.TapsEmitted[Cross]);
        Assert.Equal(0, s.TapsReplayed[Cross]);
        Assert.All(s.TapsPending, p => Assert.Equal(0, p));
    }

    [Fact]
    public void Lost_press_packet_presses_on_the_next_packet_without_a_replay()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Press(Cross);
        phone.Next(rig.Clock.NowUs); // the press packet is lost
        rig.AdvanceMs(2);
        long t = rig.Clock.NowUs;
        rig.Send(phone.Next(t));     // still held, counter already +1: d = 1, held, button up
        Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
        rig.Stream(phone, 40);
        phone.Release(Cross);
        rig.Stream(phone, 100);

        var tr = Transitions(rig.Pad.Frames, Cross);
        Assert.Equal(2, tr.Count);
        Assert.Equal(t, tr[0].Time);
        Assert.Equal(1, rig.Pad.RisingEdges(Cross));
        Assert.Equal(0, rig.Snap.TapsReplayed[Cross]);
    }

    [Fact]
    public void Lost_release_packet_releases_on_the_next_packet()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Press(Cross);
        rig.Stream(phone, 40);
        phone.Release(Cross);
        phone.Next(rig.Clock.NowUs); // the release packet is lost
        rig.AdvanceMs(2);
        long t = rig.Clock.NowUs;
        rig.Send(phone.Next(t));
        Assert.False(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
        rig.Stream(phone, 100);

        var tr = Transitions(rig.Pad.Frames, Cross);
        Assert.Equal(2, tr.Count);
        Assert.Equal(t, tr[1].Time);
        Assert.Equal(1, rig.Pad.RisingEdges(Cross));
        Assert.Equal(0, rig.Snap.TapsReplayed[Cross]);
    }

    [Fact]
    public void Lost_release_then_repress_releases_for_gap_ms_and_presses_again()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Press(Cross);
        rig.Stream(phone, 40);
        // Released and pressed again while every packet in between is lost.
        phone.Release(Cross);
        rig.Stream(phone, 20, lose: () => true);
        phone.Press(Cross);
        long t = rig.Clock.NowUs;
        rig.Send(phone.Next(t)); // held, d = 1, button down: gap_first, 0 replay taps
        Assert.False(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
        rig.AdvanceMs(2);
        rig.Stream(phone, 100);
        phone.Release(Cross);
        rig.Stream(phone, 60);

        var tr = Transitions(rig.Pad.Frames, Cross);
        Assert.Equal(4, tr.Count); // down, up (the gap), down (the re-press), up
        Assert.Equal(t, tr[1].Time);
        Assert.False(tr[1].Down);
        Assert.InRange(tr[2].Time - tr[1].Time, 40_000, 41_000);
        Assert.Equal(2, rig.Pad.RisingEdges(Cross));
        Assert.Equal(0, rig.Snap.TapsReplayed[Cross]);
    }

    [Fact]
    public void Burst_of_three_lost_taps_is_replayed_as_three_taps_of_tap_ms_and_gap_ms()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Tap(Cross, 3); // three whole taps inside a stretch of lost packets
        rig.Stream(phone, 30, lose: () => true);
        long t = rig.Clock.NowUs;
        rig.Send(phone.Next(t)); // released, d = 3: replay 3
        rig.AdvanceMs(2);
        rig.Stream(phone, 400);

        var tr = Transitions(rig.Pad.Frames, Cross);
        Assert.Equal(6, tr.Count);
        Assert.Equal(t, tr[0].Time); // the first replayed tap starts on the receive call
        for (int i = 0; i < 6; i += 2)
        {
            Assert.True(tr[i].Down && !tr[i + 1].Down);
            Assert.InRange(tr[i + 1].Time - tr[i].Time, 50_000, 51_000);
            if (i + 2 < 6) Assert.InRange(tr[i + 2].Time - tr[i + 1].Time, 40_000, 41_000);
        }
        Assert.Equal(3, rig.Pad.RisingEdges(Cross));
        HubSnapshot s = rig.Snap;
        Assert.Equal(3, s.TapsReplayed[Cross]);
        Assert.Equal(3, s.TapsEmitted[Cross]);
    }

    [Fact]
    public void Queue_is_capped_at_15_taps_per_button()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        for (int i = 0; i < 4; i++)
        {
            phone.Tap(Circle, 7); // 7 is the largest delta that counts
            rig.Send(phone.Next(rig.Clock.NowUs));
            rig.AdvanceMs(2);
            Assert.InRange(rig.Snap.TapsPending[Circle], 0, TapScheduler.MaxQueued);
        }
        Assert.Equal(TapScheduler.MaxQueued, rig.Snap.TapsPending[Circle]);
        rig.Stream(phone, 2000);
        // The one started by the first packet, plus the 15 that could queue behind it.
        Assert.Equal(1 + TapScheduler.MaxQueued, rig.Pad.RisingEdges(Circle));
        Assert.Equal(0, rig.Snap.TapsPending[Circle]);
    }

    [Fact]
    public void Tap_timing_follows_the_settings()
    {
        (PadRig rig, FakePadPhone phone) = Start(options: new HubEngineOptions { TapMs = 30, TapGapMs = 20 });
        phone.Tap(Cross, 2);
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(2);
        rig.Stream(phone, 200);
        var tr = Transitions(rig.Pad.Frames, Cross);
        Assert.Equal(4, tr.Count);
        Assert.InRange(tr[1].Time - tr[0].Time, 30_000, 31_000);
        Assert.InRange(tr[2].Time - tr[1].Time, 20_000, 21_000);
    }

    [Fact]
    public void Press_that_starts_and_ends_while_a_replay_runs_is_appended_not_lost()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Tap(Cross, 3);
        rig.Send(phone.Next(rig.Clock.NowUs)); // starts a 3 tap replay, about 270 ms
        rig.Stream(phone, 60);
        phone.Press(Cross);                    // a real press during the replay...
        rig.Stream(phone, 30);
        phone.Release(Cross);                  // ...released before the replay ends
        rig.Stream(phone, 600);

        Assert.Equal(4, rig.Pad.RisingEdges(Cross));
        Assert.Equal(4, rig.Snap.TapsReplayed[Cross]);
    }

    [Fact]
    public void Press_still_held_when_a_replay_ends_shows_once_and_stays_down()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.Tap(Cross, 2);
        rig.Send(phone.Next(rig.Clock.NowUs)); // 2 taps, about 180 ms
        rig.Stream(phone, 40);
        phone.Press(Cross);
        rig.Stream(phone, 300);                // held past the end of the replay
        Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
        phone.Release(Cross);
        rig.Stream(phone, 100);

        Assert.Equal(3, rig.Pad.RisingEdges(Cross));
        Assert.Equal(2, rig.Snap.TapsReplayed[Cross]);
    }

    [Fact]
    public void Taps_are_emitted_exactly_once_under_30_percent_loss()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        var random = new Random(4321);
        int pressed = 0;
        long delivered = 0, sent = 0;
        int holdLeft = 0;
        // 8 s at 500 Hz. Short taps (2 to 40 ms), some of them only one packet long.
        for (int ms = 0; ms < 8000; ms += 2)
        {
            if (holdLeft > 0 && --holdLeft == 0) phone.Release(Cross);
            if (ms % 170 == 20 && ms < 7400)
            {
                phone.Press(Cross);
                pressed++;
                holdLeft = 1 + random.Next(3); // 2 to 6 ms: a tap can vanish whole in a run of lost packets
            }
            byte[] p = phone.Next(rig.Clock.NowUs);
            sent++;
            if (random.NextDouble() >= 0.30)
            {
                rig.Send(p);
                delivered++;
            }
            rig.AdvanceMs(2);
        }
        rig.Stream(phone, 1000);
        Assert.InRange(delivered, (long)(sent * 0.64), (long)(sent * 0.76));
        Assert.Equal(pressed, rig.Pad.RisingEdges(Cross));
        HubSnapshot s = rig.Snap;
        Assert.Equal(pressed, s.TapsEmitted[Cross]);
        Assert.True(s.TapsReplayed[Cross] > 0, "some taps were lost whole and had to be replayed");
    }

    [Fact]
    public void Multipath_pad_copies_are_deduplicated_with_no_doubled_or_lost_taps()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        phone.State.Flags |= Wire.FlagMultipath;
        var random = new Random(77);
        var udp = new FakeSink(TransportKind.Udp, "udp");
        var tcp = new FakeSink(TransportKind.Tcp, "usb");
        var delivered = new HashSet<uint>();
        long udpN = 0, tcpN = 0;
        int pressed = 0, holdLeft = 0;
        for (int ms = 0; ms < 6000; ms += 2)
        {
            if (holdLeft > 0 && --holdLeft == 0) { phone.Release(Cross); phone.Release(Circle); }
            if (ms % 150 == 30 && ms < 5600)
            {
                phone.Press(Cross);
                phone.Press(Circle);
                pressed++;
                holdLeft = 1 + random.Next(6);
            }
            byte[] p = phone.Next(rig.Clock.NowUs);
            bool viaUdp = random.NextDouble() >= 0.30, viaTcp = random.NextDouble() >= 0.30;
            if (random.Next(2) == 0)
            {
                if (viaUdp) { rig.Send(p, TransportKind.Udp, udp); udpN++; }
                if (viaTcp) { rig.Send(p, TransportKind.Tcp, tcp); tcpN++; }
            }
            else
            {
                if (viaTcp) { rig.Send(p, TransportKind.Tcp, tcp); tcpN++; }
                if (viaUdp) { rig.Send(p, TransportKind.Udp, udp); udpN++; }
            }
            if (viaUdp || viaTcp) delivered.Add(phone.Seq);
            rig.AdvanceMs(2);
        }
        rig.Stream(phone, 800);

        HubSnapshot s = rig.Snap;
        Assert.Equal(pressed, rig.Pad.RisingEdges(Cross));
        Assert.Equal(pressed, rig.Pad.RisingEdges(Circle));
        TransportStats u = s.Transport(TransportKind.Udp), t = s.Transport(TransportKind.Tcp);
        // Plus the first packet of Start() and the 400 lossless packets streamed at the end, all on UDP.
        Assert.Equal((uint)(1 + delivered.Count + 400), s.Accepted);
        Assert.Equal(1 + udpN + 400, u.Packets);
        Assert.Equal(tcpN, t.Packets);
        Assert.Equal(u.Packets + t.Packets - s.Accepted, u.Duplicates + t.Duplicates);
        Assert.True(u.Duplicates > 0 && t.Duplicates > 0);
        Assert.True(s.Multipath);
    }

    // ------------------------------------------------------------ failsafe, PAUSED ---

    private static FakePadPhone Busy(FakePadPhone phone)
    {
        phone.State.Lx = -20000;
        phone.State.Ly = 12000;
        phone.State.Rx = 3000;
        phone.State.Ry = -30000;
        phone.State.L2 = 40000;
        phone.State.R2 = 65535;
        phone.State.Touch0X = 30000;
        phone.State.Touch0Y = 20000;
        phone.State.Touch0Id = PadPacket.TouchActive | 5;
        phone.State.GyroX = 1600;
        phone.State.AccelY = 4096;
        phone.State.Flags |= Wire.FlagMotion;
        return phone;
    }

    private static void AssertNeutralAxes(PadOutputFrame f)
    {
        PadFrame s = f.Source;
        Assert.Equal(0, s.Lx);
        Assert.Equal(0, s.Ly);
        Assert.Equal(0, s.Rx);
        Assert.Equal(0, s.Ry);
        Assert.Equal(0, s.L2);
        Assert.Equal(0, s.R2);
        Assert.False(s.Touch0Active);
        Assert.False(s.Touch1Active);
        Assert.Equal(0, s.GyroX);
        Assert.Equal(0, s.AccelY);
        Assert.Equal(128, f.Ds4LeftX);
        Assert.Equal(128, f.Ds4LeftY);
        Assert.Equal(0, f.Ds4LeftTrigger);
        Assert.Equal(0x80, f.Ds4Touch0Id & 0x80); // DualShock 4: finger up
        Assert.Equal(0, f.Ds4AccelY);
        Assert.Equal(0, f.X360LeftThumbX);
        Assert.Equal(0, f.X360RightTrigger);
    }

    [Fact]
    public void Pad_failsafe_after_200ms_neutralizes_and_running_replays_finish()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        Busy(phone);
        phone.Press(Circle);
        rig.Stream(phone, 20);
        Assert.Equal(-20000, rig.Pad.Last!.Value.Source.Lx);
        Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.East));

        phone.Tap(Cross, 4); // 4 taps take 50 + 3 * 90 + 40 = 360 ms, longer than the failsafe
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(199);
        Assert.Equal(LinkState.Live, rig.Snap.State);
        rig.AdvanceMs(1);
        Assert.Equal(LinkState.Failsafe, rig.Snap.State);
        PadOutputFrame f = rig.Pad.Last!.Value;
        AssertNeutralAxes(f);
        Assert.False(f.Source.IsDown(PadButton.East)); // held button released

        rig.AdvanceMs(400);
        Assert.Equal(4, rig.Pad.RisingEdges(Cross)); // the replay finished during the failsafe
        Assert.Equal(1, rig.Pad.RisingEdges(Circle));
        Assert.Equal(0u, rig.Pad.Last!.Value.Source.Buttons);

        rig.Send(phone.Next(rig.Clock.NowUs)); // link back: state returns at once
        Assert.Equal(-20000, rig.Pad.Last!.Value.Source.Lx);
        Assert.Equal(LinkState.Live, rig.Snap.State);
    }

    [Fact]
    public void Pad_paused_neutralizes_at_once_and_running_replays_finish()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        Busy(phone);
        phone.Press(Circle);
        rig.Stream(phone, 20);
        phone.Tap(Cross, 2);
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(2);

        phone.State.Flags |= Wire.FlagPaused;
        rig.Send(phone.Next(rig.Clock.NowUs));
        PadOutputFrame f = rig.Pad.Last!.Value;
        AssertNeutralAxes(f);
        Assert.False(f.Source.IsDown(PadButton.East));
        Assert.True(f.Source.IsDown(PadButton.South)); // the replayed tap already running goes on
        Assert.Equal(LinkState.Paused, rig.Snap.State);

        rig.Stream(phone, 300);
        Assert.Equal(2, rig.Pad.RisingEdges(Cross));
        Assert.Equal(LinkState.Paused, rig.Snap.State);

        phone.State.Flags &= unchecked((byte)~Wire.FlagPaused);
        rig.Send(phone.Next(rig.Clock.NowUs));
        Assert.Equal(-20000, rig.Pad.Last!.Value.Source.Lx);
        Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.East));
    }

    // ------------------------------------------------------------ mode switch ---

    [Fact]
    public void Mode_switch_wheel_to_pad_and_back_neutralizes_the_other_device_and_emits_no_spurious_presses()
    {
        var rig = new PadRig();
        var wheel = new FakePhone(TestKeys.Main, epoch: 0xD0D0);
        var pad = new FakePadPhone(TestKeys.Main, epoch: 0xD0D0);
        wheel.State.Throttle = 60000;
        wheel.State.Buttons = 1;
        for (int i = 0; i < 10; i++) { wheel.Press(0); rig.Send(wheel.Next(rig.Clock.NowUs)); rig.AdvanceMs(2); }
        rig.AdvanceMs(1500);
        long pulses = rig.Snap.PulsesEmitted[0];
        Assert.True(pulses >= 9);
        Assert.Equal(LinkMode.Wheel, rig.Snap.Mode);
        Assert.Empty(rig.Pads); // wheel mode never plugs in a pad

        // To controller mode: same epoch, the sequence goes on. Counters are far from zero.
        pad.Seq = wheel.State.Seq;
        pad.Tap(Cross, 5);
        pad.Tap(Circle, 3);
        pad.Press(Cross);
        int wheelFrames = rig.Wheel.Frames.Count;
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(pad.Next(rig.Clock.NowUs)));
        Assert.Equal(1, rig.Wheel.NeutralCalls);
        Assert.Single(rig.Pads);
        rig.Stream(pad, 400);
        HubSnapshot s = rig.Snap;
        Assert.Equal(LinkMode.Controller, s.Mode);
        Assert.Equal(PadStyle.PlayStation, s.Style);
        Assert.All(s.TapsReplayed, n => Assert.Equal(0, n)); // counters taken as the baseline
        Assert.Equal(1, rig.Pad.RisingEdges(Cross));           // only the button really held
        Assert.Equal(0, rig.Pad.RisingEdges(Circle));
        Assert.Equal(wheelFrames, rig.Wheel.Frames.Count);     // the wheel device is left alone
        Assert.Equal(pulses, s.PulsesEmitted[0]);
        Assert.Equal(0u, s.Frame.Held);

        // Back to the wheel: pulse counters moved while in controller mode; they are the new baseline.
        wheel.State.Seq = pad.Seq;
        wheel.Press(0, 5);
        int padFrames = rig.Pad.Frames.Count;
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(wheel.Next(rig.Clock.NowUs)));
        Assert.Equal(1, rig.Pad.NeutralCalls);
        Assert.False(rig.Pad.Disposed); // stays plugged in
        Assert.Equal(Mapping.VJoyPedal(60000), rig.Wheel.Last!.Value.VJoyY);
        rig.AdvanceMs(600);
        s = rig.Snap;
        Assert.Equal(LinkMode.Wheel, s.Mode);
        Assert.Equal(PadStyle.None, s.Style);
        Assert.Equal(pulses, s.PulsesEmitted[0]);
        Assert.Equal(padFrames, rig.Pad.Frames.Count);
        Assert.Equal(1, rig.Pad.RisingEdges(Cross));
        Assert.Single(rig.Pads);

        // And a new pulse after the switch counts once.
        wheel.Press(0);
        rig.Send(wheel.Next(rig.Clock.NowUs));
        rig.AdvanceMs(200);
        Assert.Equal(pulses + 1, rig.Snap.PulsesEmitted[0]);
    }

    [Fact]
    public void Epoch_adoption_by_a_pad_packet_baselines_taps_and_neutralizes_the_wheel()
    {
        var rig = new PadRig();
        var wheel = new FakePhone(TestKeys.Main, epoch: 1);
        wheel.State.Brake = 65535;
        rig.Send(wheel.Next(0));
        rig.AdvanceMs(400); // the phone restarts in controller mode with a new epoch
        var pad = new FakePadPhone(TestKeys.Main, epoch: 2, firstSeq: 900);
        pad.Tap(Cross, 6);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(pad.Next(rig.Clock.NowUs)));
        rig.Stream(pad, 200);
        Assert.Equal(1, rig.Wheel.NeutralCalls);
        Assert.Equal(0, rig.Pad.RisingEdges(Cross));
        Assert.Equal(LinkMode.Controller, rig.Snap.Mode);
    }

    // ------------------------------------------------------------ pad device ---

    [Fact]
    public void Pad_device_is_plugged_in_on_the_first_pad_packet_and_kept_until_the_output_changes()
    {
        (PadRig rig, FakePadPhone phone) = Start(playStation: true);
        Assert.Single(rig.Pads);
        Assert.Equal(OutputKind.DualShock4, rig.Pad.Kind); // Auto + STYLE_PS
        RecordingPadOutput ds4 = rig.Pad;
        HubSnapshot s = rig.Snap;
        Assert.True(s.PadPlugged);
        Assert.Equal(OutputKind.DualShock4, s.PadOutputKind);
        Assert.Equal(PadOutputSelection.Auto, s.PadOutputSelection);

        // PAUSED, silence and failsafe keep it.
        phone.State.Flags |= Wire.FlagPaused;
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(3000);
        phone.State.Flags &= unchecked((byte)~Wire.FlagPaused);
        rig.Send(phone.Next(rig.Clock.NowUs));
        Assert.Single(rig.Pads);
        Assert.False(ds4.Disposed);

        // The phone switches to the Xbox layout: under Auto that is another device.
        phone.State.Flags &= unchecked((byte)~Wire.FlagStylePs);
        rig.Send(phone.Next(rig.Clock.NowUs));
        Assert.Equal(2, rig.Pads.Count);
        Assert.Equal(OutputKind.Xbox360, rig.Pad.Kind);
        Assert.True(ds4.Disposed);
        Assert.Equal(1, ds4.NeutralCalls);
        Assert.Equal(PadStyle.Xbox, rig.Snap.Style);

        // Forcing DualShock 4 in the settings replaces it at once, whatever the style.
        rig.Engine.UpdateOptions(new HubEngineOptions { PadOutput = PadOutputSelection.DualShock4 });
        Assert.Equal(3, rig.Pads.Count);
        Assert.Equal(OutputKind.DualShock4, rig.Pad.Kind);
        Assert.True(rig.Pads[1].Disposed);
        rig.Send(phone.Next(rig.Clock.NowUs));
        Assert.Equal(3, rig.Pads.Count);

        // Timing changes do not touch the device.
        rig.Engine.UpdateOptions(new HubEngineOptions { PadOutput = PadOutputSelection.DualShock4, TapMs = 70 });
        Assert.Equal(3, rig.Pads.Count);

        // Engine shutdown sets it to neutral and disposes it.
        rig.Engine.Dispose();
        Assert.True(rig.Pad.Disposed);
    }

    [Fact]
    public void Changing_the_controller_output_in_wheel_mode_unplugs_the_pad_until_the_next_pad_packet()
    {
        (PadRig rig, FakePadPhone phone) = Start(playStation: false);
        Assert.Equal(OutputKind.Xbox360, rig.Pad.Kind);
        var wheel = new FakePhone(TestKeys.Main, epoch: phone.State.Epoch);
        wheel.State.Seq = phone.Seq;
        rig.Send(wheel.Next(rig.Clock.NowUs));
        rig.Engine.UpdateOptions(new HubEngineOptions { PadOutput = PadOutputSelection.DualShock4 });
        Assert.True(rig.Pads[0].Disposed);
        Assert.False(rig.Snap.PadPlugged);
        Assert.Single(rig.Pads);

        phone.Seq = wheel.State.Seq;
        rig.Send(phone.Next(rig.Clock.NowUs));
        Assert.Equal(2, rig.Pads.Count);
        Assert.Equal(OutputKind.DualShock4, rig.Pad.Kind);
    }

    /// <summary>An Xbox 360 device that can be both the wheel output and the pad (like ViGEmX360Output).</summary>
    private sealed class DualX360 : IOutputDevice, IPadOutputDevice
    {
        public int WheelApplies, PadApplies, NeutralCalls;
        public bool Disposed;
        public string Name => "Dual X360";
        public OutputKind Kind => OutputKind.Xbox360;
        public OutputState State => OutputState.Ready;
        public string StateDetail => "test";
        public void Apply(in OutputFrame frame) => WheelApplies++;
        public void Apply(in PadOutputFrame frame) => PadApplies++;
        public void Neutral() => NeutralCalls++;
        public void Dispose() => Disposed = true;
    }

    [Fact]
    public void An_xbox_360_wheel_output_serves_as_the_xbox_pad_so_games_see_one_controller()
    {
        var x360 = new DualX360();
        var rig = new PadRig(wheel: x360);
        var phone = new FakePadPhone(TestKeys.Main, epoch: 3, playStation: false);
        rig.Send(phone.Next(0));
        rig.Stream(phone, 20);
        Assert.Empty(rig.Pads); // the factory was not needed
        Assert.Same(x360, rig.Engine.PadOutput);
        Assert.True(x360.PadApplies >= 10);
        rig.Engine.Dispose();
        Assert.False(x360.Disposed); // the host owns the wheel output

        // PlayStation style still gets a separate DualShock 4.
        var rig2 = new PadRig(wheel: new DualX360());
        rig2.Send(new FakePadPhone(TestKeys.Main, epoch: 4, playStation: true).Next(0));
        Assert.Single(rig2.Pads);
        Assert.Equal(OutputKind.DualShock4, rig2.Pad.Kind);
    }

    [Fact]
    public void Swapping_the_wheel_output_to_xbox_360_frees_the_pad_slot_first_and_then_shares_it()
    {
        (PadRig rig, FakePadPhone phone) = Start(playStation: false); // wheel output: recording (not a pad)
        RecordingPadOutput owned = rig.Pad;
        Assert.Equal(OutputKind.Xbox360, owned.Kind);

        rig.Engine.BeginOutputChange(OutputKind.Xbox360);
        Assert.True(owned.Disposed);               // unplugged before the new wheel device exists
        rig.Send(phone.Next(rig.Clock.NowUs));     // packets during the swap create nothing
        Assert.Single(rig.Pads);
        var x360 = new DualX360();
        IOutputDevice old = rig.Engine.SetOutput(x360);
        Assert.Same(rig.Wheel, old);
        rig.Engine.EndOutputChange();
        Assert.Same(x360, rig.Engine.PadOutput);
        Assert.Single(rig.Pads);

        // Swapping away from Xbox 360 lets the shared device go; the next packet plugs in an owned pad.
        rig.Engine.BeginOutputChange(OutputKind.VJoy);
        rig.Engine.SetOutput(new RecordingOutput(rig.Clock));
        rig.Engine.EndOutputChange();
        Assert.Equal(2, rig.Pads.Count);
        Assert.Equal(OutputKind.Xbox360, rig.Pad.Kind);
        Assert.False(x360.Disposed);
    }

    [Fact]
    public void Status_in_controller_mode_reports_the_pad_kind_and_its_rumble()
    {
        var rig = new PadRig();
        var sink = new FakeSink(TransportKind.Udp);
        var phone = new FakePadPhone(TestKeys.Main, epoch: 8, playStation: true);
        rig.Send(phone.Next(0), TransportKind.Udp, sink);
        rig.AdvanceMs(60);
        Assert.NotEmpty(sink.Received);
        Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(sink.Received[^1], TestKeys.Main.Auth, out StatusPacket st));
        Assert.Equal((byte)OutputKind.DualShock4, st.Output);

        rig.Pad.State = OutputState.Unavailable;
        rig.Send(phone.Next(rig.Clock.NowUs), TransportKind.Udp, sink);
        rig.AdvanceMs(60);
        StatusPacket.TryDecode(sink.Received[^1], TestKeys.Main.Auth, out st);
        Assert.Equal(0x83, st.Output);
    }

    [Fact]
    public void Controller_mode_without_a_pad_factory_still_runs_the_rules()
    {
        var rig = new PadRig(factory: false);
        var phone = new FakePadPhone(TestKeys.Main, epoch: 9);
        rig.Send(phone.Next(0));
        phone.Press(Cross);
        phone.State.Lx = 1234;
        rig.Send(phone.Next(0));
        HubSnapshot s = rig.Snap;
        Assert.False(s.PadPlugged);
        Assert.Equal(1234, s.PadFrame.Lx);
        Assert.True(s.PadFrame.IsDown(PadButton.South));
        Assert.Equal(1, s.TapsEmitted[Cross]);
    }

    [Fact]
    public void New_pairing_key_in_controller_mode_neutralizes_the_pad_and_keeps_it()
    {
        (PadRig rig, FakePadPhone phone) = Start();
        rig.Engine.SetPairing(TestKeys.Other);
        Assert.Equal(1, rig.Pad.NeutralCalls);
        Assert.False(rig.Pad.Disposed);
        Assert.Equal(LinkMode.None, rig.Snap.Mode);
        Assert.Equal(ReceiveOutcome.BadTag, rig.Send(phone.Next(rig.Clock.NowUs)));
    }

    // ------------------------------------------------------------ allocation ---

    private sealed class NullSink : IStatusSink
    {
        public int Count;
        public TransportKind Transport => TransportKind.Udp;
        public string Endpoint => "null";
        public bool IsOpen => true;
        public void SendStatus(ReadOnlySpan<byte> status) => Count += status.Length;
    }

    [Fact]
    public void Pad_receive_and_tick_paths_do_not_allocate()
    {
        var clock = new ManualClock();
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, clock, k => new NullPadOutput(k));
        var phone = new FakePadPhone(TestKeys.Main, epoch: 78);
        var sink = new NullSink();
        var random = new Random(5);
        var packets = new byte[4000][];
        for (int i = 0; i < packets.Length; i++)
        {
            if (i % 40 == 0) phone.Press(i % 18);
            if (i % 40 == 5) phone.Release((i - 5) % 18);
            if (i % 97 == 0) phone.Tap(i % 18, 1 + i % 3); // lost taps: replays run on the tick
            phone.State.Lx = (short)(i * 13 % 30000);
            phone.State.L2 = (ushort)(i * 17);
            packets[i] = phone.Next(i * 2000L);
        }

        void Run(int from, int to)
        {
            for (int i = from; i < to; i++)
            {
                if (random.Next(5) != 0) engine.Receive(TransportKind.Udp, packets[i], sink, clock.GetTimestamp());
                engine.Receive(TransportKind.Tcp, packets[i], sink, clock.GetTimestamp());
                clock.AdvanceUs(1000);
                engine.Tick();
                clock.AdvanceUs(1000);
                engine.Tick();
            }
        }

        Run(0, 1000); // warm up: JIT, tiering, the pad device, first-use statics
        long before = GC.GetAllocatedBytesForCurrentThread();
        Run(1000, 4000);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(0, allocated);
        Assert.True(sink.Count > 0);
        Assert.True(engine.GetSnapshot().TapsReplayed.Sum() > 0);
    }
}
