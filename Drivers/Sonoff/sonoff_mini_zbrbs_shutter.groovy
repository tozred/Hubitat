/**
 *  Sonoff MINI-ZBRBS Zigbee Roller Shutter Switch
 *  Hubitat Elevation device driver
 *
 *  Version: 1.1.0 - Travel limit: a physical position that counts as fully open
 *  Version: 1.0.0
 *
 *  Zigbee map (endpoint 1), from the MINI-ZBRBS definition in
 *  Koenkk/zigbee-herdsman-converters (src/devices/sonoff.ts):
 *    0x0102 Window Covering  standard lift control: 0x00 up/open, 0x01 down/close,
 *                            0x02 stop, 0x05 go to lift percentage.
 *                            0x0008 currentPositionLiftPercentage: 0 = fully OPEN,
 *                            100 = fully closed (ZCL convention; Hubitat's is the reverse).
 *    0xFC11 eWeLink custom cluster (no manufacturer code):
 *        0x0016 u8  external switch trigger mode: 0 edge, 1 pulse, 2 following(off),
 *                   130 following(on)
 *        0x5001 u8  motor travel calibration action: 0 none, 2 start automatic,
 *                   3 start manual, 4 clear, 7 manual: this is fully open,
 *                   8 manual: this is fully closed
 *        0x5012 u8  travel calibration status: 0 uncalibrated, 1 calibrated
 *        0x5013 u8  motor run status: 0 stop, 1 forward, 2 reverse
 *
 *  Buttons (for Button Controller, same as the previous cinema screen driver):
 *    1 = open (to the default open position), 2 = close (to the default close position),
 *    3 = stop
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.1.0"

@Field static final int CLUSTER_WINDOW_COVERING = 0x0102
@Field static final int CLUSTER_EWELINK = 0xFC11
@Field static final int ATTR_LIFT_PERCENT = 0x0008

@Field static final int ATTR_TRIGGER_MODE = 0x0016
@Field static final int ATTR_CALIBRATION_ACTION = 0x5001
@Field static final int ATTR_CALIBRATION_STATUS = 0x5012
@Field static final int ATTR_MOTOR_RUN_STATUS = 0x5013

@Field static final Map CALIBRATION_ACTIONS = [
    "start automatic": 2, "start manual": 3, "clear": 4,
    "manual: this is fully open": 7, "manual: this is fully closed": 8
]
@Field static final Map TRIGGER_MODES = ["edge": 0, "pulse": 1, "following(off)": 2, "following(on)": 130]

metadata {
    definition (name: "Sonoff MINI-ZBRBS Roller Shutter", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "WindowShade"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "PushableButton"
        capability "HealthCheck"

        attribute "moving", "enum", ["stopped", "opening", "closing"]
        attribute "calibration", "enum", ["calibrated", "uncalibrated"]
        attribute "externalTriggerMode", "string"
        attribute "driverVersion", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "stop"
        command "calibrate", [[name: "action*", type: "ENUM", constraints: CALIBRATION_ACTIONS.keySet() as List,
                               description: "Learn the motor's travel range. Automatic runs it end to end by itself."]]
        command "setExternalTriggerMode", [[name: "mode*", type: "ENUM", constraints: TRIGGER_MODES.keySet() as List,
                                            description: "How the wired wall switch behaves"]]
        command "setDefaultOpenPosition", [[name: "position*", type: "NUMBER", description: "0-100"]]
        command "setDefaultClosePosition", [[name: "position*", type: "NUMBER", description: "0-100"]]

        fingerprint profileId: "0104", endpointId: "01", model: "MINI-ZBRBS", manufacturer: "SONOFF",
                    deviceJoinName: "Sonoff MINI-ZBRBS Roller Shutter"
    }

    preferences {
        input name: "invertDirection", type: "bool", title: "Invert open/close direction",
              description: "Swap open and close, and mirror the position. For a projector screen, where 'open' should lower the screen.",
              defaultValue: false
        input name: "openLimit", type: "number", title: "Fully open at (% of the motor's travel)",
              description: "The highest the motor may go. Hubitat, Apple Home and rules treat this as 100 %, and never send it further. 0 stays fully closed.",
              defaultValue: 100, range: "1..100"
        input name: "defaultOpenPosition", type: "number", title: "Default open position (%)",
              description: "Where 'open' (and button 1) goes", defaultValue: 100, range: "0..100"
        input name: "defaultClosePosition", type: "number", title: "Default close position (%)",
              description: "Where 'close' (and button 2) goes", defaultValue: 0, range: "0..100"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ==================== Lifecycle ====================

def installed() {
    sendEvent(name: "numberOfButtons", value: 3)
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    sendEvent(name: "moving", value: "stopped")
}

def updated() {
    log.info "${device.displayName}: settings updated"
    if (logEnable) runIn(86400, "logsOff")   // debug logging switches itself off after 24 h
    sendEvent(name: "numberOfButtons", value: 3)
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    runEvery1Hour("healthCheck")
}

def configure() {
    logInfo "configuring"
    sendEvent(name: "numberOfButtons", value: 3)
    def cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0102 {${device.zigbeeId}} {}"
    cmds += "delay 200"
    cmds += zigbee.configureReporting(CLUSTER_WINDOW_COVERING, ATTR_LIFT_PERCENT, 0x20, 1, 600, 1)
    cmds += "delay 200"
    cmds += refresh()
    return cmds
}

def refresh() {
    def cmds = []
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_LIFT_PERCENT)
    cmds += "delay 200"
    // Read one at a time: some units reject a combined read of this cluster (per the reference)
    [ATTR_CALIBRATION_STATUS, ATTR_MOTOR_RUN_STATUS, ATTR_TRIGGER_MODE].each {
        cmds += zigbee.readAttribute(CLUSTER_EWELINK, it)
        cmds += "delay 200"
    }
    return cmds
}

// ==================== Position conventions ====================

private int limit() { return Math.max(1, Math.min(100, (openLimit != null ? openLimit : 100) as int)) }

/**
 * Hubitat position (0 closed .. 100 open, after inversion) from the ZCL lift percentage
 * (0 open). The motor's travel is scaled so that openLimit counts as 100 %; anything the
 * motor reports above the limit (the wall switch can still take it there) reads as 100.
 */
