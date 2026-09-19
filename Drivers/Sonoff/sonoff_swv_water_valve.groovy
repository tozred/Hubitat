/**
 *  Sonoff SWV Water Valve  (SWV-BSP / SWV-NH  +  Hydro SWV-ZFE/ZFU/ZNE/ZNU)
 *  Hubitat Elevation device driver
 *
 *  One combined driver, two device families, branched on the reported Zigbee model:
 *    • SWV (BSP/NH): flow (0x0404), leak/shortage status, auto-close, cyclic irrigation.
 *      FC11 numerics are LITTLE-endian.
 *    • SWV-ZFE/ZFU/ZNE/ZNU (Hydro): FC11 numerics are BIG-endian, dual-channel-aware
 *      status (shortage/leak/fail-safe), child lock, real-time + hourly volume/duration,
 *      and a genTime time-sync the device demands. Irrigation plans / rain delay /
 *      seasonal / 30-day history are later phases.
 *
 *  Zigbee map (endpoint 1), reverse-engineered from the zigbee-herdsman-converters
 *  "SWV" definition (Koenkk/zigbee-herdsman-converters, src/devices/sonoff.ts):
 *    0x0006 genOnOff            valve open(on)/close(off), reports every 1..1800s
 *    0x0001 genPowerCfg  0x0021 battery %  (raw / 2)
 *    0x0404 msFlowMeasurement 0x0000 flow  (raw / 10  ->  m3/h)
 *    0xFC11 eWeLink custom cluster (Shenzhen CoolKit, mfg 0x1286):
 *        0x500C u8     device status: 0 normal, 1 shortage, 2 leak, 3 both
 *        0x5011 u16    auto-close-on-shortage timeout (0 = off, 30 = on, minutes)  FW 1.0.4+
 *        0x5006 u32    real-time irrigation duration (s)
 *        0x5007 u32    real-time irrigation volume (L)
 *        0x500D u32    irrigation start time (unix)
 *        0x500E u32    irrigation end time (unix)
 *        0x500F u32    daily irrigation volume (L)
 *        0x5010 bool   valve work state (1 working / 0 idle)
 *        0x5008 str42  cyclic TIMED irrigation        [count,total,duration(BE4),interval(BE4)]
 *        0x5009 str42  cyclic QUANTITATIVE irrigation [count,total,capacity(BE4),interval(BE4)]
 *
 *  Author: Ben
 *  License: MIT
 *
 *  NOTE: built from community specs without a paired device. The standard-cluster
 *  features (valve/switch/battery/flow/leak-status) are high confidence. The 0xFC11
 *  features (auto-close, cyclic irrigation) may need one on-device tweak — see the
 *  "useMfgCode" preference and the README test checklist.
 */

import hubitat.zigbee.zcl.DataType
import groovy.transform.Field

@Field static final Integer CLUSTER_POWER   = 0x0001
@Field static final Integer CLUSTER_ONOFF   = 0x0006
@Field static final Integer CLUSTER_TIME    = 0x000A   // genTime — Hydro reads this from the hub
@Field static final Integer CLUSTER_FLOW    = 0x0404
@Field static final Integer CLUSTER_EWELINK = 0xFC11
@Field static final Integer COOLKIT_MFG     = 0x1286

@Field static final List HYDRO_MODELS = ["SWV-ZFE", "SWV-ZFU", "SWV-ZF2", "SWV-ZNE", "SWV-ZNU"]

@Field static final Integer CMD_ON_WITH_TIMED_OFF = 0x42   // genOnOff command
@Field static final long ZIGBEE_EPOCH_OFFSET = 946684800L  // seconds between 1970-01-01 and 2000-01-01

@Field static final Map STATUS_MAP = [
    0: "normal",
    1: "water_shortage",
    2: "water_leakage",
    3: "water_shortage_and_leakage",
]

