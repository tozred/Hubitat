/**
 *  Sonoff TRVZB Thermostatic Radiator Valve
 *
 *  A comprehensive, resilient driver for SONOFF TRVZB
 *  Based on device discovery data and zigbee2mqtt documentation
 *
 *  Features:
 *  - Full thermostat control (temperature, setpoint, mode)
 *  - Child lock control
 *  - Window open detection
 *  - Frost protection
 *  - Valve position monitoring and control
 *  - Temperature calibration
 *  - External temperature sensor support
 *  - Weekly schedule support
 *  - Boost and timer modes
 *  - Battery monitoring
 *  - Robust error handling and auto-recovery
 *
 *  Version: 2.1.2
 *
 *  Clusters Used:
 *  - 0x0000 (Basic) - Device info
 *  - 0x0001 (Power Configuration) - Battery
 *  - 0x0006 (On/Off) - Valve state
 *  - 0x0201 (Thermostat) - Temperature control
 *  - 0xFC11 (Sonoff Custom) - Manufacturer specific features
 */

import hubitat.helper.HexUtils
import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "2.3.0"
// Manufacturer code - can be string "0x1286" or integer 0x1286
// Using string format for broader compatibility
@Field static final String SONOFF_MFG_CODE = "0x1286"
@Field static final int SONOFF_MFG_CODE_INT = 0x1286

// Cluster IDs
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_POWER = 0x0001
@Field static final int CLUSTER_ONOFF = 0x0006
@Field static final int CLUSTER_THERMOSTAT = 0x0201
@Field static final int CLUSTER_FC11 = 0xFC11

// Thermostat Cluster Attributes (0x0201)
@Field static final int ATTR_LOCAL_TEMP = 0x0000
@Field static final int ATTR_OUTDOOR_TEMP = 0x0001
@Field static final int ATTR_OCCUPANCY = 0x0002
@Field static final int ATTR_ABS_MIN_HEAT_SETPOINT = 0x0003
@Field static final int ATTR_ABS_MAX_HEAT_SETPOINT = 0x0004
@Field static final int ATTR_TEMP_CALIBRATION = 0x0010
@Field static final int ATTR_HEATING_SETPOINT = 0x0012
@Field static final int ATTR_MIN_HEAT_SETPOINT = 0x0015
@Field static final int ATTR_MAX_HEAT_SETPOINT = 0x0016
@Field static final int ATTR_CONTROL_SEQ = 0x001B
@Field static final int ATTR_SYSTEM_MODE = 0x001C
@Field static final int ATTR_RUNNING_MODE = 0x001E
@Field static final int ATTR_RUNNING_STATE = 0x0029

// FC11 Cluster Attributes (Sonoff Custom)
@Field static final int ATTR_CHILD_LOCK = 0x0000
@Field static final int ATTR_WINDOW_DETECTION = 0x6000
@Field static final int ATTR_WINDOW_OPEN = 0x6001
@Field static final int ATTR_FROST_PROTECTION = 0x6002
@Field static final int ATTR_IDLE_STEPS = 0x6003
@Field static final int ATTR_CLOSING_STEPS = 0x6004
@Field static final int ATTR_VALVE_OPEN_VOLTAGE = 0x6005
@Field static final int ATTR_VALVE_CLOSE_VOLTAGE = 0x6006
@Field static final int ATTR_VALVE_MOTOR_VOLTAGE = 0x6007
@Field static final int ATTR_UNKNOWN1 = 0x6008
@Field static final int ATTR_HEATING_SETPOINT_FC11 = 0x6009
@Field static final int ATTR_UNKNOWN2 = 0x600A
@Field static final int ATTR_VALVE_OPENING = 0x600B
@Field static final int ATTR_VALVE_CLOSING = 0x600C
@Field static final int ATTR_EXTERNAL_TEMP = 0x600D
@Field static final int ATTR_EXTERNAL_SENSOR = 0x600E
@Field static final int ATTR_TEMP_ACCURACY = 0x6011          // INT16, 0.01 °C (0x600F was wrong; checked against zigbee-herdsman-converters)
// Firmware 1.3+/1.4.x
@Field static final int ATTR_TEMPORARY_MODE = 0x6014         // UINT8: 0 = boost, 1 = timer
@Field static final int ATTR_TEMPORARY_MODE_TIME = 0x6015    // UINT32 seconds
@Field static final int ATTR_TEMPORARY_MODE_TEMP = 0x6016    // INT16, 0.01 °C
@Field static final int ATTR_SMART_TEMP_CONTROL = 0x6017     // BITMAP8: 1 = adaptive (PID) valve control

// Power Cluster Attributes (0x0001)
@Field static final int ATTR_BATTERY_VOLTAGE = 0x0020
@Field static final int ATTR_BATTERY_PCT = 0x0021

// Mode mappings
@Field static final Map SYSTEM_MODES = [
    0x00: "off",
    0x01: "auto",
    0x04: "heat"
]
@Field static final Map SYSTEM_MODES_REVERSE = [
    "off": 0x00,
    "auto": 0x01,
    "heat": 0x04
]
@Field static final Map OPERATING_STATES = [
    0x00: "idle",
    0x01: "heating"
]
@Field static final Map SENSOR_TYPES = [
    0: "internal",
    1: "external",
    2: "external_2",
    3: "external_3"
]

// Temperature limits
@Field static final BigDecimal TEMP_MIN = 4.0
@Field static final BigDecimal TEMP_MAX = 35.0
@Field static final BigDecimal CALIBRATION_MIN = -12.0
@Field static final BigDecimal CALIBRATION_MAX = 12.0