private int positionFromLift(int lift) {
    int motor = 100 - Math.max(0, Math.min(100, lift))       // 0 closed .. 100 motor end stop
    int pos = Math.min(100, Math.round(motor * 100.0 / limit()) as int)
    return invertDirection ? 100 - pos : pos
}

private int liftFromPosition(int pos) {
    pos = Math.max(0, Math.min(100, pos))
    if (invertDirection) pos = 100 - pos
    int motor = Math.round(pos * limit() / 100.0) as int      // never beyond the limit
    return 100 - motor
}

// ==================== Commands ====================

def open()  { setPosition((defaultOpenPosition != null ? defaultOpenPosition : 100) as int) }
def close() { setPosition((defaultClosePosition != null ? defaultClosePosition : 0) as int) }
def on()    { open() }
def off()   { close() }

def setPosition(position) {
    int target = Math.max(0, Math.min(100, position as int))
    int current = (device.currentValue("position") ?: 0) as int
    logInfo "moving to ${target}%"
    if (target != current) {
        String dir = target > current ? "opening" : "closing"
        sendEvent(name: "moving", value: dir)
        sendEvent(name: "windowShade", value: dir)
    }
    // Fully open / fully closed use the plain commands, which run the motor to its end stop
    int lift = liftFromPosition(target)
    // The plain "up" command runs to the motor's end stop, so only use it when there is no limit
    if (lift == 0 && limit() == 100) return zigbee.command(CLUSTER_WINDOW_COVERING, 0x00)
    if (lift == 100) return zigbee.command(CLUSTER_WINDOW_COVERING, 0x01)
    return zigbee.command(CLUSTER_WINDOW_COVERING, 0x05, zigbee.convertToHexString(lift, 2))
}

def setLevel(level, duration = null) { setPosition(level) }

def stop() {
    logInfo "stop"
    return zigbee.command(CLUSTER_WINDOW_COVERING, 0x02) + ["delay 1500"] + zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_LIFT_PERCENT)
}

def stopPositionChange() { stop() }
def stopLevelChange()    { stop() }

def startPositionChange(String direction) {
    // Go to the end of the allowed range rather than "run until stopped", so a missed
    // stop can never take it past the open limit
    return setPosition(direction == "open" ? 100 : 0)
}

