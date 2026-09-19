/**
 *  Sonoff SNZB-04P Contact Sensor with Tamper
 *
 *  Model: SNZB-04P
 *
 *  A driver for the Sonoff SNZB-04P door/window contact sensor with tamper detection.
 *
 *  Version: 1.0.1 - Fixed tamper not auto-clearing when device sends clear signal
 *
 *  Clusters:
 *    0x0000 - Basic
 *    0x0001 - Power Configuration (battery)
 *    0x0003 - Identify
 *    0x0020 - Poll Control
 *    0x0500 - IAS Zone (contact sensor)
 *    0xFC11 - Sonoff Custom (tamper sensor)
 *    0xFC57 - Unknown Sonoff cluster
 *
 *  Tamper Detection:
 *    Cluster: 0xFC11 (Sonoff Custom)
 *    Attribute: 0x2000
 *    Type: Uint8 (0x20)
 *    Values: 0x00 = not tampered, 0x01 = tampered
 *
 *  Author: Ben Fayershtein
 */

import groovy.transform.Field

metadata {
    definition (name: "Sonoff SNZB-04P Contact Sensor", namespace: "benberlin", author: "Ben Fayershtein") {
        capability "Configuration"
        capability "Refresh"
        capability "ContactSensor"
        capability "TamperAlert"
        capability "Battery"
        capability "Sensor"

        // Additional attributes
        attribute "batteryVoltage", "number"
        attribute "lastTamperTime", "string"

        // Commands
        command "clearTamper"

        // Fingerprint
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0020,0500,FC11,FC57",
                    outClusters: "0003,0006,0019",
                    model: "SNZB-04P",
                    manufacturer: "eWeLink",
                    deviceJoinName: "Sonoff SNZB-04P Contact Sensor"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
        input name: "tamperAutoReset", type: "enum", title: "Auto-reset tamper after",
              options: [
                  ["0": "Never (manual reset only)"],
                  ["60": "1 minute"],
                  ["300": "5 minutes"],
                  ["3600": "1 hour"]
              ], defaultValue: "0"
        input name: "tamperSticky", type: "bool", title: "Sticky tamper (ignore device clear signal)", defaultValue: true,
              description: "When enabled, tamper stays 'detected' until manually cleared or auto-reset timer expires"
    }
}

// ==================== Constants ====================

@Field static final int SONOFF_MFG_CODE = 0x1286  // eWeLink/Coolkit

// IAS Zone status bits
@Field static final int ZONE_STATUS_ALARM1 = 0x01      // Contact open
@Field static final int ZONE_STATUS_ALARM2 = 0x02
@Field static final int ZONE_STATUS_TAMPER = 0x04      // Tamper detected
@Field static final int ZONE_STATUS_BATTERY_LOW = 0x08
@Field static final int ZONE_STATUS_SUPERVISION = 0x10
@Field static final int ZONE_STATUS_RESTORE = 0x20
@Field static final int ZONE_STATUS_TROUBLE = 0x40
@Field static final int ZONE_STATUS_AC_MAINS = 0x80

// ==================== Lifecycle ====================

def installed() {
    log.info "Sonoff SNZB-04P Contact Sensor installed"
    initialize()
}

def updated() {
    log.info "Settings updated"
    if (logEnable) runIn(86400, "logsOff")   // debug logging switches itself off after 24 h
    initialize()
}

def initialize() {
    sendEvent(name: "contact", value: "closed", descriptionText: "Initialized")
    sendEvent(name: "tamper", value: "clear", descriptionText: "Initialized")
}

def configure() {
    logInfo "Configuring SNZB-04P..."

    def cmds = []

    // Read device info
    cmds += zigbee.readAttribute(0x0000, 0x0004)  // Manufacturer
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0000, 0x0005)  // Model
    cmds += "delay 300"

    // Configure IAS Zone for contact sensor
    // Write IAS CIE address (required for IAS Zone devices)
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0500 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure battery reporting (cluster 0x0001)
    // Attribute 0x0021 = battery percentage, report every 1-6 hours or on 1% change
    cmds += zigbee.configureReporting(0x0001, 0x0021, 0x20, 3600, 21600, 1)
    cmds += "delay 300"

    // Bind Sonoff custom cluster for tamper
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0xFC11 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Read current state
    cmds += refresh()

    return cmds
}

