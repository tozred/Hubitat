/**
 *  Room Zone - Child App
 *
 *  Individual room heating zone for Master Thermostat Controller.
 *  Manages one or more TRVs in a room with:
 *  - Integration with Hubitat's built-in Rooms feature
 *  - Temperature offset from master setpoint
 *  - Manual override detection (auto-reverts on schedule change)
 *  - Window detection (contact sensors and/or TRV built-in)
 *
 *  Version: 1.1.0
 *
 *  This is a child app of "Master Thermostat Controller"
 */

import groovy.transform.Field

@Field static final String APP_VERSION = "1.1.0"

definition(
    name: "Room Zone",
    namespace: "benberlin",
    author: "Ben Fayershtain",
    description: "Individual room heating zone",
    category: "Green Living",
    parent: "benberlin:Master Thermostat Controller",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Room Zone Configuration", install: true, uninstall: true) {
        section("<b>Room Settings</b>") {
            label title: "Room Name", required: true, defaultValue: "New Room"

            input "trvDevices", "capability.thermostatHeatingSetpoint",
                  title: "Thermostat Devices",
                  description: "Select thermostat(s) for this room",
                  multiple: true,
                  required: true

            input "tempOffset", "decimal",
                  title: "Temperature Offset (°C)",
                  description: "Offset from master setpoint (-5 to +5)",
                  range: "-5..5",
                  defaultValue: 0,
                  required: true

            paragraph "<i>Example: If master is 20°C and offset is -2, this room will be set to 18°C</i>"
        }

        section("<b>Window Detection</b>") {
            input "contactSensors", "capability.contactSensor",
                  title: "Window Contact Sensor(s)",
                  description: "Optional: External contact sensors",
                  multiple: true,
                  required: false

            input "useTrvWindowDetection", "bool",
                  title: "Use TRV built-in window detection",
                  description: "Use the TRV's temperature-drop detection",
                  defaultValue: true

            input "windowAction", "enum",
                  title: "When window opens",
                  options: [
                      "off": "Turn off heating completely",
                      "minimum": "Set to minimum (4°C)",
                      "frost": "Set to frost protection (7°C)"
                  ],
                  defaultValue: "minimum",
                  required: true

            input "windowResumeDelay", "number",
                  title: "Delay before resuming (seconds)",
                  description: "Wait time after window closes before resuming heating",
                  defaultValue: 60,
                  range: "0..300"
        }

        section("<b>Override Settings</b>") {
            paragraph "When you manually adjust a TRV, this room enters 'override' mode."
            paragraph "Override will automatically clear when the next schedule slot starts."

            input "overrideThreshold", "decimal",
                  title: "Override detection threshold (°C)",
                  description: "Minimum setpoint change to trigger override",
                  defaultValue: 0.5,
                  range: "0.1..2"
        }

        section("<b>Logging</b>") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
        }

        // Status display
        if (state.isOverridden || state.windowOpen) {
            section("<b>Current Status</b>") {
                if (state.isOverridden) {
                    paragraph "<span style='color:orange'>⚠ Override Active: ${state.overrideSetpoint}°C</span>"
                }
                if (state.windowOpen) {
                    paragraph "<span style='color:red'>🪟 Window Open - Heating Paused</span>"
                }
            }
        }
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "${app.label}: Installed"
    initialize()
}

def updated() {
    log.info "${app.label}: Updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    log.info "${app.label}: Uninstalled"
}

def initialize() {
    logDebug "Initializing..."

    // Initialize state
    state.isOverridden = state.isOverridden ?: false
    state.overrideSetpoint = state.overrideSetpoint ?: null
    state.windowOpen = state.windowOpen ?: false
    state.lastAppliedSetpoint = state.lastAppliedSetpoint ?: null
    state.currentMode = "heat"
    state.pendingSetpoint = null

    // Subscribe to TRV events
    if (trvDevices) {
        subscribe(trvDevices, "temperature", temperatureHandler)
        subscribe(trvDevices, "heatingSetpoint", setpointHandler)
        subscribe(trvDevices, "thermostatOperatingState", operatingStateHandler)

        // Subscribe to TRV window detection if enabled
        if (useTrvWindowDetection) {
            subscribe(trvDevices, "windowOpen", trvWindowHandler)
        }
    }

    // Subscribe to contact sensors
    if (contactSensors) {
        subscribe(contactSensors, "contact", contactHandler)

        // Check current window state
        def anyOpen = contactSensors.any { it.currentContact == "open" }
        if (anyOpen && !state.windowOpen) {
            state.windowOpen = true
            logInfo "Window currently open"
        }
    }

    // Apply current master setpoint
    def masterSetpoint = parent.getMasterSetpoint()
    if (masterSetpoint && !state.windowOpen && !state.isOverridden) {
        applyMasterSetpoint(masterSetpoint)
    }

    if (logEnable) runIn(86400, logsOff)   // debug logging switches itself off after 24 h
}

