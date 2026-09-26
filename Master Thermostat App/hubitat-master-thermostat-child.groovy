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

@Field static final String APP_VERSION = "1.4.0"

// Battery TRVs apply a setpoint on their next wake, so give them a wake cycle before
// checking, then resend to any valve that did not take it.
@Field static final int SETPOINT_VERIFY_DELAY = 120
@Field static final int SETPOINT_MAX_RETRIES = 3
@Field static final BigDecimal SETPOINT_TOLERANCE = 0.25

// A TRV that detects an open window by itself (a fast temperature drop at the valve) does
// not report it as a window: it switches itself "off" at its frost-protection temperature.
// If it never switches back on, the zone resumes it after this long.
@Field static final String TRV_WINDOW_SOURCE = "TRV detection"
@Field static final int TRV_WINDOW_TIMEOUT = 30 * 60

// After a window closes: stay off this long, then warm up 1°C per step instead of opening
// the valve fully. Both are per-zone settings; these are the defaults.
@Field static final int DEFAULT_HOLD_MINUTES = 15
@Field static final int DEFAULT_WARMUP_STEP_MINUTES = 10

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
                      "minimum": "Set to minimum (4°C) - the valve still heats if the air at it drops below 4°C",
                      "frost": "Set to frost protection (7°C)"
                  ],
                  defaultValue: "off",
                  required: true

            input "windowHoldMinutes", "number",
                  title: "Keep heating off after the window closes (minutes)",
                  description: "Lets the room recover from its own walls before the radiator works",
                  defaultValue: DEFAULT_HOLD_MINUTES,
                  range: "0..120"

            input "warmupStepMinutes", "number",
                  title: "Warm up gently: raise 1°C every N minutes (0 = go straight to the target)",
                  description: "Starts 1°C above the current temperature instead of opening the valve fully",
                  defaultValue: DEFAULT_WARMUP_STEP_MINUTES,
                  range: "0..60"
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
    state.setpointRetries = 0

    // Subscribe to TRV events
    if (trvDevices) {
        subscribe(trvDevices, "temperature", temperatureHandler)
        subscribe(trvDevices, "heatingSetpoint", setpointHandler)
        subscribe(trvDevices, "thermostatOperatingState", operatingStateHandler)
        subscribe(trvDevices, "thermostatMode", trvModeHandler)

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

    // During a warm-up, a higher target just becomes where the warm-up ends; a lower one
    // applies at once.
    if (state.warmupTarget != null) {
        if (roomTemp > (state.lastAppliedSetpoint as BigDecimal)) {
            logInfo "Warm-up now heading for ${roomTemp}°C"
            state.warmupTarget = roomTemp
            return
        }
        stopWarmup()
    }

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
    markOwnWrite(temp)

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

    // These are battery valves and they do drop commands: a lost write once left a radiator
    // sitting at frost protection for twelve hours while the rest of the room was at 16°C.
    state.setpointRetries = 0
    runIn(SETPOINT_VERIFY_DELAY, verifySetpoints)
}

/**
 * Remember what we just wrote to the valves, immediately. state is only saved when an
 * execution ends, so a valve that answered within a fraction of a second was handled by a
 * second execution that still saw the old state: it read our own 4°C window action as a
 * manual override, and the Living Room then "resumed" to 4°C for seven hours. atomicState
 * is written at once. (Kept under its own keys: mixing state and atomicState on one key
 * lets the end-of-run state save overwrite the atomic value.)
 */
private void markOwnWrite(BigDecimal setpoint) {
    atomicState.ownWriteSetpoint = setpoint
    atomicState.ownWriteUntil = now() + 30 * 1000
}

private boolean isOwnWrite(BigDecimal setpoint) {
    if (now() > (atomicState.ownWriteUntil ?: 0)) return false
    def mine = atomicState.ownWriteSetpoint
    return mine == null || (setpoint - (mine as BigDecimal)).abs() <= SETPOINT_TOLERANCE
}

def clearApplyingFlag() {
    state.applyingSetpoint = false
}