metadata {
    definition(name: "Sonoff SWV Water Valve", namespace: "ben", author: "Ben") {
        capability "Valve"
        capability "Switch"
        capability "Battery"
        capability "WaterSensor"
        capability "Refresh"
        capability "Configuration"
        capability "Actuator"
        capability "Sensor"

        attribute "flow", "number"                          // m³/h (BSP)
        attribute "deviceStatus", "string"                  // BSP enum text / Hydro bitmap (shortage/leak/fail_safe, dual-channel)
        attribute "valveWorkState", "enum", ["working", "idle"]
        attribute "realtimeIrrigationDuration", "number"    // BSP: seconds · Hydro: minutes
        attribute "realtimeIrrigationVolume", "number"      // L
        attribute "dailyIrrigationVolume", "number"         // L (BSP)
        attribute "hourlyIrrigationVolume", "number"        // L (Hydro)
        attribute "hourlyIrrigationDuration", "number"      // min (Hydro)
        attribute "childLock", "string"                     // Hydro: locked / unlocked
        attribute "scheduleStatus", "string"                 // Hydro: start / running / end / standby
        attribute "irrigationMode", "string"                 // Hydro: duration / capacity / duration_with_interval
        attribute "scheduleStart", "string"                  // Hydro: expected start (Berlin time)
        attribute "scheduleEnd", "string"                    // Hydro: expected end
        attribute "scheduleActualEnd", "string"              // Hydro: actual end / running / n/a
        attribute "expectedVolume", "number"                 // Hydro (flow-meter models)
        attribute "actualVolume", "number"                   // Hydro (flow-meter models)
        attribute "irrigationStartTime", "string"
        attribute "irrigationEndTime", "string"
        attribute "cyclicTimedIrrigation", "string"
        attribute "cyclicQuantitativeIrrigation", "string"

        command "openForDuration", [[name: "Seconds*", type: "NUMBER",
            description: "Open the valve, then auto-close after this many seconds (1-65535)"]]
        command "setCyclicTimedIrrigation", [
            [name: "Cycles*",      type: "NUMBER", description: "BSP only — total cycles (0-100; 0 = stop)"],
            [name: "Duration (s)*", type: "NUMBER", description: "Single irrigation duration, seconds (0-86400)"],
            [name: "Interval (s)*", type: "NUMBER", description: "Interval between cycles, seconds (0-86400)"]]
        command "setCyclicQuantitativeIrrigation", [
            [name: "Cycles*",     type: "NUMBER", description: "BSP only — total cycles (0-100; 0 = stop)"],
            [name: "Liters*",     type: "NUMBER", description: "Single irrigation capacity, liters (0-6500)"],
            [name: "Interval (s)*", type: "NUMBER", description: "Interval between cycles, seconds (0-86400)"]]
        command "stopCyclicIrrigation"
        command "setChildLock", [[name: "State*", type: "ENUM", constraints: ["lock", "unlock"],
            description: "Hydro models only — lock/unlock the physical button"]]

        // BSP/NH — confirmed against a real SWV-BSP (firmware 1.0.3) Data section.
        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0001,0003,0006,0020,0404,0B05,FC57,FC11", outClusters: "000A,0019",
            manufacturer: "SONOFF", model: "SWV", deviceJoinName: "Sonoff Smart Water Valve"
        // Hydro series — ZNE confirmed against hardware (firmware 1.0.7); ZFE pending verification.
        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0001,0003,0006,0020,FC57,FC11", outClusters: "0003,0019",
            manufacturer: "SONOFF", model: "SWV-ZNE", deviceJoinName: "Sonoff Smart Water Valve (Hydro)"
        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0001,0003,0006,0020,FC57,FC11", outClusters: "0003,0019",
            manufacturer: "SONOFF", model: "SWV-ZNU", deviceJoinName: "Sonoff Smart Water Valve (Hydro)"
        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0001,0003,0006,0020,FC57,FC11", outClusters: "0003,0019",
            manufacturer: "SONOFF", model: "SWV-ZFE", deviceJoinName: "Sonoff Smart Water Valve (Hydro)"
        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0001,0003,0006,0020,FC57,FC11", outClusters: "0003,0019",
            manufacturer: "SONOFF", model: "SWV-ZFU", deviceJoinName: "Sonoff Smart Water Valve (Hydro)"
    }

    preferences {
        input name: "autoCloseOnShortage", type: "bool",
            title: "Auto-close valve on water shortage (>30 min)",
            description: "Requires device firmware 1.0.4 or later.", defaultValue: false
        input name: "statusPollMinutes", type: "enum",
            title: "Poll leak/shortage status every",
            description: "The valve does not push status changes, so leak detection relies on polling.",
            options: ["0": "Disabled", "1": "1 min", "5": "5 min", "10": "10 min", "15": "15 min", "30": "30 min"],
            defaultValue: "5"
        input name: "homekitSwitch", type: "bool",
            title: "Create a child Switch device for Apple Home (HomeKit)",
            description: "Hubitat's HomeKit bridge locks this device to a leak sensor and won't expose it as controllable. This adds a plain Switch child (on = open) to select in the HomeKit Integration app; every other feature stays on this device.",
            defaultValue: true
        input name: "useMfgCode", type: "bool",
            title: "Send CoolKit manufacturer code (0x1286) on the FC11 cluster",
            description: "Leave OFF first. If status / auto-close / irrigation don't respond, turn this ON and re-test.",
            defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off in 30 min)", defaultValue: true
    }
}

// ----------------------------------------------------------------------------
// Lifecycle
// ----------------------------------------------------------------------------

def installed() {
    log.info "${device.displayName}: installed"
    sendEvent(name: "valve", value: "closed")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "water", value: "dry")
    ensureHomeKitChild()
    runIn(3, "autoConfigure")
}

