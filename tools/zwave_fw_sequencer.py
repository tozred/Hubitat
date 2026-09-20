#!/usr/bin/env python3
"""Run Hubitat's Device Firmware Updater (built-in app) over several Z-Wave nodes, one after another.

The app only handles one device at a time and has no queue, so this drives its JSON form endpoints:
select node -> read firmware targets -> pick target + file -> start -> wait -> re-read version.
Never aborts a transfer; a device that stops answering is given time, then the next one is tried.

Usage: zwave_fw_sequencer.py --hub http://<hub-ip> --app 115 --file NAME.gbl --want 14.01 8 6 7 9
"""
import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Dfu:
    def __init__(self, hub, app):
        self.hub, self.app = hub.rstrip("/"), app

    def get(self, path):
        with urllib.request.urlopen(f"{self.hub}/{path}", timeout=30) as r:
            return json.load(r)

    def page(self, name):
        return self.get(f"installedapp/configure/json/{self.app}/{name}")

    def state(self, key):
        for s in self.get(f"installedapp/statusJson/{self.app}").get("appState", []):
            if s.get("name") == key:
                v = s.get("value")
                if isinstance(v, str):
                    try:
                        return json.loads(v)
                    except ValueError:
                        try:
                            return json.loads(v.replace("'", '"').replace("None", "null")
                                              .replace("True", "true").replace("False", "false"))
                        except ValueError:
                            return v
                return v
        return None

    def set(self, page, key, value):
        j = self.page(page)
        app = j["app"]
        form = [("id", self.app), ("version", app.get("version", 1)), ("appTypeId", app["appType"]["id"]),
                ("appTypeName", app["appType"]["name"]), ("currentPage", page), ("formAction", "update"),
                ("pageBreadcrumbs", "[]"), ("referrer", f"{self.hub}/installedapp/configure/{self.app}/{page}"),
                (f"{key}.type", "enum"), (f"{key}.multiple", "false"), (f"settings[{key}]", value)]
        req = urllib.request.Request(f"{self.hub}/installedapp/update/json", data=urllib.parse.urlencode(form).encode())
        urllib.request.urlopen(req, timeout=30).read()

    def clear_progress(self, avoid):
        """Drop a finished-but-still-'active' progress record.

        The app keeps the record until another device is selected, and while it is there every
        new update request stops at STARTING. It also survives a hub restart, so clearing it is
        the only way to make a second update work.
        """
        decoy = "6" if str(avoid) != "6" else "7"
        self.set("zwaveUpdatePage", "deviceToUpdate", decoy)
        time.sleep(4)

    def targets(self, node, tries=4):
        """Select the node and return {target: 'text'}; the app queries the device on selection."""
        for attempt in range(tries):
            self.set("zwaveUpdatePage", "deviceToUpdate", str(node))
            time.sleep(6)
            det = self.state("zwaveSelectionDetails") or {}
            if isinstance(det, dict) and det.get("nodeId") == node and det.get("targetOptions"):
                return det["targetOptions"]
            log(f"node {node}: no answer to the firmware metadata request (try {attempt + 1}/{tries})")
            time.sleep(10)
        return None


def version_of(targets):
    m = re.search(r"Version: ([0-9.]+)", (targets or {}).get("0", ""))
    return m.group(1) if m else None


def wait_idle(dfu, limit=900):
    """If an update is still marked active, give it up to `limit` seconds to settle."""
    start = time.time()
    while time.time() - start < limit:
        p = dfu.state("zwaveProgress") or {}
        if not (isinstance(p, dict) and p.get("active")):
            return True
        time.sleep(15)
    return False


def update(dfu, node, filename, want):
    dfu.clear_progress(node)
    targets = dfu.targets(node)
    if not targets:
        log(f"node {node}: SKIPPED - device does not answer")
        return "no-answer"
    before = version_of(targets)
    log(f"node {node}: currently {before}")
    if before == want:
        return "already"
    dfu.set("zwaveUpdatePage", "firmwareTarget", "0")
    dfu.set("zwaveUpdatePage", "firmwareFile", filename)
    dfu.page("zwaveProgressPage")                       # opening this page starts the update
    log(f"node {node}: update started ({filename})")
    last_pct, stage_since, last_stage = -1, time.time(), None
    while True:
        time.sleep(20)
        p = dfu.state("zwaveProgress") or {}
        if not isinstance(p, dict):
            continue
        stage, pct = p.get("stage"), p.get("percent") or 0
        if stage != last_stage:
            last_stage, stage_since = stage, time.time()
            log(f"node {node}: stage {stage} - {p.get('status')}")
        if stage == "TRANSFERRING" and pct // 25 > last_pct // 25:
            last_pct = pct
            log(f"node {node}: {pct}%")
        if p.get("completed") or p.get("failed") or not p.get("active"):
            log(f"node {node}: updater finished - completed={p.get('completed')} failed={p.get('failed')} {p.get('error') or ''}")
            break
        if stage != "TRANSFERRING" and time.time() - stage_since > 600:
            log(f"node {node}: no report from the device for 10 min in stage {stage} - moving on")
            break
    time.sleep(45)                                       # let the device reboot
    dfu.clear_progress(node)                             # else the version read returns the cached value
    after = version_of(dfu.targets(node))
    log(f"node {node}: RESULT {before} -> {after}")
    return after


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hub", required=True)
    ap.add_argument("--app", type=int, required=True)
    ap.add_argument("--file", required=True)
    ap.add_argument("--want", required=True)
    ap.add_argument("nodes", type=int, nargs="+")
    a = ap.parse_args()
    dfu = Dfu(a.hub, a.app)
    # A record left "active" by an earlier session survives a hub restart, so drop it first
    # instead of letting wait_idle burn its full timeout on it.
    dfu.clear_progress(a.nodes[0])
    if not wait_idle(dfu, limit=60):
        log("an earlier update is still marked active after 15 min; continuing anyway")
    results = {}
    for node in a.nodes:
        try:
            results[node] = update(dfu, node, a.file, a.want)
        except Exception as err:                         # keep going with the next relay
            log(f"node {node}: error {err}")
            results[node] = f"error: {err}"
    log(f"ALL DONE: {results}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
