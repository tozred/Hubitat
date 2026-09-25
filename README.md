# Hubitat Drivers and Apps

Custom Hubitat Elevation drivers and apps for smart home automation.

## Contents

### Drivers

#### Sonoff TRVZB
**Location:** `Drivers/Sonoff/zigbee-sonoff-trvzb.groovy`

A comprehensive driver for the SONOFF TRVZB Thermostatic Radiator Valve.

**Features:**
- Full thermostat control (temperature, setpoint, mode)
- Child lock control
- Window open detection
- Frost protection
- Valve position monitoring and control
- Temperature calibration
- External temperature sensor support
- Battery monitoring
- Robust error handling and auto-recovery
- **Firmware 1.4.x features:** boost mode, timer mode (hold a temperature for N minutes) and
  smart temperature control, via the Sonoff custom cluster `0xFC11`
- `initialize` exposed as a command, to restore polling and health-check schedules without
  re-saving preferences

**Supported Models:** SONOFF TRVZB

---

#### Sonoff SNZB-04P Contact Sensor
**Location:** `Drivers/Sonoff/sonoff_snzb04p_contact.groovy`

Driver for the Sonoff SNZB-04P door/window contact sensor with tamper detection.

**Features:**
- Contact sensor (open/closed)
- **Tamper detection** (via Sonoff custom cluster FC11)
- Battery monitoring (percentage and voltage)
- Battery low warning
- Auto-reset tamper option (1 min, 5 min, 1 hour, or manual)
- Last tamper time tracking
- IAS Zone status parsing

**Supported Models:** SNZB-04P

**Note:** The tamper sensor is triggered when the back cover is removed or the tamper button is pressed. This is useful for security monitoring.

---

#### Sonoff SWV Water Valve
**Location:** `Drivers/Sonoff/sonoff_swv_water_valve.groovy`

One driver covering two Sonoff water-valve families, branched on the reported Zigbee model.
Built from the `SWV` definition in Koenkk/zigbee-herdsman-converters (`src/devices/sonoff.ts`).

**Features:**
- Valve open/close with state reporting
- Battery monitoring
- Flow rate (m³/h) via the Flow Measurement cluster
- Leak and water-shortage status
- Auto-close on water shortage (firmware 1.0.4+)
- Real-time and hourly irrigation volume and duration
- Cyclic irrigation (SWV BSP/NH)
- Child lock and fail-safe status (Hydro models)
- `genTime` sync, which the Hydro models require

**Supported Models:** SWV-BSP, SWV-NH, and Hydro SWV-ZFE / ZFU / ZNE / ZNU

**Note:** the two families are not just cosmetically different: the eWeLink custom cluster
(`0xFC11`) encodes its numerics **little-endian** on SWV BSP/NH and **big-endian** on the
Hydro models. The driver picks the right one from the model string. Irrigation plans, rain
delay, seasonal adjustment and the 30-day history are not implemented yet.

---

#### Nuki Bridge and Opener (patched)
**Location:** `Drivers/Nuki/nuki-bridge-maffpt-patched.groovy`, `Drivers/Nuki/nuki-opener-maffpt-patched.groovy`

Patched versions of Marco Felicio's (`maffpt`) Nuki drivers, kept here because the upstream
versions have two problems that break the integration in normal use.

**What is fixed:**
- **The bridge dies when its IP changes.** A DHCP lease change leaves the device pointing at
  an address that no longer answers, with no way to correct it short of re-adding everything.
  Added `setBridgeAddress(ip[, token])` to re-point an existing bridge device, plus
  `testBridge` to check the stored address and token, `registerCallbacks` to re-register the
  hub as a callback target, and `releaseAddress`.
- **The Opener reported battery as a boolean.** Upstream sent Nuki's `batteryCritical` flag
  straight to the `battery` attribute, so the dashboard showed `false` instead of a
  percentage. It now prefers `batteryChargeState`, falling back to 10% / 100% derived from
  `batteryCritical` when the bridge does not report a level.

