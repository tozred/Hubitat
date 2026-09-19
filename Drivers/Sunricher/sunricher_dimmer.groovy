/**
 *  Sunricher Zigbee Dimmer with Power Monitoring
 *  Model: HK-SL-DIM-EU-A (ZG2835RAC)
 *
 *  A driver for Sunricher Zigbee dimmers with power monitoring.
 *
 *  Features:
 *  - On/Off control
 *  - Dimming (0-100%)
 *  - Power monitoring (W)
 *  - Energy metering (kWh)
 *  - Voltage monitoring (V)
 *  - Current monitoring (A)
 *  - Power-on behavior (off/on/previous)
 *  - Minimum brightness setting
 *  - Transition time control
 *  - Health check monitoring
 *
 *  Version: 1.0.1
 *
 *  References:
 *  - https://github.com/Koenkk/zigbee2mqtt/issues/14315
 *  - https://www.zigbee2mqtt.io/devices/HK-SL-DIM-US-A.html
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.0.1"

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_ONOFF = 0x0006
@Field static final int CLUSTER_LEVEL = 0x0008
@Field static final int CLUSTER_METERING = 0x0702
@Field static final int CLUSTER_ELECTRICAL = 0x0B04

// On/Off Cluster Attributes
@Field static final int ATTR_ONOFF = 0x0000
@Field static final int ATTR_ONOFF_START_UP = 0x4003  // ZCL start-up behavior

// Level Cluster Attributes
@Field static final int ATTR_CURRENT_LEVEL = 0x0000
@Field static final int ATTR_ON_LEVEL = 0x0011
@Field static final int ATTR_START_UP_LEVEL = 0x4000

// Electrical Measurement Attributes (0x0B04)
@Field static final int ATTR_RMS_VOLTAGE = 0x0505
@Field static final int ATTR_RMS_CURRENT = 0x0508
@Field static final int ATTR_ACTIVE_POWER = 0x050B
@Field static final int ATTR_AC_VOLTAGE_DIVISOR = 0x0601
@Field static final int ATTR_AC_CURRENT_DIVISOR = 0x0603
@Field static final int ATTR_AC_POWER_DIVISOR = 0x0605

// Metering Attributes (0x0702)
@Field static final int ATTR_CURRENT_SUMMATION = 0x0000
@Field static final int ATTR_METERING_DIVISOR = 0x0302

// Power-on behavior values
@Field static final Map POWER_ON_BEHAVIOR = [
    0: "off",
    1: "on",
    2: "previous"
]
@Field static final Map POWER_ON_BEHAVIOR_REVERSE = [
    "off": 0,
    "on": 1,
    "previous": 2
]

// ==================== Metadata ====================

metadata {
    definition (name: "Sunricher Zigbee Dimmer", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Switch"
        capability "SwitchLevel"
        capability "Light"          // lets HomeKit/dashboards treat it as a dimmable light, not a metered switch
        capability "ChangeLevel"
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
        attribute "powerOnBehavior", "enum", ["off", "on", "previous"]
        attribute "driverVersion", "string"

        // Commands
        command "setPowerOnBehavior", [[name: "behavior*", type: "ENUM", constraints: ["off", "on", "previous"],
                                        description: "off=always off, on=always on, previous=restore last state"]]
        command "setMinBrightness", [[name: "level*", type: "NUMBER", description: "Minimum brightness (1-50%)"]]
        command "presetLevel", [[name: "level*", type: "NUMBER", description: "Set level without turning on (1-100%)"]]

        // Fingerprints - Sunricher HK-SL-DIM-EU-A
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0004,0005,0006,0008,0702,0B04,0B05,1000",
                    outClusters: "0019",
                    manufacturer: "Sunricher", model: "HK-SL-DIM-EU-A",
                    deviceJoinName: "Sunricher Dimmer"

        // Alternative fingerprint
        fingerprint manufacturer: "Sunricher", model: "HK-SL-DIM-EU-A",
                    deviceJoinName: "Sunricher Dimmer"

        // US variant
        fingerprint manufacturer: "Sunricher", model: "HK-SL-DIM-US-A",
                    deviceJoinName: "Sunricher Dimmer"

        // AU variant
        fingerprint manufacturer: "Sunricher", model: "HK-SL-DIM-AU-R-A",
                    deviceJoinName: "Sunricher Dimmer"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "transitionTime", type: "enum", title: "Default transition time",
              options: [
                  ["0": "Instant"],
                  ["5": "0.5 seconds"],
                  ["10": "1 second"],
                  ["20": "2 seconds"],
                  ["50": "5 seconds"]
              ],
              defaultValue: "10"

        input name: "minBrightness", type: "number", title: "Minimum brightness (%)",
              description: "Lowest dimming level (1-50)",
              defaultValue: 1, range: "1..50"

        input name: "pollInterval", type: "enum", title: "Power polling interval",
              options: [
                  ["0": "Disabled (rely on reports)"],
                  ["60": "Every 1 minute"],
                  ["300": "Every 5 minutes"],
                  ["600": "Every 10 minutes"]
              ],
              defaultValue: "300"

        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: [
                  ["0": "Disabled"],
                  ["60": "Every 1 hour"],
                  ["120": "Every 2 hours"]
              ],
              defaultValue: "60"

        // Divisor settings (auto-detected but can be overridden)
        input name: "powerDivisor", type: "number", title: "Power divisor",
              description: "Divisor for power (default: 10)", defaultValue: 10

        input name: "voltageDivisor", type: "number", title: "Voltage divisor",
              description: "Divisor for voltage (default: 10)", defaultValue: 10

        input name: "currentDivisor", type: "number", title: "Current divisor",
              description: "Divisor for current (default: 1000)", defaultValue: 1000

        input name: "energyDivisor", type: "number", title: "Energy divisor",
              description: "Divisor for energy (default: 3600000)", defaultValue: 3600000
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Sunricher Dimmer installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "Sunricher Dimmer updated"
    unschedule()

    if (logEnable) runIn(1800, logsOff)

    // Store divisors
    state.powerDivisor = powerDivisor ?: 10
    state.voltageDivisor = voltageDivisor ?: 10
    state.currentDivisor = currentDivisor ?: 1000
    state.energyDivisor = energyDivisor ?: 3600000

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
    state.energyDivisor = energyDivisor ?: 3600000

    schedulePoll()
    scheduleHealthCheck()

    runIn(5, configure)
}

// ==================== Configuration ====================

def configure() {
    log.info "Configuring Sunricher Dimmer..."

    def cmds = []

    // Bind On/Off cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Bind Level cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0008 {${device.zigbeeId}} {}"
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

    // Configure Level reporting
    cmds += zigbee.configureReporting(CLUSTER_LEVEL, ATTR_CURRENT_LEVEL, 0x20, 1, 3600, 1)
    cmds += "delay 300"

    // Configure power reporting
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_ACTIVE_POWER, 0x29, 5, 300, 5)
    cmds += "delay 300"

    // Configure voltage reporting
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_RMS_VOLTAGE, 0x21, 60, 600, 10)
    cmds += "delay 300"

    // Configure current reporting
    cmds += zigbee.configureReporting(CLUSTER_ELECTRICAL, ATTR_RMS_CURRENT, 0x21, 5, 300, 10)
    cmds += "delay 300"

    // Configure energy reporting
    cmds += zigbee.configureReporting(CLUSTER_METERING, ATTR_CURRENT_SUMMATION, 0x25, 60, 3600, 1)
    cmds += "delay 500"

    // Read divisors
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
    def pollSecs = (pollInterval ?: "300") as int

    if (pollSecs > 0) {
        switch(pollSecs) {
            case 60: runEvery1Minute(pollPower); break
            case 300: runEvery5Minutes(pollPower); break
            case 600: runEvery10Minutes(pollPower); break
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
        }
    }
}

def healthCheck() {
    def lastActivity = state.lastSuccessfulComm ?: 0
    def healthInterval = ((healthCheckInterval ?: "60") as int) * 60 * 1000
    def threshold = healthInterval * 2

    if (now() - lastActivity > threshold) {
        if (device.currentValue("healthStatus") != "offline") {
            sendEvent(name: "healthStatus", value: "offline")
            logWarn "Device appears offline"
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

    // Level
    cmds += zigbee.readAttribute(CLUSTER_LEVEL, ATTR_CURRENT_LEVEL)
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

    return cmds
}

def ping() {
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

// ==================== Level Commands ====================

def setLevel(level, duration = null) {
    // Convert to Integer using 'as Integer' pattern to avoid Groovy ambiguous method issues
    Integer levelInt = level as Integer

    // Use Groovy collection min/max to avoid Math.min/max ambiguity
    levelInt = [[levelInt, 100].min(), 0].max()

    // Apply minimum brightness
    Integer minLevel = (minBrightness ?: 1) as Integer
    if (levelInt > 0 && levelInt < minLevel) {
        levelInt = minLevel
    }

    // Convert percentage to 0-254 (0xFE max)
    Integer zigbeeLevel = levelInt == 0 ? 0 : [[((levelInt * 254 / 100) as Integer), 254].min(), 1].max()

    // Get transition time in deciseconds (1/10 second units)
    Integer transDeci = duration != null ? ((duration as BigDecimal) * 10).toInteger() : ((transitionTime ?: "10") as Integer)

    // Format for zigbee command - level as hex, transition as little-endian 16-bit
    String hexLevel = zigbee.convertToHexString(zigbeeLevel, 2)
    String hexTrans = zigbee.swapOctets(zigbee.convertToHexString(transDeci, 4))

    logInfo "Setting level to ${levelInt}% (zigbee: 0x${hexLevel}, transition: ${transDeci/10.0}s)"

    // Use command 0x04 = Move to Level with On/Off (turns on if level > 0, off if level = 0)
    if (levelInt == 0) {
        // Turn off
        return zigbee.off()
    } else {
        // Move to level with on/off - cluster 0x0008, command 0x04
        return zigbee.command(CLUSTER_LEVEL, 0x04, [:], 0, "${hexLevel} ${hexTrans}")
    }
}

def startLevelChange(String direction) {
    logDebug "Start level change: ${direction}"

    def upDown = (direction == "up") ? 0x00 : 0x01
    def rate = 50  // Default rate

    return zigbee.command(CLUSTER_LEVEL, 0x05, "00 ${zigbee.convertToHexString(upDown, 2)} ${zigbee.convertToHexString(rate, 2)}")
}

def stopLevelChange() {
    logDebug "Stop level change"
    return zigbee.command(CLUSTER_LEVEL, 0x07, "")
}

// ==================== Custom Commands ====================

def presetLevel(level) {
    Integer levelInt = level as Integer
    levelInt = [[levelInt, 100].min(), 1].max()
    Integer zigbeeLevel = (levelInt * 2.54).toInteger()

    logInfo "Preset level to ${levelInt}% (will apply on next on)"

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LEVEL, ATTR_ON_LEVEL, 0x20, zigbeeLevel)

    sendZigbeeCommands(cmds)
}

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

def setMinBrightness(level) {
    Integer levelInt = level as Integer
    levelInt = [[levelInt, 50].min(), 1].max()
    logInfo "Setting minimum brightness to ${levelInt}%"
    device.updateSetting("minBrightness", [value: levelInt, type: "number"])
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parse: $description"

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

    switch(cluster) {
        case "0006":  // On/Off
            events += handleOnOffCluster(attrId, value)
            break

        case "0008":  // Level
            events += handleLevelCluster(attrId, value)
            break

        case "0702":  // Metering
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

        case "4003":  // Start-up behavior
            def behaviorValue = Integer.parseInt(value, 16)
            def behavior = POWER_ON_BEHAVIOR[behaviorValue] ?: "unknown"
            events << createEvent(name: "powerOnBehavior", value: behavior)
            logDebug "Power-on behavior: ${behavior}"
            break
    }

    return events
}

private List handleLevelCluster(String attrId, String value) {
    def events = []

    if (attrId == "0000") {  // Current Level
        Integer zigbeeLevel = Integer.parseInt(value, 16)
        Integer level = (zigbeeLevel / 2.54).toInteger()
        level = [[level, 100].min(), 0].max()

        events << createEvent(name: "level", value: level, unit: "%")
        logInfo "Level: ${level}%"
    }

    return events
}

private List handleMeteringCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // Current Summation
            Long rawValue = Long.parseLong(value, 16)
            BigDecimal divisor = state.energyDivisor ?: (energyDivisor ?: 3600000)
            BigDecimal energy = rawValue / divisor
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

    if (descMap.clusterId == "0006" && descMap.command == "0B") {
        if (descMap.data?.size() > 0) {
            def cmd = descMap.data[0]
            if (cmd == "01") {
                events << createEvent(name: "switch", value: "on")
            } else if (cmd == "00") {
                events << createEvent(name: "switch", value: "off")
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

// Fixed-scale rounding; avoids BigDecimal results such as 0E+1 when a reading is exactly zero
private BigDecimal fixedScale(BigDecimal value, int places) {
    return value.setScale(places, java.math.RoundingMode.HALF_UP)
}