// ==================== Metadata ====================

metadata {
    definition (name: "Sonoff TRVZB", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Actuator"
        capability "Sensor"
        capability "Configuration"
        capability "Refresh"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatSetpoint"
        capability "ThermostatMode"
        capability "ThermostatOperatingState"
        capability "HealthCheck"

        // Custom attributes
        attribute "lastActivity", "string"
        attribute "childLock", "enum", ["on", "off"]
        attribute "windowDetection", "enum", ["on", "off"]
        attribute "windowOpen", "enum", ["open", "closed"]
        attribute "frostProtection", "number"
        attribute "valvePosition", "number"
        attribute "valveClosingDegree", "number"
        attribute "temperatureCalibration", "number"
        attribute "minHeatingSetpoint", "number"
        attribute "maxHeatingSetpoint", "number"
        attribute "externalTemperature", "number"
        attribute "externalSensor", "enum", ["internal", "external", "external_2", "external_3"]
        attribute "temperatureAccuracy", "number"
        attribute "smartTemperatureControl", "enum", ["on", "off"]
        attribute "temporaryMode", "enum", ["boost", "timer"]
        attribute "temporaryModeMinutes", "number"
        attribute "temporaryModeTemperature", "number"
        attribute "idleSteps", "number"
        attribute "closingSteps", "number"
        attribute "driverVersion", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        // Thermostat commands
        command "initialize"   // re-create the polling and health-check schedules, then configure
        command "logsOff"   // switch debug logging off now (also runs automatically 30 min after Save)
        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "Temperature (4-35°C)"]]
        command "setThermostatMode", [[name: "mode*", type: "ENUM", constraints: ["off", "heat", "auto"]]]
        command "off"
        command "heat"
        command "auto"

        // Custom commands
        command "setChildLock", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setWindowDetection", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"]]]
        command "setFrostProtection", [[name: "temperature*", type: "NUMBER", description: "Temperature (4-35°C)"]]
        command "setTemperatureCalibration", [[name: "offset*", type: "NUMBER", description: "Offset (-12 to +12°C)"]]
        command "setValvePosition", [[name: "position*", type: "NUMBER", description: "Position (0-100%)"]]
        command "setValveClosingDegree", [[name: "degree*", type: "NUMBER", description: "Closing degree (0-100%)"]]
        command "setMinHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "Min temperature (4-35°C)"]]
        command "setMaxHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "Max temperature (4-35°C)"]]
        command "setExternalTemperature", [[name: "temperature*", type: "NUMBER", description: "External sensor temperature"]]
        command "setExternalSensor", [[name: "sensor*", type: "ENUM", constraints: ["internal", "external", "external_2", "external_3"]]]
        command "setTemperatureAccuracy", [[name: "accuracy*", type: "NUMBER", description: "Accuracy (-1 to -0.2°C)"]]
        command "setSmartTemperatureControl", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"], description: "Adaptive valve control (firmware 1.4+)"]]
        command "boost", [[name: "minutes*", type: "NUMBER", description: "Full heat for this many minutes (1-180); firmware 1.4+"]]
        command "timerMode", [[name: "minutes*", type: "NUMBER", description: "Duration in minutes (1-1440)"], [name: "temperature*", type: "NUMBER", description: "Target during the timer (4-35°C)"]]
        command "readFirmware14Attributes"   // accuracy, smart control and temporary mode only (short, so a sleepy valve answers all of it)
        command "readAllAttributes"
        command "readCustomAttributes"
        command "forceRefresh"

        // Fingerprints - multiple variations to improve auto-recognition
        // Exact match from device (firmware 1.2.1) - includes FC57 cluster
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0006,0020,0201,FC57,FC11",
                    outClusters: "000A,0019",
                    manufacturer: "SONOFF", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"

        // Primary fingerprint without FC57
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0006,0020,0201,FC11",
                    outClusters: "000A,0019",
                    manufacturer: "SONOFF", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"

        // Alternative without Poll Control cluster (0020)
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0006,0201,FC11",
                    outClusters: "000A,0019",
                    manufacturer: "SONOFF", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"

        // Minimal fingerprint - just manufacturer and model (most flexible)
        fingerprint manufacturer: "SONOFF", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"

        // Case variations some devices report
        fingerprint manufacturer: "Sonoff", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"

        // Lowercase variation
        fingerprint manufacturer: "sonoff", model: "TRVZB",
                    deviceJoinName: "Sonoff TRVZB"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "pollInterval", type: "enum", title: "Poll interval",
              options: [
                  ["0": "Disabled"],
                  ["5": "Every 5 minutes"],
                  ["10": "Every 10 minutes"],
                  ["15": "Every 15 minutes"],
                  ["30": "Every 30 minutes"],
                  ["60": "Every 1 hour"]
              ],
              defaultValue: "10"
        input name: "healthCheckInterval", type: "enum", title: "Health check interval",
              options: [
                  ["0": "Disabled"],
                  ["60": "Every 1 hour"],
                  ["120": "Every 2 hours"],
                  ["240": "Every 4 hours"]
              ],
              defaultValue: "60"
        input name: "tempUnit", type: "enum", title: "Temperature unit",
              options: [["C": "Celsius"], ["F": "Fahrenheit"]],
              defaultValue: "C"
        input name: "autoRecovery", type: "bool", title: "Enable auto-recovery on communication failure", defaultValue: true
    }
}

// ==================== Lifecycle Methods ====================

def installed() {
    log.info "TRVZB Driver installed - Version ${DRIVER_VERSION}"
    initialize()
}