**Note:** assign a fixed DHCP reservation to the Nuki bridge. These drivers make a changed
address recoverable, but not painless.

---

#### Aqara H1 EU Single Switch
**Location:** `Drivers/Aqara/aqara_h1_eu_single_switch.groovy`

Simple on/off driver for Aqara H1 EU Single Switch.

**Features:**
- On/Off/Toggle control
- Health check monitoring
- State refresh and verification

**Supported Models:** lumi.switch.l1aeu1 / WS-EUK01

---

#### Aqara Smart Plug EU
**Location:** `Drivers/Aqara/aqara_smart_plug_eu.groovy`

Comprehensive driver for the Aqara Smart Plug EU with power monitoring.

**Features:**
- On/Off control
- Power monitoring (W)
- Energy metering (kWh)
- Voltage monitoring (V)
- Current monitoring (A)
- Device temperature
- Overload protection (configurable 100-2300W)
- Power outage memory
- LED disable (night mode)
- Button lock
- Health check monitoring

**Supported Models:** lumi.plug.maeu01 / SP-EUC01

**Note:** Some firmware versions may cause the plug to respond to unrelated switch commands from devices routed through it. This is a [known firmware issue](https://github.com/Koenkk/zigbee2mqtt/issues/13903).

---

#### Aqara H1 Double Rocker Remote
**Location:** `Drivers/Aqara/aqara_h1_double_rocker_remote.groovy`

Simple, focused driver for the Aqara H1 Wireless Remote Switch (Double Rocker).

**Features:**
- Single, double, triple press detection
- Hold and release detection
- Both buttons pressed simultaneously
- Battery monitoring
- Configurable click mode (fast vs multi)
- Hubitat Button Controller compatible

**Button Mapping:**
- Button 1: Left rocker
- Button 2: Right rocker
- Button 3: Both rockers together

**Supported Models:** lumi.remote.b28ac1 / WRS-R02

**Pairing:** Hold the LEFT rocker for 10 seconds until LEDs flash.

---

#### Aqara Wall Outlet H2 EU
**Location:** `Drivers/Aqara/aqara_wall_outlet_h2_eu.groovy`

Comprehensive driver for the Aqara Wall Outlet H2 EU with power monitoring.

**Features:**
- On/Off control
- Power monitoring (W)
- Energy metering (kWh)
- Voltage monitoring (V)
- Current monitoring (A)
- Device temperature monitoring
- Overload protection (configurable 100-3840W)
- Power outage memory (off/on/previous/inverted)
- LED indicator control
- Button/child lock
- Charging protection (auto-off when charging complete)
- Charging limit threshold (0.1-2W)
- Power outage counter
- Health check monitoring

**Supported Models:** lumi.plug.aeu001 / WP-P01D

---

#### Aqara Climate Sensor W100
**Location:** `Drivers/Aqara/aqara_climate_sensor_w100.groovy`

Driver for the Aqara Climate Sensor W100 with temperature, humidity, and 3 buttons.

**Features:**
- Temperature measurement with configurable offset
- Humidity measurement with configurable offset
- 3 buttons (Plus/Center/Minus) with push, hold, double-tap, release
- Battery monitoring (percentage and voltage)
- External sensor support (temperature/humidity from connected sensor)
- Power outage counter
- F7 TLV data parsing

**Supported Models:** lumi.sensor_ht.agl001

**Button Mapping:**
- Button 1: Plus (+) button
- Button 2: Center button
- Button 3: Minus (-) button

---

#### Aqara FP1E Presence Sensor
**Location:** `Drivers/Aqara/aqara_fp1e_presence.groovy`

Driver for the Aqara FP1E mmWave radar human presence detector.

**Features:**
- Presence detection (present/not present)
- Motion detection (active/inactive)
- Room state tracking (occupied/unoccupied)
- Activity state (idle/large movement/small movement)
- Target distance measurement (meters)
- Detection range configuration (0.1-6.0m)
- Motion sensitivity setting (low/medium/high)
- Device temperature monitoring
- Configurable motion timeout
- Spam filter for distance reports
- Reports its parent router (`parentNWK`), for diagnosing mesh problems

**Supported Models:** lumi.sensor_occupy.agl1 / RTCZCGQ13LM

**Why this device keeps dropping off the mesh**

The common complaint about the FP1E on Hubitat is that it pairs, works for a while, then
goes silent, often with one-way comms, where the hub still receives its reports but the
device ignores anything sent to it.

The cause is that Aqara end devices expect to be talking to an Aqara hub. This driver
answers the handshake they look for, which kkossev's Aqara drivers call the *black magic*:

- it replies to the device's ZDO Node Descriptor request (cluster `0x0002`, for NWK `0000`
  only) with a coordinator descriptor carrying Lumi's manufacturer code `0x115F`
