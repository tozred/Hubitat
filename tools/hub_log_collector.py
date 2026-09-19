#!/usr/bin/env python3
"""Continuously save a Hubitat hub's log to disk (the hub itself only keeps a few hours).

Polls /logs/past/json, appends every line not seen before to one file per day, and keeps a
small state file so restarts do not duplicate lines. Also snapshots hub events once an hour.
Standard library only.

Usage: hub_log_collector.py [--hub http://10.20.20.4] [--out DIR] [--every 60]
Run detached:  nohup caffeinate -i python3 tools/hub_log_collector.py >/dev/null 2>&1 &
"""
import argparse
import json
import os
import re
import time
import urllib.request

STAMP = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\t")


def fetch(url, timeout=40):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hub", default="http://10.20.20.4")
    ap.add_argument("--out", default=os.path.expanduser("~/Documents/GitHub/hubitat-backups/home/logs"))
    ap.add_argument("--every", type=int, default=60)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    state_file = os.path.join(a.out, ".collector-state.json")
    try:
        state = json.load(open(state_file))
    except (OSError, ValueError):
        state = {"last_ts": "", "last_lines": []}
    last_events = 0

    while True:
        try:
            raw = fetch(f"{a.hub}/logs/past/json")
            # the hub splits multi-line messages into separate entries; glue those back on
            lines = []
            for entry in raw:
                if STAMP.match(entry) or not lines:
                    lines.append(entry)
                else:
                    lines[-1] += "\\n" + entry
            lines = [l for l in lines if STAMP.match(l)]
            # lines are chronological "YYYY-MM-DD HH:MM:SS.mmm\tLEVEL\t..."; keep those newer than the
            # last timestamp, plus same-timestamp lines not written yet
            fresh = [l for l in lines if l[:23] > state["last_ts"] or (l[:23] == state["last_ts"] and l not in state["last_lines"])]
            if lines and state["last_ts"] and lines[0][:23] > state["last_ts"]:
                fresh.insert(0, f"{lines[0][:23]}\tWARN \tsys|0|collector|GAP: hub log buffer no longer reaches back to {state['last_ts']}")
            by_day = {}
            for l in fresh:
                by_day.setdefault(l[:10], []).append(l)
            for day, ls in by_day.items():
                with open(os.path.join(a.out, f"hub-log-{day}.tsv"), "a") as f:
                    f.write("\n".join(x.replace("\n", "\\n") for x in ls) + "\n")
            if fresh:
                ts = fresh[-1][:23]
                state = {"last_ts": ts, "last_lines": [l for l in fresh if l[:23] == ts]}
                json.dump(state, open(state_file, "w"))
            if time.time() - last_events > 3600:
                ev = fetch(f"{a.hub}/hub/eventsJson?max=200")
                json.dump(ev, open(os.path.join(a.out, "hub-events-latest.json"), "w"))
                last_events = time.time()
        except Exception as err:  # network hiccup, hub reboot: keep going
            with open(os.path.join(a.out, "collector-errors.log"), "a") as f:
                f.write(f"{time.strftime('%F %T')} {type(err).__name__}: {err}\n")
        time.sleep(a.every)


if __name__ == "__main__":
    main()