def updated() {
    log.info "TRVZB Driver updated"
    unschedule()

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h

    schedulePoll()
    scheduleHealthCheck()

    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
}

def initialize() {
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"])
    sendEvent(name: "supportedThermostatFanModes", value: [])
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    sendEvent(name: "healthStatus", value: "online")

    state.failedCommands = 0
    state.lastSuccessfulComm = now()

    schedulePoll()
    scheduleHealthCheck()

    runIn(5, configure)
}

def configure() {
    log.info "Configuring TRVZB..."

    state.configuring = true

    def cmds = []

    // Bind clusters
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0201 {${device.zigbeeId}} {}"  // Thermostat
    cmds += "delay 500"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"  // Power
    cmds += "delay 500"

    // Configure reporting - Thermostat cluster
    cmds += zigbee.configureReporting(CLUSTER_THERMOSTAT, ATTR_LOCAL_TEMP, 0x29, 10, 300, 10)      // Local temp: 10s-5min, delta 0.1°C
    cmds += "delay 200"
    cmds += zigbee.configureReporting(CLUSTER_THERMOSTAT, ATTR_HEATING_SETPOINT, 0x29, 1, 600, 50) // Setpoint: 1s-10min, delta 0.5°C
    cmds += "delay 200"
    cmds += zigbee.configureReporting(CLUSTER_THERMOSTAT, ATTR_SYSTEM_MODE, 0x30, 1, 600, null)    // Mode: 1s-10min
    cmds += "delay 200"
    cmds += zigbee.configureReporting(CLUSTER_THERMOSTAT, ATTR_RUNNING_STATE, 0x19, 1, 600, null)  // Running state: 1s-10min
    cmds += "delay 200"

    // Configure reporting - Power cluster
    cmds += zigbee.configureReporting(CLUSTER_POWER, ATTR_BATTERY_PCT, 0x20, 3600, 43200, 2)       // Battery: 1h-12h, delta 1%
    cmds += "delay 200"

    // Configure reporting - FC11 cluster (NO manufacturer code - causes errors)
    cmds += zigbee.configureReporting(CLUSTER_FC11, ATTR_VALVE_OPENING, 0x20, 10, 600, 1)

    cmds += "delay 1000"

    // Initial attribute read
    cmds += refresh()

    state.configuring = false

    logDebug "Configure commands: ${cmds}"
    return cmds
}

// ==================== Scheduling ====================

private void schedulePoll() {
    def poll = pollInterval ?: "30"
    if (poll != "0") {
        switch(poll) {
            case "5": runEvery5Minutes(refresh); break
            case "10": runEvery10Minutes(refresh); break
            case "15": runEvery15Minutes(refresh); break
            case "30": runEvery30Minutes(refresh); break
            case "60": runEvery1Hour(refresh); break
        }
        logInfo "Polling scheduled every ${poll} minutes"
    }
}

// Older driver versions stored this preference in hours ("1", "2", "4");
// normalise to minutes so a stale value can't produce a 2-minute offline threshold
private int healthIntervalMinutes() {
    int minutes = (healthCheckInterval ?: "120") as int
    if (minutes > 0 && minutes < 60) minutes = minutes * 60
    return minutes
}

private void scheduleHealthCheck() {
    unschedule("healthCheck")
    int minutes = healthIntervalMinutes()
    def interval = minutes as String
    if (minutes != 0) {
        // Use runEvery methods instead of cron for simplicity and reliability
        switch(minutes) {
            case 60: runEvery1Hour(healthCheck); break
            case 120: runEvery3Hours(healthCheck); break  // Closest available
            case 240: runEvery3Hours(healthCheck); break  // Closest available
            default: runEvery1Hour(healthCheck); break
        }
        logInfo "Health check scheduled every ${interval} minutes"
    }
}

// Legacy method - kept for compatibility with old scheduled jobs
def pollTemperature() {
    logDebug "pollTemperature called - redirecting to refresh"
    refresh()
}

def healthCheck() {
    def lastActivity = state.lastSuccessfulComm ?: 0
    def healthInterval = healthIntervalMinutes()
    if (healthInterval == 0) return
    def threshold = healthInterval * 60 * 1000 * 2  // 2x the health check interval

    if (now() - lastActivity > threshold) {
        logWarn "Device appears offline - no communication for ${(now() - lastActivity) / 60000} minutes"
        sendEvent(name: "healthStatus", value: "offline")

        if (autoRecovery) {
            logInfo "Attempting auto-recovery..."
            runIn(5, forceRefresh)
        }
    } else {
        if (device.currentValue("healthStatus") != "online") {
            sendEvent(name: "healthStatus", value: "online")
        }
    }
}

// ==================== Refresh Commands ====================

def refresh() {
    logDebug "Refresh"

    def cmds = []

    // Thermostat cluster - essential attributes
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_LOCAL_TEMP)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_HEATING_SETPOINT)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_SYSTEM_MODE)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_RUNNING_STATE)
    cmds += "delay 100"

    // Power cluster
    cmds += zigbee.readAttribute(CLUSTER_POWER, ATTR_BATTERY_PCT)
    cmds += "delay 100"

    // FC11 custom attributes
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_CHILD_LOCK)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_OPENING)

    // Send commands when called directly (not from configure)
    if (!state.configuring) {
        sendZigbeeCommands(cmds)
    }
    return cmds
}

def forceRefresh() {
    logInfo "Force refresh - reading all attributes"
    def cmds = readAllAttributesCmds()
    sendZigbeeCommands(cmds)
}

def readAllAttributes() {
    logDebug "Reading all attributes"
    def cmds = readAllAttributesCmds()
    sendZigbeeCommands(cmds)
}