- it answers the ZDO End Device Timeout request (`0x0036`)
- it re-sends both on every device announcement (`0x0013`), so the handshake survives a
  rejoin or a change of parent router

Equally important is what the driver does **not** do. The FP1E pushes its `0xFCC0` reports
unsolicited: it needs no bindings and no attribute reporting, and writing to it immediately
after it joins is what destabilises it. `configure()` therefore asserts the hub identity and
defers reading its settings by 10 seconds.

**Pairing**

With the handshake in place the join is usually straightforward, but if it fails:

1. Install this driver **first**, so it is available when the device joins.
2. Settings > Zigbee Details > **Rebuild network**, and run it **twice**.
3. Devices > **Add device** > Zigbee. If a plain join does not stick, use
   **"Pair using strict Zigbee 3.0 mode"**. The FP1E's symptom (found and initialised, but
   unresponsive with no attributes) is exactly the case that option is meant for.
4. Wait until the status leaves *"Preparing network"*, then hold the FP1E reset button for
   about 5 seconds until the LED flashes 3–4 times. The pairing window is short, so be at
   the device before starting it.
5. **Never delete the device in Hubitat.** Zigbee has no exclude: re-joining is recognised as
   *"Found previously joined Zigbee device"* and keeps the device's history and automations.
6. It is common for this sensor to only work on the **second** join attempt. Repeat step 3–4
   rather than deleting anything.

If it still misbehaves afterwards, check the `parentNWK` attribute: an Aqara end device
parked on an unsuitable router is the usual cause of one going quiet after a few hours.

---

#### Tuya/Zemismart 1-Gang Switch with Power Monitoring
**Location:** `Drivers/Tuya/tuya_1gang_switch_power.groovy`

Driver for Tuya-based 1-gang switches with power monitoring.

**Features:**
- On/Off control
- Power monitoring (W)
- Energy metering (kWh)
- Voltage monitoring (V)
- Current monitoring (A)
- Power-on behavior (off/on/restore)
- Countdown timer (auto-off)
- Configurable polling intervals
- Health check monitoring

**Supported Models:** TS0001 / _TZ3000_qlai3277 (Zemismart, Nous B2Z)

**Note:** these switches report RMS voltage in whole volts and do not answer the AC voltage
divisor query (`0x0601`), so the voltage divisor defaults to **1**. If yours reports a tenth
of the real mains voltage, check that preference before anything else.

---

#### Tuya TS130F Curtain/Blind Motor
**Location:** `Drivers/Tuya/tuya_ts130f_curtain_motor.groovy`

Driver for Tuya TS130F curtain/blind motor controllers. Ideal for cinema screens and roller blinds.

**Features:**
- Open/Close/Stop control
- Position control (0-100%)
- **Invert open/close direction** (essential for cinema screens where "open" should lower the screen)
- Configurable default open/close positions
- Motor reversal command
- Calibration mode support
- **Calibration time**: set the motor's stored full-travel time in seconds
- **Travel limits**: separate open and closed limits on the motor's own scale, for shades
  that over-run their stop (for example a blind that pools on the floor at the bottom)
- Moving state (opening / closing / stopped)
- Button Controller compatible (Open/Close/Stop)
- Works with WindowShade, Switch, and SwitchLevel capabilities

