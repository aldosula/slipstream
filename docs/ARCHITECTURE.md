# Slipstream architecture

Slipstream turns an Android phone into a racing wheel and pedal set for Windows games.
Two programs, one wire protocol (`PROTOCOL.md`):

- **Slipstream Wheel** (`android/`), the phone app, `.apk`.
- **Slipstream Hub** (`windows/`), the PC program, `.exe`, which drives a virtual game
  controller (vJoy or Xbox 360 through ViGEmBus).

Goal, in one line: the smallest possible delay from finger or wrist to game, and a link
that does not stutter when a packet is lost.

## 1. Latency budget and where each millisecond goes

| Stage | Typical naive app | Slipstream | How |
|---|---|---|---|
| Sensor sampling | 20 ms (50 Hz `SENSOR_DELAY_GAME`) | 2 to 5 ms | `SENSOR_DELAY_FASTEST` on a dedicated `HandlerThread` |
| Steering filter | 30 to 80 ms low-pass on the accelerometer | under 3 ms | `TYPE_GAME_ROTATION_VECTOR` (gyro fused, no magnetometer) plus a One Euro filter |
| Touch delivery | up to 16 ms (vsync batching) | about 1 ms | `View.requestUnbufferedDispatch` on every `ACTION_DOWN` |
| Send scheduling | fixed timer, 10 to 20 ms | 0 to 1 ms | send-on-change, repeat at 500 Hz when idle |
| Wi-Fi air time | power save wake-ups, 10 to 100 ms spikes | 1 to 4 ms | `WIFI_MODE_FULL_LOW_LATENCY` lock plus DSCP EF (WMM voice queue) |
| Transport | TCP: one loss stalls everything | none | UDP state packets, newest wins, multipath |
| USB | tethering (a network stack in between) | about 1 ms | `adb reverse` TCP tunnel over the cable, no loss possible |
| Hub apply | poll loop, 5 to 16 ms | under 0.1 ms | write to the virtual device on the receiving thread |

## 2. Connection modes

1. **Wi-Fi (UDP).** Zero setup: the hub broadcasts a beacon, the phone finds it.
   Best on 5 GHz. Also works over **USB tethering** or the **phone's hotspot** (the PC joins
   the phone's hotspot, so there is no router in between).
2. **USB (ADB).** Needs USB debugging enabled once. The hub runs `adb reverse` automatically
   whenever a phone is plugged in. About 1 ms and lossless.
3. **Multipath (Wi-Fi + USB together).** Default when both are up. Same packet on both, the
   hub applies whichever arrives first. The cable gives the latency, the radio gives a spare.
4. **Bluetooth HID (experimental).** Android 9+ can act as a Bluetooth gamepad
   (`BluetoothHidDevice`). Windows pairs with the phone as a real game controller. No hub,
   no driver. Some phone makers disable this profile; the app detects it.

## 3. Android app (`android/`, Kotlin, minSdk 26, targetSdk 35)

```
com.slipstream.wheel
  protocol/   Pairing (code, key, fingerprint), InputPacket encoder, Status/Beacon decoders,
              TCP framing. Pure Kotlin, no Android imports, unit-tested against test-vectors.json.
  input/      ControllerState (lock-free snapshot), SteeringSource (rotation vector to angle),
              OneEuroFilter, PedalMath (swipe and absolute modes, curves), PulseCounters.
  link/       LinkEngine (sender thread, send-on-change, multipath), UdpTransport,
              AdbTcpTransport, BeaconListener, LinkStats (RTT EWMA, loss).
  hid/        BluetoothGamepad (BluetoothHidDevice, report descriptor, report writer).
  ui/         ConnectActivity (discovered hubs, QR scan, code entry, mode choice),
              DriveActivity (full-screen pedal surface, steering gauge, status chip),
              SettingsActivity, DriveSurfaceView (custom View, multitouch zones).
  Settings    SharedPreferences-backed settings.
```

Threads on the phone:

- **Sensor thread** (`HandlerThread`, `THREAD_PRIORITY_URGENT_DISPLAY`): receives rotation
  vector events, computes the steering value, writes it into `ControllerState`, signals the
  sender.
- **Main thread**: touch and volume keys write pedal, button and pulse fields, then signal.
- **Sender thread** (highest priority the app may take): waits on the signal with a timeout
  of one idle period. On wake it enforces 1 ms minimum spacing, snapshots the state, builds
  one 52 byte packet into a reused buffer, and writes it to every connected transport.