private List readAllAttributesCmds() {
    def cmds = []

    // Basic cluster
    cmds += zigbee.readAttribute(CLUSTER_BASIC, 0x0004)  // Manufacturer
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_BASIC, 0x0005)  // Model
    cmds += "delay 100"

    // Power cluster
    cmds += zigbee.readAttribute(CLUSTER_POWER, ATTR_BATTERY_VOLTAGE)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_POWER, ATTR_BATTERY_PCT)
    cmds += "delay 100"

    // Thermostat cluster
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_LOCAL_TEMP)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_TEMP_CALIBRATION)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_HEATING_SETPOINT)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_MIN_HEAT_SETPOINT)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_MAX_HEAT_SETPOINT)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_SYSTEM_MODE)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_RUNNING_STATE)
    cmds += "delay 200"

    // FC11 custom attributes
    cmds += readCustomAttributesCmds()

    return cmds
}

def readFirmware14Attributes() {
    def cmds = []
    [ATTR_TEMP_ACCURACY, ATTR_SMART_TEMP_CONTROL, ATTR_TEMPORARY_MODE, ATTR_TEMPORARY_MODE_TIME].each {
        cmds += zigbee.readAttribute(CLUSTER_FC11, it)
        cmds += "delay 200"
    }
    sendZigbeeCommands(cmds)
}

def readCustomAttributes() {
    logDebug "Reading FC11 custom attributes"
    def cmds = readCustomAttributesCmds()
    sendZigbeeCommands(cmds)
}

private List readCustomAttributesCmds() {
    // FC11 cluster reads should NOT include manufacturer code
    def cmds = []

    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_CHILD_LOCK)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_WINDOW_DETECTION)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_FROST_PROTECTION)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_IDLE_STEPS)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_CLOSING_STEPS)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_MOTOR_VOLTAGE)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_OPENING)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_CLOSING)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_EXTERNAL_TEMP)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_EXTERNAL_SENSOR)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMP_ACCURACY)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_SMART_TEMP_CONTROL)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE)
    cmds += "delay 100"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE_TIME)

    return cmds
}

def ping() {
    logDebug "Ping"
    sendZigbeeCommands(zigbee.readAttribute(CLUSTER_BASIC, 0x0000))
}

// ==================== Thermostat Commands ====================

def setHeatingSetpoint(BigDecimal temperature) {
    // Convert from Fahrenheit to Celsius if needed
    BigDecimal tempC = temperature as BigDecimal
    if (tempUnit == "F") {
        tempC = (temperature - 32) * 5 / 9
        tempC = ((tempC * 10).toLong()) / 10.0
    }

    tempC = constrainTemperature(tempC)
    logInfo "Setting heating setpoint to ${tempC}°C${tempUnit == 'F' ? ' (' + temperature + '°F)' : ''}"

    def zigbeeTemp = temperatureToZigbee(tempC)
    logDebug "Zigbee temperature value: ${zigbeeTemp} (0x${Integer.toHexString(zigbeeTemp)})"

    def cmds = []
    def writeCmd = zigbee.writeAttribute(CLUSTER_THERMOSTAT, ATTR_HEATING_SETPOINT, 0x29, zigbeeTemp)
    logDebug "Write setpoint command: ${writeCmd}"
    cmds += writeCmd

    def readCmd = zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_HEATING_SETPOINT)
    logDebug "Read setpoint command: ${readCmd}"
    cmds += readCmd

    sendZigbeeCommands(cmds)
}

def setThermostatMode(String mode) {
    logInfo "Setting thermostat mode to ${mode}"

    def modeValue = SYSTEM_MODES_REVERSE[mode]
    if (modeValue == null) {
        logWarn "Unknown mode: ${mode}, defaulting to heat"
        modeValue = 0x04
    }

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_THERMOSTAT, ATTR_SYSTEM_MODE, 0x30, modeValue)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_SYSTEM_MODE)

    sendZigbeeCommands(cmds)
}

def off() {
    setThermostatMode("off")
}

def heat() {
    setThermostatMode("heat")
}

def auto() {
    setThermostatMode("auto")
}

// Unsupported thermostat methods
def emergencyHeat() {
    logWarn "Emergency heat not supported, using heat mode"
    heat()
}

def cool() {
    logWarn "Cool mode not supported on heating-only TRV"
}

def setCoolingSetpoint(temperature) {
    logWarn "Cooling setpoint not supported on heating-only TRV"
}

def fanAuto() { logWarn "Fan control not supported" }
def fanCirculate() { logWarn "Fan control not supported" }
def fanOn() { logWarn "Fan control not supported" }
def setThermostatFanMode(mode) { logWarn "Fan control not supported" }

// ==================== Custom Commands ====================

def setChildLock(String enabled) {
    logInfo "Setting child lock to ${enabled}"
    def value = (enabled == "on") ? 0x01 : 0x00

    // FC11 cluster commands should NOT include manufacturer code (causes "Unsupported Attribute" error)
    // Reference: https://github.com/Koenkk/zigbee-herdsman-converters/issues/7436
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_CHILD_LOCK, 0x10, value)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_CHILD_LOCK)

    sendZigbeeCommands(cmds)
}

def setWindowDetection(String enabled) {
    logInfo "Setting window detection to ${enabled}"
    def value = (enabled == "on") ? 0x01 : 0x00

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_WINDOW_DETECTION, 0x10, value)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_WINDOW_DETECTION)

    sendZigbeeCommands(cmds)
}