Uses the Tuya manufacturer-specific attributes on the Window Covering cluster
(`0xF000`–`0xF003`), matching zigbee2mqtt and the ZHA quirk.

**Button Mapping:**
- Button 1: Open (to default open position)
- Button 2: Close (to default close position)
- Button 3: Stop

**Supported Models:** TS130F with various manufacturer codes:
- _TZ3000_yruungrl
- _TZ3000_vd43bbfq
- _TZ3000_1dd0d5yi
- _TZ3000_fccpjz5z
- _TZ3000_zirycpws

**Cinema Screen Setup:**
1. Enable "Invert open/close direction"
2. Set "Default open position" to the screen-down position (e.g., 5%)
3. Set "Default close position" to the screen-up position (e.g., 100%)

---

#### Sunricher Zigbee Dimmer
**Location:** `Drivers/Sunricher/sunricher_dimmer.groovy`

Driver for Sunricher Zigbee dimmers with power monitoring.

**Features:**
- On/Off control
- Dimming (0-100%) with transition time
- Power monitoring (W)
- Energy metering (kWh)
- Voltage monitoring (V)
- Current monitoring (A)
- Power-on behavior (off/on/previous)
- Minimum brightness setting
- Preset level (set level without turning on)
- Health check monitoring

**Supported Models:** HK-SL-DIM-EU-A, HK-SL-DIM-US-A, HK-SL-DIM-AU-R-A (ZG2835RAC)

---

#### Zigbee Device Discovery Tool
**Location:** `Drivers/zigbee-device-discovery.groovy`

A diagnostic driver to discover Zigbee device capabilities.

**Features:**
- Discovers all endpoints, clusters, and attributes
- Helps create custom drivers for unsupported devices
- Generates fingerprints for device matching
- Aqara-specific initialization (magic byte for third-party hub support)
- FP1E presence sensor discovery mode
- Test commands for switches and presence sensors

**Commands:**
- `discoverAll` - Full device discovery (endpoints, clusters, attributes)
- `discoverEndpoints` - Discover active endpoints only
- `readAqaraCluster` - Read Aqara FCC0 cluster attributes
- `initializeAqara` - Initialize Aqara devices for third-party hub
- `discoverFP1E` - Specialized discovery for FP1E presence sensor
- `testSwitchOn/Off` - Test switch control
- `testPresence` - Test presence sensor attributes

---

### Apps

#### Master Thermostat Controller
**Location:** `Master Thermostat App/`

Unified heating control system for multiple TRVs.

**Components:**
- `hubitat-master-thermostat-parent.groovy` - Parent app for centralized control
- `hubitat-master-thermostat-child.groovy` - Room Zone child app
- `hubitat-virtual-master-thermostat.groovy` - Virtual thermostat driver

**Features:**
- Master virtual thermostat for dashboard/voice control
- Per-room temperature offsets
- Weekday/Weekend scheduling with multiple time slots
- Manual override with auto-revert on schedule change
- Window detection integration
- Child lock control across all TRVs
- Temperature range monitoring from multiple sensors
- **Setpoint verification:** battery TRVs drop commands, so the zone re-reads its valves two
  minutes after applying a setpoint, resends to any that did not take it (up to three
  attempts) and then warns, naming the valve

**Installation Order:**
1. Install "Virtual Master Thermostat" driver
2. Install "Master Thermostat Controller" parent app
3. Install "Room Zone" child app
4. Add app instance and configure

**Note on window sensors:** a Room Zone applies its window action when *any* of its assigned
contact sensors opens. Assign only the sensors for windows in that room. A sensor shared
with another zone will drop this room's radiators too.

---

#### Fridge Logger
**Location:** `Apps/fridge-logger.groovy`

Logs fridge temperature and humidity, and compressor on/off changes from a smart plug, to CSV
files in the hub's own File Manager. Everything runs on the hub, so history keeps accumulating
even when nothing else is connected and no cloud service is involved.

**Files produced** (readable at `http://<hub>/local/<name>`):
- `fridge-YYYY-MM.csv`: `timestamp,attribute,value`
- `fridge-daily.csv`: one summary line per day: `date,minTemp,maxTemp,avgTemp,readings,minutesAbove6,compressorOnMinutes,cycles`

