using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>Regression tests for defects found reviewing controller mode on the hub.</summary>
public class ControllerReviewTests
{
    private const int Cross = (int)PadButton.South;

    /// <summary>Queues 14 replay taps on Cross (about 1.26 s), longer than the 300 ms epoch takeover.</summary>
    private static void StartLongReplay(ControllerModeTests.PadRig rig, FakePadPhone phone)
    {
        phone.Tap(Cross, 7);
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(2);
        phone.Tap(Cross, 7);
        rig.Send(phone.Next(rig.Clock.NowUs));
        rig.AdvanceMs(2);
    }

    [Fact]
    public void Press_held_by_a_new_epoch_while_the_old_epochs_replay_runs_is_not_lost()
    {
        var rig = new ControllerModeTests.PadRig();
        var a = new FakePadPhone(TestKeys.Main, epoch: 0xA);
        rig.Send(a.Next(rig.Clock.NowUs));
        rig.AdvanceMs(10);
        StartLongReplay(rig, a);
        rig.AdvanceMs(400); // the phone app restarts: silence past the takeover

        // The new epoch starts with Cross held. The replay from the old epoch goes on (rule 2) and drives
        // the button, so this press cannot show yet; it is released before the replay ends.
        var b = new FakePadPhone(TestKeys.Main, epoch: 0xB);
        b.Press(Cross);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(b.Next(rig.Clock.NowUs)));
        Assert.True(rig.Snap.TapsPending[Cross] > 0, "the old replay is still running");
        rig.Stream(b, 100);
        b.Release(Cross);
        rig.Stream(b, 3000);