def setFrostProtection(temperature) {
    // Convert from Fahrenheit to Celsius if needed
    BigDecimal tempC = temperature as BigDecimal
    if (tempUnit == "F") {
        tempC = (temperature - 32) * 5 / 9
        tempC = ((tempC * 10).toLong()) / 10.0
    }

    tempC = constrainTemperature(tempC)
    logInfo "Setting frost protection to ${tempC}°C"

    def zigbeeTemp = temperatureToZigbee(tempC)

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_FROST_PROTECTION, 0x29, zigbeeTemp)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_FROST_PROTECTION)

    sendZigbeeCommands(cmds)
}

def setTemperatureCalibration(offset) {
    BigDecimal offsetVal = offset as BigDecimal
    offsetVal = [[offsetVal, CALIBRATION_MAX].min(), CALIBRATION_MIN].max()
    logInfo "Setting temperature calibration to ${offsetVal}°C"

    // Calibration is stored as Int8 with 0.1°C resolution
    def zigbeeOffset = (offsetVal * 10) as int

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_THERMOSTAT, ATTR_TEMP_CALIBRATION, 0x28, zigbeeOffset)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_TEMP_CALIBRATION)

    sendZigbeeCommands(cmds)
}

def setValvePosition(position) {
    Integer positionInt = position as Integer
    positionInt = [[positionInt, 100].min(), 0].max()
    logInfo "Setting valve position to ${positionInt}%"

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_VALVE_OPENING, 0x20, positionInt)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_OPENING)

    sendZigbeeCommands(cmds)
}

def setValveClosingDegree(degree) {
    Integer degreeInt = degree as Integer
    degreeInt = [[degreeInt, 100].min(), 0].max()
    logInfo "Setting valve closing degree to ${degreeInt}%"

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_VALVE_CLOSING, 0x20, degreeInt)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_VALVE_CLOSING)

    sendZigbeeCommands(cmds)
}

def setMinHeatingSetpoint(temperature) {
    // Convert from Fahrenheit to Celsius if needed
    BigDecimal tempC = temperature as BigDecimal
    if (tempUnit == "F") {
        tempC = (temperature - 32) * 5 / 9
        tempC = ((tempC * 10).toLong()) / 10.0
    }

    tempC = constrainTemperature(tempC)
    logInfo "Setting min heating setpoint to ${tempC}°C"

    def zigbeeTemp = temperatureToZigbee(tempC)

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_THERMOSTAT, ATTR_MIN_HEAT_SETPOINT, 0x29, zigbeeTemp)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_MIN_HEAT_SETPOINT)

    sendZigbeeCommands(cmds)
}

def setMaxHeatingSetpoint(temperature) {
    // Convert from Fahrenheit to Celsius if needed
    BigDecimal tempC = temperature as BigDecimal
    if (tempUnit == "F") {
        tempC = (temperature - 32) * 5 / 9
        tempC = ((tempC * 10).toLong()) / 10.0
    }

    tempC = constrainTemperature(tempC)
    logInfo "Setting max heating setpoint to ${tempC}°C"

    def zigbeeTemp = temperatureToZigbee(tempC)

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_THERMOSTAT, ATTR_MAX_HEAT_SETPOINT, 0x29, zigbeeTemp)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_THERMOSTAT, ATTR_MAX_HEAT_SETPOINT)

    sendZigbeeCommands(cmds)
}

def setExternalTemperature(temperature) {
    // Convert from Fahrenheit to Celsius if needed
    BigDecimal tempC = temperature as BigDecimal
    if (tempUnit == "F") {
        tempC = (temperature - 32) * 5 / 9
        tempC = ((tempC * 10).toLong()) / 10.0
    }

    logInfo "Setting external temperature to ${tempC}°C"

    def zigbeeTemp = temperatureToZigbee(tempC)

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_EXTERNAL_TEMP, 0x29, zigbeeTemp)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_EXTERNAL_TEMP)

    sendZigbeeCommands(cmds)
}

def setExternalSensor(String sensor) {
    logInfo "Setting external sensor to ${sensor}"

    def sensorMap = ["internal": 0, "external": 1, "external_2": 2, "external_3": 3]
    def value = sensorMap[sensor] ?: 0

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_EXTERNAL_SENSOR, 0x20, value)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_EXTERNAL_SENSOR)

    sendZigbeeCommands(cmds)
}

def setSmartTemperatureControl(String enabled) {
    logInfo "Setting smart temperature control ${enabled}"
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_SMART_TEMP_CONTROL, 0x18, enabled == "on" ? 1 : 0)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_SMART_TEMP_CONTROL)
    sendZigbeeCommands(cmds)
}

// Boost: full heat for a limited time, then the valve returns to its normal setpoint (firmware 1.4+)
def boost(minutes) {
    startTemporaryMode(0, minutes, null)
}

// Timer: hold a different target temperature for a limited time (firmware 1.4+)
def timerMode(minutes, temperature) {
    startTemporaryMode(1, minutes, temperature)
}

// Same write order as zigbee-herdsman-converters: duration, (temperature), then the mode itself
private void startTemporaryMode(int mode, minutes, temperature) {
    int mins = Math.max(1, Math.min((minutes as BigDecimal).intValue(), mode == 0 ? 180 : 1440))
    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE_TIME, 0x23, mins * 60)
    cmds += "delay 300"
    if (mode == 1 && temperature != null) {
        BigDecimal t = [[temperature as BigDecimal, 35.0].min(), 4.0].max()
        cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE_TEMP, 0x29, (t * 100) as int)
        cmds += "delay 300"
    }
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE, 0x20, mode)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE)
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMPORARY_MODE_TIME)
    logInfo "Starting ${mode == 0 ? 'boost' : 'timer'} mode for ${mins} min" + (mode == 1 ? " at ${temperature}°C" : "")
    sendZigbeeCommands(cmds)
}

