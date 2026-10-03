/**
 *  Tuya TS0225 mmWave Presence Sensor (HOBEIAN ZG-205Z / ZG-205ZL)
 *  Model: TS0225, manufacturer _TZE200_hl0ss9oa
 *
 *  A 24GHz/5.8GHz mmWave human presence sensor with an illuminance sensor and a
 *  built-in siren. Everything useful arrives over the Tuya EF00 datapoint
 *  protocol rather than standard ZCL clusters, so the IAS Zone cluster (0x0500)
 *  this device also advertises is deliberately ignored: it reports unreliable
 *  zone status and nonsense illuminance values.
 *
 *  Features:
 *  - Presence as both motion (active/inactive) and presence (present/not present)
 *  - humanMotionState: none / large / small / static
 *  - Illuminance in lux
 *  - Separate sensitivity, minimum distance and maximum distance for large
 *    motion, small motion and static (breathing) detection
 *  - Presence keep time, LED indicator, false-detection filters
 *  - Siren: mode, volume and duration. Defaults leave the siren off and muted.
 *  - Presence and absence duration counters
 *
 *  The siren defaults match the device's own defaults (mode off, volume mute) so
 *  that saving preferences never makes an unconfigured sensor start screaming.
 *
 *  Version: 1.0.0
 *
 *  References:
 *  - https://www.zigbee2mqtt.io/devices/ZG-205ZL.html
 *  - https://github.com/kkossev/Hubitat/wiki/Tuya-Zigbee-mmWave-Sensor
 *    (datapoint map cross-checked against its TS0225_HL0SS9OA_RADAR profile)
 */

import groovy.transform.Field

// ==================== Constants ====================

@Field static final String DRIVER_VERSION = "1.0.0"

// Clusters
@Field static final int CLUSTER_BASIC = 0x0000
@Field static final int CLUSTER_IAS_ZONE = 0x0500
@Field static final int CLUSTER_TUYA = 0xEF00

// Tuya EF00 commands
@Field static final int TUYA_CMD_SET_DATA = 0x00      // hub -> device, write a datapoint
@Field static final int TUYA_CMD_QUERY_ALL = 0x03     // hub -> device, report every datapoint
@Field static final int TUYA_CMD_TIME_SYNC = 0x24     // device -> hub, asking for the time

// Tuya datapoint value types
@Field static final String DP_TYPE_BOOL = "01"
@Field static final String DP_TYPE_VALUE = "02"
@Field static final String DP_TYPE_ENUM = "04"

// Datapoints (TS0225 / _TZE200_hl0ss9oa)
@Field static final int DP_MOTION = 1                 // enum   0=inactive 1=active
@Field static final int DP_HUMAN_MOTION_STATE = 11    // enum   0=none 1=large 2=small 3=static
@Field static final int DP_PRESENCE_KEEP_TIME = 12    // value  seconds
@Field static final int DP_MOTION_DISTANCE = 13       // value  metres x100
@Field static final int DP_SMALL_MOTION_DISTANCE = 14 // value  metres x100
@Field static final int DP_MOTION_SENSITIVITY = 15    // value  0..10
@Field static final int DP_SMALL_SENSITIVITY = 16     // value  0..10
@Field static final int DP_ILLUMINANCE = 20           // value  lux x10
@Field static final int DP_LED_INDICATOR = 24         // enum   0=off 1=on
@Field static final int DP_ALARM_TIME = 101           // value  seconds
@Field static final int DP_ALARM_VOLUME = 102         // enum   0=low 1=medium 2=high 3=mute
@Field static final int DP_STATIC_DISTANCE = 103      // value  metres x100
@Field static final int DP_STATIC_SENSITIVITY = 104   // value  0..10
@Field static final int DP_ALARM_MODE = 105           // enum   0=arm 1=off 2=alarm 3=doorbell
@Field static final int DP_MOTION_MIN_DISTANCE = 106  // value  metres x100
@Field static final int DP_SMALL_MIN_DISTANCE = 107   // value  metres x100
@Field static final int DP_STATIC_MIN_DISTANCE = 108  // value  metres x100
@Field static final int DP_CHECKING_TIME = 109        // value  seconds x10, read only
@Field static final int DP_SELFTEST_BREATHE = 110     // enum   read only
@Field static final int DP_SELFTEST_SMALL_MOVE = 111  // enum   read only
@Field static final int DP_MOTION_FALSE_DETECTION = 112
@Field static final int DP_RADAR_RESET = 113          // enum   write 1 to reset to factory settings
@Field static final int DP_SELFTEST_MOVE = 114        // enum   read only
@Field static final int DP_BREATHE_FALSE_DETECTION = 115
@Field static final int DP_OCCUPIED_TIME = 116        // value  seconds, read only
@Field static final int DP_ABSENCE_TIME = 117         // value  seconds, read only
@Field static final int DP_DURATION_STATUS = 118      // value  seconds, read only

