# Slipstream testing record

One dated record per version, newest first. Every result in a record comes from a command run on that
record's date; nothing is carried over from an earlier session. Each record ends with what has **not**
been tested.

- [0.2.0, 2026-09-26](#020-2026-09-26): controller mode (PAD, PROTOCOL.md section 12)
- [0.1.0, 2026-09-24](#010-2026-09-24): wheel

## 0.2.0, 2026-09-26

Version 0.2.0 adds controller mode (PROTOCOL.md section 12, ARCHITECTURE.md section 7). Every result in
this record comes from a command run on 2026-09-26. Its last subsection lists what has **not** been tested.

### Machine and toolchain

The same as in the 0.1.0 record: Apple M4, macOS 26.3 (build 25D125), arm64; .NET SDK 8.0.425 with
runtime Microsoft.NETCore.App 8.0.31; OpenJDK 17.0.20.1 (Homebrew openjdk@17); Gradle wrapper 8.11.1,
Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compileSdk and targetSdk 35, build-tools 35.0.0; Python 3.14.3.
The environment block of the 0.1.0 record was used for every command.

### 1. Windows hub (`windows/`)

```sh
cd windows
dotnet build Slipstream.sln -c Release --no-incremental
dotnet test Slipstream.sln -c Release --no-build        # once at the start, three times at the end
dotnet publish src/Slipstream.Hub -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o artifacts/hub
```

| Check | Result |
|---|---|
| Build | 0 warnings, 0 errors |
| xUnit tests | **226 passed, 0 failed, 0 skipped**, in each of 4 runs |
| Output | `artifacts/hub/SlipstreamHub.exe`, 66,252,159 bytes |
| `file` | PE32+ executable (GUI) x86-64, for MS Windows |
| Version | ProductVersion `0.2.0+6300b2053d5f...`, FileVersion `0.2.0.0` (read from the bundled `SlipstreamHub.dll`) |
| sha256 | `242061207ccd39d857882545c369b05ee6dc6f64e29e0ce1df50ee7b405a0a21`, byte-identical to the exe published earlier the same day at the end of the hub review: the build is deterministic |

No Windows source file was changed in this session.

### 2. Android app (`android/`)

```sh
cd android
./gradlew clean testDebugUnitTest assembleDebug lintDebug --no-build-cache
```

The first run failed: 1 of 175 tests, `PadLinkTest.sourceSwitchKeepsEpochAndSequenceAndNeverFlipsWithin300Ms`,
with `PAD lasted at least 300 ms, got 298818 us`. That was a real defect in `LinkEngine`, fixed and pinned
(section 5). The same command after the fix:

| Check | Result |
|---|---|
| Build | BUILD SUCCESSFUL |
| JVM unit tests | **176 tests: 165 passed, 11 skipped, 0 failures, 0 errors**. The 11 skipped are `InteropTest`, which skips itself unless `SLIPSTREAM_INTEROP_PORT` is set; it runs in section 3. |
| Lint | 0 errors, 2 warnings (both `GradleDependency`, the same two as in 0.1.0) |
| APK | `app/build/outputs/apk/debug/app-debug.apk`, 2,807,193 bytes |
| `apksigner verify` | Verifies. APK Signature Scheme v2, 1 signer, `C=US, O=Android, CN=Android Debug`, certificate SHA-256 `f8fb810a2ec7563db0fb88d1331686a6a258b6c756fb1d2eee449e510b3902bc`, the same certificate as the 0.1.0 APK, so 0.2.0 installs over 0.1.0 as an update |
| `aapt2 dump badging` | package `com.slipstream.wheel`, versionName 0.2.0, versionCode 2, minSdk 26, targetSdk 35 |
| sha256 | `4c933eed4f07948300f91472261f896f0e6655d62b9386c121c3e7d5ee6469af` |

Extra runs around the fix:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.slipstream.wheel.PadLinkTest' --rerun          # 15 times
./gradlew :app:testDebugUnitTest --tests 'com.slipstream.wheel.PadSourceHoldTest' --rerun    # 10 times, then 10 without the fix
```

`PadLinkTest` (4 tests): 15 of 15 runs passed. `PadSourceHoldTest` (new): 10 of 10 passed with the fix; with
the fix taken out it failed 10 of 10 (PAD spans of 298,844 to 299,765 us). The file was then restored from a
copy and checked identical with `diff`.

### 3. Cross-implementation wire test (`tools/interop.sh`)

```sh
tools/interop.sh      # run three times; exit code 0 each time
```

The script and `InteropTest` now run 11 tests: the five wheel tests of 0.1.0 (runs a to f, sending and
asserting the same as before) and six controller-mode tests. The phone side is the app's real code: every PAD packet is built from a real
`PadState` (held bits and 4-bit tap counters from `TapCounters`, as the play screen makes them) by the real
`PadPacketWriter`, framed by `Framing`, and the last packet of each scripted run is read back with
`PadPacketReader`; STATUS is checked by `StatusDecoder` and `FrameAssembler`, RTT by `RttMath`, loss by
`LinkStats`; runs pad_d and pad_e1 drive the real `LinkEngine` with `UdpTransport` and `AdbTcpTransport`.
Each run starts its own headless hub (`slipstream hub --no-adb --exit-idle-ms 600 --stats-json`; pad_e2
adds `--pad-output x360`), which counts the presses its pad showed per canonical button (released to
pressed edges of the pad's button output) and reports which pad it would plug in. It has no ViGEm: its
pad only counts frames. The expected Xbox 360 and DualShock 4 values are computed in the test from the
PROTOCOL.md 12.5 formulas and the two controllers' report layouts, independently of the hub's code. The
script fails if any test fails **or is skipped**, or if a run leaves no result file.

#### Controller-mode runs and assertions

| Run | Traffic | Asserted from the hub's stats JSON and the STATUS stream |
|---|---|---|
| pad_a: UDP, PlayStation | 2000 PAD at 500 Hz. 400 (exactly 20 %) never sent: 25 forced plus a seeded pick; 17 planned seqs, the first and the last are always sent. **12 short Cross taps**, each press and each release in its own packet: a press lost with every packet that held it (the release packet carries it), a lost release, a one-packet tap, a whole tap lost, a press lost but still held in the next packet, a double tap lost whole (+2 in one packet), and a press made while the previous one still shows and released during the gap. Counters before the first packet: Cross 7 (the 12 taps wrap it past 15), Circle 9, Triangle 14. Square +8 in one packet, released (a reset, no press). 5 late copies of press packets, 5 tampered copies (Cross counter bit flipped), 5 malformed datagrams (75 and 77 bytes, version 2, INPUT type at 76 bytes, PAD type at 52 bytes). Final state: a stick set past full left, L2 at 2047 and R2 at 2048 (either side of the digital threshold), one finger down, one lifted, motion. | accepted 1600, missing 400, last_seq 2000, one epoch; udp packets 1605, first arrivals 1600, duplicates 5, bad tags 5, malformed 5; presses: **Cross exactly 12, every other button 0**, nothing queued; exactly 5 taps replayed by the hub; no INPUT applied, no pulses; mode controller, style ps; pad output auto, DualShock 4 wanted and plugged in; the last PAD decoded field by field (all 18 tap counters, touch, motion; the stick arrives as -32767); the pad frame applied with the last packet equals the 12.5 mapping of the final state (Xbox 360 half and DualShock 4 half: sticks, Y inversion, triggers, digital L2 / R2, button word, hat, PS and touchpad byte, touch scaled to 1919 x 942 with the active-low finger bit, motion signs); 200 ms after the last packet the hub is in failsafe and the frame is neutral with sticks centred. Every STATUS: our epoch, echo_t_us, accepted and missing equal to the send log, output byte 0 until the pad is plugged in and 3 after it, RTT 0 to 50 ms. |
| pad_b: framed TCP, Xbox | The same packets and loss pattern in the Xbox layout (no touch, no motion). Before the first packet A 3, Y 15, LB 1; Share (bit 17, the high nibble of byte 40) +8 in one packet. Frames split inside and after the length header and mid body, and two frames coalesced in one write; 2 frames of a legal length with bad content (76 bytes of version 2, 52 bytes of PAD type); finally a 77 byte frame. | As pad_a with **A exactly 12**, pad output Xbox 360 (STATUS output byte 2); tcp packets 1605, duplicates 5, bad tags 5, malformed 3; the hub closes the connection on the 77 byte frame (section 8). |
| pad_c: multipath | 1000 PAD, every one on UDP and on TCP, the TCP copy 10 packets (20 ms) behind; MULTIPATH set. 13 packets never sent on UDP (a press, a release, a whole tap, a whole double tap), so their only copy arrives late on TCP, after newer UDP packets. 12 Cross taps; taps 10 to 12 arrive while the double tap's replay still runs and are appended to it. | **Cross exactly 12, 0 elsewhere**, exactly 6 replayed; accepted + missing = 1000; udp packets 987, tcp packets 1000; first arrivals equal accepted; every other copy a duplicate, at least 99 % of TCP copies; final frame mapping; STATUS on both paths passes the checks, with `accepted` between the UDP send log and last_seq. |
| pad_d: mode switch | The real `LinkEngine`, multipath (UDP and TCP to 127.0.0.1), starting as INPUT with the shift-up counter at 3. 2 shift-ups. 2 Cross taps on the phone while INPUT is on the wire. Switch to PAD (PlayStation; the Cross counter is 7 by then). 3 Cross taps, and 4 shift-ups on the phone while PAD is on the wire; L1 held, a stick and a trigger deflected. Switch back to INPUT with new steering. 2 shift-ups. `stop()`. | One epoch for the whole run; accepted equals packets built, missing 0; **shift-up presses exactly 4** (the 4 made during PAD are a baseline, 12.4 rule 2); **taps Cross 3, L1 1**, every other button 0; STATUS output byte 0, then 3, then 0; the last INPUT carries PAUSED, MULTIPATH, the new steering and counter 11 (the wheel is accepted after the switch back); the last PAD carried Cross counter 10 and L1; the pad frame is neutral after the switch back and the pad stays plugged in; the engine kept 300 ms between the two switches. |
| pad_e1: PAUSED | The real `LinkEngine`, Wi-Fi UDP, PlayStation with motion. 2 Cross taps; L1 held, a stick, a trigger and a finger down; PAUSED set while all of it is held, for 600 ms; everything released (as the pause menu does), resume, 1 Cross tap; R1 held, the other stick, the other trigger and a finger; PAUSED; `stop()`. | While paused the phone repeated at its paused rate (23 to 25 packets in 600 ms) and every STATUS during the pause showed a hold under 100 ms (the 200 ms failsafe was never reached); state paused; **taps Cross 3, L1 1, R1 1**; the last PAD carried PAUSED, MOTION and STYLE_PS with R1 held, the stick, trigger, finger and gyro; the pad frame, now and when the last packet was applied, neutral (12.4 rule 4). |
| pad_e2: failsafe | Scripted UDP, PlayStation, hub forced to Xbox 360 output. 600 PAD, 60 never sent (10 forced plus a seeded pick). From seq 400: 12 buttons held (L1, R1, Triangle, Create, Options, PS, Touchpad, D-pad up, left and right, Mute, Share), sticks at their ends, triggers, two fingers, motion. 3 Cross taps inside the last 10 packets, none of which is sent; then silence. | **Cross 4** (the 3 lost taps replayed after the last packet, running on past the failsafe trip: schedules finish), each held button exactly 1, the rest 0; pad output Xbox 360 although the phone is PlayStation (12.4 rule 5); the applied frame equals the mapping of the held buttons plus the first replayed Cross (hat north, since left and right cancel; Touchpad, Mute and Share absent from the Xbox 360 buttons, Mute and Share from the DualShock 4 ones); then failsafe, frame neutral with sticks centred. |

#### Results of the three runs

All three runs: **11 of 11 tests passed, exit code 0.** These counts are deterministic and were identical in
every run:

| Run | Built | Sent | Hub accepted | Hub missing | Presses the pad showed | Replayed by the hub | Duplicates | Bad tags | Malformed | Pad (STATUS byte) |
|---|---|---|---|---|---|---|---|---|---|---|
| pad_a UDP | 2000 | 1600 | 1600 | 400 | Cross 12, others 0 | 5 | udp 5 | 5 | 5 | DualShock 4 (3) |
| pad_b TCP | 2000 | 1600 | 1600 | 400 | A 12, others 0 | 5 | tcp 5 | 5 | 3 | Xbox 360 (2) |
| pad_c multipath | 1000 | 1000 on TCP, 987 on UDP | 987 | 13 | Cross 12, others 0 | 6 | udp 0, tcp 1000 | 0 | 0 | DualShock 4 (3) |
| pad_e2 failsafe | 600 | 540 | 540 | 60 | Cross 4, the 12 held buttons 1 each, others 0 | 3 | 0 | 0 | 0 | Xbox 360, forced (2) |
| a UDP (wheel) | 2000 | 1600 | 1600 | 400 | shift-up 15, other channels 0 | | udp 10 | 5 | 3 | none (0) |
| c TCP (wheel) | 1000 | 800 | 800 | 200 | shift-up 15 | | tcp 5 | 3 | 1 | none (0) |
| d multipath (wheel) | 1000 | 1000 on each path | 1000 | 0 | shift-up 15 | | udp 0, tcp 1000 | 0 | 0 | none (0) |

In pad_b the planned splits and coalesced writes that fell on never-sent packets produced no write: 9 split
frames and 3 coalesced writes per run.

The runs with the real `LinkEngine` depend on timing:

| Run (runs 1, 2, 3) | Packets built | Hub | First arrivals UDP / TCP | Other results |
|---|---|---|---|---|
| pad_d mode switch | 585, 583, 568 | all accepted, 0 missing, 1 epoch | 582/3, 580/3, 566/2 | presses 4, taps Cross 3 and L1 1, STATUS output 0, 3, 0 in every run |
| pad_e1 PAUSED | 260, 255, 255 | all accepted, 0 missing | UDP only | 23, 25, 25 packets in the 600 ms pause; 11 STATUS during it; largest hold 26.9, 28.1, 21.2 ms |
| e real LinkEngine (wheel) | 822, 829, 814 | all accepted, 0 missing | 822/0, 828/1, 813/1 | 15 presses, phone smoothed RTT 0.4, 0.3, 0.4 ms |
| f beacon then Wi-Fi (wheel) | 159, 157, 157 | all accepted, 0 missing | UDP only | 5 beacons received and 3 admitted each run, from 192.168.0.15 |

STATUS timing of the scripted controller runs (loopback on one Mac, so these measure the software path):

| Run (runs 1, 2, 3) | STATUS received | Median interval | RTT min | RTT p50 | RTT max |
|---|---|---|---|---|---|
| pad_a UDP | 92, 92, 93 | 50.23, 50.24, 50.27 ms | 0.192, 0.205, 0.206 ms | 0.358, 0.346, 0.336 ms | 0.688, 0.688, 0.768 ms |
| pad_b TCP | 81, 81, 81 | 50.31, 50.24, 50.30 ms | 0.186, 0.155, 0.159 ms | 0.265, 0.302, 0.303 ms | 0.747, 0.794, 0.912 ms |
| pad_c, UDP path | 53, 53, 52 | 50.20, 50.21, 50.31 ms | 0.234, 0.226, 0.231 ms | 0.377, 0.334, 0.349 ms | 1.142, 0.726, 0.732 ms |
| pad_c, TCP path | 60, 60, 60 | 50.21, 50.20, 50.20 ms | 0.253, 0.205, 0.274 ms | 0.415, 0.373, 0.388 ms | 2.731, 2.474, 2.016 ms |
| pad_e2 UDP | 37, 37, 37 | 50.28, 50.22, 50.19 ms | 0.220, 0.251, 0.210 ms | 0.341, 0.455, 0.367 ms | 0.821, 0.661, 0.699 ms |

#### Proof that the controller runs can fail

Two mutations of the hub, one at a time, each followed by a full `tools/interop.sh` run, then the file was
restored from a copy and checked identical with `diff` (the script rebuilds the hub on every run):

1. Tap delta accepts 1 to 15 instead of 1 to 7 (`windows/src/Slipstream.Core/Protocol/SeqMath.cs`, `TapDelta`).
   Exit code 1. pad_a failed with `presses on button 2 (Square / X) ... expected:<0> but was:<8>` and pad_b
   with `presses on button 17 (Share) ... expected:<0> but was:<8>`. The other 9 tests passed.
2. The replay ignores the held bit, `replay = d` (`windows/src/Slipstream.Core/Link/TapScheduler.cs`, `Plan`).
   Exit code 1. All six controller runs failed on the exact press count: Cross 19 instead of 12 (pad_a,
   pad_b), 14 instead of 12 (pad_c), 5 instead of 3 (pad_d, pad_e1), 5 instead of 4 (pad_e2). The wheel runs
   passed.

### 4. Other checks

The controller line of the README's simulator example, against a headless hub on the default ports:

```sh
dotnet tools/Slipstream.Cli/bin/Release/net8.0/slipstream.dll hub --code SLIP-STRE-AMTE-ST22 --no-adb --no-beacon \
  --no-qr --exit-idle-ms 800 --seconds 40 --stats-json <scratch>/readme-sim.json &
dotnet run --project tools/Slipstream.Cli -c Release -- sim --code SLIP-STRE-AMTE-ST22 --pad --style ps --loss 0.2 --taps 12
```

The sim sent 12 taps in 2500 packets with 516 dropped at random; the hub counted Cross 12, a DualShock 4
plugged in, 1984 accepted and 515 missing, and exited idle.

### 5. Defect found today, and fixed

**Phone, controller-mode source switch timed from the decision instead of the wire.** `LinkEngine` keeps
at least 300 ms between two switches of the packet source (wheel INPUT or controller PAD), so that one link
never mixes the two types within 300 ms (its own documentation, PROTOCOL.md section 12). It timed the hold
from the moment it decided a switch. When the switch is decided right after a packet, the first packet of
the new type waits out the 1 ms minimum spacing, so the packets of that type covered about 299 ms on the
wire. The first clean Android build of the day caught it (298,818 us). Fixed in
`android/app/src/main/java/com/slipstream/wheel/link/LinkEngine.kt`: the hold now counts from the first
packet built with the new source (sender thread only, no allocation; the wheel never switches, so wheel mode
is unaffected). Pinned by the new `PadSourceHoldTest` (the switch is requested from inside a transport's
`send()`, which makes the lag certain: it failed 10 of 10 without the fix), and the tolerance of
`PadLinkTest.sourceSwitchKeepsEpochAndSequenceAndNeverFlipsWithin300Ms` was tightened from 299,000 to
300,000 us.

### 6. What has NOT been tested in 0.2.0

Nothing below has run. Everything in the 0.1.0 list further down still applies (no real Windows PC, no
driver, no game, no phone, no real network, no adb tunnel, no long runs). In addition, for controller mode:

- **ViGEmBus.** No virtual DualShock 4 or Xbox 360 pad was created. The hub ran headless with a console pad
  that only counts frames and reports the kind it would plug in. Not run: the ViGEm plug-in, the extended
  DualShock 4 report (`SubmitRawReport`) and its fallback for an older ViGEmBus, the XInput slot order, one
  Xbox 360 device serving both modes, rumble coming back from a pad, the Retry button.
- **A game.** No game has read the pad. The DualShock 4 touch scaling was checked only as numbers, and the
  DualShock 4 motion values only for their sign: the motion units the hub uses (16 counts per dps, 8192 per g)
  and the report timestamp have not been confirmed by any game or tool. Steam Input was not tried.
- **A phone.** The play screen and everything behind it: touch and multitouch, slide to press, stick press
  (double tap and firm press), trigger modes, touchpad gestures and clicks, the pause ring hold, the status
  chip, the editor and profiles, layout fitting on other screen sizes, `MotionSource` with real sensors and
  its axes in both landscape rotations, gyro aim, haptics, volume-key mapping, rumble on the vibrator, and
  Android timing. In the interop runs the test drove `PadState` directly, not the touch surface.
- **The paused repeat on Android.** It was measured on the desktop JVM, where macOS timer slack gave 23 to
  25 packets in 600 ms instead of 30.
- **Consoles.** A PS4, PS5 or Xbox console cannot be driven by design (they accept only authenticated
  controllers); nothing was tried.
- **Known limits of the hub's tap scheduler** (from the hub review), not exercised here: a press can still
  be lost when a replay that hides it ends during a failsafe outage or PAUSED and the press is released
  before the link resumes; after a Wi-Fi stall a press and its release can arrive back to back and show
  for microseconds, which a polling game can miss; the first PAD packet (or a style change under Auto)
  plugs the pad in on the receive thread, and presses inside that plug-in time can be too short for a game.
- **Long runs.** No controller run was longer than about 5 s.

## 0.1.0, 2026-09-24

Date: 2026-09-24. Version 0.1.0. Every result in this record comes from a command run on this date; nothing
is carried over from an earlier session. Its last section lists what has **not** been tested.

### Machine and toolchain

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

### 1. Windows hub (`windows/`)

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

### 2. Android app (`android/`)

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

### 3. Cross-implementation wire test (`tools/interop.sh`)

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

#### Runs and assertions

| Run | Traffic | Asserted from the hub's stats JSON and from the STATUS stream |
|---|---|---|
| a, b: UDP | 2000 INPUT at 500 Hz. 400 (exactly 20 %) never sent, from a fixed seed plus 4 forced drops on shift packets. 15 shift-ups, including a double press whose first packet is dropped (+2 in one packet) and a counter that wraps 255 to 0. Channel 2 jumps by 140 (a reset: no press). 10 late replays, 5 tampered copies, 3 wrong-length or wrong-version datagrams. | accepted 1600, missing 400, last_seq 2000; udp packets 1610, first arrivals 1600, duplicates 10, bad tags 5, malformed 3; presses `[15,0,0,0,0,0,0,0]`, nothing queued; the last INPUT decoded field by field (epoch, seq, t_us, all axes, buttons, 8 counters, flags, rtt_100us); vJoy and Xbox 360 values of the final state; failsafe frame after 200 ms (steering held, pedals and buttons released). Every STATUS: our epoch, echo_t_us equal to the t_us of packet last_seq, accepted and missing equal to the send log at that seq, output byte 0, RTT 0 to 50 ms, last_seq never going back, hold under 100 ms while sending, median interval 40 to 60 ms. `LinkStats` loss exactly 0.2. |
| c: framed TCP | 1000 INPUT at 500 Hz, 200 never sent, 15 shift-ups, 5 replays, 3 tampered. Frames split on the wire (inside the length header, right after it, mid body) and two frames coalesced in one write. Finally a 53 byte frame. | accepted 800, missing 200; tcp packets 805, first 800, duplicates 5, bad tags 3, malformed 1; the hub closes the connection on the 53 byte frame (section 8); same frame, STATUS and loss checks as run a. |
| d: multipath | 1000 INPUT, every one on UDP and on TCP; the TCP copy trails by 10 packets (20 ms). MULTIPATH flag set. | accepted 1000, missing 0; each path 1000 packets; first arrivals sum to 1000 and duplicates sum to 1000, at least 99 % of duplicates on the slower path (TCP); presses exactly 15 (no double presses); STATUS arrives on both paths and passes the checks on each. |
| e: real LinkEngine | The app's `LinkEngine` in multipath (UDP and TCP to 127.0.0.1) at 500 Hz, final state set in `ControllerState`, 15 `pulse(SHIFT_UP)` calls 120 ms apart, then `stop()`. | Both paths turn LIVE in `LinkStats` on hub STATUS; smoothed RTT fed back into INPUT; hub accepted equals packets built, missing 0; presses 15; last INPUT carries PAUSED and MULTIPATH with the full state; hub state paused, output neutral with steering centred. |
| f: beacon discovery | Hub with beacon on a test port. The app's `Beacon.decode`, `BeaconGate` and fingerprint match pick the hub; then `LinkEngine` Wi-Fi only to the beacon's source IP (192.168.0.15 here, not loopback). | At least 2 beacons, 1 s apart, fingerprint `e3ca54042d049dba` (the test-vector key), udp_port and tcp_port equal to the hub's bound ports; the Wi-Fi path turns LIVE; accepted equals packets built, missing 0, one press. |

#### Results of the three runs

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

#### Proof that the test can fail

Mutation check, run once: the hub's pulse rule (section 9 rule 4) was changed to count deltas of 128
and more as presses (`windows/src/Slipstream.Core/Protocol/SeqMath.cs`). `tools/interop.sh` then
exited with code 1: runs a, c and d failed with `no presses on channel 2: [15,0,24,0,0,0,0,0]` and
`[15,0,15,0,0,0,0,0]`. Run e passed, as expected (it has no reset jump). The file was restored from
a copy, checked identical with `diff`, and the solution rebuilt before the runs above.

### 4. Defects found today by the wire test, and fixed

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

### 5. What has NOT been tested

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