def setTemperatureAccuracy(accuracy) {
    BigDecimal accuracyVal = accuracy as BigDecimal
    accuracyVal = [[accuracyVal, -0.2].min(), -1.0].max()
    logInfo "Setting temperature accuracy to ${accuracyVal}°C"

    // Accuracy is an Int16 with 0.01°C resolution (negative values, -1.00 .. -0.20)
    def zigbeeAccuracy = (accuracyVal * 100) as int

    def cmds = []
    cmds += zigbee.writeAttribute(CLUSTER_FC11, ATTR_TEMP_ACCURACY, 0x29, zigbeeAccuracy)
    cmds += "delay 500"
    cmds += zigbee.readAttribute(CLUSTER_FC11, ATTR_TEMP_ACCURACY)

    sendZigbeeCommands(cmds)
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parse: $description"

    // Update activity timestamp
    def now = new Date()
    sendEvent(name: "lastActivity", value: now.format("yyyy-MM-dd HH:mm:ss"))
    state.lastSuccessfulComm = now.getTime()

    // Reset failed command counter on successful communication
    state.failedCommands = 0

    // Update health status
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
    }

    def events = []

    try {
        if (description.startsWith("read attr -")) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            logDebug "Read attr: $descMap"
            events += handleAttribute(descMap)
        }
        else if (description.startsWith("catchall:")) {
            def descMap = zigbee.parseDescriptionAsMap(description)
            logDebug "Catchall: $descMap"
            events += handleCatchall(descMap)
        }
        else {
            logDebug "Unknown message format: $description"
        }
    } catch (e) {
        logWarn "Parse error: ${e.message}"
        logDebug "Parse exception: ${e}"
    }

    return events
}

// ==================== Attribute Handlers ====================

private List handleAttribute(Map descMap) {
    def events = []
    def cluster = descMap.cluster?.toUpperCase()  // Normalize to uppercase
    def attrId = descMap.attrId?.toUpperCase()    // Normalize to uppercase
    def value = descMap.value
    def encoding = descMap.encoding

    if (!value || value == "null") {
        logDebug "Empty or null value for cluster ${cluster}, attr ${attrId}"
        return events
    }

    logDebug "Handling attribute - cluster: ${cluster}, attrId: ${attrId}, value: ${value}, encoding: ${encoding}"

    switch(cluster) {
        case "0001":  // Power Configuration
            events += handlePowerCluster(attrId, value)
            break

        case "0201":  // Thermostat
            events += handleThermostatCluster(attrId, value)
            break

        case "FC11":  // Sonoff Custom
            events += handleFC11Cluster(attrId, value)
            break

        case "0006":  // On/Off
            events += handleOnOffCluster(attrId, value)
            break

        default:
            logDebug "Unhandled cluster: ${cluster}"
    }

    return events
}

private List handlePowerCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0020":  // Battery Voltage
            def voltage = Integer.parseInt(value, 16) / 10.0
            logDebug "Battery voltage: ${voltage}V"
            break

        case "0021":  // Battery Percentage
            Integer rawBattery = Integer.parseInt(value, 16)
            // SONOFF reports 0-200 (0.5% increments)
            Integer battery = (rawBattery / 2).toInteger()
            battery = [[battery, 100].min(), 0].max()
            events << createEvent(name: "battery", value: battery, unit: "%")
            logInfo "Battery: ${battery}%"
            break
    }

    return events
}

private List handleThermostatCluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // Local Temperature
            def temp = zigbeeToTemperature(value)
            temp = formatTemperature(temp)
            events << createEvent(name: "temperature", value: temp, unit: getTemperatureUnit())
            logInfo "Temperature: ${temp}${getTemperatureUnit()}"
            break

        case "0010":  // Temperature Calibration
            def calInt = hexToSignedInt(value)
            def cal = new BigDecimal(calInt).divide(new BigDecimal(10), 1, BigDecimal.ROUND_HALF_UP)
            events << createEvent(name: "temperatureCalibration", value: cal, unit: "°C")
            logInfo "Calibration: ${cal}°C"
            break

        case "0012":  // Occupied Heating Setpoint
            def setpoint = zigbeeToTemperature(value)
            setpoint = formatTemperature(setpoint)
            events << createEvent(name: "heatingSetpoint", value: setpoint, unit: getTemperatureUnit())
            events << createEvent(name: "thermostatSetpoint", value: setpoint, unit: getTemperatureUnit())
            logInfo "Setpoint: ${setpoint}${getTemperatureUnit()}"
            break

        case "0015":  // Min Heat Setpoint Limit
            def minSetpoint = zigbeeToTemperature(value)
            minSetpoint = formatTemperature(minSetpoint)
            events << createEvent(name: "minHeatingSetpoint", value: minSetpoint, unit: "°C")
            logDebug "Min setpoint: ${minSetpoint}°C"
            break

        case "0016":  // Max Heat Setpoint Limit
            def maxSetpoint = zigbeeToTemperature(value)
            maxSetpoint = formatTemperature(maxSetpoint)
            events << createEvent(name: "maxHeatingSetpoint", value: maxSetpoint, unit: "°C")
            logDebug "Max setpoint: ${maxSetpoint}°C"
            break

        case "001C":  // System Mode
            def modeInt = Integer.parseInt(value, 16)
            def mode = SYSTEM_MODES[modeInt] ?: "off"
            events << createEvent(name: "thermostatMode", value: mode)
            logInfo "Mode: ${mode}"
            break

        case "0029":  // Thermostat Running State
            def stateInt = Integer.parseInt(value, 16)
            def opState = (stateInt & 0x01) ? "heating" : "idle"
            events << createEvent(name: "thermostatOperatingState", value: opState)
            logInfo "Operating state: ${opState}"
            break
    }

    return events
}