def updated() {
    log.info "${device.displayName}: updated (autoClose=${settings?.autoCloseOnShortage}, poll=${settings?.statusPollMinutes}min, mfgCode=${settings?.useMfgCode})"
    if (logEnable) { log.warn "debug logging is ON; auto-off in 30 min"; runIn(1800, "logsOff") }
    else { unschedule("logsOff") }
    schedulePolling()
    ensureHomeKitChild()
    sendZigbeeCommands(writeAutoCloseCmds())
}

def configure() {
    log.info "${device.displayName}: configure"
    schedulePolling()
    return buildConfigureCmds()
}

def refresh() {
    if (txtEnable) log.info "${device.displayName}: refresh"
    return buildRefreshCmds()
}

void autoConfigure() { sendZigbeeCommands(buildConfigureCmds()) }

private void schedulePolling() {
    unschedule("pollStatus")
    Integer mins = (settings?.statusPollMinutes ?: "5") as Integer
    if (mins > 0) {
        if (mins >= 60) schedule("0 0 * ? * *", "pollStatus")
        else            schedule("0 */${mins} * ? * *", "pollStatus")
        if (txtEnable) log.info "${device.displayName}: polling status every ${mins} min"
    }
}

void pollStatus() {
    List<String> cmds = zigbee.readAttribute(CLUSTER_EWELINK, 0x500C, fc11Opts())       // status (both families)
    if (!isHydro()) cmds += zigbee.readAttribute(CLUSTER_EWELINK, 0x5010, fc11Opts())   // valve work state (BSP only)
    sendZigbeeCommands(cmds)
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ----------------------------------------------------------------------------
// Apple Home (HomeKit) child switch
//
// Hubitat's HomeKit bridge classifies this device as a leak sensor (WaterSensor)
// and won't offer it as a controllable switch. So we expose a dedicated child
// Switch (on = open / off = close) that the HomeKit Integration app accepts;
// the valve keeps every other capability on the Hubitat side.
// ----------------------------------------------------------------------------

private String homekitChildDni() { "${device.deviceNetworkId}-HK" }

private void ensureHomeKitChild() {
    String dni = homekitChildDni()
    def child = getChildDevice(dni)
    if (settings?.homekitSwitch == false) {
        if (child) { deleteChildDevice(dni); log.info "${device.displayName}: removed HomeKit switch child" }
        return
    }
    if (!child) {
        try {
            child = addChildDevice("hubitat", "Generic Component Switch", dni,
                [name: "Sonoff SWV HomeKit Switch", label: "${device.displayName} (Home)", isComponent: false])
            log.info "${device.displayName}: created HomeKit switch child '${child?.displayName}' — add it in the HomeKit Integration app"
        } catch (e) {
            log.warn "${device.displayName}: could not create HomeKit child: ${e.message}"
            return
        }
    }
    updateHomeKitChild(device.currentValue("switch") ?: "off")
}

private void updateHomeKitChild(String sw) {
    def child = getChildDevice(homekitChildDni())
    if (child) child.parse([[name: "switch", value: sw, descriptionText: "${child.displayName} is ${sw}"]])
}

// Callbacks from the child "Generic Component Switch" driver.
void componentOn(cd)      { if (txtEnable) log.info "${device.displayName}: HomeKit switch -> open";  sendZigbeeCommands(zigbee.on()) }
void componentOff(cd)     { if (txtEnable) log.info "${device.displayName}: HomeKit switch -> close"; sendZigbeeCommands(zigbee.off()) }
void componentRefresh(cd) { updateHomeKitChild(device.currentValue("switch") ?: "off") }

// ----------------------------------------------------------------------------
// Commands
// ----------------------------------------------------------------------------

def open()  { if (txtEnable) log.info "${device.displayName}: open (valve)";  return zigbee.on() }
def close() { if (txtEnable) log.info "${device.displayName}: close (valve)"; return zigbee.off() }
def on()    { return open() }
def off()   { return close() }

// Open the valve, then have the valve itself auto-close after `seconds`.
// Quirk: the SWV interprets the genOnOff on-with-timed-off "on time" field in
// SECONDS (not the ZCL-standard 0.1s units). Field order: ctrl(1) onTime(u16 LE) offWait(u16 LE).
def openForDuration(seconds) {
    int s = clampI(seconds, 1, 65535)
    String lo = zigbee.convertToHexString(s & 0xFF, 2)
    String hi = zigbee.convertToHexString((s >> 8) & 0xFF, 2)
    if (txtEnable) log.info "${device.displayName}: open for ${s}s (auto-close)"
    return zigbee.command(CLUSTER_ONOFF, CMD_ON_WITH_TIMED_OFF, "00${lo}${hi}0000")
}

def setCyclicTimedIrrigation(cycles, durationSec, intervalSec) {
    if (isHydro()) { log.warn "${device.displayName}: cyclic irrigation is BSP-only; Hydro uses irrigation plans (later phase)"; return }
    if (txtEnable) log.info "${device.displayName}: cyclic timed -> ${cycles} cycles, ${durationSec}s on, ${intervalSec}s interval"
    return writeCyclicCmds(0x5008, cycles, durationSec, intervalSec, 86400L)
}

def setCyclicQuantitativeIrrigation(cycles, liters, intervalSec) {
    if (isHydro()) { log.warn "${device.displayName}: cyclic irrigation is BSP-only; Hydro uses irrigation plans (later phase)"; return }
    if (txtEnable) log.info "${device.displayName}: cyclic quantitative -> ${cycles} cycles, ${liters}L, ${intervalSec}s interval"
    return writeCyclicCmds(0x5009, cycles, liters, intervalSec, 6500L)
}

def stopCyclicIrrigation() {
    if (isHydro()) { log.warn "${device.displayName}: cyclic irrigation is BSP-only"; return }
    if (txtEnable) log.info "${device.displayName}: stop cyclic irrigation"
    return writeCyclicCmds(0x5008, 0, 0, 0, 86400L) + writeCyclicCmds(0x5009, 0, 0, 0, 6500L)
}

// Hydro: child lock (FC11 0x0000 boolean).
def setChildLock(state) {
    if (!isHydro()) { log.warn "${device.displayName}: child lock is a Hydro-only feature"; return }
    int v = (state?.toString()?.toLowerCase() in ["lock", "locked", "on", "true"]) ? 1 : 0
    if (txtEnable) log.info "${device.displayName}: child lock -> ${v ? 'LOCK' : 'UNLOCK'}"
    return zigbee.writeAttribute(CLUSTER_EWELINK, 0x0000, DataType.BOOLEAN, v, fc11Opts())
}

// FC11 cyclic-irrigation value is a type-0x42 (char string). Wire bytes after the
// type byte:  [len=0x0a][count=0x00][total(1)][amount(BE u32)][interval(BE u32)].
// We emit a raw write so the bytes go out verbatim (zigbee.writeAttribute would
// ASCII-encode a char string and corrupt the payload). The leading 0x0a length
// byte is the one untested detail — see the README test checklist if rejected.
private List<String> writeCyclicCmds(int attr, total, amount, interval, long amountMax) {
    int  t  = clampI(total, 0, 100)
    long a  = clampL(amount, 0L, amountMax)
    long iv = clampL(interval, 0L, 86400L)
    String value = "0A00" + zigbee.convertToHexString(t, 2) + hex8(a) + hex8(iv)
    String mfg = (settings?.useMfgCode) ? " {0x${zigbee.convertToHexString(COOLKIT_MFG, 4)}}" : ""
    String raw = "he wattr 0x${device.deviceNetworkId} 0x01 0xFC11 0x${zigbee.convertToHexString(attr, 4)} 0x42 {${value}}${mfg}"
    if (logEnable) log.debug "${device.displayName}: writeCyclic -> ${raw}"
    return [raw]
}

private List<String> writeAutoCloseCmds() {
    if (isHydro()) return []   // Hydro auto-close lives in valveAlarmSettings (later phase), not 0x5011
    Integer v = (settings?.autoCloseOnShortage) ? 30 : 0
    return zigbee.writeAttribute(CLUSTER_EWELINK, 0x5011, DataType.UINT16, v, fc11Opts())
}

// ----------------------------------------------------------------------------
// Configure / Refresh builders
// ----------------------------------------------------------------------------

private List<String> buildConfigureCmds() {
    if (isHydro()) return buildConfigureCmdsHydro()
    List<String> cmds = []
    cmds += zigbee.configureReporting(CLUSTER_ONOFF, 0x0000, DataType.BOOLEAN, 1, 1800, null)
    cmds += zigbee.configureReporting(CLUSTER_POWER, 0x0021, DataType.UINT8, 3600, 43200, 1)
    cmds += zigbee.configureReporting(CLUSTER_FLOW,  0x0000, DataType.UINT16, 5, 600, 1)
    cmds += writeAutoCloseCmds()
    cmds += buildRefreshCmds()
    return cmds
}

private List<String> buildRefreshCmds() {
    if (isHydro()) return buildRefreshCmdsHydro()
    List<String> cmds = []
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, 0x0000)
    cmds += zigbee.readAttribute(CLUSTER_POWER, 0x0021)
    cmds += zigbee.readAttribute(CLUSTER_FLOW,  0x0000)
    [0x500C, 0x5011, 0x5010, 0x500F, 0x5006, 0x5007, 0x500D, 0x500E, 0x5008, 0x5009].each { attr ->
        cmds += zigbee.readAttribute(CLUSTER_EWELINK, attr, fc11Opts())
    }
    return cmds
}

