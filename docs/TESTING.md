# Slipstream testing record

Date: 2026-09-24. Version 0.1.0. Every result below comes from a command run on this date; nothing is
carried over from an earlier session. The last section lists what has **not** been tested.

## Machine and toolchain

| Item | Value |
|---|---|
| Machine | Apple M4, macOS 26.3 (build 25D125), arm64 |
| .NET | SDK 8.0.425, runtime Microsoft.NETCore.App 8.0.31 |
| Java | OpenJDK 17.0.20.1 (Homebrew openjdk@17) |
| Android build | Gradle wrapper 8.11.1, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compileSdk and targetSdk 35, build-tools 35.0.0 |
| Python | 3.14.3 (for `tools/interop.sh` port picking and its verdict) |

Environment used for every command:

```sh
export DOTNET_ROOT="$HOME/.dotnet"; export PATH="$DOTNET_ROOT:$PATH"; export DOTNET_CLI_TELEMETRY_OPTOUT=1
export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

## 1. Windows hub (`windows/`)

```sh
cd windows
dotnet build Slipstream.sln -c Release --no-incremental
dotnet test Slipstream.sln -c Release --no-build        # run three times
```

| Check | Result |
|---|---|
| Build | 0 warnings, 0 errors |
| xUnit tests | **134 passed, 0 failed, 0 skipped**, in each of 3 consecutive runs |

The 134 include two tests added today: `Snapshot_keeps_the_frame_the_newest_packet_produced_after_the_failsafe_released_it`
and `Paused_phone_that_goes_quiet_is_reported_paused_with_steering_centred_until_lost` (see section 4).

```sh
dotnet publish src/Slipstream.Hub -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o artifacts/hub
```

| Check | Result |
|---|---|
| Output | `artifacts/hub/SlipstreamHub.exe`, 66,221,647 bytes |
| `file` | PE32+ executable (GUI) x86-64, for MS Windows |
| Bundled runtime config | `System.Globalization.Invariant: false` |
| sha256 | `37b0004d1577bcdb5ddf025141e59276604becba18a2838d209f7d813ef2fc30` |

## 2. Android app (`android/`)

```sh
cd android
./gradlew clean testDebugUnitTest assembleDebug lintDebug --no-build-cache
```

| Check | Result |
|---|---|
| Build | BUILD SUCCESSFUL |
| JVM unit tests | **101 tests: 96 passed, 5 skipped, 0 failures, 0 errors**. The 5 skipped are `InteropTest`, which skips itself unless `SLIPSTREAM_INTEROP_PORT` is set; it runs in section 3. |
| Lint | 0 errors, 2 warnings (both `GradleDependency`: newer androidx versions need compileSdk 36) |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, 2,669,621 bytes |
| `apksigner verify` | Verifies. APK Signature Scheme v2, 1 signer, certificate `C=US, O=Android, CN=Android Debug` |
| `aapt2 dump badging` | package `com.slipstream.wheel`, versionName 0.1.0, versionCode 1, minSdk 26, targetSdk 35 |
| sha256 | `50ae629527ba68de0499caf8077cf6503894581a7b4aea65d8718e709fec992c` |

## 3. Cross-implementation wire test (`tools/interop.sh`)

```sh
tools/interop.sh      # run three times; exit code 0 each time
```

What it does: builds the headless hub (`windows/tools/Slipstream.Cli`, Release), picks free ports,
and runs `android/app/src/test/java/com/slipstream/wheel/InteropTest.kt` with Gradle (`--rerun
--no-build-cache`). Each run starts its own `slipstream hub` process (`--no-adb`, `--exit-idle-ms
600`, `--stats-json`), so the hub writes its own counters and exits once the phone side goes quiet.
The phone side is the app's real code: `InputPacketWriter` builds every INPUT, `Framing` frames it,
`StatusDecoder` and `FrameAssembler` validate every STATUS (header, length, tag), `RttMath` computes
the round trip, `LinkStats` computes loss, and runs e and f drive the real `LinkEngine` with its
`UdpTransport` and `AdbTcpTransport`. Expected device output is computed in the test from the
section 10 formulas, independently of the hub's code. The script fails if any test fails **or is
skipped**, or if a run leaves no result file.

### Runs and assertions

| Run | Traffic | Asserted from the hub's stats JSON and from the STATUS stream |
|---|---|---|
| a, b: UDP | 2000 INPUT at 500 Hz. 400 (exactly 20 %) never sent, from a fixed seed plus 4 forced drops on shift packets. 15 shift-ups, including a double press whose first packet is dropped (+2 in one packet) and a counter that wraps 255 to 0. Channel 2 jumps by 140 (a reset: no press). 10 late replays, 5 tampered copies, 3 wrong-length or wrong-version datagrams. | accepted 1600, missing 400, last_seq 2000; udp packets 1610, first arrivals 1600, duplicates 10, bad tags 5, malformed 3; presses `[15,0,0,0,0,0,0,0]`, nothing queued; the last INPUT decoded field by field (epoch, seq, t_us, all axes, buttons, 8 counters, flags, rtt_100us); vJoy and Xbox 360 values of the final state; failsafe frame after 200 ms (steering held, pedals and buttons released). Every STATUS: our epoch, echo_t_us equal to the t_us of packet last_seq, accepted and missing equal to the send log at that seq, output byte 0, RTT 0 to 50 ms, last_seq never going back, hold under 100 ms while sending, median interval 40 to 60 ms. `LinkStats` loss exactly 0.2. |
| c: framed TCP | 1000 INPUT at 500 Hz, 200 never sent, 15 shift-ups, 5 replays, 3 tampered. Frames split on the wire (inside the length header, right after it, mid body) and two frames coalesced in one write. Finally a 53 byte frame. | accepted 800, missing 200; tcp packets 805, first 800, duplicates 5, bad tags 3, malformed 1; the hub closes the connection on the 53 byte frame (section 8); same frame, STATUS and loss checks as run a. |
| d: multipath | 1000 INPUT, every one on UDP and on TCP; the TCP copy trails by 10 packets (20 ms). MULTIPATH flag set. | accepted 1000, missing 0; each path 1000 packets; first arrivals sum to 1000 and duplicates sum to 1000, at least 99 % of duplicates on the slower path (TCP); presses exactly 15 (no double presses); STATUS arrives on both paths and passes the checks on each. |
| e: real LinkEngine | The app's `LinkEngine` in multipath (UDP and TCP to 127.0.0.1) at 500 Hz, final state set in `ControllerState`, 15 `pulse(SHIFT_UP)` calls 120 ms apart, then `stop()`. | Both paths turn LIVE in `LinkStats` on hub STATUS; smoothed RTT fed back into INPUT; hub accepted equals packets built, missing 0; presses 15; last INPUT carries PAUSED and MULTIPATH with the full state; hub state paused, output neutral with steering centred. |
| f: beacon discovery | Hub with beacon on a test port. The app's `Beacon.decode`, `BeaconGate` and fingerprint match pick the hub; then `LinkEngine` Wi-Fi only to the beacon's source IP (192.168.0.15 here, not loopback). | At least 2 beacons, 1 s apart, fingerprint `e3ca54042d049dba` (the test-vector key), udp_port and tcp_port equal to the hub's bound ports; the Wi-Fi path turns LIVE; accepted equals packets built, missing 0, one press. |

### Results of the three runs

All three runs: **5 of 5 tests passed, exit code 0.** The counts are deterministic and were identical
in every run:

| Run | Built | Sent | Hub accepted | Hub missing | Hub loss | Presses (channels 0..7) | Hub duplicates | Bad tags | Malformed |
|---|---|---|---|---|---|---|---|---|---|
| a UDP | 2000 | 1600 | 1600 | 400 | 20 % | 15 0 0 0 0 0 0 0 | udp 10 | 5 | 3 |
| c TCP | 1000 | 800 | 800 | 200 | 20 % | 15 0 0 0 0 0 0 0 | tcp 5 | 3 | 1 |
| d multipath | 1000 | 1000 on each path | 1000 | 0 | 0 % | 15 0 0 0 0 0 0 0 | udp 0, tcp 1000 | 0 | 0 |

Timing, which varies from run to run (loopback on one Mac, so these measure the software path, not a
network):

| Run | STATUS received | Median STATUS interval | RTT min | RTT p50 | RTT avg | RTT max |
|---|---|---|---|---|---|---|
| a UDP (runs 1, 2, 3) | 93, 93, 93 | 50.07, 50.05, 50.07 ms | 0.206, 0.216, 0.270 ms | 0.431, 0.480, 0.439 ms | 0.629, 0.529, 0.492 ms | 3.116, 1.521, 1.426 ms |
| c TCP | 41, 41, 41 | 50.08, 50.03, 50.02 ms | 0.191, 0.235, 0.257 ms | 0.323, 0.373, 0.365 ms | 0.403, 0.448, 0.456 ms | 1.549, 1.723, 1.539 ms |
| d multipath, UDP path | 53, 52, 53 | 50.08, 50.04, 50.05 ms | 0.221, 0.234, 0.163 ms | 0.364, 0.460, 0.405 ms | 0.450, 0.510, 0.466 ms | 1.039, 0.845, 0.910 ms |
| d multipath, TCP path | 60, 60, 60 | 50.09, 50.04, 50.06 ms | 0.237, 0.248, 0.172 ms | 0.396, 0.509, 0.425 ms | 0.513, 0.561, 0.508 ms | 2.520, 1.868, 2.071 ms |

Largest hold_us while INPUT was flowing (run a): 7.7, 7.7 and 6.1 ms, which is the longest random run
of never-sent packets. Run c wrote 6 split frames and 4 coalesced writes per run (planned split and
coalesce points that fell on never-sent packets do not produce a write).

| Run | Packets built | Hub accepted, missing | First arrivals UDP / TCP | Phone smoothed RTT |
|---|---|---|---|---|
| e real LinkEngine (runs 1, 2, 3) | 929, 935, 927 | all accepted, 0 missing | 927/2, 929/6, 923/4 | 0.4 ms each run |
| f beacon then Wi-Fi | 166, 181, 183 | all accepted, 0 missing | UDP only | 3.58, 2.76, 2.19 ms |

In run f, 5 beacons arrived in each run and the gate admitted 3 (one per source per 900 ms).

### Proof that the test can fail

Mutation check, run once: the hub's pulse rule (section 9 rule 4) was changed to count deltas of 128
and more as presses (`windows/src/Slipstream.Core/Protocol/SeqMath.cs`). `tools/interop.sh` then
exited with code 1: runs a, c and d failed with `no presses on channel 2: [15,0,24,0,0,0,0,0]` and
`[15,0,15,0,0,0,0,0]`. Run e passed, as expected (it has no reset jump). The file was restored from
a copy, checked identical with `diff`, and the solution rebuilt before the runs above.

## 4. Defects found today by the wire test, and fixed

1. **Hub, wrong state label after the phone pauses.** The phone sends PAUSED when it leaves its drive
   screen (`DriveActivity.onStop`) and then stops its link. After 200 ms the hub snapshot said
   **Failsafe**, which the hub window shows as "Signal lost ... steering held", while the device
   actually showed the PAUSED output with steering centred. Run e caught it (`expected paused but was
   failsafe`). Fixed in `windows/src/Slipstream.Core/Link/HubEngine.cs` (PAUSED is checked before the
   failsafe threshold; Lost after 2 s is unchanged). Pinned by the hub test
   `Paused_phone_that_goes_quiet_is_reported_paused_with_steering_centred_until_lost`.
2. **Test support added to the hub, not a behaviour change:** the engine keeps the frame written to the
   device when the newest packet was applied (`HubSnapshot.AcceptedOutput`), and the CLI hub gained
   `--exit-idle-ms N`, `exit_reason`, `last_input`, `applied_frame` and `pulse_pending` in its stats
   JSON. Without these, the final device frame could only be read after the failsafe had already
   released it.

## 5. What has NOT been tested

Nothing below has run. Treat each item as unverified until it has.

- **A real Windows PC.** The WPF hub window, tray icon and menu, QR rendering, the **Allow through
  firewall** helper (netsh with administrator rights), the first-run firewall prompt, MMCSS thread
  registration, power-throttling opt-out, `timeBeginPeriod(1)`, single-instance mutex, the published
  exe starting at all. All hub tests and the interop test ran on macOS against `Slipstream.Core` and
  the headless CLI, which uses a console output instead of a game controller.
- **Real vJoy and ViGEmBus.** No vJoy driver, `vJoyInterface.dll` P/Invoke, device 1 configuration
  check, or ViGEm Xbox 360 pad was exercised. The vJoy and Xbox 360 values were checked only as
  numbers in the hub's output frame.
- **A game.** No game has read the virtual controller; rumble and force feedback from a game back to
  the phone were never produced.
- **A real phone.** The app has not been installed or started on any phone or emulator. Unverified:
  the rotation-vector steering and its fallbacks on real sensors, the One Euro filter on real noise,
  touch delivery and unbuffered dispatch, volume-key capture, haptics, the camera QR scanner, the
  connect and settings screens, orientation handling, the Wi-Fi low-latency lock, DSCP EF on a real
  radio, `NetworkBinder` network callbacks, the multicast lock for beacons, and Android's JIT or ART
  timing (the tests ran on a desktop JVM).
- **A real network.** Every interop run used loopback or the Mac's own LAN address; no packet crossed
  Wi-Fi radio, a router, an access point, the phone's hotspot or USB tethering. The loss in the tests
  was deliberate (packets not sent), not real radio loss or jitter.
- **USB through adb.** `adb reverse` with a real phone, the hub's adb polling, device authorization,
  and the phone's 127.0.0.1 connection through the tunnel were not tested. The TCP path was tested by
  connecting to the hub's loopback port directly.
- **Bluetooth HID mode with Windows.** Pairing the phone as a Bluetooth gamepad, the report descriptor
  as Windows parses it, and report delivery were not tested; only the report bytes are unit-tested.
- **Two phones.** Epoch takeover between two real phones is covered only by hub unit tests.
- **Long runs and load.** No run was longer than about 5 s. Behaviour over hours, sequence-number wrap
  in live traffic, and latency while a game loads the CPU are untested.
- **Multi-homed PCs.** Replies from a different source address after a route change (a known gap in
  the Windows review) were not exercised.
- **Release signing.** Only the debug APK exists; it is signed with the Android debug key. The hub exe
  is not code-signed.