def startLevelChange(String direction) { startPositionChange(direction == "up" ? "open" : "close") }

def push(button) {
    int b = button as int
    sendEvent(name: "pushed", value: b, isStateChange: true, type: "digital")
    switch (b) {
        case 1: return open()
        case 2: return close()
        case 3: return stop()
        default: log.warn "${device.displayName}: button ${b} does not exist"
    }
}

def setDefaultOpenPosition(position)  { device.updateSetting("defaultOpenPosition", [value: position as int, type: "number"]) }
def setDefaultClosePosition(position) { device.updateSetting("defaultClosePosition", [value: position as int, type: "number"]) }

def calibrate(String action) {
    Integer v = CALIBRATION_ACTIONS[action]
    if (v == null) { log.warn "${device.displayName}: unknown calibration action ${action}"; return }
    logInfo "calibration: ${action}"
    return zigbee.writeAttribute(CLUSTER_EWELINK, ATTR_CALIBRATION_ACTION, 0x20, v) + ["delay 1000"] +
           zigbee.readAttribute(CLUSTER_EWELINK, ATTR_CALIBRATION_STATUS)
}

def setExternalTriggerMode(String mode) {
    Integer v = TRIGGER_MODES[mode]
    if (v == null) { log.warn "${device.displayName}: unknown trigger mode ${mode}"; return }
    logInfo "wall switch mode: ${mode}"
    return zigbee.writeAttribute(CLUSTER_EWELINK, ATTR_TRIGGER_MODE, 0x20, v) + ["delay 500"] +
           zigbee.readAttribute(CLUSTER_EWELINK, ATTR_TRIGGER_MODE)
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "parse: ${description}"
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online")

    def descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) return []
    String cluster = (descMap.cluster ?: descMap.clusterId)?.toUpperCase()
    String attrId = descMap.attrId?.toUpperCase()
    String value = descMap.value

    if (cluster == "0102" && attrId == "0008" && value) {
        handlePosition(Integer.parseInt(value, 16))
    } else if (cluster == "FC11" && attrId && value) {
        handleEwelink(attrId, Integer.parseInt(value, 16))
    } else if (descMap.additionalAttrs) {
        descMap.additionalAttrs.each { a ->
            if (cluster == "FC11" && a.attrId && a.value) handleEwelink(a.attrId.toUpperCase(), Integer.parseInt(a.value, 16))
        }
    }
    return []
}

private void handlePosition(int lift) {
    int pos = positionFromLift(lift)
    sendEvent(name: "position", value: pos, unit: "%")
    sendEvent(name: "level", value: pos, unit: "%")
    sendEvent(name: "switch", value: pos > 0 ? "on" : "off")
    if (device.currentValue("moving") == "stopped") updateShadeState(pos)
    logDebug "position ${pos}% (lift ${lift}%)"
}

private void updateShadeState(int pos) {
    String shade = pos >= 100 ? "open" : (pos <= 0 ? "closed" : "partially open")
    if (device.currentValue("windowShade") != shade) logInfo "is ${shade} (${pos}%)"
    sendEvent(name: "windowShade", value: shade)
}

private void handleEwelink(String attrId, int v) {
    switch (attrId) {
        case "5013":   // motor run status
            if (v == 0) {
                sendEvent(name: "moving", value: "stopped")
                updateShadeState((device.currentValue("position") ?: 0) as int)
            }
            break
        case "5012":
            sendEvent(name: "calibration", value: v == 1 ? "calibrated" : "uncalibrated")
            break
        case "0016":
            def mode = TRIGGER_MODES.find { k, code -> code == v }?.key ?: "unknown (${v})"
            sendEvent(name: "externalTriggerMode", value: mode)
            break
        default:
            logDebug "FC11 0x${attrId} = ${v}"
    }
}

// ==================== Health ====================

def healthCheck() {
    if (state.lastRx && now() - state.lastRx > 2 * 60 * 60 * 1000) {
        sendEvent(name: "healthStatus", value: "offline")
    }
    sendHubCommand(new hubitat.device.HubMultiAction(zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_LIFT_PERCENT), hubitat.device.Protocol.ZIGBEE))
}

def ping() { healthCheck() }

// ==================== Logging ====================

def logsOff() {
    log.info "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (txtEnable) log.info "${device.displayName}: ${msg}" }