// --- Hydro (SWV-ZFE/ZFU/ZNE/ZNU) configure & refresh ---
private List<String> buildConfigureCmdsHydro() {
    // Hydro rejects onOff reporting config (returns 0x86) and reports onOff natively, so skip it.
    List<String> cmds = []
    cmds += zigbee.configureReporting(CLUSTER_POWER, 0x0021, DataType.UINT8, 3600, 43200, 1)
    cmds += buildRefreshCmdsHydro()
    return cmds
}

private List<String> buildRefreshCmdsHydro() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, 0x0000)
    cmds += zigbee.readAttribute(CLUSTER_POWER, 0x0021)
    // childLock, status, realtime duration/volume, hourly volume/duration, schedule status
    [0x0000, 0x500C, 0x5006, 0x5007, 0x501B, 0x501C, 0x501F].each { attr ->
        cmds += zigbee.readAttribute(CLUSTER_EWELINK, attr, fc11Opts())
    }
    return cmds
}

// ----------------------------------------------------------------------------
// Parse
// ----------------------------------------------------------------------------

def parse(String description) {
    try {
        if (logEnable) log.debug "parse: ${description}"
        if (!description) return
        Map descMap = zigbee.parseDescriptionAsMap(description)
        if (!descMap) { if (logEnable) log.debug "unparsed: ${description}"; return }

        String clu = (descMap.clusterId ?: descMap.cluster)?.toUpperCase()

        // Hydro valves read genTime (0x000A) from the hub. Detect it so we can verify
        // whether Hubitat auto-answers or we must send a time response (later phase).
        if (clu == "000A") {
            if (txtEnable) log.info "${device.displayName}: genTime request from device (Hydro time-sync) — cmd=${descMap.command}"
            return
        }

        if (descMap.attrId != null) {
            // The SWV packs several manufacturer attributes into one FC11 frame, and
            // Hubitat lumps the trailing bytes into descMap.value — so walk the raw
            // payload ourselves for FC11. Standard clusters are reliable via descMap.
            if (clu == "FC11") {
                Map<String, String> attrs = parseFc11Records(descMap)
                if (attrs && !attrs.isEmpty()) {
                    attrs.each { a, v -> dispatchAttr(clu, a, v) }
                } else {
                    dispatchAttr(clu, descMap.attrId.toUpperCase(), descMap.value)
                    descMap.additionalAttrs?.each { a -> if (a?.attrId != null) dispatchAttr(clu, a.attrId.toUpperCase(), a.value) }
                }
            } else {
                dispatchAttr(clu, descMap.attrId.toUpperCase(), descMap.value)
                descMap.additionalAttrs?.each { a ->
                    if (a?.attrId != null) dispatchAttr(clu, a.attrId.toUpperCase(), a.value)
                }
            }
            return
        }
        // No attribute payload: command / default / configure / write responses, bind rsp, etc.
        handleResponse(clu, descMap)
    } catch (e) {
        log.warn "${device.displayName}: parse error '${e.message}' on: ${description}"
    }
}