/** Resend the room setpoint to any valve that did not actually take it. */
def verifySetpoints() {
    if (state.expectOff) {
        verifyValvesOff()
        return
    }
    BigDecimal target = state.lastAppliedSetpoint as BigDecimal
    if (target == null) return

    // A deliberate manual change supersedes what we last applied, so leave it alone. While a
    // window is open the window action is the thing we last applied, so that still gets checked.
    if (state.isOverridden && !state.windowOpen) {
        logDebug "Skipping setpoint verification - room is overridden"
        return
    }

    def stale = trvDevices?.findAll { trv ->
        def actual = trv.currentValue("heatingSetpoint")
        actual == null || ((actual as BigDecimal) - target).abs() > SETPOINT_TOLERANCE
    }
    if (!stale) {
        if (state.setpointRetries) logInfo "All valves confirmed at ${target}°C"
        state.setpointRetries = 0
        return
    }

    if (state.setpointRetries >= SETPOINT_MAX_RETRIES) {
        logWarn "Gave up after ${SETPOINT_MAX_RETRIES} attempts: " +
                stale.collect { "${it.displayName} is ${it.currentValue('heatingSetpoint')}°C, wanted ${target}°C" }.join("; ")
        state.setpointRetries = 0
        return
    }

    state.setpointRetries = (state.setpointRetries ?: 0) + 1
    logWarn "Resending ${target}°C to ${stale.collect { it.displayName }.join(', ')} (attempt ${state.setpointRetries})"

    state.applyingSetpoint = true
    markOwnWrite(target)
    stale.each { trv ->
        try {
            trv.setHeatingSetpoint(target)
        } catch (e) {
            logWarn "Failed to set ${trv.displayName}: ${e.message}"
        }
    }
    runIn(5, clearApplyingFlag)
    runIn(SETPOINT_VERIFY_DELAY, verifySetpoints)
}

/** Same idea as verifySetpoints, for the window "off" action. */
def verifyValvesOff() {
    if (!state.expectOff) return
    def stale = trvDevices?.findAll { it.currentValue("thermostatMode") != "off" }
    if (!stale) {
        if (state.setpointRetries) logInfo "All valves confirmed off"
        state.setpointRetries = 0
        return
    }
    if ((state.setpointRetries ?: 0) >= SETPOINT_MAX_RETRIES) {
        logWarn "Gave up switching off: ${stale.collect { it.displayName }.join(', ')}"
        state.setpointRetries = 0
        return
    }
    state.setpointRetries = (state.setpointRetries ?: 0) + 1
    logWarn "Resending off to ${stale.collect { it.displayName }.join(', ')} (attempt ${state.setpointRetries})"
    switchValves("off")
    runIn(SETPOINT_VERIFY_DELAY, verifySetpoints)
}

/** Switch every valve on or off, marking it as our own change so no handler mistakes it. */
private void switchValves(String mode) {
    state.applyingSetpoint = true
    markOwnWrite(null)
    atomicState.ownModeUntil = now() + 30 * 1000
    trvDevices?.each { trv ->
        try {
            mode == "off" ? trv.off() : trv.heat()
        } catch (e) {
            logWarn "Failed to switch ${trv.displayName} ${mode}: ${e.message}"
        }
    }
    runIn(5, clearApplyingFlag)
}

// ==================== Warm-up after a window ====================

/** Start 1°C above what the valves read and climb to the target one step at a time. */
private void startWarmup(BigDecimal target) {
    int stepMinutes = (warmupStepMinutes != null ? warmupStepMinutes : DEFAULT_WARMUP_STEP_MINUTES) as int
    def current = getCurrentTemperature()
    if (stepMinutes <= 0 || current == null || target - (current as BigDecimal) <= 1) {
        setRoomTemperature(target)
        return
    }
    BigDecimal first = constrainTemp(new BigDecimal(Math.floor((current as BigDecimal).doubleValue()) + 1))
    if (first >= target) {
        setRoomTemperature(target)
        return
    }
    state.warmupTarget = target
    logInfo "Warming up gently from ${current}°C: ${first}°C now, +1°C every ${stepMinutes} min up to ${target}°C"
    setRoomTemperature(first)
    runIn(stepMinutes * 60, warmupStep)
}

def warmupStep() {
    if (state.warmupTarget == null || state.windowOpen) return
    BigDecimal target = state.warmupTarget as BigDecimal
    BigDecimal next = [(state.lastAppliedSetpoint as BigDecimal) + 1, target].min()
    setRoomTemperature(next)
    if (next < target) {
        runIn(((warmupStepMinutes ?: DEFAULT_WARMUP_STEP_MINUTES) as int) * 60, warmupStep)
    } else {
        logInfo "Warm-up finished at ${target}°C"
        state.warmupTarget = null
    }
}

