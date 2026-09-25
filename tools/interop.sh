#!/usr/bin/env bash
# Slipstream cross-implementation wire test.
#
# Builds the C# headless hub (windows/tools/Slipstream.Cli), picks free loopback ports and runs
# the JVM test android/app/src/test/.../InteropTest.kt, which drives the Android app's own
# protocol and link code against real hub processes and checks both sides:
#   a  2000 INPUT over UDP at 500 Hz, 20 % never sent, 15 shift-ups, replays, bad tags, bad lengths
#   b  every STATUS decoded and tag-checked by the app, echo, counters and RTT checked
#   c  the same over framed TCP (the USB link), frames split and coalesced on the wire
#   d  multipath: every packet on UDP and TCP, TCP 20 ms behind
#   e  the app's real LinkEngine (both transports) against the hub
#   f  BEACON discovery (app decoder, gate, fingerprint), then the real LinkEngine over Wi-Fi UDP
#      to the beacon's source IP. This run broadcasts a beacon on the LAN for about 2 s.
# Controller mode (PAD, PROTOCOL.md section 12), packets built from the app's PadState and PadPacketWriter:
#   pad_a   2000 PAD over UDP, PlayStation layout, 20 % never sent, exactly 12 short Cross taps (lost
#           presses, releases and whole taps among them), replays, bad tags, malformed datagrams
#   pad_b   the same over framed TCP, Xbox layout, frames split and coalesced, a 77 byte frame at the end
#   pad_c   multipath, every PAD on UDP and TCP (TCP 20 ms behind), 13 packets never sent on UDP
#   pad_d   the real LinkEngine switching wheel to pad to wheel within one epoch, multipath
#   pad_e1  PAUSED on PAD with the real LinkEngine; pad_e2 failsafe on PAD, hub forced to Xbox 360
# Exits non-zero on any failure, including a test that was skipped instead of run.
#
# Usage: tools/interop.sh            (from anywhere)
# Needs: JDK 17, the Android SDK, the .NET 8 SDK, python3. Defaults below match the build Mac.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/android/app/build/interop"

# ------------------------------------------------------------------ toolchain ---
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17 ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@17; fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then export ANDROID_HOME="$HOME/Library/Android/sdk"; fi
if ! command -v dotnet >/dev/null 2>&1 && [[ -x "$HOME/.dotnet/dotnet" ]]; then export DOTNET_ROOT="$HOME/.dotnet"; fi
if [[ -n "${DOTNET_ROOT:-}" ]]; then export PATH="$DOTNET_ROOT:$PATH"; fi
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1

for tool in java dotnet python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "interop: $tool not found on PATH" >&2; exit 2; }
done

# ------------------------------------------------------------------ hub build ---
echo "== building the headless hub (Release)"
dotnet build "$ROOT/windows/tools/Slipstream.Cli/Slipstream.Cli.csproj" -c Release -nologo -v quiet
HUB_DLL="$ROOT/windows/tools/Slipstream.Cli/bin/Release/net8.0/slipstream.dll"
[[ -f "$HUB_DLL" ]] || { echo "interop: $HUB_DLL was not built" >&2; exit 2; }

# ------------------------------------------------------------------ free ports ---
read -r UDP_PORT TCP_PORT BEACON_PORT < <(python3 - <<'PY'
import socket
u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(("0.0.0.0", 0))
b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); b.bind(("0.0.0.0", 0))
t = socket.socket(socket.AF_INET, socket.SOCK_STREAM); t.bind(("127.0.0.1", 0))
print(u.getsockname()[1], t.getsockname()[1], b.getsockname()[1])
u.close(); t.close(); b.close()
PY
)
echo "== ports: udp $UDP_PORT, tcp 127.0.0.1:$TCP_PORT, beacon $BEACON_PORT"

