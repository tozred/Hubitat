/**
 *  Virtual Master Thermostat
 *
 *  A virtual thermostat device for controlling multiple TRVs through the
 *  Master Thermostat Controller app. Can be added to dashboards and
 *  controlled via voice assistants (Alexa, Google Home).
 *
 *  Version: 1.0.0
 *
 *  This driver works in conjunction with:
 *  - Master Thermostat Controller (Parent App)
 *  - Room Zone (Child App)
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.1.0"

metadata {
    definition(
        name: "Virtual Master Thermostat",
        namespace: "benberlin",
        author: "Ben Fayershtain",
        importUrl: ""
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatSetpoint"
        capability "ThermostatMode"
        capability "ThermostatOperatingState"
        capability "TemperatureMeasurement"
        capability "Refresh"

        // Custom attributes
        attribute "masterSetpoint", "number"
        attribute "activeSchedule", "string"
        attribute "activeScheduleTemp", "number"
        attribute "overrideActive", "enum", ["true", "false"]
        attribute "overrideRooms", "string"
        attribute "roomCount", "number"
        attribute "heatingRooms", "number"
        attribute "windowsOpen", "number"
        attribute "childLock", "enum", ["on", "off"]
        attribute "driverVersion", "string"
        attribute "lastUpdate", "string"
        attribute "temperatureRange", "string"
        attribute "temperatureLow", "number"
        attribute "temperatureHigh", "number"

        // Commands
        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "Master setpoint (4-35°C)"]]
        command "setThermostatMode", [[name: "mode*", type: "ENUM", constraints: ["off", "heat", "auto"]]]
        command "setActiveSchedule", [[name: "scheduleName*", type: "STRING", description: "Current schedule slot name"]]
        command "updateFromApp", [
            [name: "temperature", type: "NUMBER", description: "Average room temperature"],
            [name: "setpoint", type: "NUMBER", description: "Current setpoint"],
            [name: "mode", type: "STRING", description: "Current mode"],
            [name: "operatingState", type: "STRING", description: "Operating state"],
            [name: "scheduleName", type: "STRING", description: "Active schedule"],
            [name: "roomCount", type: "NUMBER", description: "Total rooms"],
            [name: "heatingRooms", type: "NUMBER", description: "Rooms currently heating"],
            [name: "windowsOpen", type: "NUMBER", description: "Windows detected open"]
        ]
        command "setOverrideStatus", [
            [name: "active*", type: "ENUM", constraints: ["true", "false"], description: "Override active"],
            [name: "rooms", type: "STRING", description: "Comma-separated room names with overrides"]
        ]
        command "setTemperatureRange", [
            [name: "low*", type: "NUMBER", description: "Lowest temperature reading"],
            [name: "high*", type: "NUMBER", description: "Highest temperature reading"]
        ]
        command "refresh"

        // Standard thermostat commands (for compatibility)
        command "heat"
        command "off"
        command "auto"

        // Child lock command
        command "setChildLock", [[name: "enabled*", type: "ENUM", constraints: ["on", "off"], description: "Lock/unlock all TRVs"]]
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Virtual Master Thermostat installed"
    initialize()
}

def updated() {
    log.info "Virtual Master Thermostat updated"
    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h
}

def initialize() {
    sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "auto"])
    sendEvent(name: "supportedThermostatFanModes", value: [])
    sendEvent(name: "thermostatFanMode", value: "auto")
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)

    // Set defaults if not already set
    if (device.currentValue("thermostatMode") == null) {
        sendEvent(name: "thermostatMode", value: "heat")
    }
    if (device.currentValue("heatingSetpoint") == null) {
        sendEvent(name: "heatingSetpoint", value: 20, unit: "°C")
        sendEvent(name: "thermostatSetpoint", value: 20, unit: "°C")
        sendEvent(name: "masterSetpoint", value: 20, unit: "°C")
    }
    if (device.currentValue("temperature") == null) {
        sendEvent(name: "temperature", value: 20, unit: "°C")
    }
    if (device.currentValue("thermostatOperatingState") == null) {
        sendEvent(name: "thermostatOperatingState", value: "idle")
    }
    if (device.currentValue("overrideActive") == null) {
        sendEvent(name: "overrideActive", value: "false")
    }
    if (device.currentValue("activeSchedule") == null) {
        sendEvent(name: "activeSchedule", value: "Manual")
    }

    sendEvent(name: "roomCount", value: 0)
    sendEvent(name: "heatingRooms", value: 0)
    sendEvent(name: "windowsOpen", value: 0)

    if (device.currentValue("childLock") == null) {
        sendEvent(name: "childLock", value: "off")
    }

    if (device.currentValue("temperatureRange") == null) {
        sendEvent(name: "temperatureRange", value: "-- - --")
        sendEvent(name: "temperatureLow", value: null)
        sendEvent(name: "temperatureHigh", value: null)
    }
}

// ==================== Thermostat Commands ====================

def setHeatingSetpoint(BigDecimal temperature) {
    temperature = constrainTemp(temperature)
    logInfo "Setting master heating setpoint to ${temperature}°C"

    sendEvent(name: "heatingSetpoint", value: temperature, unit: "°C")
    sendEvent(name: "thermostatSetpoint", value: temperature, unit: "°C")
    sendEvent(name: "masterSetpoint", value: temperature, unit: "°C")
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Notify parent app (if exists)
    notifyParentApp("setpointChanged", temperature)
}

def setThermostatMode(String mode) {
    if (!(mode in ["off", "heat", "auto"])) {
        logWarn "Invalid mode: ${mode}, using heat"
        mode = "heat"
    }

    logInfo "Setting thermostat mode to ${mode}"
    sendEvent(name: "thermostatMode", value: mode)
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Update operating state based on mode
    if (mode == "off") {
        sendEvent(name: "thermostatOperatingState", value: "idle")
    }

    // Notify parent app
    notifyParentApp("modeChanged", mode)
}

def heat() {
    setThermostatMode("heat")
}

def off() {
    setThermostatMode("off")
}

def auto() {
    setThermostatMode("auto")
}

def refresh() {
    logDebug "Refresh requested"
    notifyParentApp("refresh", null)
}

// ==================== Custom Commands ====================

def setActiveSchedule(String scheduleName) {
    logInfo "Active schedule: ${scheduleName}"
    sendEvent(name: "activeSchedule", value: scheduleName)
}

def updateFromApp(BigDecimal temperature, BigDecimal setpoint, String mode,
                  String operatingState, String scheduleName,
                  Integer roomCount, Integer heatingRooms, Integer windowsOpen) {
    logDebug "Update from app: temp=${temperature}, setpoint=${setpoint}, mode=${mode}"

    if (temperature != null) {
        sendEvent(name: "temperature", value: temperature, unit: "°C")
    }
    if (setpoint != null) {
        sendEvent(name: "heatingSetpoint", value: setpoint, unit: "°C")
        sendEvent(name: "thermostatSetpoint", value: setpoint, unit: "°C")
        sendEvent(name: "masterSetpoint", value: setpoint, unit: "°C")
    }
    if (mode != null) {
        sendEvent(name: "thermostatMode", value: mode)
    }
    if (operatingState != null) {
        sendEvent(name: "thermostatOperatingState", value: operatingState)
    }
    if (scheduleName != null) {
        sendEvent(name: "activeSchedule", value: scheduleName)
    }
    if (roomCount != null) {
        sendEvent(name: "roomCount", value: roomCount)
    }
    if (heatingRooms != null) {
        sendEvent(name: "heatingRooms", value: heatingRooms)
    }
    if (windowsOpen != null) {
        sendEvent(name: "windowsOpen", value: windowsOpen)
    }

    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

def setOverrideStatus(String active, String rooms = "") {
    sendEvent(name: "overrideActive", value: active)
    sendEvent(name: "overrideRooms", value: rooms ?: "None")
}

def setTemperatureRange(BigDecimal low, BigDecimal high) {
    logDebug "Setting temperature range: ${low} - ${high}°C"

    // Round to 1 decimal place for display
    low = Math.round(low * 10) / 10.0
    high = Math.round(high * 10) / 10.0

    // Create display string (e.g., "7 - 22")
    def rangeStr = "${low.toInteger()} - ${high.toInteger()}"

    sendEvent(name: "temperatureLow", value: low, unit: "°C")
    sendEvent(name: "temperatureHigh", value: high, unit: "°C")
    sendEvent(name: "temperatureRange", value: rangeStr)
}

def setChildLock(String enabled) {
    logInfo "Setting child lock to ${enabled} for all TRVs"
    sendEvent(name: "childLock", value: enabled)
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    // Notify parent app
    notifyParentApp("childLockChanged", enabled)
}

def setChildLockStatus(String enabled) {
    // Called by parent app to update status without triggering another notification
    sendEvent(name: "childLock", value: enabled)
}

// ==================== Unsupported Commands ====================

def setCoolingSetpoint(temperature) {
    logWarn "Cooling setpoint not supported (heating-only system)"
}

def cool() {
    logWarn "Cool mode not supported (heating-only system)"
}

def emergencyHeat() {
    logInfo "Emergency heat - switching to heat mode"
    heat()
}

def fanAuto() { logDebug "Fan control not applicable" }
def fanCirculate() { logDebug "Fan control not applicable" }
def fanOn() { logDebug "Fan control not applicable" }
def setThermostatFanMode(mode) { logDebug "Fan control not applicable" }
def setSchedule(schedule) { logDebug "setSchedule not implemented" }

// ==================== Helper Methods ====================

private BigDecimal constrainTemp(BigDecimal temp) {
    return Math.max(4.0, Math.min(35.0, temp))
}

private void notifyParentApp(String eventType, def value) {
    // Find the parent app and notify it
    def parentApp = getParent()
    if (parentApp) {
        try {
            parentApp.virtualThermostatEvent(eventType, value)
        } catch (e) {
            logDebug "Could not notify parent app: ${e.message}"
        }
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