@Field static final Map HUMAN_MOTION_STATES = [0: "none", 1: "large", 2: "small", 3: "static"]
@Field static final Map ALARM_VOLUMES = [0: "low", 1: "medium", 2: "high", 3: "mute"]
@Field static final Map ALARM_MODES = [0: "arm", 1: "off", 2: "alarm", 3: "doorbell"]

// ==================== Metadata ====================

metadata {
    definition (name: "Tuya TS0225 mmWave Presence Sensor", namespace: "benberlin", author: "Ben Fayershtein") {
        capability "Sensor"
        capability "MotionSensor"
        capability "PresenceSensor"
        capability "IlluminanceMeasurement"
        capability "Configuration"
        capability "Refresh"

        attribute "humanMotionState", "enum", ["none", "large", "small", "static"]
        attribute "occupiedTime", "number"
        attribute "absenceTime", "number"
        attribute "alarmMode", "enum", ["arm", "off", "alarm", "doorbell"]
        attribute "alarmVolume", "enum", ["low", "medium", "high", "mute"]
        attribute "lastActivity", "string"
        attribute "driverVersion", "string"

        command "resetToFactorySettings"
        command "updateAllPreferences"
        command "logsOff"

        // As reported by this unit: it advertises EE00/E000 and illuminance (0400)
        // on top of the clusters the vendor documents.
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0500,E002,EF00,EE00,E000,0400",
                    outClusters: "0003,E002,EF00,EE00,E000",
                    manufacturer: "_TZE200_hl0ss9oa", model: "TS0225",
                    deviceJoinName: "Tuya mmWave Presence Sensor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0500,E002,EF00,0400",
                    outClusters: "0019,000A",
                    manufacturer: "_TZE200_hl0ss9oa", model: "TS0225",
                    deviceJoinName: "Tuya mmWave Presence Sensor"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0003,0500,E002,EF00",
                    outClusters: "0019,000A",
                    manufacturer: "_TZE200_hl0ss9oa", model: "TS0225",
                    deviceJoinName: "Tuya mmWave Presence Sensor"

        fingerprint manufacturer: "_TZE200_hl0ss9oa", model: "TS0225",
                    deviceJoinName: "Tuya mmWave Presence Sensor"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true

        input name: "presenceKeepTime", type: "number", title: "Presence keep time (seconds)",
              description: "How long presence stays reported after the last detection (5-3600)",
              defaultValue: 30, range: "5..3600"

        input name: "motionSensitivity", type: "number", title: "Large motion sensitivity",
              description: "0 = least sensitive, 10 = most sensitive",
              defaultValue: 7, range: "0..10"

        input name: "motionMinDistance", type: "decimal", title: "Large motion minimum distance (m)",
              description: "Ignore large motion closer than this",
              defaultValue: 0.5, range: "0..6"

        input name: "motionDistance", type: "decimal", title: "Large motion maximum distance (m)",
              defaultValue: 6.0, range: "0..10"

        input name: "smallSensitivity", type: "number", title: "Small motion sensitivity",
              defaultValue: 7, range: "0..10"

        input name: "smallMinDistance", type: "decimal", title: "Small motion minimum distance (m)",
              defaultValue: 0.5, range: "0..6"

        input name: "smallDistance", type: "decimal", title: "Small motion maximum distance (m)",
              defaultValue: 5.0, range: "0..6"

        input name: "staticSensitivity", type: "number", title: "Static (breathing) sensitivity",
              defaultValue: 7, range: "0..10"

        input name: "staticMinDistance", type: "decimal", title: "Static minimum distance (m)",
              defaultValue: 0.5, range: "0..6"

        input name: "staticDistance", type: "decimal", title: "Static maximum distance (m)",
              defaultValue: 4.0, range: "0..6"

        input name: "motionFalseDetection", type: "bool", title: "Filter false large-motion detections",
              defaultValue: false

        input name: "breatheFalseDetection", type: "bool", title: "Filter false breathing detections",
              defaultValue: false

        input name: "ledIndicator", type: "bool", title: "LED indicator on", defaultValue: false

        input name: "alarmMode", type: "enum", title: "Siren mode",
              description: "Leave on 'off' unless you want the built-in siren to sound",
              options: ["off", "arm", "alarm", "doorbell"], defaultValue: "off"

        input name: "alarmVolume", type: "enum", title: "Siren volume",
              options: ["mute", "low", "medium", "high"], defaultValue: "mute"

        input name: "alarmTime", type: "number", title: "Siren duration (seconds)",
              defaultValue: 1, range: "0..60"

        input name: "illuminanceDelta", type: "number", title: "Illuminance reporting delta (lux)",
              description: "Ignore illuminance changes smaller than this, to keep the event log quiet",
              defaultValue: 5, range: "0..500"
    }
}

