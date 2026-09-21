#!/usr/bin/env python3
"""Queue Z-Wave firmware updates through the hub's native updater (Settings > Z-Wave Details).

This uses /hub/zwave/deviceFirmware/{devices,details,files,start,progress}, the API behind the
"Device firmware updater" dialog. It replaces the older Device Firmware Updater *app*, which
kept a per-session state that wedged after the first update.

The hub runs one transfer at a time, so nodes are done in sequence: wait for any running
transfer to end, start the next, then poll until it finishes. Progress is logged only when it
changes. Written for the Z-Wave JS stack (Settings > Z-Wave Details > Switch to ZWaveJS), which
transfers one Shelly Wave relay in ~15 minutes; the legacy stack stalled indefinitely instead.

Note: switching a hub to Z-Wave JS resets the radio region (EU became US here), which makes
every device unreachable until it is set back and the hub is rebooted.

Usage: zwave_fw_queue.py --hub http://192.168.1.111 --file NAME.gbl 7 9
"""
import argparse
import json
import sys
import time
import urllib.request

POLL_S = 30
STALL_LIMIT_S = 20 * 60      # no change at all for this long -> give up on that node
MAX_NODE_S = 90 * 60         # absolute cap per node (Z-Wave JS does one in ~15 min)


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


class Hub:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def get(self, path):
        with urllib.request.urlopen(f"{self.base}/{path}", timeout=30) as r:
            return json.load(r)

    def post(self, path, payload):
        req = urllib.request.Request(f"{self.base}/{path}", data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)

    def progress(self, node):
        try:
            return (self.get(f"hub/zwave/deviceFirmware/progress?nodeId={node}") or {}).get("progress") or {}
        except OSError:
            return {}

    def version(self, node):
        """Target 0's firmware version, read straight from the device."""
        try:
            d = self.get(f"hub/zwave/deviceFirmware/details?nodeId={node}")
        except OSError:
            return None
        for t in (d.get("targets") or []):
            if str(t.get("target")) in ("0", "None") or t.get("target") == 0:
                return t.get("version")
        return (d.get("targets") or [{}])[0].get("version")


ACTIVE_STAGES = ("SENDING", "PROCESS", "TRANSFERRING", "STARTING", "FLASHING", "VERSION_CC_GET")


def active(p):
    """True while a transfer is running.

    Z-Wave JS uses SENDING/PROCESS; the legacy stack used TRANSFERRING. When the device
    reboots to install, the endpoint answers NOTFOUND - that means finished, not running.
    """
    return bool(p) and p.get("stage") in ACTIVE_STAGES


def wait_for_free(hub, nodes, limit=MAX_NODE_S):
    """Block while any node still has a transfer running."""
    start = time.time()
    while time.time() - start < limit:
        busy = [n for n in nodes if active(hub.progress(n))]
        if not busy:
            return True
        time.sleep(POLL_S)
    return False


def run_node(hub, node, filename):
    before = hub.version(node)
    log(f"node {node}: firmware {before} - starting {filename}")
    try:
        r = hub.post("hub/zwave/deviceFirmware/start", {"nodeId": node, "target": 0, "fileName": filename})
    except OSError as err:
        log(f"node {node}: start failed ({err})")
        return f"start-failed: {err}"
    if not r.get("success"):
        log(f"node {node}: hub refused the start - {r.get('message')}")
        return f"refused: {r.get('message')}"

    began, last_change, last_pct = time.time(), time.time(), None
    while time.time() - began < MAX_NODE_S:
        time.sleep(POLL_S)
        p = hub.progress(node)
        pct, stage = p.get("percent"), p.get("stage")
        if pct != last_pct:
            log(f"node {node}: {pct}% ({stage})")
            last_pct, last_change = pct, time.time()
        if not active(p):
            log(f"node {node}: updater reports {stage or 'idle'}")
            break
        if time.time() - last_change > STALL_LIMIT_S:
            log(f"node {node}: no change for {STALL_LIMIT_S // 60} min - moving on")
            break

    time.sleep(60)                                  # let the device reboot and settle
    after = hub.version(node)
    log(f"node {node}: RESULT {before} -> {after}")
    return after


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--hub", required=True)
    ap.add_argument("--file", required=True)
    ap.add_argument("nodes", type=int, nargs="+")
    a = ap.parse_args()
    hub = Hub(a.hub)

    all_nodes = sorted({n for n in a.nodes} | {d.get("nodeId") for d in
                                               (hub.get("hub/zwave/deviceFirmware/devices").get("devices") or [])
                                               if d.get("nodeId")})
    results = {}
    for node in a.nodes:
        log(f"node {node}: waiting for the hub's updater to be free")
        if not wait_for_free(hub, all_nodes):
            log("another transfer is still running after the cap - stopping")
            break
        try:
            results[node] = run_node(hub, node, a.file)
        except Exception as err:                    # never let one node stop the queue
            log(f"node {node}: error {err}")
            results[node] = f"error: {err}"
    log(f"QUEUE DONE: {results}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