// Walk an FC11 read/report frame's raw payload into a clean {attrIdHex: wireValueHex} map.
// Layout after the 1-byte length field: [attrId(2 LE)][type(1)][value(typed)] repeated.
private Map<String, String> parseFc11Records(Map descMap) {
    Map<String, String> out = [:]
    List<Integer> b = hexToBytes(descMap.raw)
    int idx = 6   // dni(2) + endpoint(1) + cluster(2) + length(1)
    while (idx + 3 <= b.size()) {
        int attrId = ((b[idx + 1] & 0xFF) << 8) | (b[idx] & 0xFF)
        idx += 2
        int type = b[idx] & 0xFF; idx += 1
        int len = zclValueLen(type, b, idx)
        if (len < 0 || idx + len > b.size()) break
        String valHex = b[idx..<(idx + len)].collect { zigbee.convertToHexString(it & 0xFF, 2) }.join()
        idx += len
        out[zigbee.convertToHexString(attrId, 4)] = valHex
    }
    return out
}

// Byte length of a ZCL value for the data types this device uses (-1 = unknown).
private int zclValueLen(int type, List<Integer> b, int idx) {
    switch (type) {
        case 0x10: case 0x20: case 0x28: return 1      // bool / u8 / i8
        case 0x21: case 0x29: return 2                 // u16 / i16
        case 0x23: case 0x2B: return 4                 // u32 / i32
        case 0x41: case 0x42:                          // octet / char string: leading length byte
            return (idx < b.size()) ? 1 + (b[idx] & 0xFF) : -1
        case 0x48: return arrayValueLen(b, idx)        // array: [elemType(1)][count(2 LE)][elements]
        default: return -1
    }
}