// ==================== Lifecycle ====================

def installed() {
    log.info "${device.displayName}: installed - version ${DRIVER_VERSION}"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    runIn(2, "configure")
}

def updated() {
    logInfo "preferences saved"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    if (logEnable) runIn(1800, "logsOff")
    List<String> cmds = buildPreferenceCommands()
    if (cmds) sendZigbeeCommands(cmds)
}

def configure() {
    logInfo "configuring - version ${DRIVER_VERSION}"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    List<String> cmds = []
    // Read the Basic cluster so firmware/build details land in the Data section.
    cmds += zigbee.readAttribute(CLUSTER_BASIC, [0x0001, 0x0004, 0x0005, 0x0007], [:], 200)
    cmds += buildPreferenceCommands()
    cmds += queryAllDatapoints()
    sendZigbeeCommands(cmds)
}

def refresh() {
    logInfo "refreshing"
    sendZigbeeCommands(queryAllDatapoints())
}

// ==================== Commands ====================

def updateAllPreferences() {
    logInfo "sending every preference to the device"
    List<String> cmds = buildPreferenceCommands()
    if (cmds) sendZigbeeCommands(cmds) else logWarn "no preferences to send"
}

def resetToFactorySettings() {
    logWarn "resetting the radar to its factory settings"
    sendZigbeeCommands(sendTuyaDatapoint(DP_RADAR_RESET, DP_TYPE_ENUM, 1, 1))
}

def logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ==================== Parse ====================

def parse(String description) {
    Map descMap = [:]
    try {
        descMap = zigbee.parseDescriptionAsMap(description)
    } catch (Exception err) {
        logWarn "could not parse: ${description} (${err.message})"
        return
    }
    if (!descMap) return
    logDebug "descMap: ${descMap}"

    if (descMap.clusterInt == CLUSTER_TUYA) {
        parseTuyaCluster(descMap)
        return
    }
    if (descMap.clusterInt == CLUSTER_IAS_ZONE) {
        // Deliberately ignored: this device's IAS Zone status and illuminance are
        // unreliable. Presence and lux come from the EF00 datapoints instead.
        logDebug "ignoring IAS Zone report: ${descMap}"
        return
    }
    if (descMap.clusterInt == CLUSTER_BASIC) {
        logDebug "basic cluster attribute ${descMap.attrId} = ${descMap.value}"
        return
    }
    logDebug "unhandled cluster ${descMap.cluster}: ${descMap}"
}

private void parseTuyaCluster(Map descMap) {
    if (descMap.command == "24") {
        syncTuyaDateTime()
        return
    }
    if (!(descMap.command in ["01", "02"])) {
        logDebug "unhandled Tuya command ${descMap.command}"
        return
    }
    List<String> data = descMap.data
    if (!data || data.size() < 7) {
        logDebug "Tuya report too short: ${data}"
        return
    }
    // [0]=status [1]=sequence [2]=dp [3]=type [4..5]=length [6..]=value
    int dp = zigbee.convertHexToInt(data[2])
    int length = zigbee.convertHexToInt(data[5])
    int value = 0
    for (int i = 0; i < length; i++) {
        value = (value << 8) + zigbee.convertHexToInt(data[6 + i])
    }
    handleDatapoint(dp, value)
}

