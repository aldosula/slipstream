using Slipstream.Core.Link;
using Slipstream.Core.Output;
using Slipstream.Core.Protocol;

namespace Slipstream.Core.Tests;

/// <summary>PROTOCOL.md section 9, rules 1 to 6, driven through the engine with a manual clock.</summary>
public class SessionRuleTests
{
    private sealed class Rig
    {
        public readonly ManualClock Clock = new();
        public readonly RecordingOutput Output;
        public readonly HubEngine Engine;

        public Rig(HubEngineOptions? options = null)
        {
            Output = new RecordingOutput(Clock);
            Engine = new HubEngine(TestKeys.Main, Output, options, Clock);
        }

        public ReceiveOutcome Send(byte[] packet, TransportKind t = TransportKind.Udp, IStatusSink? sink = null)
            => Engine.Receive(t, packet, sink, Clock.GetTimestamp());

        public void AdvanceMs(double ms, bool tick = true)
        {
            // Advance in 1 ms steps, ticking like the housekeeping thread does.
            long total = (long)Math.Round(ms * 1000);
            while (total > 0)
            {
                long step = Math.Min(1000, total);
                Clock.AdvanceUs(step);
                total -= step;
                if (tick) Engine.Tick();
            }
        }

        public OutputFrame Last => Output.Last!.Value;
        public HubSnapshot Snap => Engine.GetSnapshot();
    }

    // ------------------------------------------------------------------ rule 1 ---

