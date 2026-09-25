/**
 *  Sonoff SNZB-04P Contact Sensor with Tamper
 *
 *  Model: SNZB-04P
 *
 *  A driver for the Sonoff SNZB-04P door/window contact sensor with tamper detection.
 *
 *  Version: 1.1.0 - Report at least every 2 h and answer Poll Control check-ins so a quiet
 *                  sensor is not aged out of the mesh; answer IAS enroll requests; fix battery
 *                  percentage (always half-percent units)
 *  Version: 1.0.2 - Settings saves no longer overwrite the real contact state
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

// Bump when configure() changes. Sensors still on an older setup get it re-sent the next
// time they wake and report, because they are asleep (and miss it) whenever it is sent
// from the device page.
@Field static final int REPORTING_VERSION = 2

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
    // Seed values for a brand-new device only. This also runs on every settings save, and
    // forcing "closed" there overwrote the real window state: an open window was reported
    // shut, and heating zones watching the sensor resumed heating.
    if (device.currentValue("contact") == null) {
        sendEvent(name: "contact", value: "closed", descriptionText: "Initialized")
    }
    if (device.currentValue("tamper") == null) {
        sendEvent(name: "tamper", value: "clear", descriptionText: "Initialized")
    }
}

def configure() {
    logInfo "Configuring SNZB-04P..."

    def cmds = []

    // Read device info
    cmds += zigbee.readAttribute(0x0000, 0x0004)  // Manufacturer
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0000, 0x0005)  // Model
    cmds += "delay 300"

    // IAS Zone: bind, and enroll the sensor with the hub. The comment here used to promise
    // enrollment but only the bind was sent.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0500 {${device.zigbeeId}} {}"
    cmds += "delay 500"
    cmds += zigbee.enrollResponse()
    cmds += "delay 500"

    // Battery: report at least every 2 hours. A contact sensor on a quiet door otherwise
    // says nothing for hours, and its parent router can age it out of the mesh, which is
    // the "has to be re-paired" failure. Same intervals as zigbee2mqtt's ewelinkBattery(),
    // whose source notes "3600/7200 prevents disconnect". This used to allow 6 hours.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
    cmds += "delay 300"
    cmds += zigbee.configureReporting(0x0001, 0x0021, 0x20, 3600, 7200, 2)   // percentage, 0.5% units
    cmds += "delay 300"
    cmds += zigbee.configureReporting(0x0001, 0x0020, 0x20, 3600, 7200, 1)   // voltage, 100 mV units
    cmds += "delay 300"

    // Poll Control: bind it so the sensor sends its hourly check-in to the hub, as
    // zigbee-herdsman and zigpy do. Check-ins are answered in parseCatchall.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0020 {${device.zigbeeId}} {}"
    cmds += "delay 300"

    // Bind Sonoff custom cluster for tamper
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0xFC11 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Read current state
    cmds += refresh()

    return cmds
}

/** The sensor just transmitted, so it is awake for a moment: the only reliable time to reach it. */
private void ensureConfigured() {
    if ((state.reportingVersion ?: 0) >= REPORTING_VERSION) return
    if (now() - (state.lastConfigAttempt ?: 0) < 10 * 60 * 1000) return
    state.lastConfigAttempt = now()
    logInfo "Sensor is awake and on an older setup, sending configuration"
    sendHubCommand(new hubitat.device.HubMultiAction(configure(), hubitat.device.Protocol.ZIGBEE))
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
    ensureConfigured()

    try {
        // Check for IAS Zone status change notification
        if (description.startsWith("zone status")) {
            parseIasZoneStatus(description)
            return []
        }

        // An IAS device that has just (re)joined asks to be enrolled and, until it is, may not
        // send its open/close notifications at all. This used to fall through unanswered.
        if (description.startsWith("enroll request")) {
            logInfo "Enroll request received, answering"
            return zigbee.enrollResponse()
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

    // Configure Reporting response for the battery cluster: status 00 means it was accepted
    if (clusterId == "0001" && command == "07" && data && data[0] == "00") {
        if ((state.reportingVersion ?: 0) < REPORTING_VERSION) logInfo "Battery reporting accepted"
        state.reportingVersion = REPORTING_VERSION
        return
    }

    // Poll Control check-in: answer "no fast polling" so the sensor goes straight back to
    // sleep instead of waiting out its fast-poll timeout on battery.
    if (clusterId == "0020" && command == "00" && descMap.isClusterSpecific) {
        logDebug "Poll Control check-in"
        sendHubCommand(new hubitat.device.HubMultiAction(
            zigbee.command(0x0020, 0x00, "00", "0000"), hubitat.device.Protocol.ZIGBEE))
        return
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
            // ZCL reports this in half-percent units (200 = 100%). Halving only values above
            // 100 made a battery at 50% read as a full 100% and anything lower read double.
            int percent = Math.max(0, Math.min(100, Math.round(Integer.parseInt(value, 16) / 2.0) as int))
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