private void handleDatapoint(int dp, int value) {
    switch (dp) {
        case DP_MOTION:
            String motion = (value == 1) ? "active" : "inactive"
            String presence = (value == 1) ? "present" : "not present"
            sendStateEvent("motion", motion, "motion is ${motion}")
            sendStateEvent("presence", presence, "presence is ${presence}")
            break

        case DP_HUMAN_MOTION_STATE:
            String state = HUMAN_MOTION_STATES[value] ?: "unknown"
            sendStateEvent("humanMotionState", state, "human motion state is ${state}")
            break

        case DP_ILLUMINANCE:
            int lux = (value / 10) as int
            Integer previous = device.currentValue("illuminance") as Integer
            int delta = (illuminanceDelta == null) ? 5 : (illuminanceDelta as int)
            if (previous != null && Math.abs(lux - previous) < delta) {
                logDebug "illuminance ${lux} lx within ${delta} lx of ${previous} lx, not reporting"
                return
            }
            sendStateEvent("illuminance", lux, "illuminance is ${lux} lx", "lx")
            break

        case DP_OCCUPIED_TIME:
            sendStateEvent("occupiedTime", value, "present for ${value} s", "s")
            break

        case DP_ABSENCE_TIME:
            sendStateEvent("absenceTime", value, "absent for ${value} s", "s")
            break

        case DP_ALARM_MODE:
            sendStateEvent("alarmMode", ALARM_MODES[value] ?: "unknown", "siren mode is ${ALARM_MODES[value]}")
            break

        case DP_ALARM_VOLUME:
            sendStateEvent("alarmVolume", ALARM_VOLUMES[value] ?: "unknown", "siren volume is ${ALARM_VOLUMES[value]}")
            break

        // Settings echoed back by the device, and the read-only diagnostics.
        case DP_PRESENCE_KEEP_TIME:
        case DP_MOTION_SENSITIVITY:
        case DP_SMALL_SENSITIVITY:
        case DP_STATIC_SENSITIVITY:
        case DP_MOTION_FALSE_DETECTION:
        case DP_BREATHE_FALSE_DETECTION:
        case DP_LED_INDICATOR:
        case DP_ALARM_TIME:
        case DP_RADAR_RESET:
            logDebug "device confirms dp ${dp} = ${value}"
            break

        case DP_MOTION_DISTANCE:
        case DP_SMALL_MOTION_DISTANCE:
        case DP_STATIC_DISTANCE:
        case DP_MOTION_MIN_DISTANCE:
        case DP_SMALL_MIN_DISTANCE:
        case DP_STATIC_MIN_DISTANCE:
            logDebug "device confirms dp ${dp} = ${value / 100} m"
            break

        case DP_CHECKING_TIME:
            logDebug "checking time ${value / 10} s"
            break

        case DP_SELFTEST_MOVE:
        case DP_SELFTEST_SMALL_MOVE:
        case DP_SELFTEST_BREATHE:
        case DP_DURATION_STATUS:
            logDebug "diagnostic dp ${dp} = ${value}"
            break

        default:
            logDebug "unknown dp ${dp} = ${value}"
    }
}