def refresh() {
    logInfo "Refreshing SNZB-04P..."

    def cmds = []

    // Read battery
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // Battery voltage
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0001, 0x0021)  // Battery percentage
    cmds += "delay 200"

    // Read IAS Zone status
    cmds += zigbee.readAttribute(0x0500, 0x0002)  // Zone status
    cmds += "delay 200"

    // Read tamper from Sonoff custom cluster
    cmds += zigbee.readAttribute(0xFC11, 0x2000, [mfgCode: SONOFF_MFG_CODE])
    cmds += "delay 200"

    return cmds
}

// ==================== Commands ====================

def clearTamper() {
    logInfo "Clearing tamper alert"
    sendEvent(name: "tamper", value: "clear", descriptionText: "Tamper cleared manually")
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parsing: ${description?.take(100)}..."

    try {
        // Check for IAS Zone status change notification
        if (description.startsWith("zone status")) {
            parseIasZoneStatus(description)
            return []
        }

        def descMap = zigbee.parseDescriptionAsMap(description)

        if (description.startsWith("read attr -")) {
            parseReadAttr(descMap)
        } else if (description.startsWith("catchall:")) {
            parseCatchall(descMap)
        }
    } catch (e) {
        log.error "Parse error: ${e.message}"
    }

    return []
}

private void parseIasZoneStatus(String description) {
    // Format: zone status 0x0001 -- extended status 0x00
    def matcher = description =~ /zone status (0x[0-9A-Fa-f]+)/
    if (matcher.find()) {
        def zoneStatus = Integer.parseInt(matcher.group(1).replace("0x", ""), 16)
        processZoneStatus(zoneStatus)
    }
}

private void processZoneStatus(int zoneStatus) {
    logDebug "IAS Zone status: 0x${String.format('%04X', zoneStatus)}"

    // Contact (Alarm 1 bit)
    def isOpen = (zoneStatus & ZONE_STATUS_ALARM1) != 0
    def contactState = isOpen ? "open" : "closed"
    logInfo "Contact: ${contactState}"
    sendEvent(name: "contact", value: contactState, descriptionText: "Contact is ${contactState}")

    // Tamper bit from IAS Zone
    def isTampered = (zoneStatus & ZONE_STATUS_TAMPER) != 0
    if (isTampered) {
        handleTamperDetected("IAS Zone")
    }

    // Battery low bit
    def batteryLow = (zoneStatus & ZONE_STATUS_BATTERY_LOW) != 0
    if (batteryLow) {
        logInfo "Battery low warning from IAS Zone"
    }
}

private void parseReadAttr(Map descMap) {
    def cluster = descMap.cluster?.toUpperCase()
    def attrId = descMap.attrId?.toUpperCase()
    def value = descMap.value

    logDebug "Attribute: cluster=0x${cluster}, attr=0x${attrId}, value=${value}"

    switch (cluster) {
        case "0000":  // Basic
            parseBasicCluster(attrId, value, descMap)
            break
        case "0001":  // Power Configuration
            parsePowerCluster(attrId, value)
            break
        case "0500":  // IAS Zone
            parseIasZoneCluster(attrId, value)
            break
        case "FC11":  // Sonoff Custom (tamper)
            parseSonoffCluster(attrId, value)
            break
    }
}

private void parseCatchall(Map descMap) {
    def clusterId = (descMap.clusterId ?: descMap.cluster)?.toUpperCase()
    def command = descMap.command
    def data = descMap.data

    logDebug "Catchall: cluster=0x${clusterId}, cmd=${command}, data=${data}"

    // Handle IAS Zone status change (command 0x00 = zone status change)
    if (clusterId == "0500" && command == "00" && data?.size() >= 2) {
        def zoneStatus = Integer.parseInt(data[0], 16) + (Integer.parseInt(data[1], 16) << 8)
        processZoneStatus(zoneStatus)
    }

    // Handle Sonoff custom cluster reports
    if (clusterId == "FC11" && data?.size() >= 3) {
        // Attribute report format: [attrLow, attrHigh, type, value...]
        def attrId = "${data[1]}${data[0]}".toUpperCase()
        if (attrId == "2000" && data.size() >= 4) {
            def tamperValue = data[3]
            handleTamperValue(tamperValue)
        }
    }
}