private List handleFC11Cluster(String attrId, String value) {
    def events = []

    switch(attrId) {
        case "0000":  // Child Lock
            def locked = Integer.parseInt(value, 16) == 1
            events << createEvent(name: "childLock", value: locked ? "on" : "off")
            logInfo "Child lock: ${locked ? 'on' : 'off'}"
            break

        case "6000":  // Window Open Detection
            def enabled = Integer.parseInt(value, 16) == 1
            events << createEvent(name: "windowDetection", value: enabled ? "on" : "off")
            logInfo "Window detection: ${enabled ? 'on' : 'off'}"
            break

        case "6001":  // Window Open Status
            def open = Integer.parseInt(value, 16) == 1
            events << createEvent(name: "windowOpen", value: open ? "open" : "closed")
            logInfo "Window: ${open ? 'open' : 'closed'}"
            break

        case "6002":  // Frost Protection Temperature
            def frost = zigbeeToTemperature(value)
            frost = formatTemperature(frost)
            events << createEvent(name: "frostProtection", value: frost, unit: "°C")
            logInfo "Frost protection: ${frost}°C"
            break

        case "6003":  // Idle Steps
            def steps = Integer.parseInt(value, 16)
            events << createEvent(name: "idleSteps", value: steps)
            logDebug "Idle steps: ${steps}"
            break

        case "6004":  // Closing Steps
            def steps = Integer.parseInt(value, 16)
            events << createEvent(name: "closingSteps", value: steps)
            logDebug "Closing steps: ${steps}"
            break

        case "6007":  // Valve Motor Running Voltage
            def voltage = Integer.parseInt(value, 16)
            logDebug "Valve motor voltage: ${voltage}mV"
            break

        case "600B":  // Valve Opening Degree
            def pos = Integer.parseInt(value, 16)
            events << createEvent(name: "valvePosition", value: pos, unit: "%")
            logInfo "Valve position: ${pos}%"
            break

        case "600C":  // Valve Closing Degree
            def degree = Integer.parseInt(value, 16)
            events << createEvent(name: "valveClosingDegree", value: degree, unit: "%")
            logDebug "Valve closing degree: ${degree}%"
            break

        case "600D":  // External Temperature
            def extTemp = zigbeeToTemperature(value)
            extTemp = formatTemperature(extTemp)
            events << createEvent(name: "externalTemperature", value: extTemp, unit: "°C")
            logDebug "External temperature: ${extTemp}°C"
            break

        case "600E":  // External Sensor Selection
            def sensorInt = Integer.parseInt(value, 16)
            def sensor = SENSOR_TYPES[sensorInt] ?: "internal"
            events << createEvent(name: "externalSensor", value: sensor)
            logDebug "External sensor: ${sensor}"
            break

        case "6011":  // Temperature Accuracy (INT16, 0.01 °C)
            def accuracy = formatTemperature(zigbeeToTemperature(value))
            events << createEvent(name: "temperatureAccuracy", value: accuracy, unit: "°C")
            logDebug "Temperature accuracy: ${accuracy}°C"
            break

        case "6014":  // Temporary mode (boost / timer)
            def tm = Integer.parseInt(value, 16) == 0 ? "boost" : "timer"
            events << createEvent(name: "temporaryMode", value: tm)
            logDebug "Temporary mode: ${tm}"
            break

        case "6015":  // Temporary mode duration, seconds
            def minutes = Math.round(Long.parseLong(value, 16) / 60.0) as int
            events << createEvent(name: "temporaryModeMinutes", value: minutes, unit: "min")
            logDebug "Temporary mode time: ${minutes} min"
            break

        case "6016":  // Temporary mode target temperature
            def tmTemp = formatTemperature(zigbeeToTemperature(value))
            events << createEvent(name: "temporaryModeTemperature", value: tmTemp, unit: "°C")
            logDebug "Temporary mode temperature: ${tmTemp}°C"
            break

        case "6017":  // Smart temperature control
            def smart = (Integer.parseInt(value, 16) & 0x01) ? "on" : "off"
            events << createEvent(name: "smartTemperatureControl", value: smart)
            logDebug "Smart temperature control: ${smart}"
            break

        default:
            logDebug "Unknown FC11 attribute: ${attrId} = ${value}"
    }

    return events
}

private List handleOnOffCluster(String attrId, String value) {
    def events = []

    if (attrId == "0000") {
        def onOff = Integer.parseInt(value, 16) == 1
        logDebug "On/Off state: ${onOff ? 'on' : 'off'}"
    }

    return events
}

private List handleCatchall(Map descMap) {
    def events = []

    // Handle attribute reports (command 0x0A or 0x01)
    if (descMap.command in ["0A", "01"] && descMap.data?.size() >= 4) {
        events += parseReportAttributes(descMap.clusterId, descMap.data)
    }
    // Handle weekly schedule response (command 0x00 on thermostat cluster)
    else if (descMap.clusterId == "0201" && descMap.command == "00" && descMap.data?.size() > 5) {
        logDebug "Weekly schedule data received"
        // Schedule data parsing could be added here
    }

    return events
}