        Assert.Equal(15, rig.Pad.RisingEdges(Cross)); // 14 replayed taps plus the new epoch's press
        Assert.Equal(0, rig.Snap.TapsPending[Cross]);
    }

    [Fact]
    public void Press_held_by_a_new_epoch_past_the_old_replay_shows_once_and_stays_down()
    {
        var rig = new ControllerModeTests.PadRig();
        var a = new FakePadPhone(TestKeys.Main, epoch: 0xA);
        rig.Send(a.Next(rig.Clock.NowUs));
        rig.AdvanceMs(10);
        StartLongReplay(rig, a);
        rig.AdvanceMs(400);

        var b = new FakePadPhone(TestKeys.Main, epoch: 0xB);
        b.Press(Cross);
        rig.Send(b.Next(rig.Clock.NowUs));
        rig.Stream(b, 2000); // held well past the end of the replay
        Assert.True(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
        Assert.Equal(15, rig.Pad.RisingEdges(Cross));
        b.Release(Cross);
        rig.Stream(b, 100);
        Assert.Equal(15, rig.Pad.RisingEdges(Cross)); // not a second time
    }

    [Fact]
    public void Press_the_old_epoch_was_hiding_is_replayed_once_after_adoption()
    {
        var rig = new ControllerModeTests.PadRig();
        var a = new FakePadPhone(TestKeys.Main, epoch: 0xA);
        rig.Send(a.Next(rig.Clock.NowUs));
        rig.AdvanceMs(10);
        StartLongReplay(rig, a);
        a.Press(Cross); // a real press during the replay, hidden by it; the app dies holding it
        rig.Send(a.Next(rig.Clock.NowUs));
        rig.AdvanceMs(400);

        var b = new FakePadPhone(TestKeys.Main, epoch: 0xB);
        rig.Send(b.Next(rig.Clock.NowUs));
        rig.Stream(b, 3000);
        Assert.Equal(15, rig.Pad.RisingEdges(Cross)); // 14 replayed, plus the hidden press, once
        Assert.False(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
    }

    [Fact]
    public void Tap_scheduler_rebaseline_appends_the_ended_hidden_press_and_hides_the_new_held_one()
    {
        var s = new TapScheduler(50_000, 40_000);
        s.OnPacket(Cross, 3, held: true, outputDown: false, nowUs: 0); // replay 2, then the held press (hidden)
        Assert.Equal(1, s.Pending(Cross));
        s.Rebaseline(held: 1u << Cross);
        Assert.Equal(2, s.Pending(Cross));  // the old hidden press, appended
        s.OnPacket(Cross, 0, held: false, outputDown: true, nowUs: 1000);
        Assert.Equal(3, s.Pending(Cross));  // the new epoch's press, released while hidden
        s.Rebaseline(held: 0);
        Assert.Equal(3, s.Pending(Cross));  // nothing hidden any more
        s.Rebaseline(held: 1u << 5);        // a held button with no schedule running is not hidden
        s.OnPacket(5, 0, held: false, outputDown: true, nowUs: 2000);
        Assert.Equal(0, s.Pending(5));
        Assert.False(s.IsActive(5));
    }

    /// <summary>An Xbox 360 device that can be both the wheel output and the pad (like ViGEmX360Output).</summary>
    private sealed class DualX360 : IOutputDevice, IPadOutputDevice
    {
        public int PadApplies;
        public bool Disposed;
        public string Name => "Dual X360";
        public OutputKind Kind => OutputKind.Xbox360;
        public OutputState State => OutputState.Ready;
        public string StateDetail => "test";
        public void Apply(in OutputFrame frame) { }
        public void Apply(in PadOutputFrame frame) => PadApplies++;
        public void Neutral() { }
        public void Dispose() => Disposed = true;
    }

    [Fact]
    public void Entering_controller_mode_uses_an_xbox_360_wheel_output_set_in_wheel_mode()
    {
        var rig = new ControllerModeTests.PadRig();
        var pad = new FakePadPhone(TestKeys.Main, epoch: 0x31, playStation: false);
        rig.Send(pad.Next(rig.Clock.NowUs));
        rig.Stream(pad, 10);
        RecordingPadOutput owned = rig.Pad;
        Assert.Equal(OutputKind.Xbox360, owned.Kind);

        // Wheel mode, then the wheel output becomes an Xbox 360 device (no host bracket around the swap).
        var wheel = new FakePhone(TestKeys.Main, epoch: 0x31);
        wheel.State.Seq = pad.Seq;
        rig.Send(wheel.Next(rig.Clock.NowUs));
        var x360 = new DualX360();
        rig.Engine.SetOutput(x360);
        Assert.False(owned.Disposed);

        // Back to controller mode: one Xbox 360 pad, the shared one, not the wheel device plus a second pad.
        pad.Seq = wheel.Seq;
        rig.Send(pad.Next(rig.Clock.NowUs));
        rig.Stream(pad, 10);
        Assert.Same(x360, rig.Engine.PadOutput);
        Assert.True(owned.Disposed);
        Assert.True(x360.PadApplies > 0);
        Assert.Single(rig.Pads);
    }

    [Fact]
    public void Replug_replaces_an_owned_pad_that_cannot_retry_itself()
    {
        int created = 0;
        var clock = new ManualClock();
        IPadOutputDevice Factory(OutputKind kind)
        {
            created++;
            return created == 1 ? new FailedPadOutput(kind, "driver failed to load") : new RecordingPadOutput(kind, clock);
        }
        using var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, clock, Factory);
        var phone = new FakePadPhone(TestKeys.Main, epoch: 0x32, playStation: true);
        engine.Receive(TransportKind.Udp, phone.Next(clock.NowUs), null, clock.GetTimestamp());
        Assert.IsType<FailedPadOutput>(engine.PadOutput);
        Assert.Equal(OutputState.Faulted, engine.GetSnapshot().PadOutputState);

        engine.ReplugPad();
        Assert.Equal(2, created);
        var fresh = Assert.IsType<RecordingPadOutput>(engine.PadOutput);
        Assert.Equal(OutputKind.DualShock4, fresh.Kind);
        Assert.NotNull(fresh.Last); // it got the current frame at once

        // A shared pad (the wheel output) is not replugged by the engine.
        var x360 = new DualX360();
        using var engine2 = new HubEngine(TestKeys.Main, x360, null, clock, Factory);
        engine2.Receive(TransportKind.Udp, new FakePadPhone(TestKeys.Main, epoch: 0x33, playStation: false).Next(clock.NowUs), null, clock.GetTimestamp());
        Assert.Same(x360, engine2.PadOutput);
        engine2.ReplugPad();
        Assert.Same(x360, engine2.PadOutput);
        Assert.False(x360.Disposed);
    }

    /// <summary>
    /// Property test of the tap scheduler: presses of random length (1 ms to 150 ms) and gaps, send-on-change
    /// plus a 2 ms idle repeat, bursty loss on each path (bursts capped at 120 ms, under the failsafe) and, with
    /// multipath, a second path 2.5 to 4.5 ms slower so copies arrive out of order. The pad must show exactly
    /// the presses the phone made: none lost, none doubled.
    /// </summary>
    [Theory]
    [InlineData(11, false)]
    [InlineData(12, true)]
    [InlineData(13, false)]
    [InlineData(14, true)]
    public void Bursty_loss_and_reordered_multipath_neither_lose_nor_double_a_press(int seed, bool multipath)
    {
        var rig = new ControllerModeTests.PadRig();
        var phone = new FakePadPhone(TestKeys.Main, epoch: 0x77);
        var random = new Random(seed);
        var inflight = new List<(long At, long Order, byte[] Packet)>();
        long order = 0;
        int presses = 0, burstA = 0, burstB = 0;
        bool held = false, dirty = false;
        long lastSent = long.MinValue / 2, lastAccept = rig.Clock.NowUs, maxGap = 0;
        long start = rig.Clock.NowUs, nextChange = 50_000;
        rig.Send(phone.Next(start));

        bool Lose(ref int burst)
        {
            if (burst > 0) { burst--; return true; }
            if (random.NextDouble() < 0.01) { burst = 1 + random.Next(59); return true; } // up to 120 ms
            return random.NextDouble() < 0.05;
        }

        for (int ms = 0; ms < 30_000; ms++)
        {
            long now = rig.Clock.NowUs, t = now - start;
            if (t >= nextChange && ms < 29_000)
            {
                if (!held) { phone.Press(Cross); presses++; nextChange = t + 1000L * (1 + random.Next(random.Next(4) == 0 ? 3 : 150)); }
                else { phone.Release(Cross); nextChange = t + 1000L * (20 + random.Next(300)); }
                held = !held;
                dirty = true;
            }
            else if (held && ms >= 29_000) { phone.Release(Cross); held = false; dirty = true; }
            if ((dirty && now - lastSent >= 1000) || now - lastSent >= 2000)
            {
                byte[] p = phone.Next(now);
                lastSent = now;
                dirty = false;
                if (!Lose(ref burstA)) inflight.Add((now + 1500, order++, p));
                if (multipath && !Lose(ref burstB)) inflight.Add((now + 4000 + random.Next(2000), order++, p));
            }
            inflight.Sort((a, b) => a.At != b.At ? a.At.CompareTo(b.At) : a.Order.CompareTo(b.Order));
            while (inflight.Count > 0 && inflight[0].At <= now)
            {
                if (rig.Send(inflight[0].Packet) == ReceiveOutcome.Accepted)
                {
                    maxGap = Math.Max(maxGap, now - lastAccept);
                    lastAccept = now;
                }
                inflight.RemoveAt(0);
            }
            rig.AdvanceMs(1);
        }
        rig.AdvanceMs(3000);

        Assert.True(maxGap < 200_000, $"the loss model must stay under the failsafe, longest gap {maxGap} us");
        Assert.True(rig.Snap.TapsReplayed[Cross] > 0, "some presses were lost whole and replayed");
        Assert.Equal(presses, rig.Pad.RisingEdges(Cross));
        Assert.False(rig.Pad.Last!.Value.Source.IsDown(PadButton.South));
    }
}
