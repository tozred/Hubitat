/**
 *  Tuya 1-Gang Switch with Power Monitoring
 *  Model: TS0001 / _TZ3000_qlai3277
 *
 *  A driver for Tuya-based 1-gang switches with power monitoring.
 *
 *  Features:
 *  - On/Off control
 *  - Power monitoring (W)
 *  - Energy metering (kWh)
 *  - Voltage monitoring (V)
 *  - Current monitoring (A)
 *  - Power-on behavior (off/on/restore)
 *  - Countdown timer
 *  - Health check monitoring
 *
 *  Tested with:
 *  - Zemismart (_TZ3000_qlai3277)
 *  - Nous B2Z (_TZ3000_qlai3277)
 *
 *  Should also work with other TS0001 variants with power monitoring.
 *
 *  Version: 1.0.0
 *
 *  References:
 *  - https://www.zigbee2mqtt.io/devices/TS0001.html
 *  - https://nous.technology/product/b2z.html
 *  - https://github.com/Koenkk/zigbee-herdsman-converters
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.0.1"

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_ONOFF = 0x0006
@Field static final int CLUSTER_METERING = 0x0702
@Field static final int CLUSTER_ELECTRICAL = 0x0B04
@Field static final int CLUSTER_TUYA = 0xE001

// On/Off Cluster Attributes
@Field static final int ATTR_ONOFF = 0x0000
@Field static final int ATTR_ONOFF_START_UP = 0x8002  // Tuya-specific power-on behavior

// Electrical Measurement Attributes (0x0B04)
@Field static final int ATTR_RMS_VOLTAGE = 0x0505
@Field static final int ATTR_RMS_CURRENT = 0x0508
@Field static final int ATTR_ACTIVE_POWER = 0x050B
@Field static final int ATTR_AC_VOLTAGE_MULTIPLIER = 0x0600
@Field static final int ATTR_AC_VOLTAGE_DIVISOR = 0x0601
@Field static final int ATTR_AC_CURRENT_MULTIPLIER = 0x0602
@Field static final int ATTR_AC_CURRENT_DIVISOR = 0x0603
@Field static final int ATTR_AC_POWER_MULTIPLIER = 0x0604
@Field static final int ATTR_AC_POWER_DIVISOR = 0x0605

// Metering Attributes (0x0702)
@Field static final int ATTR_CURRENT_SUMMATION = 0x0000
@Field static final int ATTR_METERING_MULTIPLIER = 0x0301
@Field static final int ATTR_METERING_DIVISOR = 0x0302

// Power-on behavior values
@Field static final Map POWER_ON_BEHAVIOR = [
    0: "off",
    1: "on",
    2: "restore"
]
@Field static final Map POWER_ON_BEHAVIOR_REVERSE = [
    "off": 0,
    "on": 1,
    "restore": 2
]

// ==================== Metadata ====================

metadata {
    definition (name: "Tuya 1-Gang Switch with Power Monitoring", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "Refresh"
        capability "Configuration"
        capability "HealthCheck"

        // Custom attributes
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "lastActivity", "string"
        attribute "powerOnBehavior", "enum", ["off", "on", "restore"]
        attribute "driverVersion", "string"

        // Commands
        command "logsOff"   // switch debug logging off now (also runs automatically 30 min after Save)
        command "setPowerOnBehavior", [[name: "behavior*", type: "ENUM", constraints: ["off", "on", "restore"],
                                        description: "off=always off, on=always on, restore=previous state"]]
        command "countdown", [[name: "seconds*", type: "NUMBER", description: "Auto-off timer (0-43200 seconds, 0=disable)"]]

        // Fingerprints - Zemismart / Nous B2Z
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0702,0B04,E000,E001",
                    outClusters: "000A,0019",
                    manufacturer: "_TZ3000_qlai3277", model: "TS0001",
                    deviceJoinName: "Zemismart 1-Gang Switch"

        // Generic TS0001 with power monitoring
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0702,0B04",
                    manufacturer: "_TZ3000_qlai3277", model: "TS0001",
                    deviceJoinName: "Zemismart 1-Gang Switch"

        // Fallback fingerprint
        fingerprint manufacturer: "_TZ3000_qlai3277", model: "TS0001",
                    deviceJoinName: "Zemismart 1-Gang Switch"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "pollInterval", type: "enum", title: "Power polling interval",
              options: [
                  ["0": "Disabled (rely on reports)"],
                  ["30": "Every 30 seconds"],
                  ["60": "Every 1 minute"],
                  ["300": "Every 5 minutes"],
                  ["600": "Every 10 minutes"]
              ],
              defaultValue: "60"

        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: [
                  ["0": "Disabled"],
                  ["60": "Every 1 hour"],
                  ["120": "Every 2 hours"],
                  ["240": "Every 4 hours"]
              ],
              defaultValue: "60"

        input name: "powerDivisor", type: "number", title: "Power divisor",
              description: "Divisor for power calculation (default: 10)",
              defaultValue: 10

        input name: "voltageDivisor", type: "number", title: "Voltage divisor",
              description: "Divisor for voltage calculation (default: 10)",
              defaultValue: 10

        input name: "currentDivisor", type: "number", title: "Current divisor",
              description: "Divisor for current calculation (default: 1000)",
              defaultValue: 1000

        input name: "energyDivisor", type: "number", title: "Energy divisor",
              description: "Divisor for energy calculation (default: 100)",
              defaultValue: 100
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Tuya 1-Gang Switch installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "Tuya 1-Gang Switch updated"
    unschedule()

    if (logEnable) runIn(1800, logsOff)

    // Store divisors in state
    state.powerDivisor = powerDivisor ?: 10
    state.voltageDivisor = voltageDivisor ?: 10
    state.currentDivisor = currentDivisor ?: 1000
    state.energyDivisor = energyDivisor ?: 100

    schedulePoll()
    scheduleHealthCheck()

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
}

def initialize() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    sendEvent(name: "healthStatus", value: "online")

    state.lastSuccessfulComm = now()
    state.powerDivisor = powerDivisor ?: 10
    state.voltageDivisor = voltageDivisor ?: 10
    state.currentDivisor = currentDivisor ?: 1000
    state.energyDivisor = energyDivisor ?: 100

    schedulePoll()
    scheduleHealthCheck()

    runIn(5, configure)
}

// ==================== Configuration ====================

def configure() {
    log.info "Configuring Tuya 1-Gang Switch..."

    def cmds = []

    // Bind On/Off cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Bind Electrical Measurement cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0B04 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Bind Metering cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0702 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure On/Off reporting
    cmds += zigbee.configureReporting(CLUSTER_ONOFF, ATTR_ONOFF, 0x10, 0, 3600, null)
    cmds += "delay 300"

    // Try to configure power reporting (may not work on all firmware versions)
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_ACTIVE_POWER, 0x29, 5, 300, 1)
    cmds += "delay 300"
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_RMS_VOLTAGE, 0x21, 5, 300, 1)
    cmds += "delay 300"
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_RMS_CURRENT, 0x21, 5, 300, 1)
    cmds += "delay 300"

    // Configure energy reporting
    cmds += zigbee.configureReporting(CLUSTER_METERING, ATTR_CURRENT_SUMMATION, 0x25, 5, 300, 1)
    cmds += "delay 500"

    // Read multipliers/divisors
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_AC_POWER_DIVISOR)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_AC_VOLTAGE_DIVISOR)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_AC_CURRENT_DIVISOR)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(CLUSTER_METERING, ATTR_METERING_DIVISOR)
    cmds += "delay 500"

    // Initial refresh
    cmds += refresh()

    return cmds
}

// ==================== Scheduling ====================

private void schedulePoll() {
    def pollSecs = (pollInterval ?: "60") as int

    if (pollSecs > 0) {
        switch(pollSecs) {
            case 30:
                schedule("0/30 * * * * ?", pollPower)
                break
            case 60:
                runEvery1Minute(pollPower)
                break
            case 300:
                runEvery5Minutes(pollPower)
                break
            case 600:
                runEvery10Minutes(pollPower)
                break
        }
        logInfo "Polling scheduled every ${pollSecs} seconds"
    }
}

private void scheduleHealthCheck() {
    def interval = (healthCheckInterval ?: "60") as int
    if (interval > 0) {
        switch(interval) {
            case 60: runEvery1Hour(healthCheck); break
            case 120: runEvery3Hours(healthCheck); break
            case 240: runEvery3Hours(healthCheck); break
        }
        logDebug "Health check scheduled every ${interval} minutes"
    }
}

def healthCheck() {
    def lastActivity = state.lastSuccessfulComm ?: 0
    def healthInterval = ((healthCheckInterval ?: "60") as int) * 60 * 1000
    def threshold = healthInterval * 2

    if (now() - lastActivity > threshold) {
        if (device.currentValue("healthStatus") != "offline") {
            sendEvent(name: "healthStatus", value: "offline")
            logWarn "Device appears offline - no communication for ${(now() - lastActivity) / 60000} minutes"
        }
        refresh()
    } else {
        if (device.currentValue("healthStatus") != "online") {
            sendEvent(name: "healthStatus", value: "online")
        }
    }
}

// ==================== Polling ====================

def pollPower() {
    logDebug "Polling power..."
    def cmds = []
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_ACTIVE_POWER)
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_RMS_VOLTAGE)
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_RMS_CURRENT)
    cmds += zigbee.readAttribute(CLUSTER_METERING, ATTR_CURRENT_SUMMATION)
    sendZigbeeCommands(cmds)
}

def refresh() {
    logDebug "Refresh"

    def cmds = []

    // On/Off state
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, ATTR_ONOFF)
    cmds += "delay 100"

    // Power
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_ACTIVE_POWER)
    cmds += "delay 100"

    // Voltage
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_RMS_VOLTAGE)
    cmds += "delay 100"

    // Current
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_RMS_CURRENT)
    cmds += "delay 100"

    // Energy
    cmds += zigbee.readAttribute(CLUSTER_METERING, ATTR_CURRENT_SUMMATION)
    cmds += "delay 100"

    // Power-on behavior
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, ATTR_ONOFF_START_UP)

    return cmds
}

def ping() {
    logDebug "Ping"
    return refresh()
}

// ==================== Switch Commands ====================

def on() {
    logInfo "Turning on"
    return zigbee.on()
}

def off() {
    logInfo "Turning off"
    return zigbee.off()
}

// ==================== Custom Commands ====================

def setPowerOnBehavior(String behavior) {
    logInfo "Setting power-on behavior to: ${behavior}"

    def value = POWER_ON_BEHAVIOR_REVERSE[behavior]
    if (value == null) {
        logWarn "Invalid power-on behavior: ${behavior}"
        return
    }

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_ONOFF, ATTR_ONOFF_START_UP, 0x30, value)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, ATTR_ONOFF_START_UP)

    sendEvent(name: "powerOnBehavior", value: behavior)
    sendZigbeeCommands(cmds)
}

def countdown(BigDecimal seconds) {
    seconds = Math.max(0, Math.min(43200, seconds)) as int
    logInfo "Setting countdown timer: ${seconds} seconds"

    if (seconds == 0) {
        logInfo "Countdown disabled"
    } else {
        logInfo "Switch will turn off in ${seconds} seconds"
    }

    // Tuya countdown uses attribute 0xF000 on OnOff cluster
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_ONOFF, 0xF000, 0x23, seconds)  // Uint32

    sendZigbeeCommands(cmds)
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parse: $description"

    // Update activity timestamp
    updateLastActivity()

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

    if (!value || value == "null") return events

    logDebug "Handling: cluster=${cluster}, attr=${attrId}, value=${value}"

    switch(cluster) {
        case "0006":  // On/Off
            events += handleOnOffCluster(attrId, value)
            break

        case "0702":  // Metering (Energy)
            events += handleMeteringCluster(attrId, value)
            break

        case "0B04":  // Electrical Measurement
            events += handleElectricalCluster(attrId, value)
            break
    }

    return events
}

private List handleOnOffCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // On/Off state
            def switchState = value == "01" ? "on" : "off"
            events << createEvent(name: "switch", value: switchState)
            logInfo "Switch: ${switchState}"
            break

        case "8002":  // Power-on behavior (Tuya-specific)
            def behaviorValue = Integer.parseInt(value, 16)
            def behavior = POWER_ON_BEHAVIOR[behaviorValue] ?: "unknown"
            events << createEvent(name: "powerOnBehavior", value: behavior)
            logInfo "Power-on behavior: ${behavior}"
            break
    }

    return events
}

private List handleMeteringCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // Current Summation Delivered (Energy)
            Long rawValue = Long.parseLong(value, 16)
            BigDecimal divisor = state.energyDivisor ?: (energyDivisor ?: 100)
            BigDecimal energy = rawValue / divisor

            // Format appropriately - 3 decimal places
            BigDecimal energyFormatted = fixedScale(energy, 3)
            events << createEvent(name: "energy", value: energyFormatted, unit: "kWh")
            logInfo "Energy: ${energyFormatted} kWh"
            break

        case "0302":  // Divisor
            state.energyDivisor = Integer.parseInt(value, 16)
            logDebug "Energy divisor: ${state.energyDivisor}"
            break
    }

    return events
}

private List handleElectricalCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "050B":  // Active Power
            Integer rawPower = Integer.parseInt(value, 16)
            BigDecimal divisor = state.powerDivisor ?: (powerDivisor ?: 10)
            BigDecimal power = rawPower / divisor
            BigDecimal powerFormatted = fixedScale(power, 1)

            events << createEvent(name: "power", value: powerFormatted, unit: "W")
            logInfo "Power: ${powerFormatted} W"
            break

        case "0505":  // RMS Voltage
            Integer rawVoltage = Integer.parseInt(value, 16)
            BigDecimal vDivisor = state.voltageDivisor ?: (voltageDivisor ?: 10)
            BigDecimal voltage = rawVoltage / vDivisor
            BigDecimal voltageFormatted = fixedScale(voltage, 1)

            events << createEvent(name: "voltage", value: voltageFormatted, unit: "V")
            logInfo "Voltage: ${voltageFormatted} V"
            break

        case "0508":  // RMS Current
            Integer rawCurrent = Integer.parseInt(value, 16)
            BigDecimal cDivisor = state.currentDivisor ?: (currentDivisor ?: 1000)
            BigDecimal current = rawCurrent / cDivisor
            BigDecimal currentFormatted = fixedScale(current, 3)

            events << createEvent(name: "amperage", value: currentFormatted, unit: "A")
            logInfo "Current: ${currentFormatted} A"
            break

        case "0601":  // Voltage Divisor
            state.voltageDivisor = Integer.parseInt(value, 16)
            logDebug "Voltage divisor: ${state.voltageDivisor}"
            break

        case "0603":  // Current Divisor
            state.currentDivisor = Integer.parseInt(value, 16)
            logDebug "Current divisor: ${state.currentDivisor}"
            break

        case "0605":  // Power Divisor
            state.powerDivisor = Integer.parseInt(value, 16)
            logDebug "Power divisor: ${state.powerDivisor}"
            break
    }

    return events
}

private List handleCatchall(Map descMap) {
    def events = []

    // Handle on/off commands
    if (descMap.clusterId == "0006") {
        if (descMap.command == "0B" && descMap.data?.size() > 0) {
            // Default response
            def cmd = descMap.data[0]
            if (cmd == "01") {
                events << createEvent(name: "switch", value: "on")
                logInfo "Switch turned on"
            } else if (cmd == "00") {
                events << createEvent(name: "switch", value: "off")
                logInfo "Switch turned off"
            }
        }
    }

    return events
}

// ==================== Helper Methods ====================

private void updateLastActivity() {
    def now = new Date()
    sendEvent(name: "lastActivity", value: now.format("yyyy-MM-dd HH:mm:ss"))
    state.lastSuccessfulComm = now.getTime()

    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
    }
}

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

// Fixed-scale rounding; avoids BigDecimal results such as 0E+1 when a reading is exactly zero
private BigDecimal fixedScale(BigDecimal value, int places) {
    return value.setScale(places, java.math.RoundingMode.HALF_UP)
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