// Total wire length of a ZCL array value (fixed-size element types only; -1 if unknown).
private int arrayValueLen(List<Integer> b, int idx) {
    if (idx + 3 > b.size()) return -1
    int et = b[idx] & 0xFF
    int esz = (et == 0x10 || et == 0x20 || et == 0x28) ? 1 : (et == 0x21 || et == 0x29) ? 2 : (et == 0x23 || et == 0x2B) ? 4 : -1
    if (esz < 0) return -1
    int cnt = ((b[idx + 2] & 0xFF) << 8) | (b[idx + 1] & 0xFF)
    return 3 + cnt * esz
}

private void dispatchAttr(String clu, String attr, String val) {
    switch (clu) {
        case "0006": if (attr == "0000" && val != null) handleOnOff(val); break
        case "0001": if (attr == "0021" && val != null) handleBattery(val); break
        case "0404": if (attr == "0000" && val != null) handleFlow(val); break
        case "FC11": handleEwelink(attr, val); break
        default: if (logEnable) log.debug "ignored cluster=${clu} attr=${attr} val=${val}"
    }
}

// Frames with no attribute value: read/write/configure responses, bind responses.
private void handleResponse(String clu, Map descMap) {
    List d = (descMap.data instanceof List) ? descMap.data*.toString()*.toUpperCase() : []
    // Auto-close (0x5011) read/write on firmware < 1.0.4 returns status 0x86 (unsupported).
    boolean unsupported5011 = d.size() >= 3 &&
        ((d[0] == "11" && d[1] == "50" && d[2] == "86") || (d[0] == "86" && d[1] == "11" && d[2] == "50"))
    if (clu == "FC11" && unsupported5011) {
        if (txtEnable) log.info "${device.displayName}: auto-close (0x5011) not supported by this firmware — needs 1.0.4+"
        return
    }
    if (logEnable) log.debug "response cluster=${clu} cmd=${descMap.command} data=${descMap.data}"
}

private void handleOnOff(String hex) {
    boolean isOn = (zigbee.convertHexToInt(hex) != 0)
    String v = isOn ? "open" : "closed"
    String sw = isOn ? "on" : "off"
    sendEvent(name: "valve",  value: v,  descriptionText: "${device.displayName} valve is ${v}")
    sendEvent(name: "switch", value: sw, descriptionText: "${device.displayName} switch is ${sw}")
    updateHomeKitChild(sw)
    if (txtEnable) log.info "${device.displayName}: valve ${v}"
}

private void handleBattery(String hex) {
    Integer raw = zigbee.convertHexToInt(hex)     // single byte, no swap
    Integer pct = Math.max(0, Math.min(100, Math.round(raw / 2.0) as Integer))
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery ${pct}%")
}

private void handleFlow(String hex) {
    Integer raw = leHexToInt(hex)                 // u16, little-endian on the wire
    BigDecimal f = (raw / 10.0G).setScale(1, java.math.RoundingMode.HALF_UP)
    sendEvent(name: "flow", value: f, unit: "m³/h", descriptionText: "${device.displayName} flow ${f} m³/h")
}

private void handleEwelink(String attr, String val) {
    if (attr == null || val == null) return
    if (isHydro()) { handleEwelinkHydro(attr, val); return }
    switch (attr) {
        case "500C": handleStatus(val); break                                   // u8
        case "5010": handleWorkState(val); break                                // u8/bool
        case "5006": sendNum("realtimeIrrigationDuration", leHexToLong(val), "s"); break  // u32 LE
        case "5007": sendNum("realtimeIrrigationVolume",   leHexToLong(val), "L"); break  // u32 LE
        case "500F": sendNum("dailyIrrigationVolume",      leHexToLong(val), "L"); break  // u32 LE
        case "500D": sendStr("irrigationStartTime", fmtEpoch(leHexToLong(val))); break    // u32 LE, Zigbee epoch
        case "500E": sendStr("irrigationEndTime",   fmtEpoch(leHexToLong(val))); break
        case "5011": if (txtEnable) log.info "${device.displayName}: auto-close timeout = ${leHexToInt(val)} min"; break
        case "5008": handleCyclic("cyclicTimedIrrigation", val, "s"); break     // type-0x42 string blob
        case "5009": handleCyclic("cyclicQuantitativeIrrigation", val, "L"); break
        default: if (logEnable) log.debug "FC11 unhandled attr ${attr} = ${val}"
    }
}

private void handleStatus(String hex) {
    Integer s = zigbee.convertHexToInt(hex)       // single byte
    String st = STATUS_MAP[s] ?: "unknown"
    sendEvent(name: "deviceStatus", value: st, descriptionText: "${device.displayName} status: ${st}")
    String water = ((s & 0x02) != 0) ? "wet" : "dry"      // bit 1 = leakage
    sendEvent(name: "water", value: water, descriptionText: "${device.displayName} water is ${water}")
    if (txtEnable) log.info "${device.displayName}: deviceStatus=${st}, water=${water}"
}