// ==================== Cluster Parsers ====================

private void parseBasicCluster(String attrId, String value, Map descMap) {
    def encoding = descMap.encoding

    switch (attrId) {
        case "0004":  // Manufacturer
            def mfg = (encoding == "42") ? value : decodeString(value)
            if (mfg) {
                logInfo "Manufacturer: ${mfg}"
                updateDataValue("manufacturer", mfg)
            }
            break
        case "0005":  // Model
            def model = (encoding == "42") ? value : decodeString(value)
            if (model) {
                logInfo "Model: ${model}"
                updateDataValue("model", model)
            }
            break
    }
}

private void parsePowerCluster(String attrId, String value) {
    switch (attrId) {
        case "0020":  // Battery voltage (in 100mV units)
            def voltage = Integer.parseInt(value, 16)
            def volts = voltage / 10.0
            logInfo "Battery voltage: ${volts}V"
            sendEvent(name: "batteryVoltage", value: volts, unit: "V")
            break
        case "0021":  // Battery percentage
            def percent = Integer.parseInt(value, 16)
            // Some devices report 0-200, others 0-100
            if (percent > 100) percent = percent / 2
            logInfo "Battery: ${percent}%"
            sendEvent(name: "battery", value: percent, unit: "%", descriptionText: "Battery is ${percent}%")
            break
    }
}

private void parseIasZoneCluster(String attrId, String value) {
    switch (attrId) {
        case "0002":  // Zone status
            def zoneStatus = Integer.parseInt(value, 16)
            processZoneStatus(zoneStatus)
            break
        case "0000":  // Zone state (enrolled or not)
            logDebug "IAS Zone state: ${value}"
            break
        case "0001":  // Zone type
            logDebug "IAS Zone type: ${value}"
            break
    }
}

private void parseSonoffCluster(String attrId, String value) {
    logDebug "Sonoff FC11 attr 0x${attrId} = ${value}"

    switch (attrId) {
        case "2000":  // Tamper
            handleTamperValue(value)
            break
        default:
            logDebug "Unknown Sonoff attribute 0x${attrId} = ${value}"
    }
}

// ==================== Tamper Handling ====================

private void handleTamperValue(String value) {
    def tampered = (value != "00" && value != "0")
    if (tampered) {
        handleTamperDetected("FC11 cluster")
    } else {
        // Only clear if sticky mode is disabled
        if (settings?.tamperSticky == false) {
            logInfo "Tamper: clear (from device)"
            sendEvent(name: "tamper", value: "clear", descriptionText: "Tamper cleared by device")
        } else {
            logDebug "Tamper clear signal from device ignored (sticky mode enabled)"
        }
    }
}

private void handleTamperDetected(String source) {
    logInfo "Tamper DETECTED (from ${source})"
    sendEvent(name: "tamper", value: "detected", descriptionText: "Tamper detected")
    sendEvent(name: "lastTamperTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Cancel any pending auto-reset and reschedule if configured
    unschedule("clearTamper")
    def autoReset = settings?.tamperAutoReset?.toInteger() ?: 0
    if (autoReset > 0) {
        logDebug "Scheduling tamper auto-reset in ${autoReset} seconds"
        runIn(autoReset, "clearTamper")
    }
}

// ==================== Utility Functions ====================

private String decodeString(String hex) {
    if (!hex) return ""
    if (hex =~ /[g-zG-Z.]/) return hex  // Already decoded
    def result = ""
    try {
        for (int i = 0; i < hex.length(); i += 2) {
            def charCode = Integer.parseInt(hex.substring(i, Math.min(i + 2, hex.length())), 16)
            if (charCode >= 32 && charCode < 127) result += (char)charCode
        }
    } catch (e) {
        return hex
    }
    return result
}

private void logDebug(String msg) {
    if (settings?.logEnable) log.debug msg
}

private void logInfo(String msg) {
    if (settings?.txtEnable) log.info msg
}

def logsOff() {
    log.info "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
