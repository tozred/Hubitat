/**
 *  Aqara H1 EU Single Switch
 *  Model: lumi.switch.l1aeu1 / WS-EUK01
 *
 *  Simple on/off/toggle driver based on Generic Zigbee Switch
 */

metadata {
    definition (name: "Aqara H1 EU Single Switch", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "HealthCheck"

        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "lastActivity", "string"

        command "toggle"

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0002,0003,0004,0005,0006,0009", outClusters: "000A,0019", manufacturer: "LUMI", model: "lumi.switch.l1aeu1"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: [["0": "Disabled"], ["1": "Every 1 hour"], ["2": "Every 2 hours"], ["3": "Every 3 hours"]],
              defaultValue: "1"
        input name: "refreshAfterCommand", type: "bool", title: "Refresh state after on/off command",
              description: "Adds a state verification after sending commands", defaultValue: true
        input name: "offlineThreshold", type: "enum", title: "Mark offline after no activity for",
              options: [["3600000": "1 hour"], ["7200000": "2 hours"], ["10800000": "3 hours"], ["21600000": "6 hours"]],
              defaultValue: "7200000"
    }
}

def installed() {
    logInfo "Installed"
    initialize()
}

def updated() {
    logInfo "Updated"
    initialize()
}

def initialize() {
    unschedule()

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h

    // Schedule health checks
    def interval = healthCheckInterval ?: "1"
    if (interval != "0") {
        switch(interval) {
            case "1":
                runEvery1Hour(healthCheck)
                break
            case "2":
                schedule("0 0 */2 * * ?", healthCheck)
                break
            case "3":
                runEvery3Hours(healthCheck)
                break
        }
        logInfo "Health check scheduled every ${interval} hour(s)"
    }

    // Schedule periodic refresh to keep state in sync
    runEvery1Hour(refresh)

    configure()
}

def configure() {
    logInfo "Configuring..."

    def cmds = []

    // Bind to On/Off cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure reporting: report on/off state changes immediately (min 0 sec), and at least every hour (3600 sec)
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 3600, null)
    cmds += "delay 500"

    // Read current state
    cmds += zigbee.readAttribute(0x0006, 0x0000)

    return cmds
}

def refresh() {
    logDebug "Refresh"
    return zigbee.readAttribute(0x0006, 0x0000)
}

def ping() {
    logDebug "Ping"
    return refresh()
}

def healthCheck() {
    logDebug "Health check"

    def threshold = (offlineThreshold ?: "7200000") as Long
    def lastAct = device.currentValue("lastActivity")

    if (lastAct) {
        try {
            def lastActTime = Date.parse("yyyy-MM-dd HH:mm:ss", lastAct).getTime()
            def now = now()

            if ((now - lastActTime) > threshold) {
                if (device.currentValue("healthStatus") != "offline") {
                    sendEvent(name: "healthStatus", value: "offline", descriptionText: "Device is offline")
                    logWarn "Device marked offline - no activity for over ${threshold/3600000} hour(s)"
                }
                // Try to wake it up
                runIn(2, refresh)
            }
        } catch (e) {
            logDebug "Could not parse lastActivity: $e"
        }
    }

    // Send a refresh to verify device is responsive
    return refresh()
}

def on() {
    logDebug "Turning on"
    def cmds = zigbee.on()

    if (refreshAfterCommand) {
        cmds += "delay 500"
        cmds += zigbee.readAttribute(0x0006, 0x0000)
    }

    return cmds
}

def off() {
    logDebug "Turning off"
    def cmds = zigbee.off()

    if (refreshAfterCommand) {
        cmds += "delay 500"
        cmds += zigbee.readAttribute(0x0006, 0x0000)
    }

    return cmds
}

def toggle() {
    logDebug "Toggle"
    if (device.currentValue("switch") == "on") {
        off()
    } else {
        on()
    }
}

def parse(String description) {
    logDebug "Parse: $description"

    // Update activity timestamp
    updateLastActivity()

    def event = null
    def events = []

    // Handle "read attr" format
    if (description.startsWith("read attr -")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        logDebug "Parsed map: $descMap"

        if (descMap.cluster == "0006" && descMap.attrId == "0000") {
            def value = descMap.value == "01" ? "on" : "off"
            events << createEvent(name: "switch", value: value)
            logInfo "Switch is $value"
        }
    }
    // Handle catchall format
    else if (description.startsWith("catchall:")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        logDebug "Catchall map: $descMap"

        // On/Off cluster
        if (descMap.clusterId == "0006" || descMap.clusterInt == 6) {

            // Command 0B = default response (data[0] = command executed)
            if (descMap.command == "0B" && descMap.data?.size() > 0) {
                def cmd = descMap.data[0]
                if (cmd == "01") {
                    events << createEvent(name: "switch", value: "on")
                    logInfo "Switch turned on"
                } else if (cmd == "00") {
                    events << createEvent(name: "switch", value: "off")
                    logInfo "Switch turned off"
                }
            }
            // Command 01 = On
            else if (descMap.command == "01") {
                events << createEvent(name: "switch", value: "on")
                logInfo "Switch turned on"
            }
            // Command 00 = Off
            else if (descMap.command == "00") {
                events << createEvent(name: "switch", value: "off")
                logInfo "Switch turned off"
            }
            // Command 0A = Report attributes
            else if (descMap.command == "0A" && descMap.data?.size() >= 4) {
                def value = descMap.data[3] == "01" ? "on" : "off"
                events << createEvent(name: "switch", value: value)
                logInfo "Switch is $value (reported)"
            }
            // Command 07 = Configure reporting response
            else if (descMap.command == "07") {
                logDebug "Configure reporting response received"
            }
        }
    }

    // Mark device as online if we received any valid message
    if (device.currentValue("healthStatus") != "online") {
        events << createEvent(name: "healthStatus", value: "online", descriptionText: "Device is online")
        logInfo "Device is online"
    }

    return events
}

private void updateLastActivity() {
    def now = new Date().format("yyyy-MM-dd HH:mm:ss")
    sendEvent(name: "lastActivity", value: now)
}

// Logging helpers
private void logDebug(String msg) {
    if (logEnable) log.debug msg
}

private void logInfo(String msg) {
    if (txtEnable) log.info msg
}

private void logWarn(String msg) {
    log.warn msg
}

def logsOff() {
    logWarn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