// ==================== Parent Interface ====================

def applyMasterSetpoint(BigDecimal masterTemp, Boolean forceOverride = true) {
    logDebug "Received master setpoint: ${masterTemp}°C (forceOverride: ${forceOverride})"

    // Check if heating is off
    if (state.currentMode == "off") {
        logDebug "Heating is off, not adjusting"
        return
    }

    // Check window state
    if (state.windowOpen) {
        logInfo "Window open, storing setpoint for later: ${masterTemp}°C"
        state.pendingSetpoint = masterTemp
        // Still clear override if forced
        if (forceOverride && state.isOverridden) {
            logInfo "Clearing override (master change while window open)"
            state.isOverridden = false
            state.overrideSetpoint = null
        }
        return
    }

    // Clear override when master setpoint changes (always takes priority)
    if (state.isOverridden && forceOverride) {
        logInfo "Master setpoint changed - clearing room override"
        state.isOverridden = false
        state.overrideSetpoint = null
    }

    // Calculate room temperature with offset
    def roomTemp = masterTemp + (tempOffset ?: 0)
    roomTemp = constrainTemp(roomTemp)

    logInfo "Applying master ${masterTemp}°C + offset ${tempOffset ?: 0}°C = ${roomTemp}°C"
    setRoomTemperature(roomTemp)
}

def setMode(String mode) {
    logDebug "Mode changed to: ${mode}"
    state.currentMode = mode

    if (mode == "off") {
        // Turn off all TRVs
        trvDevices?.each { trv ->
            trv.off()
        }
    } else if (mode == "heat" && !state.windowOpen) {
        // Resume heating
        trvDevices?.each { trv ->
            trv.heat()
        }
        // Re-apply setpoint
        if (state.lastAppliedSetpoint) {
            setRoomTemperature(state.lastAppliedSetpoint)
        }
    }
}

def clearOverride() {
    if (state.isOverridden) {
        logInfo "Override cleared"
        state.isOverridden = false
        state.overrideSetpoint = null
    }
}

def setChildLock(String enabled) {
    logInfo "Setting child lock to ${enabled} for all devices"
    trvDevices?.each { trv ->
        try {
            trv.setChildLock(enabled)
        } catch (e) {
            logWarn "Failed to set child lock on ${trv.displayName}: ${e.message}"
        }
    }
}

// ==================== Temperature Control ====================

def setRoomTemperature(BigDecimal temp) {
    temp = constrainTemp(temp)
    state.lastAppliedSetpoint = temp

    logInfo "Setting room to ${temp}°C"

    // Set flag to prevent setpointHandler from detecting this as manual override
    state.applyingSetpoint = true

    trvDevices?.each { trv ->
        try {
            trv.setHeatingSetpoint(temp)
        } catch (e) {
            logWarn "Failed to set ${trv.displayName}: ${e.message}"
        }
    }

    // Clear the flag after a delay (give time for events to be processed)
    runIn(5, clearApplyingFlag)

    // Notify parent of temperature change
    runIn(2, notifyParentTemperature)
}

def clearApplyingFlag() {
    state.applyingSetpoint = false
}

def notifyParentTemperature() {
    def temp = getCurrentTemperature()
    if (temp != null) {
        parent.notifyTemperatureChange(app.id, app.label, temp)
    }
}

// ==================== Event Handlers ====================

def temperatureHandler(evt) {
    logDebug "${evt.device.displayName} temperature: ${evt.value}°C"
    // Debounce parent notification
    runIn(10, notifyParentTemperature)
}

def setpointHandler(evt) {
    def newSetpoint = evt.value as BigDecimal

    logDebug "${evt.device.displayName} setpoint changed to ${newSetpoint}°C"

    // Ignore if we're currently applying a setpoint (prevents false override detection)
    if (state.applyingSetpoint) {
        logDebug "Ignoring setpoint event - we initiated this change"
        return
    }

    // Detect manual override
    if (state.lastAppliedSetpoint != null) {
        def threshold = (overrideThreshold ?: 0.5) as BigDecimal
        def diff = Math.abs(newSetpoint - state.lastAppliedSetpoint)

        if (diff >= threshold) {
            // This is likely a manual change
            if (!state.isOverridden) {
                logInfo "Manual override detected: ${newSetpoint}°C (was ${state.lastAppliedSetpoint}°C)"
                state.isOverridden = true
                state.overrideSetpoint = newSetpoint

                // Notify parent
                parent.notifyOverride(app.id, app.label, newSetpoint)

                // Sync other TRVs in this room to the override value
                trvDevices?.each { trv ->
                    if (trv.id != evt.device.id) {
                        trv.setHeatingSetpoint(newSetpoint)
                    }
                }
            } else {
                // Update existing override
                state.overrideSetpoint = newSetpoint
            }
        }
    }
}