private List parseReportAttributes(String clusterId, List data) {
    def events = []
    def idx = 0

    while (idx + 3 <= data.size()) {
        try {
            // Attribute ID is little-endian
            def attrId = data[idx + 1] + data[idx]
            def dataType = data[idx + 2]
            idx += 3

            // Get value based on data type
            def value = ""
            def valueLen = getDataTypeLength(dataType)

            if (valueLen > 0 && idx + valueLen <= data.size()) {
                // Little-endian value
                for (int i = valueLen - 1; i >= 0; i--) {
                    value += data[idx + i]
                }
                idx += valueLen
            } else if (dataType == "42" && idx < data.size()) {
                // String type
                def strLen = Integer.parseInt(data[idx], 16)
                idx++
                def strValue = ""
                for (int i = 0; i < strLen && idx < data.size(); i++) {
                    def charCode = Integer.parseInt(data[idx], 16)
                    if (charCode >= 32 && charCode < 127) {
                        strValue += (char)charCode
                    }
                    idx++
                }
                value = strValue
            } else {
                // Unknown type, try to get single byte
                if (idx < data.size()) {
                    value = data[idx]
                    idx++
                }
            }

            if (value) {
                logDebug "Report: cluster=${clusterId}, attrId=${attrId}, type=${dataType}, value=${value}"

                // Process the attribute
                def fakeDescMap = [cluster: clusterId, attrId: attrId, value: value, encoding: dataType]
                events += handleAttribute(fakeDescMap)
            }
        } catch (e) {
            logWarn "Error parsing report attribute: ${e.message}"
            break
        }
    }

    return events
}

private int getDataTypeLength(String dataType) {
    switch(dataType) {
        case "10": return 1  // Boolean
        case "18": return 1  // 8-bit Bitmap
        case "19": return 2  // 16-bit Bitmap
        case "20": return 1  // Uint8
        case "21": return 2  // Uint16
        case "22": return 3  // Uint24
        case "23": return 4  // Uint32
        case "28": return 1  // Int8
        case "29": return 2  // Int16
        case "2A": return 3  // Int24
        case "2B": return 4  // Int32
        case "30": return 1  // Enum8
        case "31": return 2  // Enum16
        case "39": return 4  // Float
        default: return 0
    }
}

// ==================== Command Sending ====================

/**
 * Send Zigbee commands to the device
 * Flattens nested lists and sends commands with delays
 */
void sendZigbeeCommands(def cmds) {
    if (cmds == null) {
        logWarn "sendZigbeeCommands: null input"
        return
    }

    logDebug "sendZigbeeCommands called with: ${cmds}"

    // Handle single command (not a list)
    List cmdList
    if (cmds instanceof List) {
        cmdList = cmds.flatten()
    } else {
        cmdList = [cmds]
    }

    logDebug "After processing: ${cmdList.size()} items"

    if (cmdList.isEmpty()) {
        logWarn "sendZigbeeCommands: empty list"
        return
    }

    // Filter out null, empty, and delay strings - keep only actual commands
    def filteredCmds = cmdList.findAll { cmd ->
        cmd != null && cmd != "" && !cmd.toString().trim().startsWith("delay")
    }

    if (filteredCmds.isEmpty()) {
        logWarn "sendZigbeeCommands: no valid commands after filtering"
        return
    }

    logInfo "Sending ${filteredCmds.size()} Zigbee command(s)..."
    filteredCmds.eachWithIndex { cmd, idx ->
        logDebug "  [${idx}] ${cmd}"
        def hubAction = new hubitat.device.HubAction(cmd.toString(), hubitat.device.Protocol.ZIGBEE)
        sendHubCommand(hubAction)
    }
    logDebug "All commands sent"
}

// ==================== Helper Methods ====================

private BigDecimal constrainTemperature(temp) {
    BigDecimal tempVal = temp as BigDecimal
    return [[tempVal, TEMP_MAX].min(), TEMP_MIN].max()
}

/**
 * Convert temperature from Fahrenheit to Celsius if user preference is Fahrenheit
 */
private BigDecimal convertToDeviceTemp(temperature) {
    BigDecimal tempVal = temperature as BigDecimal
    if (tempUnit == "F") {
        BigDecimal tempC = (tempVal - 32) * 5 / 9
        return ((tempC * 10).toLong()) / 10.0
    }
    return tempVal
}

private int temperatureToZigbee(BigDecimal temp) {
    return (temp * 100) as int
}

private BigDecimal zigbeeToTemperature(String hexValue) {
    def intVal = hexToSignedInt(hexValue)
    // Use BigDecimal to avoid scientific notation
    return new BigDecimal(intVal).divide(new BigDecimal(100), 1, BigDecimal.ROUND_HALF_UP)
}

private int hexToSignedInt(String hex) {
    if (!hex) return 0
    def value = Integer.parseInt(hex, 16)
    // Check if it's a signed 16-bit value
    if (hex.length() == 4 && value > 32767) {
        value -= 65536
    }
    // Check if it's a signed 8-bit value
    else if (hex.length() == 2 && value > 127) {
        value -= 256
    }
    return value
}

private BigDecimal formatTemperature(temp) {
    // Round to 1 decimal place and avoid scientific notation
    BigDecimal tempVal = temp as BigDecimal
    tempVal = new BigDecimal(((tempVal * 10).toLong())).divide(new BigDecimal(10), 1, BigDecimal.ROUND_HALF_UP)
    if (tempUnit == "F") {
        tempVal = tempVal.multiply(new BigDecimal(9)).divide(new BigDecimal(5), 1, BigDecimal.ROUND_HALF_UP).add(new BigDecimal(32))
    }
    return tempVal
}

private String getTemperatureUnit() {
    return (tempUnit == "F") ? "°F" : "°C"
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