    [Fact]
    public void Rule1_invalid_packets_are_counted_and_dropped_before_touching_state()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Other, epoch: 7); // wrong key
        Assert.Equal(ReceiveOutcome.BadTag, rig.Send(phone.Next(0)));
        Assert.Equal(ReceiveOutcome.Malformed, rig.Send(new byte[51]));
        Assert.Equal(ReceiveOutcome.Malformed, rig.Send(new byte[52], TransportKind.Tcp));
        HubSnapshot s = rig.Snap;
        Assert.False(s.HasEpoch);
        Assert.Equal(1, s.Transport(TransportKind.Udp).BadTags);
        Assert.Equal(1, s.Transport(TransportKind.Udp).Malformed);
        Assert.Equal(1, s.Transport(TransportKind.Tcp).Malformed);
        Assert.Equal(0, s.Transport(TransportKind.Udp).Packets);
    }

    // ------------------------------------------------------------------ rule 2 ---

    [Fact]
    public void Rule2_first_packet_adopts_the_epoch()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 0x1A2B3C4D);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(phone.Next(0)));
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(phone.Next(2000)));
        HubSnapshot s = rig.Snap;
        Assert.Equal(0x1A2B3C4Du, s.Epoch);
        Assert.Equal(2u, s.Accepted);
        Assert.Equal(0u, s.Missing);
        Assert.Equal(LinkState.Live, s.State);
    }

    [Fact]
    public void Rule2_new_epoch_is_rejected_before_300ms_of_silence_and_adopted_at_300ms()
    {
        var rig = new Rig();
        var a = new FakePhone(TestKeys.Main, epoch: 111);
        var b = new FakePhone(TestKeys.Main, epoch: 222, firstSeq: 5000);
        for (int i = 0; i < 10; i++) { rig.Send(a.Next(0)); rig.AdvanceMs(2); }
        rig.Send(a.Next(0)); // last packet of phone A

        rig.AdvanceMs(299);
        Assert.Equal(ReceiveOutcome.EpochRejected, rig.Send(b.Next(0)));
        Assert.Equal(111u, rig.Snap.Epoch);

        rig.AdvanceMs(1); // exactly 300 ms since A's last accepted packet
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(b.Next(0)));
        HubSnapshot s = rig.Snap;
        Assert.Equal(222u, s.Epoch);
        Assert.Equal(1u, s.Accepted);
        Assert.Equal(0u, s.Missing); // b skipped seq 5000 (rejected); counters restart at adoption
        Assert.Equal(5001u, s.LastSeq);
        Assert.Equal(1, s.Transport(TransportKind.Udp).ForeignEpoch);
        Assert.Equal(2, s.EpochChanges);

        // Phone A comes back: B is alive now, so A is the one rejected.
        Assert.Equal(ReceiveOutcome.EpochRejected, rig.Send(a.Next(0)));
    }

    [Fact]
    public void Rule2_two_phones_with_the_same_key_cannot_fight()
    {
        var rig = new Rig();
        var a = new FakePhone(TestKeys.Main, epoch: 1);
        var b = new FakePhone(TestKeys.Main, epoch: 2);
        a.State.Throttle = 1000;
        b.State.Throttle = 60000;
        rig.Send(a.Next(0));
        for (int i = 0; i < 500; i++)
        {
            rig.AdvanceMs(1);
            Assert.Equal(ReceiveOutcome.EpochRejected, rig.Send(b.Next(0)));
            rig.AdvanceMs(1);
            Assert.Equal(ReceiveOutcome.Accepted, rig.Send(a.Next(0)));
        }
        Assert.Equal(1u, rig.Snap.Epoch);
        Assert.Equal(Mapping.VJoyPedal(1000), rig.Last.VJoyY);
        Assert.Equal(500, rig.Snap.Transport(TransportKind.Udp).ForeignEpoch);
    }

    [Fact]
    public void Rule2_adoption_takes_pulse_counters_as_baseline_without_presses()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 9);
        phone.Press(0, 5);
        phone.Press(3, 100);
        rig.Send(phone.Next(0));
        rig.AdvanceMs(200);
        Assert.Equal(0, rig.Output.RisingEdges(0));
        Assert.Equal(0, rig.Output.RisingEdges(3));
        Assert.Equal(0, rig.Snap.PulsesEmitted[0]);

        phone.Press(0);
        rig.Send(phone.Next(0));
        rig.AdvanceMs(200);
        Assert.Equal(1, rig.Output.RisingEdges(0));

        // Epoch takeover also baselines: the new phone's counter value is not a burst of presses.
        rig.AdvanceMs(400);
        var other = new FakePhone(TestKeys.Main, epoch: 10);
        other.Press(0, 77);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(other.Next(0)));
        rig.AdvanceMs(300);
        Assert.Equal(1, rig.Output.RisingEdges(0));
    }

    // ------------------------------------------------------------------ rule 3 ---

    [Fact]
    public void Rule3_duplicates_and_late_packets_are_dropped_and_counted_per_transport()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 3);
        byte[] p1 = phone.Next(0);
        byte[] p2 = phone.Next(0);
        byte[] p3 = phone.Next(0);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(p1));
        Assert.Equal(ReceiveOutcome.Duplicate, rig.Send(p1, TransportKind.Tcp));
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(p3));
        Assert.Equal(1u, rig.Snap.Missing);
        Assert.Equal(ReceiveOutcome.Duplicate, rig.Send(p2)); // late: never un-counted as missing
        Assert.Equal(ReceiveOutcome.Duplicate, rig.Send(p3));
        HubSnapshot s = rig.Snap;
        Assert.Equal(2u, s.Accepted);
        Assert.Equal(1u, s.Missing);
        Assert.Equal(2, s.Transport(TransportKind.Udp).Duplicates);
        Assert.Equal(1, s.Transport(TransportKind.Tcp).Duplicates);
        Assert.Equal(2, s.Transport(TransportKind.Udp).FirstArrivals);
        Assert.Equal(0, s.Transport(TransportKind.Tcp).FirstArrivals);
    }

    [Fact]
    public void Rule3_missing_count_is_correct_across_the_u32_wrap()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 4, firstSeq: 0xFFFF_FFFE);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(phone.Next(0))); // 0xFFFFFFFE
        phone.Next(0);                                                         // 0xFFFFFFFF lost
        phone.Next(0);                                                         // 0x00000000 lost
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(phone.Next(0)));        // 0x00000001
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(phone.Next(0)));        // 0x00000002
        HubSnapshot s = rig.Snap;
        Assert.Equal(2u, s.LastSeq);
        Assert.Equal(3u, s.Accepted);
        Assert.Equal(2u, s.Missing);
    }

    [Fact]
    public void Rule3_a_jump_of_2_pow_31_or_more_is_old_not_new()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 5, firstSeq: 10);
        rig.Send(phone.Next(0));
        phone.State.Seq = unchecked(10u + 0x8000_0000u - 1); // Next() makes it 10 + 2^31
        Assert.Equal(ReceiveOutcome.Duplicate, rig.Send(phone.Next(0)));
        phone.State.Seq = unchecked(10u + 0x7FFF_FFFFu - 1); // 10 + 2^31 - 1 is newer
        Assert.Equal(ReceiveOutcome.Accepted, rig.Send(phone.Next(0)));
        Assert.Equal(0x7FFF_FFFEu, rig.Snap.Missing);
    }

    // ------------------------------------------------------------------ rule 4 ---

    [Fact]
    public void Rule4_press_is_60ms_down_then_40ms_gap_per_channel()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 6);
        rig.Send(phone.Next(0));
        long t0 = rig.Clock.NowUs;
        phone.Press(0, 2); // two presses in one packet
        phone.Press(1, 1);
        rig.Send(phone.Next(0));
        Assert.Equal(0b11u, rig.Last.VJoyButtons & 0b11); // applied on the receive call, not on a tick

        for (int ms = 0; ms < 400; ms++) rig.AdvanceMs(1);
        var edges = Transitions(rig.Output.Frames, bit: 0);
        Assert.Equal(4, edges.Count); // down, up, down, up
        Assert.Equal(t0, edges[0].Time);
        Assert.InRange(edges[1].Time - edges[0].Time, 60_000, 61_000);
        Assert.InRange(edges[2].Time - edges[1].Time, 40_000, 41_000);
        Assert.InRange(edges[3].Time - edges[2].Time, 60_000, 61_000);
        Assert.Equal(2, rig.Output.RisingEdges(0));
        Assert.Equal(1, rig.Output.RisingEdges(1));
        Assert.Equal(new long[] { 2, 1, 0, 0, 0, 0, 0, 0 }, rig.Snap.PulsesEmitted);
    }

    [Fact]
    public void Rule4_a_counter_jump_of_128_or_more_is_a_reset_and_emits_nothing()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 7);
        rig.Send(phone.Next(0));
        phone.Press(2, 130);
        rig.Send(phone.Next(0));
        rig.AdvanceMs(300);
        Assert.Equal(0, rig.Output.RisingEdges(2));
        phone.Press(2, 1); // baseline was stored: the next single press counts
        rig.Send(phone.Next(0));
        rig.AdvanceMs(150);
        Assert.Equal(1, rig.Output.RisingEdges(2));
    }

    [Fact]
    public void Rule4_pulses_are_emitted_exactly_once_under_30_percent_loss()
    {
        var rig = new Rig();
        var random = new Random(1234);
        var phone = new FakePhone(TestKeys.Main, epoch: 8);
        int pressed = 0, delivered = 0, sent = 0;
        // 6 s at 500 Hz. A shift every 150 ms, and a triple tap every 1.5 s.
        for (int ms = 0; ms < 6000; ms++)
        {
            if (ms % 150 == 75 && ms < 5400) { phone.Press(0); pressed++; }
            if (ms % 1500 == 700 && ms < 5400) { phone.Press(0, 3); pressed += 3; }
            if (ms % 2 == 0)
            {
                byte[] p = phone.Next(rig.Clock.NowUs);
                sent++;
                if (random.NextDouble() >= 0.30) { rig.Send(p); delivered++; }
            }
            rig.AdvanceMs(1);
        }
        Assert.InRange(delivered, (int)(sent * 0.65), (int)(sent * 0.75));
        Assert.Equal(pressed, rig.Output.RisingEdges(0));
        Assert.Equal(pressed, rig.Snap.PulsesEmitted[0]);
        AssertPressTiming(rig.Output.Frames, bit: 0);
        HubSnapshot s = rig.Snap;
        Assert.Equal((uint)delivered, s.Accepted);
        Assert.InRange(s.LossPercent, 25, 35);
    }

    [Fact]
    public void Rule4_multipath_udp_and_tcp_copies_are_deduplicated_with_no_double_pulses()
    {
        var rig = new Rig();
        var random = new Random(99);
        var phone = new FakePhone(TestKeys.Main, epoch: 12);
        phone.State.Flags = Wire.FlagMultipath;
        var udp = new FakeSink(TransportKind.Udp, "udp");
        var tcp = new FakeSink(TransportKind.Tcp, "usb");
        int pressed = 0;
        var deliveredSeqs = new HashSet<uint>();
        long udpDelivered = 0, tcpDelivered = 0;
        for (int ms = 0; ms < 5000; ms++)
        {
            if (ms % 240 == 30 && ms < 4600) { phone.Press(0); phone.Press(1, 2); pressed++; }
            if (ms % 2 == 0)
            {
                byte[] p = phone.Next(rig.Clock.NowUs);
                bool viaUdp = random.NextDouble() >= 0.30;
                bool viaTcp = random.NextDouble() >= 0.30;
                // Random arrival order between the two paths.
                if (random.Next(2) == 0)
                {
                    if (viaUdp) { rig.Send(p, TransportKind.Udp, udp); udpDelivered++; }
                    if (viaTcp) { rig.Send(p, TransportKind.Tcp, tcp); tcpDelivered++; }
                }
                else
                {
                    if (viaTcp) { rig.Send(p, TransportKind.Tcp, tcp); tcpDelivered++; }
                    if (viaUdp) { rig.Send(p, TransportKind.Udp, udp); udpDelivered++; }
                }
                if (viaUdp || viaTcp) deliveredSeqs.Add(phone.Seq);
            }
            rig.AdvanceMs(1);
        }

        HubSnapshot s = rig.Snap;
        Assert.Equal(pressed, rig.Output.RisingEdges(0));
        Assert.Equal(pressed * 2, rig.Output.RisingEdges(1));
        Assert.Equal((uint)deliveredSeqs.Count, s.Accepted);
        TransportStats u = s.Transport(TransportKind.Udp), t = s.Transport(TransportKind.Tcp);
        Assert.Equal(udpDelivered, u.Packets);
        Assert.Equal(tcpDelivered, t.Packets);
        Assert.Equal(s.Accepted, (uint)(u.FirstArrivals + t.FirstArrivals));
        Assert.Equal(udpDelivered + tcpDelivered - deliveredSeqs.Count, u.Duplicates + t.Duplicates);
        Assert.True(u.Duplicates > 0 && t.Duplicates > 0);
        Assert.True(s.Multipath);
        AssertPressTiming(rig.Output.Frames, bit: 0);
        AssertPressTiming(rig.Output.Frames, bit: 1);
    }

    // ------------------------------------------------------------------ rule 5 ---

    [Fact]
    public void Rule5_accepted_state_is_written_to_the_device_on_the_receive_call()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 13);
        phone.State.Steer = -16384;
        phone.State.Throttle = 32768;
        phone.State.Buttons = 0b101;
        int before = rig.Output.Frames.Count;
        rig.Send(phone.Next(0));
        Assert.Equal(before + 1, rig.Output.Frames.Count); // no tick needed
        OutputFrame f = rig.Last;
        Assert.Equal(8193, f.VJoyX);
        Assert.Equal(16385, f.VJoyY);
        Assert.Equal(0b101u << 8, f.VJoyButtons);
    }

    // ------------------------------------------------------------------ rule 6 ---

    [Fact]
    public void Rule6_failsafe_after_200ms_releases_pedals_and_buttons_and_holds_steering()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 14);
        phone.State.Steer = 12000;
        phone.State.Throttle = 50000;
        phone.State.Brake = 1000;
        phone.State.Clutch = 2000;
        phone.State.Handbrake = 40000;
        phone.State.Buttons = 0b1011;
        rig.Send(phone.Next(0));

        rig.AdvanceMs(199);
        Assert.Equal(Mapping.VJoyPedal(50000), rig.Last.VJoyY);
        Assert.Equal(LinkState.Live, rig.Snap.State);

        rig.AdvanceMs(1);
        OutputFrame f = rig.Last;
        Assert.Equal(Mapping.VJoySteer(12000), f.VJoyX);
        Assert.Equal(1, f.VJoyY);
        Assert.Equal(1, f.VJoyZ);
        Assert.Equal(1, f.VJoyRx);
        Assert.Equal(1, f.VJoyRy);
        Assert.Equal(0u, f.VJoyButtons);
        Assert.Equal(12000, f.X360LeftThumbX);
        Assert.Equal(0, f.X360RightTrigger);
        Assert.Equal(LinkState.Failsafe, rig.Snap.State);

        rig.AdvanceMs(2000);
        Assert.Equal(LinkState.Lost, rig.Snap.State);
        Assert.Equal(Mapping.VJoySteer(12000), rig.Last.VJoyX);

        rig.Send(phone.Next(0)); // link back
        Assert.Equal(Mapping.VJoyPedal(50000), rig.Last.VJoyY);
        Assert.Equal(LinkState.Live, rig.Snap.State);
    }

    [Fact]
    public void Snapshot_keeps_the_frame_the_newest_packet_produced_after_the_failsafe_released_it()
    {
        // The interop test checks the device frame of the final INPUT from the stats file, which is
        // written after the failsafe fired: AcceptedOutput must not follow the failsafe, Output must.
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 16);
        phone.State.Steer = -12345;
        phone.State.Throttle = 54321;
        phone.State.Brake = 777;
        phone.State.Clutch = 32768;
        phone.State.Handbrake = 65535;
        phone.State.Buttons = 0x00A5_0F01;
        rig.Send(phone.Next(0));
        OutputFrame applied = rig.Last;

        rig.AdvanceMs(250);
        HubSnapshot s = rig.Snap;
        Assert.Equal(LinkState.Failsafe, s.State);
        Assert.Equal(applied, s.AcceptedOutput);
        Assert.Equal(Mapping.VJoySteer(-12345), s.AcceptedOutput.VJoyX);
        Assert.Equal(Mapping.VJoyPedal(54321), s.AcceptedOutput.VJoyY);
        Assert.Equal(Mapping.VJoyPedal(65535), s.AcceptedOutput.VJoyRy);
        Assert.Equal(0xA50F_0100u, s.AcceptedOutput.VJoyButtons);
        Assert.Equal(1, s.Output.VJoyY);
        Assert.Equal(0u, s.Output.VJoyButtons);
        Assert.Equal(Mapping.VJoySteer(-12345), s.Output.VJoyX);

        rig.Engine.SetPairing(TestKeys.Other);
        Assert.Equal(default, rig.Snap.AcceptedOutput);
    }

    [Fact]
    public void Rule6_failsafe_lets_queued_pulses_finish()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 15);
        rig.Send(phone.Next(0));
        phone.Press(0, 4); // 4 presses take 60 + 3 * 100 = 360 ms, longer than the failsafe
        rig.Send(phone.Next(0));
        rig.AdvanceMs(600);
        Assert.Equal(LinkState.Failsafe, rig.Snap.State);
        Assert.Equal(4, rig.Output.RisingEdges(0));
        Assert.Equal(0u, rig.Last.VJoyButtons);
    }

    [Fact]
    public void Paused_phone_that_goes_quiet_is_reported_paused_with_steering_centred_until_lost()
    {
        // Found by the cross-implementation test: the phone sends PAUSED when it leaves its drive screen
        // and then stops its link. The state shown to the user must match the output (centred), which
        // "Failsafe" (steering held) did not.
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 17);
        phone.State.Steer = 20000;
        phone.State.Throttle = 60000;
        rig.Send(phone.Next(0));
        phone.State.Flags = Wire.FlagPaused;
        rig.Send(phone.Next(0));

        rig.AdvanceMs(600);
        Assert.Equal(LinkState.Paused, rig.Snap.State);
        Assert.Equal(16385, rig.Last.VJoyX);
        Assert.Equal(1, rig.Last.VJoyY);

        rig.AdvanceMs(1500);
        Assert.Equal(LinkState.Lost, rig.Snap.State);
        Assert.Equal(16385, rig.Last.VJoyX);
    }

    [Fact]
    public void Rule6_paused_centers_steering_releases_everything_and_pulses_finish()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 16);
        phone.State.Steer = 20000;
        phone.State.Throttle = 60000;
        phone.State.Buttons = 1;
        rig.Send(phone.Next(0));
        phone.Press(0);
        rig.Send(phone.Next(0));
        rig.AdvanceMs(10);

        phone.State.Flags = Wire.FlagPaused;
        rig.Send(phone.Next(0));
        OutputFrame f = rig.Last;
        Assert.Equal(16385, f.VJoyX); // centred
        Assert.Equal(1, f.VJoyY);
        Assert.Equal(1u, f.VJoyButtons); // the press already running continues, held button 0 is released
        Assert.Equal(0, f.X360LeftThumbX);
        Assert.Equal(LinkState.Paused, rig.Snap.State);

        rig.AdvanceMs(51);
        Assert.Equal(0u, rig.Last.VJoyButtons);
        Assert.Equal(1, rig.Output.RisingEdges(0));

        phone.State.Flags = 0;
        rig.Send(phone.Next(0));
        Assert.Equal(Mapping.VJoySteer(20000), rig.Last.VJoyX);
        Assert.Equal(1u << 8, rig.Last.VJoyButtons);
    }

    [Fact]
    public void Failsafe_threshold_follows_settings()
    {
        var rig = new Rig(new HubEngineOptions { FailsafeMs = 100 });
        var phone = new FakePhone(TestKeys.Main, epoch: 17);
        phone.State.Throttle = 65535;
        rig.Send(phone.Next(0));
        rig.AdvanceMs(99);
        Assert.Equal(32768, rig.Last.VJoyY);
        rig.AdvanceMs(1);
        Assert.Equal(1, rig.Last.VJoyY);
    }

    // ------------------------------------------------------------------ STATUS ---

    [Fact]
    public void Status_is_sent_at_20Hz_with_the_fields_of_section_6_and_stops_after_1s_of_silence()
    {
        var rig = new Rig();
        var sink = new FakeSink(TransportKind.Udp);
        var quiet = new FakeSink(TransportKind.Udp) { IsOpen = false };
        var phone = new FakePhone(TestKeys.Main, epoch: 0xCAFE);
        for (int ms = 0; ms < 1000; ms++)
        {
            if (ms % 2 == 0)
            {
                if (ms % 10 == 4) phone.Next(0); // lose one packet in five
                rig.Send(phone.Next(rig.Clock.NowUs), TransportKind.Udp, sink);
                rig.Send(phone.Next(rig.Clock.NowUs), TransportKind.Udp, quiet);
            }
            rig.AdvanceMs(1);
        }
        int count = sink.Received.Count;
        Assert.InRange(count, 19, 21);
        Assert.Empty(quiet.Received);

        Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(sink.Received[^1], TestKeys.Main.Auth, out StatusPacket st));
        HubSnapshot s = rig.Snap;
        Assert.Equal(0xCAFEu, st.Epoch);
        Assert.True(st.LastSeq <= s.LastSeq && st.LastSeq + 6 >= s.LastSeq);
        Assert.True(st.Accepted > 0);
        Assert.True(st.Missing > 0);
        Assert.InRange(st.HoldUs, 0u, 2000u); // packets arrive every 2 ms
        Assert.Equal(0, st.Output); // Recording output: kind none, healthy

        // Hold time and echo: a single packet, then a STATUS 30 ms later carries hold_us = 30 ms.
        var rig2 = new Rig();
        var sink2 = new FakeSink(TransportKind.Tcp);
        var p2 = new FakePhone(TestKeys.Main, epoch: 1);
        rig2.AdvanceMs(60); // STATUS slots at 1, 50, 100 ms: the first two pass with nobody to send to
        rig2.Send(p2.Next(123_456_789), TransportKind.Tcp, sink2);
        rig2.AdvanceMs(39);
        Assert.Empty(sink2.Received);
        rig2.AdvanceMs(1);
        Assert.Single(sink2.Received);
        Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(sink2.Received[0], TestKeys.Main.Auth, out StatusPacket s2));
        Assert.Equal(123_456_789u, s2.EchoTimeUs);
        Assert.Equal(1u, s2.LastSeq);
        Assert.Equal(1u, s2.Accepted);
        Assert.Equal(40_000u, s2.HoldUs); // received at 60 ms, reported at 100 ms

        // Silence: after one second without an accepted packet the endpoint gets nothing more.
        rig.AdvanceMs(1000);
        int after = sink.Received.Count;
        rig.AdvanceMs(1000);
        Assert.Equal(after, sink.Received.Count);
    }

    [Fact]
    public void Status_output_byte_carries_kind_and_error_bit()
    {
        var clock = new ManualClock();
        var output = new RecordingOutput(clock) { Kind = OutputKind.VJoy, State = OutputState.Unavailable };
        var engine = new HubEngine(TestKeys.Main, output, null, clock);
        var sink = new FakeSink(TransportKind.Udp);
        var phone = new FakePhone(TestKeys.Main, epoch: 1);
        engine.Receive(TransportKind.Udp, phone.Next(0), sink, clock.GetTimestamp());
        clock.AdvanceMs(1);
        engine.Tick();
        Assert.Equal(DecodeResult.Ok, StatusPacket.TryDecode(sink.Received[0], TestKeys.Main.Auth, out StatusPacket st));
        Assert.Equal(0x81, st.Output);
    }

    // ------------------------------------------------------------ configuration ---

    [Fact]
    public void New_pairing_key_resets_the_session_and_rejects_the_old_key()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 1);
        phone.State.Throttle = 65535;
        rig.Send(phone.Next(0));
        rig.Engine.SetPairing(TestKeys.Other);
        Assert.False(rig.Snap.HasEpoch);
        Assert.Equal(1, rig.Last.VJoyY);
        Assert.Equal(ReceiveOutcome.BadTag, rig.Send(phone.Next(0)));
        var paired = new FakePhone(TestKeys.Other, epoch: 2);
        Assert.Equal(ReceiveOutcome.AcceptedNewEpoch, rig.Send(paired.Next(0)));
    }

    [Fact]
    public void Output_swap_neutralizes_the_old_device_and_feeds_the_new_one()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 1);
        phone.State.Brake = 65535;
        rig.Send(phone.Next(0));
        var next = new RecordingOutput(rig.Clock);
        IOutputDevice old = rig.Engine.SetOutput(next);
        Assert.Same(rig.Output, old);
        Assert.Equal(1, rig.Output.NeutralCalls);
        Assert.Equal(32768, next.Last!.Value.VJoyZ);
    }

    [Fact]
    public void Inversion_setting_applies_to_the_next_frame()
    {
        var rig = new Rig();
        var phone = new FakePhone(TestKeys.Main, epoch: 1);
        phone.State.Steer = -32767;
        rig.Send(phone.Next(0));
        Assert.Equal(1, rig.Last.VJoyX);
        rig.Engine.UpdateOptions(new HubEngineOptions { Invert = AxisInvert.Steer | AxisInvert.Throttle });
        Assert.Equal(32768, rig.Last.VJoyX);
        Assert.Equal(32768, rig.Last.VJoyY);
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
    public void Receive_and_tick_paths_do_not_allocate()
    {
        var clock = new ManualClock();
        var engine = new HubEngine(TestKeys.Main, new NullOutput(), null, clock);
        var phone = new FakePhone(TestKeys.Main, epoch: 77);
        var sink = new NullSink();
        var packets = new byte[4000][];
        for (int i = 0; i < packets.Length; i++)
        {
            if (i % 50 == 0) phone.Press(i % 8);
            phone.State.Steer = (short)(i * 7 % 30000);
            packets[i] = phone.Next(i * 2000L);
        }

        void Run(int from, int to)
        {
            for (int i = from; i < to; i++)
            {
                engine.Receive(TransportKind.Udp, packets[i], sink, clock.GetTimestamp());
                engine.Receive(TransportKind.Tcp, packets[i], sink, clock.GetTimestamp()); // duplicate path
                clock.AdvanceUs(1000);
                engine.Tick();
                clock.AdvanceUs(1000);
                engine.Tick();
            }
        }

        Run(0, 1000); // warm up: JIT, tiering, first-use statics
        long before = GC.GetAllocatedBytesForCurrentThread();
        Run(1000, 4000);
        long allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.Equal(0, allocated);
        Assert.True(sink.Count > 0);
    }

    // ---------------------------------------------------------------- helpers ---

    private static List<(long Time, bool Down)> Transitions(IReadOnlyList<(long Timestamp, OutputFrame Frame)> frames, int bit)
    {
        var list = new List<(long, bool)>();
        bool state = false;
        foreach ((long ts, OutputFrame f) in frames)
        {
            bool down = (f.VJoyButtons & (1u << bit)) != 0;
            if (down != state) list.Add((ts, down));
            state = down;
        }
        return list;
    }

    private static void AssertPressTiming(IReadOnlyList<(long Timestamp, OutputFrame Frame)> frames, int bit)
    {
        var t = Transitions(frames, bit);
        Assert.True(t.Count % 2 == 0, "every press must be released");
        for (int i = 0; i + 1 < t.Count; i += 2)
        {
            Assert.True(t[i].Down && !t[i + 1].Down);
            Assert.InRange(t[i + 1].Time - t[i].Time, 60_000, 61_000);
            if (i + 2 < t.Count) Assert.True(t[i + 2].Time - t[i + 1].Time >= 40_000, "gap between presses is at least 40 ms");
        }
    }
}