Useful for spotting a fridge that is cooling but cycling too often, or one that quietly
drifted above 6 °C.

---

### Tools

Standard-library Python scripts that talk to a hub's local admin endpoints. They need
**hub login security switched off**, and they only read unless stated otherwise.

Hub addresses are never hardcoded: pass `--hub http://<ip>` or set `HUBITAT_HUBS`
(comma-separated) / `HUBITAT_HUB`.

#### `tools/hub_health_check.py`
Daily health report: devices that have gone silent, low batteries, hub alerts and log floods.
Prints `STATUS: OK`, `STATUS: ISSUES` or `STATUS: UNREACHABLE` as its first line, so it is
easy to wire to a notification. Handles several hubs on different sites: a hub that does not
answer is only `UNREACHABLE` when none of them do.

Liveness is judged on events the device itself produced, read from
`/device/eventsJson/<id>`, **not** on `lastActivity`. Saving a preference re-runs a driver's
`initialize()`, which stamps `lastActivity` and files "Initialized" events, so a device that
fell off the mesh weeks ago looks healthy right after any settings change. Radio traffic
counts as life too, because contact sensors legitimately sit days between events.

Set `HUBITAT_PARKED="Name=reason;Name=reason"` for devices deliberately out of service; they
are listed as parked rather than reported as faults.

```bash
python3 tools/hub_health_check.py --hub http://192.168.1.10 [--json]
```

#### `tools/hub_log_collector.py`
The hub only keeps a few hours of log. This polls `/logs/past/json`, appends anything new to
one file per day, glues multi-line entries back together, and notes a gap if the buffer rolled
over before the next poll. Keeps a small state file so restarts do not duplicate lines.

```bash
nohup caffeinate -i python3 tools/hub_log_collector.py --hub http://192.168.1.10 &
```

#### `tools/hub_cmd.py`
Calls a tool on a hub's built-in MCP endpoint without an MCP client, using the token from
`~/.claude.json`. Useful for running device commands from a script.

```bash
python3 tools/hub_cmd.py run_device_command '{"deviceId":35,"command":"refresh"}'
```

Thermostats, locks and doors additionally need `"allowSensitive":true`.

#### `tools/zwave_fw_sequencer.py`
Drives a multi-step Z-Wave firmware update through the hub's updater app.

---

### Dashboards

`Dashboards/*.json` are exported layouts for Hubitat's built-in dashboards, kept in version
control so a layout can be restored after an accident. They are specific to the device IDs of
the hub they came from, so treat them as worked examples rather than something to import
directly.

---

## Upgrading

A few changes alter behaviour on devices that are already paired:

- **Tuya 1-Gang Switch 1.0.2**: the voltage divisor now defaults to **1**. These switches
  report RMS voltage in whole volts and never answer the divisor query, so the previous
  default of 10 reported 23 V on a 230 V supply. If you set the divisor by hand to work
  around that, set it back to 1. The default power poll also drops from 60 s to 300 s.
- **Sonoff TRVZB 2.3.0**: temperature accuracy was being read and written at the wrong
  attribute, with the wrong type and scale; it is now `0x6011` (INT16, ×100). Adds boost,
  timer mode and smart temperature control for firmware 1.4.x.
- **Aqara FP1E**: detection range (`0x015B`) is a uint32, not a uint16, so range writes were
  previously rejected by the device. Settings are now read back after writing, because this
  device acknowledges writes it did not apply.
- **Sonoff SNZB-04P 1.0.2** and **Aqara FP1E**: saving a device's settings no longer resets
  its state. Both drivers used to force `closed` (contact sensor) or `not present` (FP1E)
  on every save, so an open window could be reported shut and a heating zone watching it
  would resume heating.
- **Room Zone 1.2.0**: the window action no longer writes setpoints straight to the valves.
  It went through a path that bypassed the app's own guard, so the app could read its own
  write back as a *manual override* and leave a radiator stuck at frost protection.