// --- Hydro (SWV-ZFE/ZFU/ZNE/ZNU) FC11 handling — numerics are BIG-endian (opposite of BSP) ---
private void handleEwelinkHydro(String attr, String val) {
    switch (attr) {
        case "0000": sendStr("childLock", (beHexToLong(val) != 0) ? "locked" : "unlocked"); break    // bool
        case "500C": handleStatusHydro(val); break                                                   // u8 bitmap
        case "5006": sendNum("realtimeIrrigationDuration", beHexToLong(val), "min"); break           // u32 BE
        case "5007": sendNum("realtimeIrrigationVolume",   beHexToLong(val), "L"); break             // u32 BE
        case "501B": sendNum("hourlyIrrigationVolume",     beHexToLong(val), "L"); break             // u32 BE
        case "501C": sendNum("hourlyIrrigationDuration",   beHexToLong(val), "min"); break           // u32 BE
        case "501F": handleScheduleStatus(val); break                                                // array: live schedule
        case "5014": if (txtEnable) log.info "${device.displayName}: rain-delay end (raw ${val}) — full support in a later phase"; break
        default: if (logEnable) log.debug "Hydro FC11 attr ${attr} = ${val} (handled in a later phase)"
    }
}

// Hydro valveAbnormalState (0x500C) bitmap: bit0 shortage, bit1 leak, bit3 fail-safe;
// dual-channel (ZF2) adds bit4 shortage-ch2, bit5 fail-safe-ch2.
private void handleStatusHydro(String hex) {
    int s = (int) beHexToLong(hex)
    boolean dual = isDualChannel()
    List<String> states = []
    if ((s & 0x01) != 0) states << (dual ? "water_shortage_ch1" : "water_shortage")
    if ((s & 0x02) != 0) states << "water_leakage"
    if ((s & 0x08) != 0) states << (dual ? "fail_safe_ch1" : "fail_safe")
    if (dual && (s & 0x10) != 0) states << "water_shortage_ch2"
    if (dual && (s & 0x20) != 0) states << "fail_safe_ch2"
    String st = states ? states.join(",") : "normal"
    sendEvent(name: "deviceStatus", value: st, descriptionText: "${device.displayName} status: ${st}")
    String water = ((s & 0x02) != 0) ? "wet" : "dry"
    sendEvent(name: "water", value: water, descriptionText: "${device.displayName} water is ${water}")
    if (txtEnable) log.info "${device.displayName}: deviceStatus=${st}, water=${water}"
}

// Hydro irrigationScheduleStatus (0x501F) — ZCL array of u8, big-endian timestamps/volumes.
// record after [arrayType,count]: [status][index][type][mode], then start/end times;
// running/end (21 B) add actual-end + expected/actual volume; start/standby (15 B) add expected only.
private void handleScheduleStatus(String hex) {
    List<Integer> raw = hexToBytes(hex)
    if (raw.size() < 7) return
    List<Integer> a = raw.drop(3)                    // skip arrayType(0x20) + count(2 LE)
    if (a.size() < 15) { if (logEnable) log.debug "scheduleStatus short: ${hex}"; return }
    Map sMap = [0: "start", 1: "end", 2: "running", 3: "standby"]
    Map mMap = [0: "duration", 1: "capacity", 2: "duration_with_interval"]
    String status = sMap[a[0]] ?: "unknown"
    String mode   = mMap[a[3]] ?: "duration"
    String prev   = device.currentValue("scheduleStatus")
    sendStr("scheduleStatus", status)
    sendStr("irrigationMode", mode)
    sendStr("scheduleStart", fmtEpoch(uint32BE(a, 4)))
    sendStr("scheduleEnd",   fmtEpoch(uint32BE(a, 8)))
    if (a[0] == 1 && a.size() >= 21) {               // end — real actual-end + final volumes
        sendStr("scheduleActualEnd", fmtEpoch(uint32BE(a, 12)))
        sendNum("expectedVolume", ((a[17] & 0xff) << 8) | (a[18] & 0xff), a[16] == 0 ? "gal" : "L")
        sendNum("actualVolume",   ((a[19] & 0xff) << 8) | (a[20] & 0xff), a[16] == 0 ? "gal" : "L")
    } else if (a[0] == 2 && a.size() >= 21) {        // running — live volume (bytes 12-15 are a live clock tick)
        sendStr("scheduleActualEnd", "running")
        sendNum("expectedVolume", ((a[17] & 0xff) << 8) | (a[18] & 0xff), a[16] == 0 ? "gal" : "L")
        sendNum("actualVolume",   ((a[19] & 0xff) << 8) | (a[20] & 0xff), a[16] == 0 ? "gal" : "L")
    } else {                                          // start / standby (15 bytes)
        sendStr("scheduleActualEnd", "n/a")
        sendNum("expectedVolume", ((a[13] & 0xff) << 8) | (a[14] & 0xff), a[12] == 0 ? "gal" : "L")
    }
    if (txtEnable && status != prev) log.info "${device.displayName}: irrigation ${status} (${mode}) ${fmtEpoch(uint32BE(a, 4))} → ${fmtEpoch(uint32BE(a, 8))}"
}