- **Receiver threads**, one per transport: parse STATUS, update `LinkStats`, drive rumble.

Steering math: from the rotation vector take the rotation matrix `R`; world-up in device
coordinates is `g = (R[6], R[7], R[8])`. For landscape `ROTATION_90`:
`angle = atan2(g.y, g.x)`; for `ROTATION_270`: `angle = atan2(-g.y, -g.x)`; positive is a
clockwise turn as seen by the driver (steer right). When `hypot(g.x, g.y) < 0.25` (phone
nearly flat) the last angle is held. Then `angle -= center`, deadzone, `clamp(angle / lock)`,
response curve, One Euro filter, scale to `i16`. Fallback sensors when there is no gyroscope:
`TYPE_GRAVITY`, then `TYPE_ACCELEROMETER` with a stronger filter; the app says which is used.

Pedals: left half brake, right half throttle (swappable). **Swipe** mode (default): the value
is how far the finger has moved up from where it landed, over `travel` (default 35 % of the
screen height); moving below the landing point re-anchors it. **Absolute** mode: the value
is the finger's height in the zone. Each pedal has its own curve. Buttons on screen:
handbrake (axis), four programmable held buttons, on-screen shift paddles, recenter.

Volume keys: consumed while on the drive screen (the phone volume does not change). Vol Up is
pulse channel 0 (shift up), Vol Down is channel 1 (shift down). A setting swaps them.
A light haptic tick on each shift.

## 4. Windows hub (`windows/`, .NET 8)

```
Slipstream.sln
  src/Slipstream.Core      net8.0, cross-platform, no Windows APIs.
      Protocol/            Pairing, packet codecs, framing.
      Link/                LinkSession (epoch, seq, missing, dedupe), PulseScheduler,
                           Failsafe, HubEngine (single lock, apply on receive thread).
      Transport/           UdpInputServer, TcpInputServer (loopback), BeaconBroadcaster,
                           AdbReverseManager (runs adb, pluggable process runner).
      Output/              IOutputDevice, OutputFrame, mapping to vJoy and X360 values,
                           NullOutput, RecordingOutput (tests).
      Config/              HubConfig (JSON, %APPDATA%\Slipstream\hub.json).
  src/Slipstream.Hub       net8.0-windows, WPF, tray icon. VJoyOutput (P/Invoke to
                           vJoyInterface.dll), ViGEmX360Output (Nefarius.ViGEm.Client),
                           pairing QR, live telemetry, settings, firewall helper.
  tools/Slipstream.Cli     net8.0 console. `hub`: runs HubEngine headless with a console
                           output (runs on macOS too, for testing with a real phone).
                           `sim`: a fake phone that sends INPUT with configurable loss and
                           prints RTT from STATUS.
  tests/Slipstream.Core.Tests  xUnit, test vectors, session rules, pulses, failsafe,
                           mapping, UDP and TCP loopback end to end.
```

Threads on the hub:

- **UDP receive thread** (`Highest`): blocking `Receive`, validate, `HubEngine.Accept`,
  which under one lock updates the session and writes the virtual device. `SIO_UDP_CONNRESET`
  is disabled so an ICMP error cannot break the socket.
- **TCP connection threads**: same path, framed.
- **Housekeeping thread** (1 ms tick, `timeBeginPeriod(1)` on Windows): releases pulse
  presses on time, fires the failsafe, sends STATUS at 20 Hz.
- **Beacon thread**: 1 Hz broadcast. **ADB thread**: every 2 s, `adb devices` then
  `adb reverse tcp:47802 tcp:47802` for new devices.

## 5. What the user installs on the PC

- **Slipstream Hub.exe** (self-contained, no .NET install needed).
- **vJoy** 2.2.x, the signed, maintained fork (github.com/BrunnerInnovation/vJoy), with
  device 1 set to axes X Y Z Rx Ry and 32 buttons. The hub checks this and says what is missing.
- Optional: **ViGEmBus** (github.com/nefarius/ViGEmBus) for Xbox 360 mode. Retired upstream
  but still works on Windows 10 and 11.
- Optional for USB mode: Android **platform-tools** (adb). The hub finds it on `PATH`, in
  `%LOCALAPPDATA%\Android\Sdk\platform-tools`, or a folder set in settings.

## 6. Security

The pairing key never crosses the network. A packet without a valid tag is dropped before
it touches any state. Beacons reveal only a name and a key fingerprint. The TCP port listens
on loopback only. There is no cloud, account, telemetry or update check: nothing leaves the
local network.