private void stopWarmup() {
    unschedule("warmupStep")
    state.warmupTarget = null
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
    if (state.applyingSetpoint || isOwnWrite(newSetpoint)) {
        logDebug "Ignoring setpoint event - we initiated this change"
        return
    }

    // The valve closing itself for an open window is not someone turning the dial. Reading
    // it as a manual override pinned the bedroom at 7°C and switched off the resend check.
    if (isTrvWindowAction(evt.device, newSetpoint)) {
        logInfo "${evt.device.displayName} closed itself for an open window"
        handleWindowState(true, TRV_WINDOW_SOURCE)
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
                stopWarmup()
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

/** True when the valve itself went to "off" at its frost temperature, rather than the hub. */
private boolean isTrvWindowAction(dev, BigDecimal newSetpoint) {
    def frost = dev.currentValue("frostProtection")
    boolean atFrost = frost != null && newSetpoint <= (frost as BigDecimal) + SETPOINT_TOLERANCE
    if (dev.currentValue("thermostatMode") != "off" && !atFrost) return false
    // A valve switched off from a dashboard or Apple Home goes through a hub command, and
    // that stays a deliberate choice.
    try {
        long cutoff = now() - 60 * 1000
        if (dev.events([max: 15]).any { it.name?.startsWith("command-") && it.date.time >= cutoff }) return false
    } catch (e) {
        logDebug "Could not read recent commands for ${dev.displayName}: ${e.message}"
    }
    return true
}

def trvModeHandler(evt) {
    logDebug "${evt.device.displayName} mode: ${evt.value}"
    if (now() < (atomicState.ownModeUntil ?: 0)) return   // we switched it
    if (evt.value == "heat" && state.windowOpen && state.windowSource == TRV_WINDOW_SOURCE) {
        handleWindowState(false, TRV_WINDOW_SOURCE)
    }
}

def trvWindowTimeout() {
    if (state.windowOpen && state.windowSource == TRV_WINDOW_SOURCE) {
        logInfo "Valve still closed for a window after ${TRV_WINDOW_TIMEOUT / 60} min - resuming"
        handleWindowState(false, TRV_WINDOW_SOURCE)
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
        state.windowSource = source
        stopWarmup()
        logInfo "Window open (${source}) - pausing heating"

        // Store current setpoint for later
        if (state.isOverridden) {
            state.pendingSetpoint = state.overrideSetpoint
        } else {
            state.pendingSetpoint = state.lastAppliedSetpoint
        }

        // The valve has already closed itself; writing to it now would only fight it.
        if (source == TRV_WINDOW_SOURCE) {
            runIn(TRV_WINDOW_TIMEOUT, trvWindowTimeout)
            parent.notifyWindowState(app.id, app.label, true)
            return
        }

        // Apply window action. This has to go through setRoomTemperature: writing the
        // setpoint directly left setpointHandler free to read our own change as a manual
        // override, and skipped the resend that catches valves which drop the command.
        switch (windowAction) {
            case "off":
                // Really off. At "minimum" (4°C) a valve under a window open to a frosty night
                // measures air below 4°C and opens fully.
                switchValves("off")
                state.expectOff = true
                state.setpointRetries = 0
                runIn(SETPOINT_VERIFY_DELAY, verifySetpoints)
                break
            case "frost":
                setRoomTemperature(7)
                break
            default:                      // "minimum"
                setRoomTemperature(4)
                break
        }

        // Notify parent
        parent.notifyWindowState(app.id, app.label, true)

    } else if (!isOpen && state.windowOpen) {
        // Window just closed. Stay off for a while: the walls and furniture bring the air
        // back up by themselves, and a radiator started straight away works flat out.
        int hold = (windowHoldMinutes != null ? windowHoldMinutes : DEFAULT_HOLD_MINUTES) as int
        logInfo "Window closed (${source}) - heating stays off for ${hold} min, then warms up gently"
        if (windowAction == "off" && !state.expectOff) {
            // The valve closed itself and may already have switched back on
            switchValves("off")
            state.expectOff = true
        }
        runIn(Math.max(hold * 60, 5), resumeAfterWindowClose)
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
    state.expectOff = false
    logInfo "Resuming heating after window close"

    // Notify parent
    parent.notifyWindowState(app.id, app.label, false)

    // Restore heating mode
    if (state.currentMode != "off") {
        switchValves("heat")
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
        if (state.isOverridden) {
            setRoomTemperature(setpointToApply)      // someone chose this, honour it at once
        } else {
            startWarmup(constrainTemp(setpointToApply as BigDecimal))
        }
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
