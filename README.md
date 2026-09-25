# Slipstream

Slipstream turns an Android phone into a racing wheel and pedal set for Windows games. Since
version 0.2.0 it can also be a **PlayStation-style or Xbox-style gamepad** for PC games
([Controller mode](#controller-mode)).

- **Slipstream Wheel** is the phone app (`.apk`). As a wheel you steer by tilting the phone, press
  the throttle and brake by sliding your thumbs on the screen, and shift with the volume keys. As a
  controller the screen shows sticks, triggers, face buttons, a D-pad and, in PlayStation style, a
  touchpad.
- **Slipstream Hub** is the PC program (`.exe`). It receives the phone's controls and drives a
  virtual game controller: for the wheel **vJoy** (a DirectInput joystick) or an **Xbox 360** pad,
  for controller mode a **DualShock 4** or an **Xbox 360** pad, both through ViGEmBus.

It is built for low delay and a steady link. Every packet carries the complete controller
state, so a lost packet costs nothing: the next one arrives 2 ms later. Gear shifts and button
presses are counters, not button events, so a press survives packet loss and is never pressed twice. With Multipath the
same packet travels over Wi-Fi and the USB cable at once, and the first copy to arrive wins.
The wire protocol is specified in [docs/PROTOCOL.md](docs/PROTOCOL.md) and the design in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Nothing leaves your local network: no account, no
cloud, no telemetry, no update check.

> **Status, version 0.2.0.** Both programs build, their test suites pass, and a cross-implementation
> wire test runs the phone's own link code against the real hub over loopback, for the wheel and for
> controller mode. Nothing has run yet on a real Windows PC, with real vJoy or ViGEmBus, in a game, or on
> a real phone. See [docs/TESTING.md](docs/TESTING.md) for exactly what was and was not tested.

## Contents

1. [Connection modes](#connection-modes)
2. [PC setup](#pc-setup)
3. [Phone setup](#phone-setup)
4. [First pairing](#first-pairing)
5. [Driving](#driving)
6. [Binding controls in a game](#binding-controls-in-a-game)
7. [Controller mode](#controller-mode)
8. [Build from source](#build-from-source)
9. [Troubleshooting](#troubleshooting)

## Connection modes

Pick the mode on the phone's connect screen before you press **Drive** (or **Play** in controller mode).

| Mode | How it connects | Use it when |
|---|---|---|
| **Multipath, Wi-Fi and USB** (recommended) | The same packet on both paths; the hub applies whichever copy arrives first. | You can plug the phone in. The cable gives the lowest delay and the radio is a live spare: pull the cable and driving goes on over Wi-Fi. |
| **USB cable** | TCP over the cable through `adb reverse`, which the hub sets up by itself. | The Wi-Fi is busy or unreliable. Lossless and steady. Needs USB debugging on the phone and Android platform-tools on the PC. |
| **Wi-Fi** | UDP on the local network. The hub announces itself, the phone finds it. | You want no cable. Best on 5 GHz. Also works when the PC joins the **phone's hotspot** (no router in between) or over **USB tethering**. |
| **Bluetooth gamepad** (experimental) | The phone pairs with Windows as a standard game controller. No hub, no driver. | You cannot install anything on the PC. Needs Android 9 or later, and some phone makers disable this Bluetooth profile; the app tells you if yours does. |

Wi-Fi, USB and Multipath need Slipstream Hub on the PC. Bluetooth does not, but it offers only the
wheel: controller mode always runs through the hub.

## PC setup

Windows 10 (1903 or later) or Windows 11, 64-bit.

1. **Get Slipstream Hub.** Download `Slipstream-Hub-0.2.0-win-x64.exe` from this repository's Releases page (or build it,
   see [Build from source](#build-from-source)). It is a single self-contained file: no .NET install is
   needed. To check the file, compare its hash with `SHA256SUMS.txt` from the same release. In PowerShell:
   `Get-FileHash .\Slipstream-Hub-0.2.0-win-x64.exe -Algorithm SHA256`.
   The exe is not code-signed, so Windows SmartScreen may warn on first start. Choose **More info**,
   then **Run anyway**, only for a file whose hash you checked.
2. **Install vJoy (for the wheel).** Use the signed, maintained 2.2.x fork from
   [github.com/BrunnerInnovation/vJoy/releases](https://github.com/BrunnerInnovation/vJoy/releases).
   Run the installer and restart the PC if it asks.
3. **Configure vJoy device 1.** Open **Configure vJoy** from the Start menu. Select tab **1**, tick
   **Enable vJoy**, then under **Axes** tick **X, Y, Z, Rx and Ry**, set **Number of Buttons** to
   **32**, leave POV hats at 0, and press **Apply**. The hub checks this device and tells you exactly
   what is missing if the configuration is incomplete.
4. **ViGEmBus, required for controller mode (and for the wheel's Xbox 360 mode).** Install ViGEmBus
   from [github.com/nefarius/ViGEmBus/releases](https://github.com/nefarius/ViGEmBus/releases). The
   project is retired upstream but still works on Windows 10 and 11. Controller mode creates its
   DualShock 4 or Xbox 360 pad through it, so without ViGEmBus controller mode shows an error on the hub
   and on the phone. For the wheel it is optional: some racing games only accept an Xbox controller;
   then choose **Xbox 360** under **Wheel output** in the hub. If you only use controller mode, you can
   skip vJoy and set **Wheel output** to **None**.
5. **Optional: Android platform-tools, for USB and Multipath.** Download them from
   [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools)
   and unzip them anywhere. The hub finds `adb` on `PATH`, in
   `%LOCALAPPDATA%\Android\Sdk\platform-tools`, or in the folder you enter under **adb folder** in the
   hub's settings. Keep **USB link: run adb reverse automatically when a phone is plugged in** ticked.
6. **Start Slipstream Hub and answer the firewall prompt.** On the first start Windows Defender Firewall
   asks whether the hub may communicate. Tick **Private networks** and press **Allow access**. The phone sends
   to UDP port 47800 on the PC, and without this permission nothing arrives over Wi-Fi. If you pressed
   Cancel, or the phone finds the hub but no packets arrive, press **Allow through firewall** in the hub
   (Windows asks for administrator rights for this one step). Your home network must be set to
   **Private** in Windows network settings.

The hub keeps its settings in `%APPDATA%\Slipstream\hub.json`. Closing the window hides it to the
notification area and the hub keeps driving the controller; to stop it, right-click the tray icon and
choose **Quit**.

## Phone setup

Android 8.0 or later.

1. **Copy the APK to the phone.** Download `Slipstream-Wheel-0.2.0-debug.apk` from the Releases page and
   copy it over the USB cable (file transfer mode), or install it from the PC with
   `adb install Slipstream-Wheel-0.2.0-debug.apk` once USB debugging is on (step 3). It installs over
   0.1.0 as an update (same package and signing certificate), so the stored pairing and settings stay on
   the phone.
2. **Allow unknown sources.** Open the APK in the phone's file manager. Android asks to allow that app
   to install unknown apps: open the setting, allow it, go back and press **Install**. Google Play Protect
   may warn about an app from an unknown developer; this build is signed with a development (debug)
   key, so choose to install anyway only for the file you copied yourself.
3. **For USB and Multipath: turn on USB debugging.** Open **Settings, About phone** and tap **Build
   number** seven times to unlock Developer options. Then open **Developer options** (often under
   **System**) and turn on **USB debugging**. Plug the phone into the PC and accept **Allow USB debugging**
   for this computer (tick **Always allow**). The hub shows the phone under **USB (adb)** within a few
   seconds.
4. The app asks for the camera only to scan the pairing QR code, and for Nearby devices (Bluetooth)
   only in Bluetooth gamepad mode.

## First pairing

1. Start Slipstream Hub. Its **Pair a phone** panel shows a QR code and a 16 character pairing code
   such as `ABCD-EFGH-IJKL-MNOP`.
2. Open Slipstream Wheel on the phone. Under **Pairing**, press **Scan QR** and point the camera at the
   hub's code. If the camera is not an option, press **Enter code** and type the code (dashes and
   spaces are optional).
3. The phone shows **Paired with** and the hub's name. Under **Hubs on this network** your PC appears,
   marked **Paired**, as soon as its announcement arrives (the phone and PC must share the Wi-Fi, the
   phone's hotspot or USB tethering). In USB mode the phone connects over the cable without it.
4. Under **Play as** choose **Wheel** or **Controller**, choose a mode under **Connection**, and press
   **Drive** (or **Play**). The hub's state changes from **Waiting for the phone** to **Live**.

The pairing code never travels over the network: the QR code and the screen are the only places it
exists. Packets carry a tag made from it, so no other device or app can drive your car. **New code** in
the hub makes a new one; phones paired with the old code stop working until they scan again.

## Driving

- **Steering:** hold the phone in landscape like a wheel and tilt it. Press **CENTER** on the drive
  screen while holding the phone straight to set the center. **Lock angle**, **Deadzone**, **Response
  curve** and **Smoothing** are in the phone's Settings.
- **Pedals:** the right half of the screen is the throttle, the left half the brake (**Swap pedal
  zones** swaps them). In **Swipe** mode (default) the pedal value is how far your thumb slides up from
  where it landed; **Swipe travel** sets the distance for a full press. **Absolute** mode uses the
  thumb's height instead. Each pedal has its own curve.
- **Gears:** **Volume up** shifts up, **Volume down** shifts down (**Swap volume keys** reverses this).
  The on-screen paddles do the same. A short haptic tick confirms each shift.
- **Handbrake** and four programmable held buttons are on the drive screen.
- Leaving the drive screen tells the hub **Paused on the phone**: pedals and buttons release and the
  steering centres. If the signal drops for 200 ms the hub releases pedals and buttons and holds the
  steering (**Signal lost**) until packets arrive again.

## Binding controls in a game

Start the hub, get the phone **Live**, then open the game's controller settings. The hub's **Controls**
panel shows every axis and button live, which helps you see what the game should be receiving.

**vJoy (default)**

| Control | vJoy |
|---|---|
| Steering | X axis |
| Throttle | Y axis |
| Brake | Z axis |
| Clutch | Rx axis |
| Handbrake | Ry axis |
| Shift up (volume up) | Button 1 |
| Shift down (volume down) | Button 2 |
| Pulse channels 3 to 8 (reserved for user actions, not used by this version of the app) | Buttons 3 to 8 |
| On-screen held buttons 1 to 4 | Buttons 9 to 12 (held bit i is button i + 9) |

Tips:

- Bind **steering to X**, **throttle to Y**, **brake to Z**, and the gears to **buttons 1 and 2**.
- When the game listens for movement, move only the control you are binding. Keep the phone flat and
  still while you bind a pedal or a gear, and turn the phone only when you bind steering.
- Bind each pedal by pressing it fully when the game asks. If a pedal reads backwards, fix it in the
  game or tick it under **Invert axes** in the hub.
- Set the game's steering deadzone to 0 and its steering filtering or assists to off: the phone
  already filters and applies your curve, and doing it twice adds delay.
- Shifts arrive as a 60 ms press. If a game misses shifts, raise **Shift press (ms)** in the hub; if
  rapid double shifts merge, raise **Shift gap (ms)**.

**Xbox 360 mode (ViGEmBus)**

| Control | Xbox 360 |
|---|---|
| Steering | Left stick X |
| Throttle | Right trigger |
| Brake | Left trigger |
| Clutch | Right stick Y |
| Handbrake | A (held while the handbrake is past half) |
| Shift up | B |
| Shift down | X |
| Pulse channels 3 to 8 (reserved) | RB, LB, Y, Back, Start, right stick click |
| Held buttons 1 to 4 | D-pad up, down, left, right |

Most games that support an Xbox pad bind these by default. In this mode only steering follows
**Invert axes**, because triggers have a fixed direction.

## Controller mode

New in 0.2.0. The phone becomes a gamepad for PC games: two sticks, two analog triggers, shoulder
buttons, face buttons, a D-pad and the menu buttons, and in PlayStation style also a touchpad and motion.
It uses the same pairing, the same hub and the same connection modes as the wheel (Wi-Fi, USB cable or
Multipath; not Bluetooth), and the same protection against lost packets: the hub replays a button press
whose packets were lost, and a packet that arrives twice never presses a button twice
([docs/TESTING.md](docs/TESTING.md) lists what this was tested with, and its limits).

On the connect screen, under **Play as**, choose **Controller**. The **Profile** row shows the layout in
use; **Change** opens **Controller profiles**. Press **Play** and hold the phone in landscape.

**It works with PC games only.** A real PlayStation 5, PlayStation 4 or Xbox console cannot be controlled:
consoles accept only controllers that pass their own authentication, which a phone cannot do. The
"PlayStation" and "Xbox" in the names describe the layout and the virtual pad on the PC.

### PlayStation style and Xbox style

Two built-in profiles, drawn after the real controllers:

| | PlayStation style | Xbox style |
|---|---|---|
| Layout | D-pad on the left, both sticks low toward the middle, face buttons on the right, touchpad at the top centre | Left stick on the left, D-pad and right stick low toward the middle, face buttons on the right |
| Buttons | Cross, Circle, Square, Triangle, L1, R1, L2, R2, L3, R3, Create, Options, PS, Mute, touchpad click | A, B, X, Y, LB, RB, LT, RT, stick presses, View, Menu, Xbox, Share |
| Touchpad | two fingers, and a short tap is a touchpad click | none |
| Motion (gyro and accelerometer) | sent to the PC | not sent |
| Windows sees (hub setting **Auto**) | a DualShock 4 | an Xbox 360 controller |

### What Windows sees

**ViGEmBus is required for controller mode** (see [PC setup](#pc-setup), step 4). The hub plugs in one
virtual pad through ViGEmBus when the first controller packet arrives, and keeps it plugged in until the
hub quits or you change the setting, so a game does not lose its controller when you pause. The hub's
**Controller output** setting picks the pad:

- **Auto** (default): PlayStation style gives a DualShock 4, Xbox style gives an Xbox 360 controller.
- **Xbox 360**: always an Xbox 360 controller. Use it for games that only read XInput.
- **DualShock 4**: always a DualShock 4. Games with PlayStation support can then show PlayStation button
  prompts, and only the DualShock 4 carries the touchpad and motion.

Touchpad and motion need a recent ViGEmBus. With an older one the hub still connects the DualShock 4,
without them, and says so; install the latest ViGEmBus release and press **Retry** under **Controller
output**.

If the **Wheel output** is Xbox 360 as well, one virtual Xbox 360 pad serves both modes, so a game never
sees a second controller take player one. The hub's live panel shows the active mode and, in controller
mode, both sticks, the triggers and a lamp per button.

| On the phone (PlayStation / Xbox) | Xbox 360 pad | DualShock 4 |
|---|---|---|
| Cross / A, Circle / B, Square / X, Triangle / Y | A, B, X, Y | Cross, Circle, Square, Triangle |
| L1 / LB, R1 / RB | LB, RB | L1, R1 |
| L2 / LT, R2 / RT | left and right trigger | L2 and R2 (analog; the digital bit is set from about 3 %) |
| L3, R3 (stick presses) | left and right stick click | L3, R3 |
| Create / View | Back | Share |
| Options / Menu | Start | Options |
| PS / Xbox | Guide | PS |
| D-pad | D-pad | D-pad |
| Left and right stick | left and right thumb stick | left and right stick |
| Touchpad click, fingers (PlayStation) | not sent | touchpad click, two fingers |
| Motion (PlayStation) | not sent | gyro and accelerometer |
| Mute (PlayStation), Share (Xbox) | not sent | not sent |

### Playing

- A finger belongs to the control it lands on until it lifts. Sticks and triggers keep following it
  outside their outline. With **Slide to press** (on by default) you can slide a finger from one face
  button or D-pad direction to the next without lifting.
- **Sticks:** a fixed centre or a floating one (where your thumb lands), a deadzone (8 % by default) and
  a response curve. **Stick press** (L3 or R3) can be a double tap, a firm press, both, or off.
- **Triggers:** slide along the bar for a partial press, or **Tap** mode for full on touch.
- **Volume keys** keep changing the volume unless you map them: in the phone's Settings, under
  **Controller**, set **Volume up key** and **Volume down key** to any button.
- A short haptic tick confirms each press (strength per control, 0 turns it off). With **Rumble** on in
  the phone's Settings, the game's rumble makes the phone vibrate.
- Nothing else is drawn while the link is fine. A status chip appears only when something is wrong (a
  slow round trip, loss, no reply from the hub, or a hub output error such as a missing ViGEmBus) and
  names the problem.

### The pause ring

A small, faint ring sits at the top centre. **Hold it for 1.5 seconds**: it fills up and the pause menu
opens with **Resume**, **Edit layout**, **Switch profile** and **Exit**. A shorter touch does nothing, so a
thumb brushing past cannot pause the game. While the menu is open the hub holds every control at rest
(sticks centred, triggers and buttons released) and the virtual pad stays plugged in.

### Gyro aim

Off by default. In the layout editor open **Options** and set **Gyro aim** to **Always** or **On stick**
(only while a finger is on the right stick). Turning and tilting the phone then moves the right stick, on
top of what your thumb does, which makes fine aiming easier. **Gyro sensitivity** sets the speed and
**Invert gyro Y** flips up and down. Gyro aim works in both styles, because it arrives as right stick
movement. It is separate from the raw motion that PlayStation style sends to the DualShock 4.

### Layouts and profiles

- **Controller profiles** (from the connect screen's **Profile** row, or from the phone's Settings) lists
  the two built-in profiles and your own. **More** on a profile offers **Use**, **Edit layout**,
  **Duplicate**, **Rename**, **Reset** and **Delete**. The built-in PlayStation and Xbox profiles cannot be
  renamed or deleted, only reset; duplicate one to make your own. The last profile you used is remembered.
- **The editor** opens from **Edit layout** on the profile screen or in the pause menu. Drag a control to
  move it; pinch, or drag a corner handle, to resize. **Grid** snaps to 4 dp, **Mirror** swaps left and
  right for left-handed play, **Reset** goes back to the default layout, **Options** holds the layout-wide
  settings (Slide to press, Gyro aim). Touch a control to set its own options: opacity, haptic tick, shape
  and, depending on the control, trigger mode, stick centre, stick press, deadzone and response curve.
  **Save** keeps the changes. Controls stay clear of the curved screen edges and the camera cutout;
  overlapping controls are allowed but outlined as a warning.
- On a phone with another screen size, positions scale with the screen and controls keep their size,
  shrinking together only if two of them would otherwise overlap.

## Build from source

The repository has two independent projects and one shared protocol spec with byte-exact test vectors
(`docs/test-vectors.json`, generated by `tools/gen_test_vectors.py`). Both test suites load the vectors.

**Windows hub** (`windows/`, .NET 8 SDK; builds on Windows, macOS or Linux)

```sh
cd windows
dotnet build Slipstream.sln -c Release
dotnet test Slipstream.sln -c Release
dotnet publish src/Slipstream.Hub -c Release -r win-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o artifacts/hub
# result: artifacts/hub/SlipstreamHub.exe
```

A headless hub and a fake phone for testing run on any OS:

```sh
dotnet run --project tools/Slipstream.Cli -c Release -- hub --code SLIP-STRE-AMTE-ST22
dotnet run --project tools/Slipstream.Cli -c Release -- sim --code SLIP-STRE-AMTE-ST22 --loss 0.2 --pulses 10
dotnet run --project tools/Slipstream.Cli -c Release -- sim --code SLIP-STRE-AMTE-ST22 --pad --style ps --loss 0.2 --taps 12
```

The last line is a fake phone in controller mode (PlayStation layout, 12 scripted taps, 20 % loss).

**Android app** (`android/`, JDK 17 and the Android SDK with platform 35 and build-tools 35.0.0)

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
# result: app/build/outputs/apk/debug/app-debug.apk
```

**Cross-implementation wire test** (needs both toolchains and python3)

```sh
tools/interop.sh
```

It builds the headless hub, picks free ports, and runs `InteropTest` from the Android test suite: the
app's own packet writers (wheel and controller), framing, STATUS decoder, link statistics and link engine
against real hub processes, over UDP, framed TCP, multipath and beacon discovery, and in controller mode
also the switch between wheel and controller, PAUSED and the failsafe. It exits non-zero on any failure,
including a test that was skipped instead of run. The beacon run broadcasts a test beacon on your LAN
for about two seconds.

## Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| The phone does not list the hub under **Hubs on this network** | Phone and PC on different networks, a guest network with client isolation, or the firewall blocks the hub | Put both on the same Wi-Fi (or the phone's hotspot). Scan the QR code instead: it carries the PC's addresses, so discovery is not needed. |
| The hub is listed but stays **Waiting for the phone** over Wi-Fi | Windows Firewall blocks UDP 47800, or the network is set to Public | Press **Allow through firewall** in the hub and set the network to **Private**. If you pressed Cancel on the first firewall prompt, Windows created a block rule that wins over the allow rule: remove the Slipstream Hub block entries under **Windows Defender Firewall, Advanced settings, Inbound Rules**. |
| The phone says the code does not match | The phone holds an older code, or you pressed **New code** in the hub | Scan the hub's current QR code again. |
| **USB (adb)** shows no device or says to authorize | USB debugging off, the prompt on the phone was not accepted, or a charge-only cable | Turn on USB debugging, accept **Allow USB debugging** on the phone, try another cable, and set the phone's USB mode to file transfer. |
| USB does not connect although `adb devices` lists the phone | adb was not found by the hub, or another adb (for example Android Studio's) keeps restarting the server | Set **adb folder** in the hub to the same platform-tools that Android Studio uses, or close Android Studio. |
| The hub says vJoy device 1 does not exist or is missing axes or buttons | Device 1 not enabled or not configured | Open **Configure vJoy**, tab 1: enable it, tick X Y Z Rx Ry, 32 buttons, Apply, then **Retry** in the hub. |
| The hub says vJoy device 1 is used by another program | Another feeder or wheel app holds device 1 | Close the other program. The hub retries every 2 s. |
| The game does not react, but the hub's meters move | The game was started before the controller existed, or it only supports Xbox pads | Restart the game with the hub already Live. Try **Xbox 360** mode (needs ViGEmBus). |
| Xbox 360 mode reports an error | ViGEmBus is not installed | Install ViGEmBus and press **Retry**. |
| Steering drifts or the center is off | The center was set while the phone was tilted | Hold the phone straight and press **CENTER**. |
| Steering is jittery | Hand tremor or a phone without a gyroscope (the app then falls back to gravity or the accelerometer and says so) | Raise **Smoothing** or **Deadzone** in the phone's Settings. |
| Shifts are sometimes missed by the game | The game polls slower than the 60 ms press | Raise **Shift press (ms)** in the hub to 80 or 100. |
| Delay or stutter over Wi-Fi | 2.4 GHz band, a crowded channel, or phone power saving | Use 5 GHz or the phone's hotspot, or plug in the cable and use **Multipath**. The hub's **Round trip (phone)** and **Loss** figures show the link quality. |
| **Signal lost** on the hub while driving | Wi-Fi dropouts longer than 200 ms | Use Multipath with the cable. Raise **Failsafe (ms)** only if you accept a longer release on real signal loss. |
| A second phone stays on **WAITING FOR HUB** while the first one drives | Only one phone drives at a time. The hub ignores the second phone until the first has been silent for 300 ms, and sends it nothing meanwhile, so it looks the same as a stopped hub | Leave the drive screen on the first phone; the second takes over within 300 ms. **HUB BUSY** shows only briefly, when a phone restarts its drive screen while the hub still tracks its previous session. |
| Controller mode: the phone says **Hub output error: is ViGEmBus installed?** | ViGEmBus is missing, so the hub cannot create the virtual pad | Install ViGEmBus (PC setup, step 4) and press **Retry** under **Controller output** in the hub. |
| Controller mode: **No reply from the hub** on the phone, while the wheel works | The PC runs Slipstream Hub 0.1.0, which ignores controller packets | Install Slipstream Hub 0.2.0. |
| Controller mode: the hub's pad lamps move but the game does not react | The game reads only Xbox controllers (XInput), or it was started before the virtual pad appeared | Set **Controller output** to **Xbox 360**, then restart the game with the phone already playing. |
| Controller mode: the touchpad or motion does nothing in the game | Xbox style or an Xbox 360 output (neither has them), or an older ViGEmBus (the hub says so) | Use PlayStation style with **Controller output** on **Auto** or **DualShock 4**, and the latest ViGEmBus. |
| Bluetooth gamepad mode says the profile is not supported | The phone maker disabled the Bluetooth HID device profile, or Android is older than 9 | Use Wi-Fi or USB with the hub instead. |

## Project layout

```
android/   Slipstream Wheel, Kotlin, minSdk 26, targetSdk 35
windows/   Slipstream Hub (WPF), Slipstream.Core, the CLI (headless hub and simulator), xUnit tests
docs/      PROTOCOL.md (normative), ARCHITECTURE.md, test-vectors.json, TESTING.md
tools/     gen_test_vectors.py (reference implementation of the byte layouts), interop.sh
dist/      built files and SHA256SUMS.txt (not in git)
```
