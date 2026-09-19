/**
 *  Aqara Smart Plug EU (SP-EUC01)
 *  Model: lumi.plug.maeu01
 *
 *  A comprehensive driver for the Aqara Smart Plug EU with power monitoring.
 *
 *  Features:
 *  - On/Off control
 *  - Power monitoring (W)
 *  - Energy metering (kWh)
 *  - Voltage monitoring (V)
 *  - Current monitoring (A)
 *  - Device temperature
 *  - Overload protection
 *  - Power outage memory
 *  - LED disable (night mode)
 *  - Button lock
 *  - Health check monitoring
 *
 *  Known Issue:
 *  After certain firmware updates, this plug may respond to unrelated switch
 *  commands from devices routed through it. This is a firmware issue, not a
 *  driver issue. See: https://github.com/Koenkk/zigbee2mqtt/issues/13903
 *
 *  Version: 1.0.0
 *
 *  References:
 *  - https://www.zigbee2mqtt.io/devices/SP-EUC01.html
 *  - https://github.com/Koenkk/zigbee-herdsman-converters
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.0.1"

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_POWER_CONFIG = 0x0001
@Field static final int CLUSTER_DEVICE_TEMP = 0x0002
@Field static final int CLUSTER_ONOFF = 0x0006
@Field static final int CLUSTER_METERING = 0x0702
@Field static final int CLUSTER_ELECTRICAL = 0x0B04
@Field static final int CLUSTER_LUMI = 0xFCC0

// Electrical Measurement Attributes (0x0B04)
@Field static final int ATTR_RMS_VOLTAGE = 0x0505
@Field static final int ATTR_RMS_CURRENT = 0x0508
@Field static final int ATTR_ACTIVE_POWER = 0x050B
@Field static final int ATTR_AC_POWER_MULTIPLIER = 0x0604
@Field static final int ATTR_AC_POWER_DIVISOR = 0x0605

// Metering Attributes (0x0702)
@Field static final int ATTR_CURRENT_SUMMATION = 0x0000
@Field static final int ATTR_MULTIPLIER = 0x0301
@Field static final int ATTR_DIVISOR = 0x0302

// Device Temperature Attribute
@Field static final int ATTR_DEVICE_TEMP = 0x0000

// Lumi manufacturer code
@Field static final int LUMI_MFG_CODE = 0x115F

// ==================== Metadata ====================

metadata {
    definition (name: "Aqara Smart Plug EU", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "CurrentMeter"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Configuration"
        capability "HealthCheck"

        // Custom attributes
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "lastActivity", "string"
        attribute "overloadProtection", "number"
        attribute "powerOutageMemory", "enum", ["on", "off"]
        attribute "ledDisabled", "enum", ["on", "off"]
        attribute "buttonLock", "enum", ["on", "off"]
        attribute "driverVersion", "string"

        // Commands
        command "setOverloadProtection", [[name: "maxPower*", type: "NUMBER", description: "Maximum power in watts (100-2300)"]]
        command "setPowerOutageMemory", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setLedDisabled", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setButtonLock", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]

        // Fingerprints
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0002,0003,0004,0005,0006,0009,0702,0B04",
                    outClusters: "000A,0019",
                    manufacturer: "LUMI", model: "lumi.plug.maeu01",
                    deviceJoinName: "Aqara Smart Plug EU"

        // Alternative fingerprint
        fingerprint manufacturer: "LUMI", model: "lumi.plug.maeu01",
                    deviceJoinName: "Aqara Smart Plug EU"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "powerReportInterval", type: "enum", title: "Power reporting interval",
              options: [
                  ["10": "Every 10 seconds"],
                  ["30": "Every 30 seconds"],
                  ["60": "Every 1 minute"],
                  ["300": "Every 5 minutes"]
              ],
              defaultValue: "60"

        input name: "energyReportInterval", type: "enum", title: "Energy reporting interval",
              options: [
                  ["60": "Every 1 minute"],
                  ["300": "Every 5 minutes"],
                  ["600": "Every 10 minutes"],
                  ["3600": "Every 1 hour"]
              ],
              defaultValue: "300"

        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: [
                  ["0": "Disabled"],
                  ["60": "Every 1 hour"],
                  ["120": "Every 2 hours"],
                  ["240": "Every 4 hours"]
              ],
              defaultValue: "60"

        input name: "powerDivisor", type: "number", title: "Power divisor",
              description: "Divisor for power calculation (usually 10)",
              defaultValue: 10

        input name: "energyDivisor", type: "number", title: "Energy divisor",
              description: "Divisor for energy calculation (usually 1000)",
              defaultValue: 1000
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Aqara Smart Plug EU installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "Aqara Smart Plug EU updated"
    unschedule()

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h

    schedulePoll()
    scheduleHealthCheck()

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
}

def initialize() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    sendEvent(name: "healthStatus", value: "online")

    state.lastSuccessfulComm = now()
    state.powerMultiplier = 1
    state.powerDivisor = powerDivisor ?: 10
    state.energyMultiplier = 1
    state.energyDivisor = energyDivisor ?: 1000

    schedulePoll()
    scheduleHealthCheck()

    runIn(5, configure)
}

// ==================== Configuration ====================

def configure() {
    log.info "Configuring Aqara Smart Plug EU..."

    def cmds = []

    // Bind On/Off cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0006 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Configure On/Off reporting
    cmds += zigbee.configureReporting(CLUSTER_ONOFF, 0x0000, 0x10, 0, 3600, null)
    cmds += "delay 300"

    // Bind Electrical Measurement cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0B04 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Bind Metering cluster
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0702 {${device.zigbeeId}} {}"
    cmds += "delay 500"

    // Read electrical measurement multiplier/divisor
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_AC_POWER_MULTIPLIER)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_AC_POWER_DIVISOR)
    cmds += "delay 200"

    // Read metering multiplier/divisor
    cmds += zigbee.readAttribute(CLUSTER_METERING, ATTR_MULTIPLIER)
    cmds += "delay 200"
    cmds += zigbee.readAttribute(CLUSTER_METERING, ATTR_DIVISOR)
    cmds += "delay 500"

    // Initial refresh
    cmds += refresh()

    return cmds
}

// ==================== Scheduling ====================

private void schedulePoll() {
    def pollSecs = (powerReportInterval ?: "60") as int

    // Use runEvery instead of cron for simplicity
    switch(pollSecs) {
        case 10:
            schedule("0/10 * * * * ?", pollPower)
            break
        case 30:
            schedule("0/30 * * * * ?", pollPower)
            break
        case 60:
            runEvery1Minute(pollPower)
            break
        case 300:
            runEvery5Minutes(pollPower)
            break
        default:
            runEvery1Minute(pollPower)
    }

    // Energy polling (less frequent)
    def energySecs = (energyReportInterval ?: "300") as int
    switch(energySecs) {
        case 60:
            runEvery1Minute(pollEnergy)
            break
        case 300:
            runEvery5Minutes(pollEnergy)
            break
        case 600:
            runEvery10Minutes(pollEnergy)
            break
        case 3600:
            runEvery1Hour(pollEnergy)
            break
        default:
            runEvery5Minutes(pollEnergy)
    }

    // Temperature polling every 30 minutes (device doesn't support reporting)
    runEvery30Minutes(pollTemperature)

    logInfo "Polling scheduled: power every ${pollSecs}s, energy every ${energySecs}s"
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
        // Try to wake it up
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
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_ELECTRICAL, ATTR_ACTIVE_POWER))
}

def pollEnergy() {
    logDebug "Polling energy..."
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_METERING, ATTR_CURRENT_SUMMATION))
}

def pollTemperature() {
    logDebug "Polling temperature..."
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_DEVICE_TEMP, ATTR_DEVICE_TEMP))
}

def refresh() {
    logDebug "Refresh"

    def cmds = []

    // On/Off state
    cmds += zigbee.readAttribute(CLUSTER_ONOFF, 0x0000)
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

    // Temperature
    cmds += zigbee.readAttribute(CLUSTER_DEVICE_TEMP, ATTR_DEVICE_TEMP)

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

def setOverloadProtection(maxPower) {
    Integer maxPowerInt = maxPower as Integer
    maxPowerInt = [[maxPowerInt, 2300].min(), 100].max()
    logInfo "Setting overload protection to ${maxPowerInt}W"

    // Write to Lumi manufacturer-specific cluster
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x020B, 0x39, floatToHex(maxPowerInt), [mfgCode: LUMI_MFG_CODE])

    sendEvent(name: "overloadProtection", value: maxPowerInt, unit: "W")
    sendZigbeeCommands(cmds)
}

def setPowerOutageMemory(String enabled) {
    logInfo "Setting power outage memory to ${enabled}"

    def value = (enabled == "on") ? 1 : 0
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x0201, 0x10, value, [mfgCode: LUMI_MFG_CODE])

    sendEvent(name: "powerOutageMemory", value: enabled)
    sendZigbeeCommands(cmds)
}

def setLedDisabled(String enabled) {
    logInfo "Setting LED disabled to ${enabled}"

    def value = (enabled == "on") ? 1 : 0
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x0203, 0x10, value, [mfgCode: LUMI_MFG_CODE])

    sendEvent(name: "ledDisabled", value: enabled)
    sendZigbeeCommands(cmds)
}

def setButtonLock(String enabled) {
    logInfo "Setting button lock to ${enabled}"

    def value = (enabled == "on") ? 1 : 0
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, 0x0200, 0x10, value, [mfgCode: LUMI_MFG_CODE])

    sendEvent(name: "buttonLock", value: enabled)
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
            logDebug "Unhandled message: $description"
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
            if (attrId == "0000") {
                def switchState = value == "01" ? "on" : "off"
                events << createEvent(name: "switch", value: switchState)
                logInfo "Switch: ${switchState}"
            }
            break

        case "0002":  // Device Temperature
            if (attrId == "0000") {
                def temp = hexToSignedInt(value)
                events << createEvent(name: "temperature", value: temp, unit: "°C")
                logInfo "Temperature: ${temp}°C"
            }
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

private List handleMeteringCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // Current Summation Delivered (Energy)
            Long rawValue = Long.parseLong(value, 16)
            BigDecimal divisor = state.energyDivisor ?: (energyDivisor ?: 1000)
            BigDecimal energy = rawValue / divisor

            // Format appropriately
            def energyFormatted
            def unit = "kWh"
            if (energy < 0.01) {
                energyFormatted = ((energy * 10000).toLong()) / 10.0  // 1 decimal place in Wh
                unit = "Wh"
            } else {
                energyFormatted = fixedScale(energy, 3)  // 3 decimal places
            }

            events << createEvent(name: "energy", value: energyFormatted, unit: unit)
            logInfo "Energy: ${energyFormatted} ${unit}"
            break

        case "0301":  // Multiplier
            state.energyMultiplier = Integer.parseInt(value, 16)
            logDebug "Energy multiplier: ${state.energyMultiplier}"
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
            BigDecimal voltage = Integer.parseInt(value, 16) / 10.0
            BigDecimal voltageFormatted = fixedScale(voltage, 1)

            events << createEvent(name: "voltage", value: voltageFormatted, unit: "V")
            logInfo "Voltage: ${voltageFormatted} V"
            break

        case "0508":  // RMS Current
            BigDecimal current = Integer.parseInt(value, 16) / 1000.0
            BigDecimal currentFormatted = fixedScale(current, 3)

            events << createEvent(name: "amperage", value: currentFormatted, unit: "A")
            logInfo "Current: ${currentFormatted} A"
            break

        case "0604":  // Power Multiplier
            state.powerMultiplier = Integer.parseInt(value, 16)
            logDebug "Power multiplier: ${state.powerMultiplier}"
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

    // Handle on/off commands (these can come from routing issues!)
    if (descMap.clusterId == "0006") {
        if (descMap.command in ["00", "01"]) {
            // Command 00 = Off, 01 = On
            // Log this but be aware it might be from routing issues
            logDebug "Received On/Off command: ${descMap.command}"

            // Only process if it looks like a direct response
            if (descMap.isClusterSpecific == false || descMap.data?.size() == 0) {
                // This might be a report, handle it
                def switchState = descMap.command == "01" ? "on" : "off"
                events << createEvent(name: "switch", value: switchState)
                logInfo "Switch (catchall): ${switchState}"
            } else {
                // This might be from another device routed through this plug
                logWarn "Received routed command - possible firmware routing issue"
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

private int hexToSignedInt(String hex) {
    if (!hex) return 0
    def value = Integer.parseInt(hex, 16)
    if (hex.length() == 4 && value > 32767) value -= 65536
    else if (hex.length() == 2 && value > 127) value -= 256
    return value
}

private String floatToHex(float value) {
    return Integer.toHexString(Float.floatToIntBits(value))
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
