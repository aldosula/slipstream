#!/usr/bin/env python3
"""Reference implementation of the Slipstream Link Protocol v1 (SLP/1).

This script is the single source of truth for byte layouts. It writes
docs/test-vectors.json, which the Android (Kotlin) and Hub (C#) test suites
both load and must reproduce byte for byte. If the protocol changes, change
this file first, regenerate, then make both implementations pass again.

Run:  python3 tools/gen_test_vectors.py
"""
import base64
import hashlib
import hmac
import json
import os
import struct

MAGIC0, MAGIC1, VERSION = 0x53, 0x4C, 1          # "SL", version 1
T_INPUT, T_STATUS, T_BEACON, T_PAD = 1, 2, 3, 4
INPUT_LEN, STATUS_LEN, PAD_LEN, TAG_LEN = 52, 44, 76, 8
PAD_BUTTONS = 18                                   # canonical pad buttons, bits 0..17
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"


# ---------------------------------------------------------------- pairing ---
def normalize_code(text):
    """Uppercase, drop separators, map look-alike digits. None if invalid."""
    out = []
    for ch in text.upper():
        if ch in "- \t":
            continue
        ch = {"0": "O", "1": "I", "8": "B"}.get(ch, ch)
        if ch not in B32:
            return None
        out.append(ch)
    s = "".join(out)
    return s if len(s) == 16 else None


def code_raw(code):
    return base64.b32decode(code)                       # 16 chars -> 10 bytes


def derive_key(code):
    return hashlib.sha256(b"slipstream/v1/key" + code_raw(code)).digest()


def fingerprint(key):
    return hashlib.sha256(b"slipstream/v1/fp" + key).digest()[:8]


def tag(key, body):
    return hmac.new(key, body, hashlib.sha256).digest()[:TAG_LEN]


def display_code(code):
    return "-".join(code[i:i + 4] for i in range(0, 16, 4))


# ---------------------------------------------------------------- packets ---
def input_packet(key, f):
    body = struct.pack(
        "<BBBBIIIhHHHHHI8sBBH",
        MAGIC0, MAGIC1, VERSION, T_INPUT,
        f["epoch"], f["seq"], f["t_us"],
        f["steer"], f["throttle"], f["brake"], f["clutch"], f["handbrake"], f["aux"],
        f["buttons"], bytes(f["pulses"]),
        f["flags"], 0, f["rtt_100us"],
    )
    assert len(body) == INPUT_LEN - TAG_LEN
    return body + tag(key, body)


def status_packet(key, f):
    body = struct.pack(
        "<BBBBIIIIIIHHBBH",
        MAGIC0, MAGIC1, VERSION, T_STATUS,
        f["epoch"], f["last_seq"], f["echo_t_us"], f["hold_us"],
        f["accepted"], f["missing"],
        f["rumble_strong"], f["rumble_weak"],
        f["output"], f["hub_flags"], 0,
    )
    assert len(body) == STATUS_LEN - TAG_LEN
    return body + tag(key, body)


def beacon_packet(key, f):
    name = f["name"].encode("utf-8")
    assert len(name) <= 32
    return struct.pack("<BBBBHH8sB", MAGIC0, MAGIC1, VERSION, T_BEACON,
                       f["udp_port"], f["tcp_port"], fingerprint(key), len(name)) + name