def operatingStateHandler(evt) {
    logDebug "${evt.device.displayName} operating state: ${evt.value}"
}

// ==================== Window Detection ====================

def contactHandler(evt) {
    logDebug "${evt.device.displayName} contact: ${evt.value}"

    def anyOpen = contactSensors.any { it.currentContact == "open" }
    handleWindowState(anyOpen, "contact sensor")
}

def trvWindowHandler(evt) {
    logDebug "${evt.device.displayName} window detection: ${evt.value}"
    handleWindowState(evt.value == "open", "TRV detection")
}

def handleWindowState(boolean isOpen, String source) {
    if (isOpen && !state.windowOpen) {
        // Window just opened
        state.windowOpen = true
        logInfo "Window open (${source}) - pausing heating"

        // Store current setpoint for later
        if (state.isOverridden) {
            state.pendingSetpoint = state.overrideSetpoint
        } else {
            state.pendingSetpoint = state.lastAppliedSetpoint
        }

        // Apply window action
        trvDevices?.each { trv ->
            switch (windowAction) {
                case "off":
                    trv.off()
                    break
                case "minimum":
                    trv.setHeatingSetpoint(4)
                    break
                case "frost":
                    trv.setHeatingSetpoint(7)
                    break
            }
        }

        // Notify parent
        parent.notifyWindowState(app.id, app.label, true)

    } else if (!isOpen && state.windowOpen) {
        // Window just closed
        logInfo "Window closed (${source}) - will resume heating in ${windowResumeDelay ?: 60}s"

        // Schedule resume with delay
        runIn(windowResumeDelay ?: 60, resumeAfterWindowClose)
    }
}

def resumeAfterWindowClose() {
    // Double-check window is still closed
    def anyOpen = false

    if (contactSensors) {
        anyOpen = contactSensors.any { it.currentContact == "open" }
    }

    if (anyOpen) {
        logDebug "Window still open, not resuming"
        return
    }

    state.windowOpen = false
    logInfo "Resuming heating after window close"

    // Notify parent
    parent.notifyWindowState(app.id, app.label, false)

    // Restore heating mode
    if (state.currentMode != "off") {
        trvDevices?.each { trv ->
            trv.heat()
        }
    }

    // Restore setpoint
    def setpointToApply = state.pendingSetpoint ?: state.lastAppliedSetpoint

    if (state.isOverridden && state.overrideSetpoint) {
        setpointToApply = state.overrideSetpoint
    } else {
        // Get fresh from parent
        def masterSetpoint = parent.getMasterSetpoint()
        setpointToApply = masterSetpoint + (tempOffset ?: 0)
    }

    if (setpointToApply) {
        setRoomTemperature(setpointToApply)
    }

    state.pendingSetpoint = null
}

// ==================== Status Methods ====================

def getCurrentTemperature() {
    if (!trvDevices) return null

    def temps = []
    trvDevices.each { trv ->
        def temp = trv.currentTemperature
        if (temp != null) {
            temps << (temp as BigDecimal)
        }
    }

    if (temps.isEmpty()) return null

    // Return average temperature
    def avg = temps.sum() / temps.size()
    return Math.round(avg * 10) / 10.0
}

def isHeating() {
    if (!trvDevices) return false

    return trvDevices.any { trv ->
        trv.currentThermostatOperatingState == "heating"
    }
}

def isWindowOpen() {
    return state.windowOpen == true
}

def hasOverride() {
    return state.isOverridden == true
}

def getRoomName() {
    return app.label ?: "Unknown Room"
}

def getOffset() {
    return tempOffset ?: 0
}

// ==================== Helper Methods ====================

private BigDecimal constrainTemp(BigDecimal temp) {
    return Math.max(4.0, Math.min(35.0, temp))
}

// ==================== Logging ====================

private void logDebug(String msg) {
    if (logEnable) log.debug "${app.label}: ${msg}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "${app.label}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.label}: ${msg}"
}

def logsOff() {
    log.warn "${app.label}: Debug logging disabled"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
}
