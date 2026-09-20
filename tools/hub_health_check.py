#!/usr/bin/env python3
"""Daily Hubitat health check: quiet devices, low batteries, hub alerts, noisy logs.

Reads the hub's local admin endpoints (hub login security must be off) and prints a
short plain-text report. First line is always one of:
    STATUS: OK | STATUS: ISSUES | STATUS: UNREACHABLE
The hubs are on different sites, so normally only one is reachable: a hub that does not
answer is mentioned but only counts as UNREACHABLE when none of them answers.
Standard library only. Usage: hub_health_check.py [--hub URL ...] [--json]
"""
import argparse
import collections
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone

# Hours without any sign of life before a device is reported
QUIET_BATTERY_H = 48     # battery sensors/buttons check in at least daily
QUIET_MAINS_H = 72       # some mains drivers only report on change
QUIET_MAINS_SENSOR_H = 24  # a powered sensor reports readings, not just state changes
QUIET_LAN_H = 7 * 24     # LAN integrations that should see regular use (Nuki)
LOW_BATTERY_PCT = 15
NOISY_LOG_LINES = 200    # WARN/ERROR lines from one source within the log buffer

# Drivers that legitimately stay silent (virtual, groups, phones, media players, IR blasters)
IGNORED_TYPES = ("Virtual", "Group", "Mobile App Device", "AirPlay", "SwitchBot")
LAN_WATCHED_TYPES = ("Nuki Smart Lock", "Nuki Opener")

# Devices deliberately out of service, by device name. Listed in the report so they are not
# forgotten, but never counted as a problem; remove an entry when the device goes back in use.
# This is local config - set HUBITAT_PARKED to "Name=reason;Name=reason" to fill it, so that
# a device someone else happens to have named "Fan" is not silently ignored.
PARKED = dict(
    entry.split("=", 1) for entry in os.environ.get("HUBITAT_PARKED", "").split(";") if "=" in entry
)


def fetch(hub, path, timeout=12):
    with urllib.request.urlopen(f"{hub}/{path}", timeout=timeout) as r:
        return json.load(r)


def parse_ts(value):
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%S%z") if value else None


def last_real_report(hub, device_id):
    """Newest event the device itself produced, or None.

    lastActivity is not trustworthy on its own: saving a preference re-runs the driver's
    initialize(), which stamps lastActivity and files "Initialized" events, so a device that
    dropped off the mesh weeks ago looks alive right after any settings change. Commands the
    hub sent (digital, command-*) say nothing about the device either.
    """
    try:
        events = fetch(hub, f"device/eventsJson/{device_id}?max=30")
    except OSError:
        return None
    for event in events:                       # newest first
        if event.get("digital") or str(event.get("name", "")).startswith("command-"):
            continue
        if event.get("descriptionText") == "Initialized":
            continue
        try:
            return datetime.strptime(event["date"], "%Y-%m-%dT%H:%M:%S.%f%z")
        except (KeyError, ValueError):
            continue
    return None


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

    quiet, low_battery, parked = [], [], []
    for dev in devices:
        if dev.get("disabled") or any(t in dev["type"] for t in IGNORED_TYPES):
            continue
        if dev["name"] in PARKED:
            parked.append((dev["name"], PARKED[dev["name"]]))
            continue
        states = {s["key"]: s["value"] for s in dev.get("currentStates", [])}
        has_battery = "battery" in states

        if dev.get("isZigbee"):
            if has_battery:
                limit = QUIET_BATTERY_H
            elif any(t in dev["type"] for t in ("Presence", "Motion", "Sensor")):
                limit = QUIET_MAINS_SENSOR_H
            else:
                limit = QUIET_MAINS_H
        elif dev["type"] in LAN_WATCHED_TYPES:
            limit = QUIET_LAN_H
        else:
            continue

        # Either signal counts as life. Contact sensors only file an event when they actually
        # open or close, so a quiet door can sit two days between events while still checking
        # in over the radio; conversely sleepy devices are often missing from the Zigbee table
        # altogether. A device that has gone dark really does go quiet on both at once.
        seen = [last_real_report(hub, dev["id"]),
                parse_ts(zigbee.get(dev["id"], {}).get("lastMessage"))]
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
        "hubName": hub_data.get("name") or hub,
        "hubVersion": hub_data.get("version"),
        "deviceCount": len(devices),
        "quiet": [{"id": d["id"], "name": d["name"], "room": d.get("roomName") or "-",
                   "silentFor": age_text(None if h == float("inf") else h),
                   "battery": has_batt(d)} for h, d in sorted(quiet, key=lambda x: -x[0])],
        "lowBattery": [{"id": d["id"], "name": d["name"], "battery": int(p)} for p, d in sorted(low_battery)],
        "hubAlerts": alerts,
        "noisyLogs": [{"source": n, "lines": c} for n, c in noisy],
        "parked": [{"name": n, "reason": r} for n, r in sorted(parked)],
    }