# ------------------------------------------------------------------ JVM test ---
rm -rf "$OUT"
mkdir -p "$OUT"
echo "== running InteropTest against the hub"
set +e
(
  cd "$ROOT/android"
  SLIPSTREAM_INTEROP_PORT="$UDP_PORT" \
  SLIPSTREAM_INTEROP_TCP_PORT="$TCP_PORT" \
  SLIPSTREAM_INTEROP_BEACON_PORT="$BEACON_PORT" \
  SLIPSTREAM_INTEROP_HUB="$HUB_DLL" \
  SLIPSTREAM_INTEROP_DOTNET="$(command -v dotnet)" \
  SLIPSTREAM_INTEROP_OUT="$OUT" \
  ./gradlew -q :app:testDebugUnitTest --tests 'com.slipstream.wheel.InteropTest' --rerun --no-build-cache
)
GRADLE_EXIT=$?
set -e

# ------------------------------------------------------------------ verdict ---
python3 - "$ROOT" "$OUT" "$GRADLE_EXIT" <<'PY'
import glob, json, os, sys
import xml.etree.ElementTree as ET

root, out, gradle_exit = sys.argv[1], sys.argv[2], int(sys.argv[3])
xml = os.path.join(root, "android/app/build/test-results/testDebugUnitTest/TEST-com.slipstream.wheel.InteropTest.xml")
ok = gradle_exit == 0
if not os.path.exists(xml):
    print("interop: FAIL, no test report at " + xml)
    sys.exit(1)
suite = ET.parse(xml).getroot()
tests, skipped = int(suite.get("tests")), int(suite.get("skipped"))
failures, errors = int(suite.get("failures")), int(suite.get("errors"))
print(f"== InteropTest: {tests} tests, {failures} failures, {errors} errors, {skipped} skipped")
for case in suite.findall("testcase"):
    state = "PASS"
    if case.find("failure") is not None or case.find("error") is not None: state = "FAIL"
    elif case.find("skipped") is not None: state = "SKIP"
    print(f"   {state}  {case.get('name')}  ({float(case.get('time')):.1f} s)")
    for f in list(case.findall("failure")) + list(case.findall("error")):
        print("      " + (f.get("message") or "").splitlines()[0][:400] if f.get("message") else "      (no message)")
if tests == 0 or skipped > 0 or failures > 0 or errors > 0:
    ok = False

def ms(us): return f"{us / 1000:.3f} ms"
for name in ["udp", "tcp", "multipath", "engine", "beacon"]:
    p = os.path.join(out, f"result-{name}.json")
    if not os.path.exists(p):
        print(f"   {name}: no result (the run did not finish)")
        ok = False
        continue
    r = json.load(open(p))
    if name == "beacon":
        print(f"   beacon: {r['beacons_admitted']} admitted of {r['beacon_arrivals']} received from {r['beacon_source']}, "
              f"fingerprint {r['fingerprint']}, then Wi-Fi link: built {r['packets_built']}, hub accepted {r['hub_accepted']} "
              f"missing {r['hub_missing']}, phone rtt {r['phone_rtt_wifi_us'] / 1000:.3f} ms")
        continue
    if name == "engine":
        print(f"   engine: built {r['packets_built']}, hub accepted {r['hub_accepted']} missing {r['hub_missing']}, "
              f"udp first/dup {r['udp_first']}/{r['udp_duplicates']}, tcp first/dup {r['tcp_first']}/{r['tcp_duplicates']}, "
              f"presses {r['pulse_presses']}, phone smoothed rtt {r['phone_rtt_ms_smoothed']} ms")
        continue
    t = r["hub_transports"]
    print(f"   {name}: built {r['built']}, sent {r['sent']}, not sent {r['not_sent']} | hub accepted {r['hub_accepted']} "
          f"missing {r['hub_missing']} loss {r['hub_loss_percent']}% | presses {r['pulse_presses']}")
    print(f"      hub udp packets {t['udp']['packets']} first {t['udp']['first_arrivals']} dup {t['udp']['duplicates']} "
          f"badtag {t['udp']['bad_tags']} malformed {t['udp']['malformed']} | tcp packets {t['tcp']['packets']} "
          f"first {t['tcp']['first_arrivals']} dup {t['tcp']['duplicates']} badtag {t['tcp']['bad_tags']} malformed {t['tcp']['malformed']}")
    for path in ["udp", "tcp"]:
        s = r["status"].get(path)
        if s:
            print(f"      STATUS on {path}: {s['count']} received, median interval {s['median_interval_ms']} ms, rtt min "
                  f"{ms(s['rtt_min_us'])} p50 {ms(s['rtt_p50_us'])} avg {ms(s['rtt_avg_us'])} p99 {ms(s['rtt_p99_us'])} max {ms(s['rtt_max_us'])}")