private void sendStateEvent(String name, Object value, String text, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString() && name != "occupiedTime" && name != "absenceTime") {
        logDebug "${name} already ${value}, not resending"
        return
    }
    Map event = [name: name, value: value, descriptionText: "${device.displayName} ${text}"]
    if (unit) event.unit = unit
    logInfo text
    sendEvent(event)
    sendEvent(name: "lastActivity", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// ==================== Preferences to datapoints ====================

private List<String> buildPreferenceCommands() {
    List<String> cmds = []
    cmds += dpIfSet(DP_PRESENCE_KEEP_TIME, DP_TYPE_VALUE, presenceKeepTime, 4)
    cmds += dpIfSet(DP_MOTION_SENSITIVITY, DP_TYPE_VALUE, motionSensitivity, 4)
    cmds += dpIfSet(DP_SMALL_SENSITIVITY, DP_TYPE_VALUE, smallSensitivity, 4)
    cmds += dpIfSet(DP_STATIC_SENSITIVITY, DP_TYPE_VALUE, staticSensitivity, 4)
    cmds += dpIfSet(DP_ALARM_TIME, DP_TYPE_VALUE, alarmTime, 4)

    cmds += dpDistance(DP_MOTION_DISTANCE, motionDistance)
    cmds += dpDistance(DP_MOTION_MIN_DISTANCE, motionMinDistance)
    cmds += dpDistance(DP_SMALL_MOTION_DISTANCE, smallDistance)
    cmds += dpDistance(DP_SMALL_MIN_DISTANCE, smallMinDistance)
    cmds += dpDistance(DP_STATIC_DISTANCE, staticDistance)
    cmds += dpDistance(DP_STATIC_MIN_DISTANCE, staticMinDistance)

    cmds += dpIfSet(DP_LED_INDICATOR, DP_TYPE_ENUM, (ledIndicator) ? 1 : 0, 1)
    cmds += dpIfSet(DP_MOTION_FALSE_DETECTION, DP_TYPE_ENUM, (motionFalseDetection) ? 1 : 0, 1)
    cmds += dpIfSet(DP_BREATHE_FALSE_DETECTION, DP_TYPE_ENUM, (breatheFalseDetection) ? 1 : 0, 1)

    Integer mode = ALARM_MODES.find { k, v -> v == (alarmMode ?: "off") }?.key
    cmds += dpIfSet(DP_ALARM_MODE, DP_TYPE_ENUM, mode, 1)
    Integer volume = ALARM_VOLUMES.find { k, v -> v == (alarmVolume ?: "mute") }?.key
    cmds += dpIfSet(DP_ALARM_VOLUME, DP_TYPE_ENUM, volume, 1)
    return cmds
}

private List<String> dpIfSet(int dp, String type, Object value, int bytes) {
    if (value == null) return []
    return sendTuyaDatapoint(dp, type, (value as BigDecimal).intValue(), bytes)
}

private List<String> dpDistance(int dp, Object metres) {
    if (metres == null) return []
    int raw = ((metres as BigDecimal) * 100).intValue()
    return sendTuyaDatapoint(dp, DP_TYPE_VALUE, raw, 4)
}

// ==================== Tuya EF00 plumbing ====================

private List<String> queryAllDatapoints() {
    return zigbee.command(CLUSTER_TUYA, TUYA_CMD_QUERY_ALL, [:], 200, "00")
}

/**
 * Write one Tuya datapoint.
 *
 * Payload is: 2-byte sequence, 1-byte datapoint, 1-byte type, 2-byte length,
 * then the value, big-endian. Enums and booleans are one byte, numbers four.
 */
private List<String> sendTuyaDatapoint(int dp, String type, int value, int bytes) {
    String payload = nextSequence() +
                     zigbee.convertToHexString(dp, 2) +
                     type +
                     zigbee.convertToHexString(bytes, 4) +
                     zigbee.convertToHexString(value, bytes * 2)
    logDebug "dp ${dp} = ${value} (payload ${payload})"
    return zigbee.command(CLUSTER_TUYA, TUYA_CMD_SET_DATA, [:], 200, payload)
}

private String nextSequence() {
    Integer seq = ((state.tuyaSequence ?: 0) as Integer) + 1
    if (seq > 0xFFFF) seq = 1
    state.tuyaSequence = seq
    return zigbee.convertToHexString(seq, 4)
}

/**
 * Answer the device's time request. Tuya devices ask for UTC and local time as
 * seconds since the epoch; without an answer some of them retry indefinitely.
 */
private void syncTuyaDateTime() {
    long seconds = (now() / 1000) as long
    long offset = 0
    try {
        offset = (location.timeZone.getOffset(now()) / 1000) as long
    } catch (Exception err) {
        logDebug "no timezone available for the Tuya time sync (${err.message})"
    }
    String payload = "0008" +
                     zigbee.convertToHexString((int) seconds, 8) +
                     zigbee.convertToHexString((int) (seconds + offset), 8)
    logDebug "answering the device's time request"
    sendZigbeeCommands(zigbee.command(CLUSTER_TUYA, TUYA_CMD_TIME_SYNC, [:], 200, payload))
}

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
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