def has_batt(dev):
    return any(s["key"] == "battery" for s in dev.get("currentStates", []))


def render_hub(result):
    lines = [f"[{result['hubName']}] Hubitat {result['hubVersion']}, {result['deviceCount']} devices checked"]
    if result["deviceCount"] == 0:
        lines.append("  !! Hub reports ZERO devices - database loss? Check Settings > Backup and Restore.")
    if result["quiet"]:
        lines.append("  Silent devices (likely dead battery or dropped off the mesh):")
        lines += [f"    - {q['name']} ({q['room']}): silent {q['silentFor']}" for q in result["quiet"]]
    if result["lowBattery"]:
        lines.append("  Low battery (still reporting):")
        lines += [f"    - {b['name']}: {b['battery']}%" for b in result["lowBattery"]]
    if result["hubAlerts"]:
        lines.append("  Hub alerts: " + ", ".join(result["hubAlerts"]))
    if result["noisyLogs"]:
        lines.append("  Warnings/errors flooding the log:")
        lines += [f"    - {n['source']}: {n['lines']} lines" for n in result["noisyLogs"]]
    if result["parked"]:
        lines.append("  Out of service on purpose (not a fault):")
        lines += [f"    - {p['name']}: {p['reason']}" for p in result["parked"]]
    return "\n".join(lines)


def has_issues(result):
    return bool(result["quiet"] or result["lowBattery"] or result["hubAlerts"] or result["noisyLogs"]
                or result["deviceCount"] == 0)


# Hubs checked when --hub is not given. Set HUBITAT_HUBS to a comma-separated list of base
# URLs, e.g. HUBITAT_HUBS="http://192.168.1.10,http://192.168.2.10"
DEFAULT_HUBS = [h.strip() for h in os.environ.get("HUBITAT_HUBS", "").split(",") if h.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--hub", action="append",
                        help="hub base URL (repeatable); defaults to $HUBITAT_HUBS")
    parser.add_argument("--json", action="store_true", help="print raw results as JSON")
    args = parser.parse_args()

    hubs = args.hub or DEFAULT_HUBS
    if not hubs:
        print("No hub given. Pass --hub http://<ip> (repeatable), or set HUBITAT_HUBS.")
        return 2

    results, unreachable = [], []
    for hub in hubs:
        try:
            results.append(check(hub.rstrip("/")))
        except OSError as err:
            unreachable.append(f"{hub} ({err})")

    if args.json:
        print(json.dumps({"results": results, "unreachable": unreachable}, indent=2))
        return 0
    if not results:
        print("STATUS: UNREACHABLE\nNo hub answered: " + "; ".join(unreachable))
        return 0
    print(f"STATUS: {'ISSUES' if any(has_issues(r) for r in results) else 'OK'}")
    print("\n\n".join(render_hub(r) for r in results))
    if unreachable:
        print("\nNot reachable from this network (normal when the Mac is at the other site): " + "; ".join(unreachable))
    return 0


if __name__ == "__main__":
    sys.exit(main())
