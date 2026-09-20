#!/bin/bash
# Update Z-Wave relays one per hub boot.
#
# Hubitat's Z-Wave firmware engine appears to allow a single update session per boot: the first
# attempt after a restart transfers, and every later one stops at STARTING - even after the
# updater app's progress record is cleared. So restart the hub before each device.
#
# Before each restart the fridge plug is switched on, so a restart never leaves the fridge off.
# Usage: zwave_fw_reboot_cycle.sh <file.gbl> <want-version> <node> [node ...]
set -u
HUB=http://192.168.1.111
APP=115
FRIDGE_PLUG=135
REPO=/Users/ben/Documents/GitHub/hubitat
GMCP=/private/tmp/claude-501/-Users-ben-Documents-GitHub-hubitat/a334f75b-c2fc-49a6-ad14-734dd7af34e4/scratchpad/gmcp.py
FILE=$1; WANT=$2; shift 2

hub_up() {
  for _ in $(seq 1 60); do
    curl -sS -m 5 "$HUB/hub2/hubData" 2>/dev/null | python3 -c 'import sys,json; json.load(sys.stdin)' 2>/dev/null && return 0
    sleep 5
  done
  return 1
}

for NODE in "$@"; do
  echo "$(date +%H:%M:%S) ===== node $NODE ====="

  echo "$(date +%H:%M:%S) switching the fridge plug on before the restart"
  python3 "/private/tmp/claude-501/-Users-ben-Documents-GitHub-hubitat/a334f75b-c2fc-49a6-ad14-734dd7af34e4/scratchpad/gmcp.py" call run_device_command "{\"deviceId\":$FRIDGE_PLUG,\"command\":\"on\"}" 60 >/dev/null 2>&1 || true
  sleep 5

  echo "$(date +%H:%M:%S) restarting the hub"
  curl -sS -m 15 -X POST -o /dev/null "$HUB/hub/reboot" || true
  sleep 45
  if ! hub_up; then echo "$(date +%H:%M:%S) hub did not come back - stopping"; exit 1; fi
  sleep 30
  echo "$(date +%H:%M:%S) hub is back"

  python3 -u "$REPO/tools/zwave_fw_sequencer.py" --hub "$HUB" --app "$APP" --file "$FILE" --want "$WANT" "$NODE"
done
echo "$(date +%H:%M:%S) ===== all nodes attempted ====="
