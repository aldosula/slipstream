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

## 7. Controller mode (0.2.0)

The phone becomes a PlayStation-style or Xbox-style gamepad for PC games. Wire format:
PROTOCOL.md section 12 (PAD). The wheel is unchanged; the user picks **Wheel** or
**Controller** on the connect screen. Consoles themselves cannot be driven: PS4, PS5 and Xbox
accept only authenticated controllers.

### 7.1 Phone

```
com.slipstream.wheel
  pad/        PadState (lock-free snapshot like ControllerState), PadButton (canonical bits),
              TapCounters (4-bit), StickMath (deadzone, curve, fixed or floating origin),
              TriggerMath, GyroAim, MotionSource (TYPE_GYROSCOPE + TYPE_ACCELEROMETER at
              SENSOR_DELAY_FASTEST, rotated into the controller frame of PROTOCOL 12.1).
  pad/layout/ PadLayout (list of PadControl), PadControl (id, kind, binding, cx, cy, width_dp,
              height_dp, opacity, haptic, options), LayoutStore (JSON in SharedPreferences,
              profiles), DefaultLayouts (PlayStation, Xbox; tables below).
  ui/         PadActivity (play), PadSurfaceView (draw and multitouch), PadEditorView (edit),
              PauseMenu, ProfilesActivity.
  protocol/   PadPacketWriter (76 bytes, reused buffer and Mac).
  link/       LinkEngine gains a packet source switch: INPUT (wheel) or PAD (controller).
```

Play screen rules:

- Only playable controls are drawn. No edit button and no status chip while things are fine.
- A **pause ring** sits at the top centre (16 dp drawn, 44 dp hit area, faint; where the hit area
  overlaps another control, such as the PlayStation touchpad, the control wins outside a 24 dp core). Holding it for
  1.5 s fills the ring and opens the pause menu: Resume, Edit layout, Switch profile, Exit. A
  shorter touch does nothing. Opening the menu sets `PAUSED` until Resume.
- The status chip appears only when the link degrades (smoothed RTT above 30 ms, loss above 5 %
  over the last second, or no STATUS for 500 ms) and hides again after 3 s of good link.
- **Touch model:** a finger belongs to the control it lands on until it lifts. Sticks and
  triggers keep tracking the finger outside their bounds. Face buttons and the D-pad allow
  **slide-to-press** (sliding a finger from Cross to Circle releases Cross and presses Circle),
  a per-layout option on by default. Unbuffered touch dispatch on every down.
- **Sticks:** fixed (origin at the control centre) or floating (origin where the thumb lands,
  inside the control's area); radius = half the control width; deadzone default 8 %; response
  curve; a firm press (touch major axis grows by 35 %) or a double tap toggles L3 / R3 for as
  long as the finger stays down, option per stick.
- **Triggers:** slide along the bar for 0..65535 like the wheel's pedals, or tap mode (full on
  touch), per trigger.
- **Touchpad (PlayStation):** up to two fingers reported as touch0 / touch1; a tap shorter than
  200 ms that moves less than 4 % of the pad counts as a touchpad click (held for the tap).
- **Motion:** raw gyro and accel are sent with `MOTION` in PlayStation style while playing; the
  sensors stop while paused. **Gyro aim**
  (off by default) adds gyro yaw and pitch to the right stick: off, always, or only while a finger
  is on the right stick; sensitivity and invert Y.
- **Volume keys:** unmapped by default (they change the volume); mappable to any canonical button.
- **Haptics:** a short tick on every press (per control strength, 0 = off); rumble from STATUS
  drives the vibrator on its own thread.

Editor (from the profile screen, or from the pause menu):

- Drag to move, pinch or corner handles to resize, a 4 dp snap grid (toggle), per control:
  opacity, haptic strength, and the options above. Mirror for left-handed play. Reset to default.
- Controls cannot be placed inside the safe margin: 12 dp from the long (curved) edges and
  clear of the display cutout. Overlapping controls are allowed but shown with a warning outline.
