/**
 *  Aqara H1 Wireless Remote Switch (Double Rocker)
 *  Model: lumi.remote.b28ac1 / WRS-R02
 *
 *  A simple, focused driver for the Aqara H1 Double Rocker wireless remote.
 *
 *  Features:
 *  - Single, double, triple press detection
 *  - Hold and release detection
 *  - Both buttons pressed simultaneously
 *  - Battery monitoring
 *  - Hubitat Button Controller compatible
 *
 *  Button Mapping:
 *  - Button 1: Left rocker
 *  - Button 2: Right rocker
 *  - Button 3: Both rockers pressed together
 *
 *  Events sent:
 *  - pushed (single press)
 *  - doubleTapped (double press)
 *  - held (hold)
 *  - released (release after hold)
 *
 *  For triple press, a "pushed" event is sent with additional attribute.
 *
 *  Pairing Instructions:
 *  Hold the LEFT rocker for 10 seconds until the LEDs start flashing.
 *
 *  Version: 1.0.0
 *
 *  References:
 *  - https://www.zigbee2mqtt.io/devices/WRS-R02.html
 *  - https://github.com/Koenkk/zigbee-herdsman-converters/issues/2620
 *  - https://zigbee.blakadder.com/Aqara_WRS-R02.html
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.0.0"

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_POWER_CONFIG = 0x0001
@Field static final int CLUSTER_MULTISTATE = 0x0012
@Field static final int CLUSTER_ONOFF = 0x0006
@Field static final int CLUSTER_LUMI = 0xFCC0

// Lumi manufacturer code
@Field static final int LUMI_MFG_CODE = 0x115F

// Button action values (from multistate input)
@Field static final Map BUTTON_ACTIONS = [
    1: "pushed",      // Single press
    2: "doubleTapped", // Double press
    3: "pushed",      // Triple press (reported as pushed with count=3)
    0: "held",        // Hold
    255: "released"   // Release after hold
]

// ==================== Metadata ====================

metadata {
    definition (name: "Aqara H1 Double Rocker Remote", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "PushableButton"
        capability "DoubleTapableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"

        // Custom attributes
        attribute "lastButton", "number"
        attribute "lastAction", "string"
        attribute "lastActivity", "string"
        attribute "clickMode", "enum", ["fast", "multi"]
        attribute "driverVersion", "string"

        // Commands
        command "setClickMode", [[name: "mode*", type: "ENUM", constraints: ["fast", "multi"],
                                  description: "fast=single click only (instant), multi=single/double/triple/hold (slight delay)"]]

        // Fingerprints
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0001",
                    outClusters: "0003,0006,0008,0300",
                    manufacturer: "LUMI", model: "lumi.remote.b28ac1",
                    deviceJoinName: "Aqara H1 Double Rocker Remote"

        fingerprint manufacturer: "LUMI", model: "lumi.remote.b28ac1",
                    deviceJoinName: "Aqara H1 Double Rocker Remote"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "defaultClickMode", type: "enum", title: "Click mode",
              options: [
                  ["fast": "Fast (single click only, instant response)"],
                  ["multi": "Multi (single/double/triple/hold, slight delay)"]
              ],
              defaultValue: "multi",
              description: "Fast mode sends single click immediately. Multi mode waits to detect double/triple/hold."
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Aqara H1 Double Rocker Remote installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "Aqara H1 Double Rocker Remote updated"

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)

    // Apply click mode setting
    if (defaultClickMode) {
        runIn(2, "applyClickMode")
    }
}

def initialize() {
    // 3 buttons: Left, Right, Both
    sendEvent(name: "numberOfButtons", value: 3)
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)

    state.lastButton = 0
    state.lastAction = ""

    runIn(5, configure)
}

// ==================== Configuration ====================

def configure() {
    log.info "Configuring Aqara H1 Double Rocker Remote..."

    def cmds = []

    // Bind power configuration for battery reporting
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure battery reporting (report every 6 hours or on 1% change)
    cmds += zigbee.configureReporting(CLUSTER_POWER_CONFIG, 0x0021, 0x20, 3600, 21600, 1)
    cmds += "delay 500"

    // Enable multi-click mode (mode: 1)
    // This tells the device to detect double/triple/hold instead of just single clicks
    def modeValue = (defaultClickMode == "fast") ? 0 : 1
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x0009, 0x20, modeValue, [mfgCode: LUMI_MFG_CODE])
    cmds += "delay 500"

    // Read battery
    cmds += zigbee.readAttribute(CLUSTER_POWER_CONFIG, 0x0021)

    return cmds
}

def refresh() {
    logDebug "Refresh"

    def cmds = []
    cmds += zigbee.readAttribute(CLUSTER_POWER_CONFIG, 0x0021)  // Battery percentage

    return cmds
}

// ==================== Click Mode ====================

def setClickMode(String mode) {
    logInfo "Setting click mode to: ${mode}"

    def modeValue = (mode == "fast") ? 0 : 1

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x0009, 0x20, modeValue, [mfgCode: LUMI_MFG_CODE])

    sendEvent(name: "clickMode", value: mode)
    sendZigbeeCommands(cmds)
}

def applyClickMode() {
    setClickMode(defaultClickMode ?: "multi")
}

// ==================== Button Commands ====================
// These are required by the PushableButton, HoldableButton, etc. capabilities
// They allow testing buttons from the device page and automation triggers

def push(buttonNumber) {
    Integer btn = buttonNumber as Integer
    btn = [[btn, 3].min(), 1].max()
    def buttonName = (btn == 1) ? "left" : (btn == 2) ? "right" : "both"
    logInfo "Button ${btn} (${buttonName}) pushed (virtual)"
    sendEvent(name: "pushed", value: btn, isStateChange: true,
              descriptionText: "Button ${btn} (${buttonName}) pushed (virtual)")
}

def hold(buttonNumber) {
    Integer btn = buttonNumber as Integer
    btn = [[btn, 3].min(), 1].max()
    def buttonName = (btn == 1) ? "left" : (btn == 2) ? "right" : "both"
    logInfo "Button ${btn} (${buttonName}) held (virtual)"
    sendEvent(name: "held", value: btn, isStateChange: true,
              descriptionText: "Button ${btn} (${buttonName}) held (virtual)")
}

def release(buttonNumber) {
    Integer btn = buttonNumber as Integer
    btn = [[btn, 3].min(), 1].max()
    def buttonName = (btn == 1) ? "left" : (btn == 2) ? "right" : "both"
    logInfo "Button ${btn} (${buttonName}) released (virtual)"
    sendEvent(name: "released", value: btn, isStateChange: true,
              descriptionText: "Button ${btn} (${buttonName}) released (virtual)")
}

def doubleTap(buttonNumber) {
    Integer btn = buttonNumber as Integer
    btn = [[btn, 3].min(), 1].max()
    def buttonName = (btn == 1) ? "left" : (btn == 2) ? "right" : "both"
    logInfo "Button ${btn} (${buttonName}) double-tapped (virtual)"
    sendEvent(name: "doubleTapped", value: btn, isStateChange: true,
              descriptionText: "Button ${btn} (${buttonName}) double-tapped (virtual)")
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
        else {
            logDebug "Unhandled: $description"
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

    switch(cluster) {
        case "0001":  // Power Configuration
            if (attrId == "0021") {  // Battery percentage
                Integer battery = Integer.parseInt(value, 16)
                // Aqara reports 0-200 (0.5% steps)
                battery = (battery / 2).toInteger()
                battery = [[battery, 100].min(), 0].max()
                events << createEvent(name: "battery", value: battery, unit: "%")
                logInfo "Battery: ${battery}%"
            }
            break

        case "0012":  // Multistate Input
            if (attrId == "0055") {  // Present Value
                events += handleMultistateButton(descMap)
            }
            break
    }

    return events
}

private List handleCatchall(Map descMap) {
    def events = []

    // Handle multistate input (button presses)
    if (descMap.clusterId == "0012") {
        events += handleMultistateButton(descMap)
    }
    // Handle toggle commands (some firmware versions send these)
    else if (descMap.clusterId == "0006") {
        if (descMap.command == "02") {  // Toggle
            // Determine button from endpoint
            def endpoint = descMap.sourceEndpoint ?: descMap.endpoint ?: "01"
            def button = getButtonFromEndpoint(endpoint)
            events += createButtonEvent(button, "pushed", 1)
        }
    }
    // Handle Lumi-specific cluster (FCC0)
    else if (descMap.clusterId == "FCC0" && descMap.data) {
        events += handleLumiCluster(descMap)
    }

    return events
}

private List handleMultistateButton(Map descMap) {
    def events = []

    // Get endpoint to determine which button
    def endpoint = descMap.sourceEndpoint ?: descMap.endpoint ?: descMap.data?.get(0) ?: "01"
    def button = getButtonFromEndpoint(endpoint)

    // Get action value
    def actionValue = 0
    if (descMap.value) {
        actionValue = Integer.parseInt(descMap.value, 16)
    } else if (descMap.data && descMap.data.size() >= 4) {
        // Parse from data array (multistate report format)
        // Format: attrId(2) + dataType(1) + value(1+)
        def valueIndex = 3
        if (descMap.data.size() > valueIndex) {
            actionValue = Integer.parseInt(descMap.data[valueIndex], 16)
        }
    }

    logDebug "Button event: endpoint=${endpoint}, button=${button}, actionValue=${actionValue}"

    // Determine action type
    def actionName = BUTTON_ACTIONS[actionValue] ?: "pushed"
    def pressCount = 1

    if (actionValue == 2) {
        pressCount = 2
    } else if (actionValue == 3) {
        pressCount = 3
        actionName = "pushed"  // Triple is reported as pushed with count=3
    } else if (actionValue == 0) {
        actionName = "held"
    } else if (actionValue == 255) {
        actionName = "released"
    }

    events += createButtonEvent(button, actionName, pressCount)

    return events
}

private List handleLumiCluster(Map descMap) {
    def events = []

    // Lumi cluster often contains battery info in attribute reports
    if (descMap.data) {
        // Try to parse battery from Lumi-specific data
        // Format varies, so we look for known patterns
        logDebug "Lumi cluster data: ${descMap.data}"
    }

    return events
}

private int getButtonFromEndpoint(String endpoint) {
    switch(endpoint?.toLowerCase()) {
        case "01":
            return 1  // Left button
        case "02":
            return 2  // Right button
        case "03":
            return 3  // Both buttons
        default:
            return 1
    }
}

private List createButtonEvent(int button, String action, int count = 1) {
    def events = []

    def buttonName = (button == 1) ? "left" : (button == 2) ? "right" : "both"
    def description = "Button ${button} (${buttonName}) ${action}"
    if (action == "pushed" && count > 1) {
        description += " (${count}x)"
    }

    logInfo description

    // Update state
    state.lastButton = button
    state.lastAction = action
    sendEvent(name: "lastButton", value: button)
    sendEvent(name: "lastAction", value: action)

    // Send appropriate event based on action type
    switch(action) {
        case "pushed":
            events << createEvent(name: "pushed", value: button, isStateChange: true,
                                  descriptionText: description, data: [count: count])
            break
        case "doubleTapped":
            events << createEvent(name: "doubleTapped", value: button, isStateChange: true,
                                  descriptionText: description)
            break
        case "held":
            events << createEvent(name: "held", value: button, isStateChange: true,
                                  descriptionText: description)
            break
        case "released":
            events << createEvent(name: "released", value: button, isStateChange: true,
                                  descriptionText: description)
            break
    }

    return events
}

// ==================== Helper Methods ====================

void sendZigbeeCommands(def cmds) {
    if (cmds == null) return

    List cmdList = (cmds instanceof List) ? cmds.flatten() : [cmds]
    cmdList = cmdList.findAll { it != null && it != "" && !it.toString().startsWith("delay") }

    if (cmdList.isEmpty()) return

    logDebug "Sending ${cmdList.size()} command(s)"
    cmdList.each { cmd ->
        def hubAction = new hubitat.device.HubAction(cmd.toString(), hubitat.device.Protocol.ZIGBEE)
        sendHubCommand(hubAction)
    }
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