# ------------------------------------------------------------ controller mode ---
def taps(r):
    # The hub's own stats file keeps the canonical button order (PROTOCOL.md 12.2).
    hub_file = os.path.join(out, f"hub-{r['run']}.json")
    named = json.load(open(hub_file))["taps_emitted_by_name"] if os.path.exists(hub_file) else r["taps_emitted_by_name"]
    shown = [f"{k} {v}" for k, v in named.items() if v]
    return ", ".join(shown) if shown else "none"

def pad(r):
    p = r["pad_output"]
    return f"pad {p['kind']} ({p['selected']}{', plugged' if p['plugged'] else ', NOT plugged'})"

for name in ["pad_udp", "pad_tcp", "pad_multipath", "pad_switch", "pad_paused", "pad_failsafe"]:
    p = os.path.join(out, f"result-{name}.json")
    if not os.path.exists(p):
        print(f"   {name}: no result (the run did not finish)")
        ok = False
        continue
    r = json.load(open(p))
    if name == "pad_switch":
        print(f"   {name}: built {r['packets_built']}, hub accepted {r['hub_accepted']} missing {r['hub_missing']}, "
              f"epochs {r['epoch_changes']}, udp first/dup {r['udp_first']}/{r['udp_duplicates']}, tcp first/dup "
              f"{r['tcp_first']}/{r['tcp_duplicates']} | presses {r['pulse_presses']} | taps {taps(r)} | STATUS output "
              f"{' -> '.join(str(o) for o in r['status_outputs'])} | {pad(r)}, mode at end {r['mode_at_end']}")
        continue
    if name == "pad_paused":
        print(f"   {name}: built {r['packets_built']}, hub accepted {r['hub_accepted']} missing {r['hub_missing']} | "
              f"{r['packets_built_while_paused']} packets in {r['pause_ms']} ms paused, {r['status_while_paused']} STATUS, "
              f"max hold {r['max_hold_us_while_paused'] / 1000:.3f} ms | taps {taps(r)} | state {r['state']} | {pad(r)}")
        continue
    t = r["hub_transports"]
    replayed = sum(r["taps_replayed"])
    print(f"   {name} ({r['style']}): built {r['built']}, sent {r['sent']}, not sent {r['not_sent']}"
          f"{', not sent on udp only ' + str(r['not_sent_on_udp_only']) if r['not_sent_on_udp_only'] else ''} | hub accepted "
          f"{r['hub_accepted']} missing {r['hub_missing']} loss {r['hub_loss_percent']}% | taps {taps(r)} ({replayed} replayed) | "
          f"{pad(r)} | state {r['state']}")
    print(f"      hub udp packets {t['udp']['packets']} first {t['udp']['first_arrivals']} dup {t['udp']['duplicates']} "
          f"badtag {t['udp']['bad_tags']} malformed {t['udp']['malformed']} | tcp packets {t['tcp']['packets']} "
          f"first {t['tcp']['first_arrivals']} dup {t['tcp']['duplicates']} badtag {t['tcp']['bad_tags']} malformed {t['tcp']['malformed']}")
    for path in ["udp", "tcp"]:
        s = r["status"].get(path)
        if s:
            print(f"      STATUS on {path}: {s['count']} received, output byte {s['final_output']}, median interval "
                  f"{s['median_interval_ms']} ms, rtt min {ms(s['rtt_min_us'])} p50 {ms(s['rtt_p50_us'])} max {ms(s['rtt_max_us'])}")
print(f"== artifacts: {out}")
print("interop: PASS" if ok else "interop: FAIL")
sys.exit(0 if ok else 1)
PY