private void handleWorkState(String hex) {
    String ws = (zigbee.convertHexToInt(hex) != 0) ? "working" : "idle"
    sendEvent(name: "valveWorkState", value: ws, descriptionText: "${device.displayName} valve work state ${ws}")
}

// 0x5008/0x5009 are type-0x42 string blobs: Hubitat's value = [len][content].
// content = [count][total][amount(BE u32)][interval(BE u32)]. len 0 = no program set.
private void handleCyclic(String attrName, String hex, String unit) {
    List<Integer> b = hexToBytes(hex)
    if (b.isEmpty() || b[0] == 0) { sendStr(attrName, "not set"); return }
    b = b.drop(1)                                          // strip the length byte
    if (b.size() < 10) { if (logEnable) log.debug "${attrName}: partial payload '${hex}'"; return }
    int count = b[0]
    int total = b[1]
    long amount   = uint32BE(b, 2)
    long interval = uint32BE(b, 6)
    String summary = "${count}/${total} cycles, ${amount}${unit} every ${interval}s"
    sendEvent(name: attrName, value: summary, descriptionText: "${device.displayName} ${attrName}: ${summary}")
    if (txtEnable) log.info "${device.displayName}: ${attrName} -> ${summary}"
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

private Map fc11Opts() { return (settings?.useMfgCode) ? [mfgCode: COOLKIT_MFG] : [:] }

// --- Model detection (combined driver: BSP vs Hydro) ---
private String hydroModel()     { String m = (device.getDataValue("model") ?: "").toUpperCase(); return HYDRO_MODELS.contains(m) ? m : null }
private boolean isHydro()       { return hydroModel() != null }
private boolean isDualChannel() { return hydroModel() == "SWV-ZF2" }

void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds.findAll { it }, hubitat.device.Protocol.ZIGBEE))
}

private void sendNum(String name, Number v, String unit) {
    sendEvent(name: name, value: v, unit: unit, descriptionText: "${device.displayName} ${name} ${v}${unit}")
}

private void sendStr(String name, String v) {
    sendEvent(name: name, value: v, descriptionText: "${device.displayName} ${name} ${v}")
}

private String hex8(long v) {
    return Long.toHexString(v & 0xFFFFFFFFL).toUpperCase().padLeft(8, '0')
}

private List<Integer> hexToBytes(String hex) {
    List<Integer> out = []
    if (!hex) return out
    for (int i = 0; i + 1 < hex.length(); i += 2) out << Integer.parseInt(hex.substring(i, i + 2), 16)
    return out
}

// Native ZCL numeric attributes arrive little-endian in descMap.value (e.g. "0300" = 3).
private String reverseHex(String hex) {
    if (!hex) return hex
    List<String> bytes = []
    for (int i = 0; i + 1 < hex.length(); i += 2) bytes << hex.substring(i, i + 2)
    return bytes.reverse().join()
}

private long leHexToLong(String hex) {
    try { return Long.parseLong(reverseHex(hex), 16) } catch (ignored) { return 0L }
}

private int leHexToInt(String hex) { return (int) leHexToLong(hex) }

// Hydro FC11 numerics are already big-endian on the wire — parse directly.
private long beHexToLong(String hex) {
    try { return Long.parseLong(hex, 16) } catch (ignored) { return 0L }
}

private long uint32BE(List<Integer> b, int off) {
    return ((long)(b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff)
}

private int clampI(v, int lo, int hi) {
    int x
    try { x = (v as BigDecimal).intValue() } catch (ignored) { x = lo }
    return Math.max(lo, Math.min(hi, x))
}

private long clampL(v, long lo, long hi) {
    long x
    try { x = (v as BigDecimal).longValue() } catch (ignored) { x = lo }
    return Math.max(lo, Math.min(hi, x))
}

// SWV irrigation timestamps use the Zigbee epoch (2000-01-01 UTC), not Unix.
// Rendered in Berlin local time, European date format.
private String fmtEpoch(long zigbeeSeconds) {
    if (zigbeeSeconds <= 0) return "n/a"
    try { return new Date((zigbeeSeconds + ZIGBEE_EPOCH_OFFSET) * 1000L).format("dd.MM.yyyy HH:mm:ss", TimeZone.getTimeZone("Europe/Berlin")) }
    catch (ignored) { return zigbeeSeconds.toString() }
}
