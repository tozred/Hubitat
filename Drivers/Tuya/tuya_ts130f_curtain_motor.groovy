/**
 *  Tuya TS130F Curtain/Blind Motor Controller
 *  Model: TS130F (various _TZ3000_* manufacturers)
 *
 *  A driver for Tuya TS130F curtain/blind motor controllers.
 *  Designed for cinema screens and roller blinds where open/close
 *  direction may need to be inverted.
 *
 *  Features:
 *  - Open/Close/Stop control
 *  - Position control (0-100%)
 *  - Invert open/close direction (for cinema screens)
 *  - Configurable default open/close positions
 *  - Motor reversal command
 *  - Calibration mode
 *  - Position reporting
 *
 *  Button Mapping (for Button Controller):
 *  - Button 1: Open
 *  - Button 2: Close
 *  - Button 3: Stop
 *
 *  Version: 1.0.0
 *
 *  References:
 *  - https://www.zigbee2mqtt.io/devices/TS130F.html
 *  - https://community.hubitat.com/t/zigbee-cutain-module-ts130f/107907
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.2.0"

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_WINDOW_COVERING = 0x0102

// Window Covering Cluster Attributes
@Field static final int ATTR_CURRENT_POSITION_LIFT = 0x0008   // Current position (0-100, 0=open, 100=closed)
@Field static final int ATTR_CONFIG_STATUS = 0x0007
@Field static final int ATTR_MODE = 0x0017

// Tuya manufacturer-specific attributes on the Window Covering cluster.
// Same IDs/semantics zigbee2mqtt and the ZHA quirk use for TS130F.
@Field static final int ATTR_TUYA_MOVING_STATE = 0xF000       // enum8:  0=up, 1=stop, 2=down
@Field static final int ATTR_TUYA_CALIBRATION = 0xF001         // enum8:  0=calibration ON, 1=calibration OFF
@Field static final int ATTR_TUYA_MOTOR_REVERSAL = 0xF002      // enum8:  0=normal, 1=reversed
@Field static final int ATTR_TUYA_CALIBRATION_TIME = 0xF003    // uint16: full travel time in 0.1s units

// ZCL data types
@Field static final int DT_ENUM8 = 0x30
@Field static final int DT_UINT16 = 0x21

// Window Covering Cluster Commands
@Field static final int CMD_UP_OPEN = 0x00
@Field static final int CMD_DOWN_CLOSE = 0x01
@Field static final int CMD_STOP = 0x02
@Field static final int CMD_GO_TO_LIFT_PERCENTAGE = 0x05

// ==================== Metadata ====================

metadata {
    definition (name: "Tuya TS130F Curtain Motor", namespace: "benberlin", author: "Ben Fayershtein") {
        capability "Actuator"
        capability "WindowShade"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "PushableButton"
        capability "Configuration"
        capability "Refresh"

        // Custom attributes
        attribute "lastActivity", "string"
        attribute "moving", "enum", ["stopped", "opening", "closing"]
        attribute "motorReversal", "enum", ["normal", "reversed"]
        attribute "calibrationMode", "enum", ["off", "on"]
        attribute "calibrationTime", "number"
        attribute "driverVersion", "string"

        // Commands
        command "stop"
        command "setMotorReversal", [[name: "reversal*", type: "ENUM", constraints: ["normal", "reversed"],
                                      description: "Reverse motor direction"]]
        command "startCalibration"
        command "stopCalibration"
        command "setCalibrationTime", [[name: "seconds*", type: "NUMBER",
                                        description: "Full travel time in seconds (0-655). Overwrites the motor's stored travel time."]]
        command "setDefaultOpenPosition", [[name: "position*", type: "NUMBER", description: "Default position when opening (0-100)"]]
        command "setDefaultClosePosition", [[name: "position*", type: "NUMBER", description: "Default position when closing (0-100)"]]

        // Fingerprints for various TS130F variants
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0004,0005,0102",
                    outClusters: "0019",
                    manufacturer: "_TZ3000_yruungrl", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0004,0005,0102",
                    outClusters: "0019",
                    manufacturer: "_TZ3000_vd43bbfq", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0004,0005,0102",
                    outClusters: "0019",
                    manufacturer: "_TZ3000_1dd0d5yi", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0004,0005,0102",
                    outClusters: "0019",
                    manufacturer: "_TZ3000_fccpjz5z", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0004,0005,0102",
                    outClusters: "0019",
                    manufacturer: "_TZ3000_zirycpws", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"

        // Generic TS130F fingerprint
        fingerprint manufacturer: "_TZ3000_yruungrl", model: "TS130F",
                    deviceJoinName: "Tuya Curtain Motor"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "invertPosition", type: "bool", title: "Invert open/close direction",
              description: "Swap open and close (for cinema screens where 'open' should lower the screen)",
              defaultValue: false

        input name: "defaultOpenPosition", type: "number", title: "Default open position (%)",
              description: "Position to go to when 'open' is pressed (0-100). With invert: 0=screen down, 100=screen up",
              defaultValue: 100, range: "0..100"

        input name: "defaultClosePosition", type: "number", title: "Default close position (%)",
              description: "Position to go to when 'close' is pressed (0-100). With invert: 0=screen down, 100=screen up",
              defaultValue: 0, range: "0..100"

        input name: "openThreshold", type: "number", title: "Open threshold (%)",
              description: "Position at or above this is considered 'open'",
              defaultValue: 99, range: "1..100"

        input name: "closedThreshold", type: "number", title: "Closed threshold (%)",
              description: "Position at or below this is considered 'closed'",
              defaultValue: 1, range: "0..99"

        input name: "closedLimit", type: "number", title: "Closed travel limit (%)",
              description: "Where the motor should stop when fully closed, on the motor's own scale. " +
                           "Raise above 0 if the shade over-runs at the bottom (pools on the floor).",
              defaultValue: 0, range: "0..99"

        input name: "openLimit", type: "number", title: "Open travel limit (%)",
              description: "Where the motor should stop when fully open, on the motor's own scale. " +
                           "Lower below 100 if the shade over-runs at the top. " +
                           "Cannot make the motor travel FURTHER than its mechanical limit.",
              defaultValue: 100, range: "1..100"
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Tuya TS130F Curtain Motor installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "Tuya TS130F Curtain Motor updated"
    unschedule()

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
}

def initialize() {
    // 3 buttons: Open, Close, Stop
    sendEvent(name: "numberOfButtons", value: 3)
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    sendEvent(name: "moving", value: "stopped")

    runIn(5, "delayedConfigure")
}

// runIn() discards a method's return value, so scheduled configuration has to
// push its commands out explicitly rather than returning them.
def delayedConfigure() {
    sendZigbeeCommands(configure())
}

// ==================== Configuration ====================

def configure() {
    log.info "Configuring Tuya TS130F Curtain Motor..."

    def cmds = []

    // Bind Window Covering cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0102 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure position reporting
    cmds += zigbee.configureReporting(CLUSTER_WINDOW_COVERING, ATTR_CURRENT_POSITION_LIFT, 0x20, 0, 3600, 1)
    cmds += "delay 500"

    // Initial refresh (includes the Tuya calibration/reversal attributes)
    cmds += refresh()

    return cmds
}

def refresh() {
    logDebug "Refresh"

    def cmds = []
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_CURRENT_POSITION_LIFT)
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION)
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_MOTOR_REVERSAL)
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION_TIME)

    return cmds
}

// ==================== WindowShade Commands ====================

def open() {
    logInfo "Opening"

    Integer targetPos = (defaultOpenPosition ?: 100) as Integer
    targetPos = [[targetPos, 100].min(), 0].max()

    setPosition(targetPos)
}

def close() {
    logInfo "Closing"

    Integer targetPos = (defaultClosePosition ?: 0) as Integer
    targetPos = [[targetPos, 100].min(), 0].max()

    setPosition(targetPos)
}

def stop() {
    logInfo "Stopping"
    sendEvent(name: "moving", value: "stopped")
    return zigbee.command(CLUSTER_WINDOW_COVERING, CMD_STOP)
}

def setPosition(position) {
    Integer pos = position as Integer
    pos = [[pos, 100].min(), 0].max()

    // Invert if needed (for cinema screens)
    Integer zigbeePos = invertPosition ? (100 - pos) : pos

    // Squeeze the request into the usable part of the motor's travel
    Integer motorPos = toMotorScale(zigbeePos)

    // ZCL GoToLiftPercentage: 0% = fully open (lift up), 100% = fully closed
    Integer cmdPos = 100 - motorPos

    logInfo "Setting position to ${pos}% (motor: ${motorPos}%, zigbee lift: ${cmdPos}%)"

    // Determine direction for moving status
    def currentPos = device.currentValue("position") ?: 50
    if (pos > currentPos) {
        sendEvent(name: "moving", value: "opening")
    } else if (pos < currentPos) {
        sendEvent(name: "moving", value: "closing")
    }

    def cmds = []
    cmds += zigbee.command(CLUSTER_WINDOW_COVERING, CMD_GO_TO_LIFT_PERCENTAGE, zigbee.convertToHexString(cmdPos, 2))

    // Schedule a refresh to get final position
    runIn(30, "refreshPosition")

    return cmds
}

def refreshPosition() {
    sendEvent(name: "moving", value: "stopped")
    sendZigbeeCommands(refresh())
}

// ==================== Switch Commands ====================

def on() {
    open()
}

def off() {
    close()
}

// ==================== SwitchLevel Commands ====================

def setLevel(level, duration = null) {
    setPosition(level)
}

// ==================== ChangeLevel Commands ====================

def startLevelChange(String direction) {
    logDebug "Start level change: ${direction}"

    if (direction == "up") {
        sendEvent(name: "moving", value: "opening")
        return zigbee.command(CLUSTER_WINDOW_COVERING, CMD_UP_OPEN)
    } else {
        sendEvent(name: "moving", value: "closing")
        return zigbee.command(CLUSTER_WINDOW_COVERING, CMD_DOWN_CLOSE)
    }
}

def stopLevelChange() {
    stop()
}

// ==================== Button Commands ====================

def push(buttonNumber) {
    Integer btn = buttonNumber as Integer

    switch(btn) {
        case 1:
            logInfo "Button 1 pushed - Opening"
            open()
            break
        case 2:
            logInfo "Button 2 pushed - Closing"
            close()
            break
        case 3:
            logInfo "Button 3 pushed - Stopping"
            stop()
            break
    }
}

// ==================== Custom Commands ====================

def setMotorReversal(String reversal) {
    logInfo "Setting motor reversal to: ${reversal}"

    // Tuya-specific attribute 0xF002 on the window covering cluster: 0=normal, 1=reversed.
    // The standard ZCL Mode attribute (0x0017) is rejected as read-only by TS130F firmware.
    Integer value = (reversal == "reversed") ? 0x01 : 0x00

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_MOTOR_REVERSAL, DT_ENUM8, value)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_MOTOR_REVERSAL)

    sendZigbeeCommands(cmds)
}

def startCalibration() {
    logInfo "Entering calibration mode"

    // Tuya-specific attribute 0xF001: 0 = calibration ON, 1 = calibration OFF.
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION, DT_ENUM8, 0x00)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION)

    logWarn "Calibration started. Now: (1) run the screen to its FULLY OPEN limit and let it stop, " +
            "(2) send Close and let it run all the way to the FULLY CLOSED limit without stopping it, " +
            "(3) then press stopCalibration. The motor stores the measured travel time."

    sendZigbeeCommands(cmds)
}

def stopCalibration() {
    logInfo "Leaving calibration mode"

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION, DT_ENUM8, 0x01)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION_TIME)

    sendZigbeeCommands(cmds)
}

def setCalibrationTime(seconds) {
    BigDecimal secs = seconds as BigDecimal
    if (secs < 0G) secs = 0G
    if (secs > 655G) secs = 655G

    // 0xF003 stores the full-travel time in tenths of a second.
    // setScale rather than Math.round: BigDecimal makes Math.round ambiguous in Groovy.
    Integer tenths = (secs * 10G).setScale(0, java.math.RoundingMode.HALF_UP).intValue()

    logInfo "Setting calibration (full travel) time to ${secs}s"

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION_TIME, DT_UINT16, tenths)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_WINDOW_COVERING, ATTR_TUYA_CALIBRATION_TIME)

    sendZigbeeCommands(cmds)
}

def setDefaultOpenPosition(position) {
    Integer pos = position as Integer
    pos = [[pos, 100].min(), 0].max()
    logInfo "Setting default open position to ${pos}%"
    device.updateSetting("defaultOpenPosition", [value: pos, type: "number"])
}

def setDefaultClosePosition(position) {
    Integer pos = position as Integer
    pos = [[pos, 100].min(), 0].max()
    logInfo "Setting default close position to ${pos}%"
    device.updateSetting("defaultClosePosition", [value: pos, type: "number"])
}

// ==================== Travel Limits ====================
//
// The TS130F reports and accepts positions on the motor's own scale, which runs
// between whatever mechanical stops the motor has. When those stops do not line
// up with the real window, the usable travel is only a sub-range of 0-100%.
// These two helpers map Hubitat's 0-100% onto [closedLimit .. openLimit] so the
// user-facing scale stays a full, linear 0-100 over the range that is actually
// useful.

private Integer travelClosedLimit() {
    Integer v = (closedLimit == null ? 0 : closedLimit) as Integer
    if (v < 0) v = 0
    if (v > 99) v = 99
    return v
}

private Integer travelOpenLimit() {
    Integer v = (openLimit == null ? 100 : openLimit) as Integer
    if (v < 1) v = 1
    if (v > 100) v = 100
    // Guard against an inverted or empty range from bad settings.
    Integer lo = travelClosedLimit()
    if (v <= lo) v = lo + 1
    return v
}

// Hubitat 0-100% -> motor scale
private Integer toMotorScale(Integer userPos) {
    Integer lo = travelClosedLimit()
    Integer hi = travelOpenLimit()
    BigDecimal scaled = lo + ((userPos as BigDecimal) * (hi - lo) / 100G)
    Integer out = scaled.setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    if (out < 0) out = 0
    if (out > 100) out = 100
    return out
}

// Motor scale -> Hubitat 0-100%
private Integer fromMotorScale(Integer motorPos) {
    Integer lo = travelClosedLimit()
    Integer hi = travelOpenLimit()
    BigDecimal scaled = ((motorPos - lo) as BigDecimal) * 100G / ((hi - lo) as BigDecimal)
    Integer out = scaled.setScale(0, java.math.RoundingMode.HALF_UP).intValue()
    if (out < 0) out = 0
    if (out > 100) out = 100
    return out
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parse: $description"

    // Update activity timestamp
    sendEvent(name: "lastActivity", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    def events = []

    try {
        if (description.startsWith("read attr -")) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            logDebug "Read attr: $descMap"
            events += handleReadAttr(descMap)
        }
        else if (description.startsWith("catchall:")) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            logDebug "Catchall: $descMap"
            events += handleCatchall(descMap)
        }
    } catch (e) {
        logWarn "Parse error: ${e.message}"
    }

    return events
}

private List handleReadAttr(Map descMap) {
    def events = []
    def cluster = descMap.cluster?.toUpperCase()
    def attrId = descMap.attrId?.toUpperCase()
    def value = descMap.value

    if (!value) return events

    if (cluster == "0102") {  // Window Covering
        events += handleWindowCoveringCluster(attrId, value)
    }

    return events
}

private List handleWindowCoveringCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0008":  // Current Position Lift Percentage
            Integer rawPos = Integer.parseInt(value, 16)

            // ZCL reports 0-100 where 0=fully open, 100=fully closed
            // Convert to our standard where 100=open, 0=closed
            Integer pos = 100 - rawPos

            // Undo the travel-limit squeeze so the user sees a full 0-100 scale
            pos = fromMotorScale(pos)

            // Apply inversion if configured
            if (invertPosition) {
                pos = 100 - pos
            }

            pos = [[pos, 100].min(), 0].max()

            // Determine window shade state
            Integer openThresh = (openThreshold ?: 99) as Integer
            Integer closedThresh = (closedThreshold ?: 1) as Integer

            String shadeState
            if (pos >= openThresh) {
                shadeState = "open"
            } else if (pos <= closedThresh) {
                shadeState = "closed"
            } else {
                shadeState = "partially open"
            }

            events << createEvent(name: "position", value: pos, unit: "%")
            events << createEvent(name: "level", value: pos, unit: "%")
            events << createEvent(name: "windowShade", value: shadeState)
            events << createEvent(name: "moving", value: "stopped")

            // Switch state
            events << createEvent(name: "switch", value: (pos >= openThresh) ? "on" : "off")

            logInfo "Position: ${pos}% (${shadeState})"
            break

        case "F000":  // Tuya moving state: 0=up, 1=stop, 2=down
            Integer ms = Integer.parseInt(value, 16)
            String moveState = (ms == 0) ? "opening" : (ms == 2) ? "closing" : "stopped"
            events << createEvent(name: "moving", value: moveState)
            logDebug "Moving state: ${moveState}"
            break

        case "F001":  // Tuya calibration mode: 0=on, 1=off
            String calState = (Integer.parseInt(value, 16) == 0) ? "on" : "off"
            events << createEvent(name: "calibrationMode", value: calState)
            logInfo "Calibration mode: ${calState}"
            break

        case "F002":  // Tuya motor reversal: 0=normal, 1=reversed
            String rev = (Integer.parseInt(value, 16) == 0) ? "normal" : "reversed"
            events << createEvent(name: "motorReversal", value: rev)
            logInfo "Motor reversal: ${rev}"
            break

        case "F003":  // Tuya calibration time, in 0.1s units
            BigDecimal calSecs = (Integer.parseInt(value, 16) as BigDecimal) / 10
            events << createEvent(name: "calibrationTime", value: calSecs, unit: "s")
            logInfo "Calibration (full travel) time: ${calSecs}s"
            break
    }

    return events
}

private List handleCatchall(Map descMap) {
    def events = []

    // Handle Window Covering cluster responses
    if (descMap.clusterId == "0102") {
        if (descMap.command in ["00", "01", "02"]) {
            // Command acknowledgment
            logDebug "Window covering command acknowledged"
        }
    }

    return events
}

// ==================== Helper Methods ====================

void sendZigbeeCommands(def cmds) {
    if (cmds == null) return

    List cmdList = (cmds instanceof List) ? cmds.flatten() : [cmds]
    cmdList = cmdList.findAll { it != null && it.toString() != "" }.collect { it.toString() }

    if (cmdList.isEmpty()) return

    // HubMultiAction honours "delay N" entries; HubAction would choke on them,
    // and dropping them fires the writes faster than the device can absorb.
    logDebug "Sending ${cmdList.size()} command(s)"
    sendHubCommand(new hubitat.device.HubMultiAction(cmdList, hubitat.device.Protocol.ZIGBEE))
}

// ==================== Logging ====================

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

def logsOff() {
    log.warn "${device.displayName}: Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