def pack_taps(taps):
    """18 four-bit tap counters, button 2k in the low nibble of byte k, 2k+1 in the high nibble."""
    assert len(taps) == PAD_BUTTONS and all(0 <= t <= 15 for t in taps)
    return bytes((taps[2 * k] & 0xF) | ((taps[2 * k + 1] & 0xF) << 4) for k in range(PAD_BUTTONS // 2))


def pad_packet(key, f):
    t0, t1 = f["touch"]
    body = struct.pack(
        "<BBBBIIIhhhhHHI9sBHHHHHBBhhhhhhH",
        MAGIC0, MAGIC1, VERSION, T_PAD,
        f["epoch"], f["seq"], f["t_us"],
        f["lx"], f["ly"], f["rx"], f["ry"], f["l2"], f["r2"],
        f["buttons"], pack_taps(f["taps"]),
        f["flags"], f["rtt_100us"],
        t0["x"], t0["y"], t1["x"], t1["y"],
        (0x80 if t0["active"] else 0) | (t0["id"] & 0x7F),
        (0x80 if t1["active"] else 0) | (t1["id"] & 0x7F),
        *f["gyro"], *f["accel"], 0,
    )
    assert len(body) == PAD_LEN - TAG_LEN, len(body)
    return body + tag(key, body)


def frame(packet):
    return struct.pack("<H", len(packet)) + packet


# ---------------------------------------------------------------- helpers ---
def seq_newer(a, b):
    """True when seq a is newer than seq b (serial-number arithmetic, u32)."""
    d = (a - b) & 0xFFFFFFFF
    return d != 0 and d < 0x80000000


def pulse_delta(new, old):
    """Presses to emit for a u8 pulse counter. >=128 is a reset, emit 0."""
    d = (new - old) & 0xFF
    return d if 1 <= d <= 127 else 0


def vjoy_steer(steer):
    return 1 + ((steer + 32767) * 32767 + 32767) // 65534


def vjoy_pedal(p):
    return 1 + (p * 32767 + 32767) // 65535


def x360_trigger(p):
    return p >> 8


def tap_delta(new, old):
    """Taps to account for from a 4-bit tap counter. >=8 is a reset, 0 taps."""
    d = (new - old) & 0xF
    return d if 1 <= d <= 7 else 0


def tap_schedule(output_down, held, d):
    """Reference tap scheduler (PROTOCOL.md 12.4) for one button on one accepted PAD packet.

    output_down: whether the virtual button is currently down (held or a replayed tap).
    Returns (gap_first, replay_taps): release for gap_ms first if gap_first, then replay_taps
    taps of tap_ms down and gap_ms up, then follow the held bit.
    """
    if d == 0:
        return (False, 0)
    replay = d - 1 if held else d
    return (output_down, replay)


def ds4_axis(v):
    """PAD stick axis (-32767..32767, +x right) to DualShock 4 byte 0..255, center 128."""
    return ((v + 32767) * 255 + 32767) // 65534


def ds4_axis_y(v):
    """PAD stick Y (+y up) to DualShock 4 byte, where 0 is up."""
    return ds4_axis(-v)


def ds4_trigger_digital(p):
    """DualShock 4 digital L2/R2 bit from an analog trigger."""
    return (p >> 8) >= 8


def hid_steer(steer):
    """Bluetooth HID report value for the steering axis, 0..65534 (center 32767)."""
    return steer + 32767


# ------------------------------------------------------------------ build ---
def main():
    code = "SLIPSTREAMTEST22"
    key = derive_key(code)
    inputs = [
        dict(epoch=0x1A2B3C4D, seq=1, t_us=123456789, steer=0, throttle=0, brake=0,
             clutch=0, handbrake=0, aux=0, buttons=0, pulses=[0] * 8, flags=0, rtt_100us=0),
        dict(epoch=0x1A2B3C4D, seq=2, t_us=123458789, steer=-32767, throttle=65535, brake=1234,
             clutch=40000, handbrake=65535, aux=0, buttons=0x00800001,
             pulses=[3, 255, 0, 1, 2, 4, 8, 16], flags=0b101, rtt_100us=42),
        dict(epoch=0xFFFFFFFF, seq=0xFFFFFFFF, t_us=0xFFFFFFFF, steer=32767, throttle=32768,
             brake=65535, clutch=1, handbrake=0, aux=0, buttons=0x00FF00FF,
             pulses=[127, 128, 200, 0, 0, 0, 0, 9], flags=0b010, rtt_100us=65535),
    ]
    statuses = [
        dict(epoch=0x1A2B3C4D, last_seq=2, echo_t_us=123458789, hold_us=350, accepted=2, missing=0,
             rumble_strong=0, rumble_weak=0, output=1, hub_flags=0),
        dict(epoch=0xFFFFFFFF, last_seq=0xFFFFFFFF, echo_t_us=0xFFFFFFFF, hold_us=49999,
             accepted=1000000, missing=17, rumble_strong=65535, rumble_weak=12345,
             output=0x82, hub_flags=0),
    ]
    beacon = dict(udp_port=47800, tcp_port=47802, name="RACING-PC")
    no_touch = dict(x=0, y=0, id=0, active=False)
    pads = [
        dict(epoch=0x1A2B3C4D, seq=3, t_us=123460789, lx=0, ly=0, rx=0, ry=0, l2=0, r2=0,
             buttons=0, taps=[0] * 18, flags=0, rtt_100us=0,
             touch=[no_touch, no_touch], gyro=[0, 0, 0], accel=[0, 0, 0]),
        dict(epoch=0x1A2B3C4D, seq=4, t_us=123462789, lx=-32767, ly=32767, rx=12345, ry=-23456,
             l2=65535, r2=4096, buttons=(1 << 0) | (1 << 5) | (1 << 12) | (1 << 17),
             taps=[1, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 15, 0, 0, 0, 0, 7], flags=0b0001_1100,
             rtt_100us=37,
             touch=[dict(x=65535, y=0, id=5, active=True), dict(x=32768, y=40000, id=127, active=True)],
             gyro=[-32767, 1600, 32767], accel=[0, -4096, 4096]),
        dict(epoch=0xFFFFFFFF, seq=0xFFFFFFFF, t_us=0xFFFFFFFF, lx=32767, ly=-32767, rx=-1, ry=1,
             l2=1, r2=65534, buttons=(1 << 18) - 1, taps=list(range(1, 16)) + [0, 8, 9],
             flags=0b0000_0001, rtt_100us=65535,
             touch=[dict(x=100, y=200, id=0, active=False), no_touch],
             gyro=[1, -1, 0], accel=[32767, -32767, 0]),
    ]

    vectors = {
        "protocol": "SLP/1",
        "note": "Generated by tools/gen_test_vectors.py. Do not edit by hand.",
        "pairing": {
            "code": code,
            "display": display_code(code),
            "raw_hex": code_raw(code).hex(),
            "key_hex": key.hex(),
            "fingerprint_hex": fingerprint(key).hex(),
            "normalize": [
                {"in": "slip-stre-amte-st22", "out": "SLIPSTREAMTEST22"},
                {"in": " SLIP STRE AMTE ST22 ", "out": "SLIPSTREAMTEST22"},
                {"in": "abcd-efgh-ijkl-mn0p", "out": "ABCDEFGHIJKLMNOP"},
                {"in": "1BCD-EFGH-IJKL-MNOP", "out": "IBCDEFGHIJKLMNOP"},
                {"in": "8BCD-EFGH-IJKL-MNOP", "out": "BBCDEFGHIJKLMNOP"},
                {"in": "ABCD-EFGH-IJKL-MNO", "out": None},
                {"in": "ABCD-EFGH-IJKL-MNOPQ", "out": None},
                {"in": "ABCD-EFGH-IJKL-MN9P", "out": None},
                {"in": "ABCD_EFGH_IJKL_MNOP", "out": None},
            ],
        },
        "input": [{"fields": f, "hex": input_packet(key, f).hex()} for f in inputs],
        "status": [{"fields": f, "hex": status_packet(key, f).hex()} for f in statuses],
        "beacon": {"fields": beacon, "hex": beacon_packet(key, beacon).hex()},
        "frame": {"packet_hex": input_packet(key, inputs[0]).hex(),
                  "hex": frame(input_packet(key, inputs[0])).hex()},
        "tamper": {
            "note": "input[1] with byte 16 (steer low byte) xor 0x01; tag must fail",
            "hex": (lambda b: (b[:16] + bytes([b[16] ^ 1]) + b[17:]).hex())(input_packet(key, inputs[1])),
        },
        "seq_newer": [
            {"a": a, "b": b, "newer": seq_newer(a, b)}
            for a, b in [(2, 1), (1, 2), (5, 5), (0, 0xFFFFFFFF), (0xFFFFFFFF, 0),
                         (0x80000000, 0), (0x7FFFFFFF, 0), (10, 0xFFFFFFF0)]
        ],
        "pulse_delta": [
            {"new": n, "old": o, "presses": pulse_delta(n, o)}
            for n, o in [(1, 0), (0, 255), (5, 5), (130, 0), (127, 0), (128, 0), (3, 250)]
        ],
        "map_vjoy_steer": [{"in": s, "out": vjoy_steer(s)} for s in [-32767, -16384, 0, 1, 16384, 32767]],
        "map_vjoy_pedal": [{"in": p, "out": vjoy_pedal(p)} for p in [0, 1, 2, 32767, 32768, 65534, 65535]],
        "map_x360_trigger": [{"in": p, "out": x360_trigger(p)} for p in [0, 255, 256, 32768, 65535]],
        "map_hid_steer": [{"in": s, "out": hid_steer(s)} for s in [-32767, 0, 32767]],
        "pad": [{"fields": f, "hex": pad_packet(key, f).hex()} for f in pads],
        "pad_frame": {"packet_hex": pad_packet(key, pads[1]).hex(),
                      "hex": frame(pad_packet(key, pads[1])).hex()},
        "pad_tamper": {
            "note": "pad[1] with byte 53 (touch1 id) xor 0x01; tag must fail",
            "hex": (lambda b: (b[:53] + bytes([b[53] ^ 1]) + b[54:]).hex())(pad_packet(key, pads[1])),
        },
        "tap_delta": [
            {"new": n, "old": o, "taps": tap_delta(n, o)}
            for n, o in [(1, 0), (0, 15), (5, 5), (7, 0), (8, 0), (9, 0), (2, 12), (15, 0)]
        ],
        "tap_schedule": [
            {"output_down": od, "held": h, "d": d,
             "gap_first": tap_schedule(od, h, d)[0], "replay_taps": tap_schedule(od, h, d)[1]}
            for od in (False, True) for h in (False, True) for d in (0, 1, 3)
        ],
        "map_ds4_axis": [{"in": v, "out": ds4_axis(v)} for v in [-32767, -16384, -1, 0, 1, 16384, 32767]],
        "map_ds4_axis_y": [{"in": v, "out": ds4_axis_y(v)} for v in [-32767, 0, 32767]],
        "map_ds4_trigger_digital": [{"in": p, "out": ds4_trigger_digital(p)}
                                    for p in [0, 2047, 2048, 65535]],
    }

    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "docs", "test-vectors.json")
    with open(out, "w") as fh:
        json.dump(vectors, fh, indent=2)
        fh.write("\n")
    print("wrote", os.path.normpath(out))


if __name__ == "__main__":
    main()
