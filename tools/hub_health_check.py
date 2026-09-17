#!/usr/bin/env python3
"""Daily Hubitat health check: quiet devices, low batteries, hub alerts, noisy logs.

Reads the hub's local admin endpoints (hub login security must be off) and prints a
short plain-text report. First line is always one of:
    STATUS: OK | STATUS: ISSUES | STATUS: UNREACHABLE
Standard library only. Usage: hub_health_check.py [--hub http://10.20.20.4] [--json]
"""
import argparse
import collections
import json
import sys
import urllib.request
from datetime import datetime, timezone

# Hours without any sign of life before a device is reported
QUIET_BATTERY_H = 48     # battery sensors/buttons check in at least daily
QUIET_MAINS_H = 72       # some mains drivers only report on change
QUIET_LAN_H = 7 * 24     # LAN integrations that should see regular use (Nuki)
LOW_BATTERY_PCT = 15
NOISY_LOG_LINES = 200    # WARN/ERROR lines from one source within the log buffer

# Drivers that legitimately stay silent (virtual, groups, phones, media players, IR blasters)
IGNORED_TYPES = ("Virtual", "Group", "Mobile App Device", "AirPlay", "SwitchBot")
LAN_WATCHED_TYPES = ("Nuki Smart Lock", "Nuki Opener")


def fetch(hub, path, timeout=25):
    with urllib.request.urlopen(f"{hub}/{path}", timeout=timeout) as r:
        return json.load(r)


def parse_ts(value):
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%S%z") if value else None


def flatten(nodes):
    for node in nodes:
        yield node["data"]
        yield from flatten(node.get("children", []))


def age_text(hours):
    if hours is None:
        return "never"
    return f"{hours:.0f}h" if hours < 72 else f"{hours / 24:.0f}d"


def check(hub):
    now = datetime.now(timezone.utc)
    devices = list(flatten(fetch(hub, "hub2/devicesList")["devices"]))
    zigbee = {d["id"]: d for d in fetch(hub, "hub/zigbeeDetails/json").get("devices", [])}
    hub_data = fetch(hub, "hub2/hubData")

    quiet, low_battery = [], []
    for dev in devices:
        if dev.get("disabled") or any(t in dev["type"] for t in IGNORED_TYPES):
            continue
        states = {s["key"]: s["value"] for s in dev.get("currentStates", [])}
        has_battery = "battery" in states

        if dev.get("isZigbee"):
            limit = QUIET_BATTERY_H if has_battery else QUIET_MAINS_H
        elif dev["type"] in LAN_WATCHED_TYPES:
            limit = QUIET_LAN_H
        else:
            continue

        # Mains drivers may only report on change, so radio traffic counts as life for them.
        # A sensor that still pings the radio but produces no readings is broken, so
        # battery devices are judged on driver events alone.
        seen = [parse_ts(dev.get("lastActivity"))]
        if not has_battery:
            seen.append(parse_ts(zigbee.get(dev["id"], {}).get("lastMessage")))
        seen = [t for t in seen if t]
        hours = (now - max(seen)).total_seconds() / 3600 if seen else None
        if hours is None or hours > limit:
            quiet.append((hours if hours is not None else float("inf"), dev))

        try:
            pct = float(states.get("battery"))
            if pct <= LOW_BATTERY_PCT and (hours is not None and hours <= limit):
                low_battery.append((pct, dev))
        except (TypeError, ValueError):
            pass

    alerts = [k for k, v in hub_data.get("alerts", {}).items() if v is True]

    noisy = collections.Counter()
    for line in fetch(hub, "logs/past/json"):
        parts = line.split("\t", 2)
        if len(parts) == 3 and parts[1].strip() in ("WARN", "ERROR"):
            source = parts[2].split("|")
            noisy[source[2] if len(source) > 2 else "hub"] += 1
    noisy = [(n, c) for n, c in noisy.most_common() if c >= NOISY_LOG_LINES]

    return {
        "hubVersion": hub_data.get("version"),
        "deviceCount": len(devices),
        "quiet": [{"id": d["id"], "name": d["name"], "room": d.get("roomName") or "-",
                   "silentFor": age_text(None if h == float("inf") else h),
                   "battery": has_batt(d)} for h, d in sorted(quiet, key=lambda x: -x[0])],
        "lowBattery": [{"id": d["id"], "name": d["name"], "battery": int(p)} for p, d in sorted(low_battery)],
        "hubAlerts": alerts,
        "noisyLogs": [{"source": n, "lines": c} for n, c in noisy],
    }


def has_batt(dev):
    return any(s["key"] == "battery" for s in dev.get("currentStates", []))


def render(result):
    issues = result["quiet"] or result["lowBattery"] or result["hubAlerts"] or result["noisyLogs"]
    lines = [f"STATUS: {'ISSUES' if issues else 'OK'}",
             f"Hubitat {result['hubVersion']}, {result['deviceCount']} devices checked"]
    if result["quiet"]:
        lines.append("\nSilent devices (likely dead battery or dropped off the mesh):")
        lines += [f"  - {q['name']} ({q['room']}): silent {q['silentFor']}" for q in result["quiet"]]
    if result["lowBattery"]:
        lines.append("\nLow battery (still reporting):")
        lines += [f"  - {b['name']}: {b['battery']}%" for b in result["lowBattery"]]
    if result["hubAlerts"]:
        lines.append("\nHub alerts: " + ", ".join(result["hubAlerts"]))
    if result["noisyLogs"]:
        lines.append("\nWarnings/errors flooding the log:")
        lines += [f"  - {n['source']}: {n['lines']} lines" for n in result["noisyLogs"]]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--hub", default="http://10.20.20.4")
    parser.add_argument("--json", action="store_true", help="print raw result as JSON")
    args = parser.parse_args()
    try:
        result = check(args.hub.rstrip("/"))
    except OSError as err:
        print(f"STATUS: UNREACHABLE\nCould not reach the hub at {args.hub}: {err}")
        return 0
    print(json.dumps(result, indent=2) if args.json else render(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