## Project Structure

```
hubitat/
├── Drivers/
│   ├── Aqara/
│   │   ├── aqara_h1_eu_single_switch.groovy
│   │   ├── aqara_smart_plug_eu.groovy
│   │   ├── aqara_h1_double_rocker_remote.groovy
│   │   ├── aqara_wall_outlet_h2_eu.groovy
│   │   ├── aqara_fp1e_presence.groovy
│   │   └── aqara_climate_sensor_w100.groovy
│   ├── Sonoff/
│   │   ├── zigbee-sonoff-trvzb.groovy
│   │   ├── sonoff_snzb04p_contact.groovy
│   │   └── sonoff_swv_water_valve.groovy
│   ├── Tuya/
│   │   ├── tuya_1gang_switch_power.groovy
│   │   └── tuya_ts130f_curtain_motor.groovy
│   ├── Sunricher/
│   │   └── sunricher_dimmer.groovy
│   ├── Nuki/
│   │   ├── nuki-bridge-maffpt-patched.groovy
│   │   └── nuki-opener-maffpt-patched.groovy
│   └── zigbee-device-discovery.groovy
├── Apps/
│   └── fridge-logger.groovy
├── Master Thermostat App/
│   ├── hubitat-master-thermostat-parent.groovy
│   ├── hubitat-master-thermostat-child.groovy
│   └── hubitat-virtual-master-thermostat.groovy
├── Dashboards/
│   ├── home-layout.json
│   └── garden-layout.json
├── tools/
│   ├── hub_health_check.py
│   ├── hub_log_collector.py
│   ├── hub_cmd.py
│   └── zwave_fw_sequencer.py
├── .gitignore
├── LICENSE
└── README.md
```

## Installation

### Drivers
1. In Hubitat, go to **Drivers Code**
2. Click **New Driver**
3. Paste the driver code
4. Click **Save**
5. Assign the driver to your device

### Apps
1. In Hubitat, go to **Apps Code**
2. Click **New App**
3. Paste the app code
4. Click **Save**
5. Go to **Apps** > **Add User App**

## Requirements

- Hubitat Elevation hub
- Compatible Zigbee devices
- For the scripts in `tools/`: Python 3 (standard library only), and **hub login security
  switched off**, since they use the hub's local admin endpoints. If you keep hub login
  security on, the drivers and apps still work; only the tools need it.

## Credits

**Author:** Ben Fayershtain
**Namespace:** benberlin

### References & Resources

The following resources were used as references during development:

