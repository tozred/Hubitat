/**
 *  Master Thermostat Controller - Parent App
 *
 *  Unified heating control for multiple TRVs with:
 *  - Master virtual thermostat for dashboard/voice control
 *  - Per-room temperature offsets via child apps
 *  - Weekday/Weekend scheduling with multiple time slots
 *  - Manual override with auto-revert on schedule change
 *  - Window detection integration
 *
 *  Version: 1.0.0
 *
 *  Install Order:
 *  1. Install "Virtual Master Thermostat" driver
 *  2. Install this parent app
 *  3. Install "Room Zone" child app
 *  4. Add app instance and configure
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "1.1.0"

definition(
    name: "Master Thermostat Controller",
    namespace: "benberlin",
    author: "Ben Fayershtain",
    description: "Unified heating control for multiple TRVs with scheduling and room zones",
    category: "Green Living",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "Master Thermostat Controller", install: true, uninstall: true) {
        section() {
            paragraph "<b>Master Thermostat Controller v${APP_VERSION}</b>"
            paragraph "Control all your TRVs from one place with scheduling and per-room offsets."
        }

        section("<b>Virtual Thermostat</b>") {
            input "virtualThermostat", "device.VirtualMasterThermostat",
                  title: "Select Virtual Master Thermostat",
                  description: "Select an existing device or create one below",
                  required: false,
                  submitOnChange: true

            if (!virtualThermostat) {
                input "createVirtual", "button", title: "Create Virtual Thermostat"
            }
        }

        section("<b>Default Settings</b>") {
            input "defaultSetpoint", "decimal",
                  title: "Default Temperature (°C)",
                  description: "Default heating setpoint",
                  defaultValue: 20,
                  range: "4..35",
                  required: true

            input "tempUnit", "enum",
                  title: "Temperature Unit",
                  options: ["C": "Celsius", "F": "Fahrenheit"],
                  defaultValue: "C",
                  required: true
        }

        section("<b>Temperature Sensors</b>") {
            input "tempSensors", "capability.temperatureMeasurement",
                  title: "Temperature/Humidity Sensors",
                  description: "Select sensors to monitor house-wide temperature range",
                  multiple: true,
                  required: false

            paragraph "<i>Shows lowest and highest readings across all selected sensors (e.g., \"7 - 22\")</i>"
        }

        section("<b>Weekday Schedule (Mon-Fri)</b>") {
            paragraph "Add up to 6 time slots for weekdays"
            (1..6).each { i ->
                input "wdTime${i}", "time", title: "Slot ${i} - Time", required: false, submitOnChange: true, width: 4
                input "wdTemp${i}", "decimal", title: "Temperature", range: "4..35", required: false, width: 4
                input "wdName${i}", "text", title: "Label", required: false, width: 4
            }
        }

        section("<b>Weekend Schedule (Sat-Sun)</b>") {
            input "sameAsWeekday", "bool",
                  title: "Use same schedule as weekday",
                  defaultValue: false,
                  submitOnChange: true

            if (!sameAsWeekday) {
                paragraph "Add up to 6 time slots for weekends"
                (1..6).each { i ->
                    input "weTime${i}", "time", title: "Slot ${i} - Time", required: false, submitOnChange: true, width: 4
                    input "weTemp${i}", "decimal", title: "Temperature", range: "4..35", required: false, width: 4
                    input "weName${i}", "text", title: "Label", required: false, width: 4
                }
            }
        }

        section("<b>Room Zones</b>") {
            app(name: "roomZones", appName: "Room Zone", namespace: "benberlin", title: "Add Room Zone", multiple: true)
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
        }
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "Master Thermostat Controller installed"
    initialize()
}

def updated() {
    log.info "Master Thermostat Controller updated"
    unsubscribe()
    unschedule()
    initialize()
}

def uninstalled() {
    log.info "Master Thermostat Controller uninstalled"
    // Remove virtual device if we created it
    if (state.createdVirtualDevice) {
        deleteChildDevice(state.virtualDeviceId)
    }
}

def initialize() {
    logDebug "Initializing..."

    // Initialize state
    state.masterSetpoint = settings.defaultSetpoint ?: 20
    state.currentMode = "heat"
    state.currentScheduleSlot = "Manual"
    state.lastScheduleChange = now()
    state.isScheduleChange = false

    // Subscribe to virtual thermostat
    if (virtualThermostat) {
        subscribe(virtualThermostat, "heatingSetpoint", virtualSetpointHandler)
        subscribe(virtualThermostat, "thermostatMode", virtualModeHandler)

        // Set initial values on virtual device
        virtualThermostat.updateFromApp(
            getAverageTemperature(),
            state.masterSetpoint,
            state.currentMode,
            getOverallOperatingState(),
            state.currentScheduleSlot,
            getChildApps().size(),
            getHeatingRoomCount(),
            getOpenWindowCount()
        )
    }

    // Subscribe to temperature sensors
    if (tempSensors) {
        subscribe(tempSensors, "temperature", tempSensorHandler)
        // Update temperature range immediately
        updateTemperatureRange()
    }

    // Set up schedules
    initializeSchedule()

    // Apply current schedule immediately
    runIn(5, applyCurrentSchedule)

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h
}

def appButtonHandler(String buttonName) {
    if (buttonName == "createVirtual") {
        createVirtualThermostat()
    }
}

// ==================== Virtual Device Management ====================

def createVirtualThermostat() {
    logInfo "Creating Virtual Master Thermostat device"

    try {
        def deviceId = "virtual-master-thermostat-${app.id}"
        def deviceLabel = "Master Thermostat"

        def existingDevice = getChildDevice(deviceId)
        if (existingDevice) {
            log.warn "Virtual device already exists"
            return
        }

        def newDevice = addChildDevice(
            "benberlin",
            "Virtual Master Thermostat",
            deviceId,
            null,
            [
                name: deviceLabel,
                label: deviceLabel,
                isComponent: false
            ]
        )

        state.createdVirtualDevice = true
        state.virtualDeviceId = deviceId

        logInfo "Created virtual thermostat: ${newDevice.displayName}"

        // Update the setting to point to the new device
        app.updateSetting("virtualThermostat", [type: "device.VirtualMasterThermostat", value: deviceId])

    } catch (e) {
        log.error "Failed to create virtual thermostat: ${e.message}"
    }
}

// ==================== Schedule Management ====================

def initializeSchedule() {
    logDebug "Initializing schedules..."
    unschedule()

    def weekdaySlots = getWeekdaySlots()
    def weekendSlots = getWeekendSlots()

    logDebug "Weekday slots: ${weekdaySlots}"
    logDebug "Weekend slots: ${weekendSlots}"

    // Schedule weekday slots
    weekdaySlots.each { slot ->
        if (slot.time && slot.temp != null) {
            def timeParts = parseTimeString(slot.time)
            if (timeParts) {
                def cronExpr = "0 ${timeParts.minute} ${timeParts.hour} ? * MON-FRI"
                logDebug "Scheduling weekday: ${cronExpr} -> ${slot.temp}°C (${slot.name})"
                schedule(cronExpr, "scheduleHandler", [data: [temp: slot.temp, name: slot.name ?: "Weekday"]])
            }
        }
    }

    // Schedule weekend slots
    weekendSlots.each { slot ->
        if (slot.time && slot.temp != null) {
            def timeParts = parseTimeString(slot.time)
            if (timeParts) {
                def cronExpr = "0 ${timeParts.minute} ${timeParts.hour} ? * SAT,SUN"
                logDebug "Scheduling weekend: ${cronExpr} -> ${slot.temp}°C (${slot.name})"
                schedule(cronExpr, "scheduleHandler", [data: [temp: slot.temp, name: slot.name ?: "Weekend"]])
            }
        }
    }

    // Schedule periodic status update
    runEvery10Minutes(updateStatus)
}

def getWeekdaySlots() {
    def slots = []
    (1..6).each { i ->
        def time = settings["wdTime${i}"]
        def temp = settings["wdTemp${i}"]
        def name = settings["wdName${i}"]
        if (time && temp != null) {
            slots << [time: time, temp: temp, name: name ?: "Slot ${i}"]
        }
    }
    return slots.sort { parseTimeString(it.time)?.totalMinutes ?: 0 }
}

def getWeekendSlots() {
    if (settings.sameAsWeekday) {
        return getWeekdaySlots()
    }

    def slots = []
    (1..6).each { i ->
        def time = settings["weTime${i}"]
        def temp = settings["weTemp${i}"]
        def name = settings["weName${i}"]
        if (time && temp != null) {
            slots << [time: time, temp: temp, name: name ?: "Slot ${i}"]
        }
    }
    return slots.sort { parseTimeString(it.time)?.totalMinutes ?: 0 }
}

def parseTimeString(String timeStr) {
    if (!timeStr) return null

    try {
        // Time inputs come as "yyyy-MM-dd'T'HH:mm:ss.sssXX"
        def date = toDateTime(timeStr)
        def hour = date.format("HH") as int
        def minute = date.format("mm") as int
        return [hour: hour, minute: minute, totalMinutes: hour * 60 + minute]
    } catch (e) {
        logWarn "Could not parse time: ${timeStr}"
        return null
    }
}

def scheduleHandler(data) {
    logInfo "Schedule triggered: ${data?.name} -> ${data?.temp}°C"

    state.isScheduleChange = true
    state.lastScheduleChange = now()
    state.currentScheduleSlot = data?.name ?: "Scheduled"
    state.masterSetpoint = data?.temp ?: settings.defaultSetpoint

    // Clear all overrides on schedule change
    clearAllOverrides()

    // Update virtual thermostat
    if (virtualThermostat) {
        virtualThermostat.setHeatingSetpoint(state.masterSetpoint)
        virtualThermostat.setActiveSchedule(state.currentScheduleSlot)
    }

    // Distribute to all rooms
    distributeToAllRooms(state.masterSetpoint)

    // Reset flag after a short delay
    runIn(10, clearScheduleChangeFlag)
}

def clearScheduleChangeFlag() {
    state.isScheduleChange = false
}

def applyCurrentSchedule() {
    logDebug "Applying current schedule slot..."

    def now = new Date()
    def isWeekend = now.format("EEEE") in ["Saturday", "Sunday"]
    def currentMinutes = (now.format("HH") as int) * 60 + (now.format("mm") as int)

    def slots = isWeekend ? getWeekendSlots() : getWeekdaySlots()

    if (slots.isEmpty()) {
        logDebug "No schedule slots configured, using default"
        state.currentScheduleSlot = "Default"
        state.masterSetpoint = settings.defaultSetpoint ?: 20
    } else {
        // Find the most recent slot that has passed
        def activeSlot = null
        slots.each { slot ->
            def slotMinutes = parseTimeString(slot.time)?.totalMinutes ?: 0
            if (slotMinutes <= currentMinutes) {
                activeSlot = slot
            }
        }

        if (activeSlot) {
            state.currentScheduleSlot = activeSlot.name
            state.masterSetpoint = activeSlot.temp
            logInfo "Current schedule: ${activeSlot.name} -> ${activeSlot.temp}°C"
        } else {
            // Before first slot, use last slot from previous day (wrap around)
            def lastSlot = slots.last()
            state.currentScheduleSlot = lastSlot.name + " (continued)"
            state.masterSetpoint = lastSlot.temp
            logInfo "Before first slot, using: ${lastSlot.name} -> ${lastSlot.temp}°C"
        }
    }

    // Update virtual thermostat
    if (virtualThermostat) {
        virtualThermostat.updateFromApp(
            getAverageTemperature(),
            state.masterSetpoint,
            state.currentMode,
            getOverallOperatingState(),
            state.currentScheduleSlot,
            getChildApps().size(),
            getHeatingRoomCount(),
            getOpenWindowCount()
        )
    }

    // Distribute to rooms
    distributeToAllRooms(state.masterSetpoint)
}

// ==================== Event Handlers ====================

def virtualSetpointHandler(evt) {
    def newSetpoint = evt.value as BigDecimal
    logInfo "Virtual thermostat setpoint changed to ${newSetpoint}°C"

    state.masterSetpoint = newSetpoint
    state.currentScheduleSlot = "Manual"

    // Distribute to all rooms
    distributeToAllRooms(newSetpoint)
}

def virtualModeHandler(evt) {
    def newMode = evt.value
    logInfo "Virtual thermostat mode changed to ${newMode}"

    state.currentMode = newMode

    // Notify all child apps
    getChildApps().each { child ->
        child.setMode(newMode)
    }
}

def tempSensorHandler(evt) {
    logDebug "Temperature sensor ${evt.device.displayName}: ${evt.value}°C"
    // Debounce temperature range updates
    runIn(3, updateTemperatureRange)
}

// Called by virtual device
def virtualThermostatEvent(String eventType, def value) {
    logDebug "Virtual thermostat event: ${eventType} = ${value}"

    switch (eventType) {
        case "setpointChanged":
            state.masterSetpoint = value
            state.currentScheduleSlot = "Manual"
            distributeToAllRooms(value)
            break
        case "modeChanged":
            state.currentMode = value
            getChildApps().each { child -> child.setMode(value) }
            break
        case "childLockChanged":
            setAllChildLocks(value)
            break
        case "refresh":
            updateStatus()
            break
    }
}

// ==================== Child Lock Control ====================

def setAllChildLocks(String enabled) {
    logInfo "Setting child lock to ${enabled} for all rooms"
    state.childLockEnabled = (enabled == "on")

    getChildApps().each { child ->
        try {
            child.setChildLock(enabled)
        } catch (e) {
            logWarn "Failed to set child lock for ${child.label}: ${e.message}"
        }
    }

    if (virtualThermostat) {
        virtualThermostat.setChildLockStatus(enabled)
    }
}

// ==================== Room Distribution ====================

def distributeToAllRooms(BigDecimal masterTemp) {
    logDebug "Distributing master setpoint ${masterTemp}°C to all rooms"

    getChildApps().each { child ->
        try {
            child.applyMasterSetpoint(masterTemp)
        } catch (e) {
            logWarn "Failed to update room ${child.label}: ${e.message}"
        }
    }
}

def clearAllOverrides() {
    logDebug "Clearing all room overrides"

    getChildApps().each { child ->
        try {
            child.clearOverride()
        } catch (e) {
            logWarn "Failed to clear override for ${child.label}: ${e.message}"
        }
    }

    if (virtualThermostat) {
        virtualThermostat.setOverrideStatus("false", "")
    }
}

// ==================== Status Methods ====================

def updateStatus() {
    if (!virtualThermostat) return

    def avgTemp = getAverageTemperature()
    def heatingRooms = getHeatingRoomCount()
    def windowsOpen = getOpenWindowCount()
    def operatingState = getOverallOperatingState()
    def overrideRooms = getOverrideRooms()

    virtualThermostat.updateFromApp(
        avgTemp,
        state.masterSetpoint,
        state.currentMode,
        operatingState,
        state.currentScheduleSlot,
        getChildApps().size(),
        heatingRooms,
        windowsOpen
    )

    if (overrideRooms) {
        virtualThermostat.setOverrideStatus("true", overrideRooms.join(", "))
    } else {
        virtualThermostat.setOverrideStatus("false", "")
    }

    // Update temperature range from sensors
    updateTemperatureRange()
}

def getAverageTemperature() {
    def temps = []
    getChildApps().each { child ->
        def temp = child.getCurrentTemperature()
        if (temp != null) {
            temps << temp
        }
    }

    if (temps.isEmpty()) return 20.0

    def avg = temps.sum() / temps.size()
    return Math.round(avg * 10) / 10.0
}

def getHeatingRoomCount() {
    def count = 0
    getChildApps().each { child ->
        if (child.isHeating()) count++
    }
    return count
}

def getOpenWindowCount() {
    def count = 0
    getChildApps().each { child ->
        if (child.isWindowOpen()) count++
    }
    return count
}

def getOverallOperatingState() {
    def heatingCount = getHeatingRoomCount()
    return heatingCount > 0 ? "heating" : "idle"
}

def updateTemperatureRange() {
    if (!tempSensors || !virtualThermostat) return

    def temps = []
    tempSensors.each { sensor ->
        def temp = sensor.currentTemperature
        if (temp != null) {
            temps << (temp as BigDecimal)
        }
    }

    if (temps.isEmpty()) {
        logDebug "No temperature readings available from sensors"
        return
    }

    def minTemp = temps.min()
    def maxTemp = temps.max()

    // Round to 1 decimal place
    minTemp = Math.round(minTemp * 10) / 10.0
    maxTemp = Math.round(maxTemp * 10) / 10.0

    logDebug "Temperature range: ${minTemp} - ${maxTemp}°C from ${temps.size()} sensors"

    // Update virtual thermostat
    virtualThermostat.setTemperatureRange(minTemp, maxTemp)
}

def getTemperatureRange() {
    if (!tempSensors) return null

    def temps = []
    tempSensors.each { sensor ->
        def temp = sensor.currentTemperature
        if (temp != null) {
            temps << (temp as BigDecimal)
        }
    }

    if (temps.isEmpty()) return null

    def minTemp = Math.round(temps.min() * 10) / 10.0
    def maxTemp = Math.round(temps.max() * 10) / 10.0

    return [min: minTemp, max: maxTemp]
}

def getOverrideRooms() {
    def rooms = []
    getChildApps().each { child ->
        if (child.hasOverride()) {
            rooms << child.getRoomName()
        }
    }
    return rooms
}

// ==================== Child App Interface ====================

def getMasterSetpoint() {
    return state.masterSetpoint ?: settings.defaultSetpoint ?: 20
}

def getMasterMode() {
    return state.currentMode ?: "heat"
}

def isScheduleChange() {
    return state.isScheduleChange == true
}

def notifyOverride(childId, roomName, setpoint) {
    logInfo "Room override: ${roomName} -> ${setpoint}°C"
    updateStatus()
}

def notifyWindowState(childId, roomName, isOpen) {
    logInfo "Window state: ${roomName} -> ${isOpen ? 'OPEN' : 'closed'}"
    updateStatus()
}

def notifyTemperatureChange(childId, roomName, temperature) {
    // Debounce temperature updates
    runIn(5, updateStatus)
}

// ==================== Logging ====================

private void logDebug(String msg) {
    if (logEnable) log.debug "MasterThermostat: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "MasterThermostat: ${msg}"
}

private void logWarn(String msg) {
    log.warn "MasterThermostat: ${msg}"
}

def logsOff() {
    log.warn "MasterThermostat: Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}