- Profiles: the two built-ins (PlayStation, Xbox) cannot be deleted, only reset; any profile can
  be duplicated, renamed, deleted. The last used profile is remembered.

### 7.2 Default layouts

Positions are the control centre as a fraction of the drawing area (the full landscape view
minus system insets), `cx` from the left, `cy` from the top. Sizes are in dp. Taken from the
approved drawings of 25/09/2026 (Note 20 Ultra, 882 x 411 dp).

PlayStation:

| Control | Kind | cx | cy | w x h dp |
|---|---|---|---|---|
| L2 | trigger (horizontal bar) | 0.134 | 0.105 | 171 x 47 |
| R2 | trigger | 0.866 | 0.105 | 171 x 47 |
| L1 | button | 0.134 | 0.230 | 171 x 39 |
| R1 | button | 0.866 | 0.230 | 171 x 39 |
| Create | button (pill) | 0.313 | 0.098 | 77 x 30 |
| Touchpad | touchpad | 0.500 | 0.193 | 226 x 124 |
| Options | button (pill) | 0.692 | 0.098 | 85 x 30 |
| D-pad | dpad | 0.144 | 0.480 | 127 x 127 |
| Left stick | stick (L3) | 0.303 | 0.764 | 116 x 116 |
| Right stick | stick (R3) | 0.697 | 0.764 | 116 x 116 |
| Face buttons | face cluster (Triangle top, Circle right, Cross bottom, Square left) | 0.856 | 0.500 | 154 x 154, buttons 50 dp |
| PS | button (round) | 0.500 | 0.480 | 47 x 47 |
| Mute | button (pill) | 0.500 | 0.598 | 55 x 25 |

Xbox:

| Control | Kind | cx | cy | w x h dp |
|---|---|---|---|---|
| LT | trigger | 0.134 | 0.105 | 171 x 47 |
| RT | trigger | 0.866 | 0.105 | 171 x 47 |
| LB | button | 0.134 | 0.230 | 171 x 39 |
| RB | button | 0.866 | 0.230 | 171 x 39 |
| Left stick | stick | 0.153 | 0.507 | 116 x 116 |
| D-pad | dpad | 0.328 | 0.784 | 127 x 127 |
| Right stick | stick | 0.681 | 0.764 | 116 x 116 |
| Face buttons | face cluster (Y top, B right, A bottom, X left) | 0.856 | 0.500 | 154 x 154, buttons 50 dp |
| View | button (round) | 0.413 | 0.419 | 36 x 36 |
| Xbox | button (round) | 0.500 | 0.419 | 55 x 55 |
| Menu | button (round) | 0.588 | 0.419 | 36 x 36 |
| Share | button (pill) | 0.500 | 0.605 | 61 x 25 |

Both layouts: pause ring at (0.500, 0.054). Face glyph colours: PlayStation Triangle teal,
Circle red, Cross blue, Square pink; Xbox A green, B red, X blue, Y amber, on neutral buttons.
On other screen sizes positions scale with the area and sizes stay in dp, shrunk uniformly only
if two controls would otherwise overlap.

### 7.3 Hub

- `PadSession` state beside the wheel state inside `LinkSession` (same epoch and sequence),
  `TapScheduler` per canonical button (PROTOCOL 12.4 rule 3, driven by the housekeeping tick),
  mode switch neutralization (rule 2).
- Output: `ViGEmDs4Output` (Nefarius.ViGEm.Client `IDualShock4Controller`, extended report for
  touch and motion when the client exposes it) and the existing `ViGEmX360Output` fed from
  `PadFrame`. Setting **Controller output**: Auto (by style), always Xbox 360, always
  DualShock 4. The pad device is plugged in on the first accepted PAD packet and stays plugged
  in until the hub quits or the output changes, so games do not see it vanish on a pause.
- UI: the live panel shows the active mode (Wheel or Controller, style) and, in controller mode,
  both sticks as dots in circles, trigger bars and button lamps named for the style.
- CLI: `sim --pad [--style ps|xbox]` sends PAD packets with moving sticks and scripted taps;
  `hub --stats-json` adds taps emitted per button and the last pad frame.