#### Zigbee Protocol & Clusters
- [Zigbee Cluster Library (ZCL) Specification](https://zigbeealliance.org/developer_resources/zigbee-cluster-library/) - Official Zigbee cluster definitions
- [Zigbee2MQTT Device Documentation](https://www.zigbee2mqtt.io/devices/) - Device-specific cluster and attribute information

#### Sonoff Devices
- [Zigbee2MQTT - Sonoff TRVZB](https://www.zigbee2mqtt.io/devices/TRVZB.html) - Device specifications and custom cluster (FC11) documentation
- [Zigbee2MQTT - Sonoff SNZB-04P](https://www.zigbee2mqtt.io/devices/SNZB-04P.html) - SNZB-04P contact sensor documentation
- [zigbee-herdsman-converters - sonoff.ts](https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/sonoff.ts) - SWV water valve cluster map and the eWeLink FC11 attributes
- [Sonoff SNZB-04P Product Page](https://sonoff.tech/en-us/products/sonoff-zigbee-door-window-sensor-snzb-04p) - Official product specifications
- [Koenkk/zigbee-herdsman-converters](https://github.com/Koenkk/zigbee-herdsman-converters) - Converter implementations and attribute mappings

#### Aqara Devices
- [Zigbee2MQTT - Aqara H1 Switch](https://www.zigbee2mqtt.io/devices/WS-EUK01.html) - Aqara H1 EU switch documentation
- [Zigbee2MQTT - Aqara Smart Plug](https://www.zigbee2mqtt.io/devices/SP-EUC01.html) - Aqara Smart Plug EU documentation
- [Zigbee2MQTT - Aqara H1 Remote](https://www.zigbee2mqtt.io/devices/WRS-R02.html) - Aqara H1 Double Rocker remote documentation
- [Aqara Plug Random Toggle Issue](https://github.com/Koenkk/zigbee2mqtt/issues/13903) - Known firmware issue documentation
- [Aqara H1 Remote Support](https://github.com/Koenkk/zigbee-herdsman-converters/issues/2620) - Technical implementation details
- [Zigbee2MQTT - Aqara Wall Outlet H2 EU](https://www.zigbee2mqtt.io/devices/WP-P01D.html) - Aqara Wall Outlet H2 EU documentation
- [Aqara Wall Outlet H2 Product Page](https://www.aqara.com/en/product/wall-outlet-h2-eu/) - Official product specifications
- [Zigbee2MQTT - Aqara FP1E](https://www.zigbee2mqtt.io/devices/FP1E.html) - Aqara FP1E presence sensor documentation
- [Aqara FP1E Product Page](https://www.aqara.com/en/product/presence-sensor-fp1e/) - Official product specifications
- [Hubitat FP1E Community Thread](https://community.hubitat.com/t/aqara-fp1e-presence-sensor/142027) - Community pairing solutions
- [kkossev Aqara P1 Motion Sensor Driver](https://github.com/kkossev/Hubitat/blob/main/Drivers/Aqara%20P1%20Motion%20Sensor/Aqara_P1_Motion_Sensor.groovy) - Reference implementation
- [Aqara W100 Climate Sensor Product Page](https://www.aqara.com/en/product/climate-sensor-w100/) - Official product specifications
- [Hubitat W100 Community Thread](https://community.hubitat.com/t/beta-aqara-climate-sensor-w100-zigbee-driver/155433) - Community driver discussion
- [kkossev W100 Driver](https://github.com/kkossev/Hubitat/tree/development/Drivers/Aqara%20Climate%20Sensor%20W100) - Reference implementation
- [Blakadder Zigbee Database](https://zigbee.blakadder.com/) - Device compatibility information
- Hubitat Generic Zigbee Switch driver - Base implementation reference

#### Tuya Devices
- [Zigbee2MQTT - Tuya TS0001](https://www.zigbee2mqtt.io/devices/TS0001.html) - Tuya switch documentation
- [Zigbee2MQTT - Tuya TS130F](https://www.zigbee2mqtt.io/devices/TS130F.html) - Tuya curtain/blind motor documentation
- [Hubitat TS130F Discussion](https://community.hubitat.com/t/zigbee-cutain-module-ts130f/107907) - Community driver discussion
- [Nous B2Z Product Page](https://nous.technology/product/b2z.html) - Nous B2Z specifications

#### Sunricher Devices
- [Zigbee2MQTT - Sunricher Dimmer](https://www.zigbee2mqtt.io/devices/HK-SL-DIM-US-A.html) - Sunricher dimmer documentation
- [Sunricher HK-SL-DIM-EU-A Support](https://github.com/Koenkk/zigbee2mqtt/issues/14315) - Device implementation details

#### Nuki
- [maffpt/Hubitat Nuki drivers](https://github.com/maffpt/Hubitat) - Marco Felicio's original Nuki Bridge, Smart Lock and Opener drivers, which the patched versions here are based on
- [Nuki Bridge HTTP API](https://developer.nuki.io/page/nuki-bridge-http-api-1-13/4) - Bridge endpoint and callback documentation

#### Hubitat Development
- [Hubitat Developer Documentation](https://docs2.hubitat.com/en/developer) - Official Hubitat driver and app development guides
- [Hubitat Community Forums](https://community.hubitat.com/) - Community driver examples and troubleshooting

#### AI Assistance
- Development assisted by [Claude](https://claude.ai) (Anthropic) - Code generation and documentation

## License

MIT License

Copyright (c) 2024 Ben Fayershtain

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues and feature requests, please use the GitHub Issues page.
