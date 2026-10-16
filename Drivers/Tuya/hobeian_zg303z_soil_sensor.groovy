/**
 *  HOBEIAN ZG-303Z Soil Moisture Sensor (also sold unbranded, and as AOYAN AY-303Z / COOLO CS-201Z)
 *  Hubitat Elevation device driver
 *
 *  Version: 1.1.0 - Dry warning is a soilStatus (moist/dry), no longer a leak sensor: moist soil
 *                   showed as a red water leak on dashboards
 *  Version: 1.0.2 - Fix sending settings (hex formatting threw)
 *  Version: 1.0.1 - Ask for all values on wake while any is still missing
 *  Version: 1.0.0
 *
 *  A battery Tuya device: everything useful travels as Tuya datapoints on cluster 0xEF00.
 *  Datapoint map from the ZG-303Z definition in Koenkk/zigbee-herdsman-converters
 *  (src/devices/hobeian.ts), checked against what a real unit reported on joining:
 *      3    soil moisture, %
 *      5    air temperature, 0.1 °C
 *      109  air humidity, %
 *      15   battery, %
 *      106  dry warning (bool): soil moisture is below the warning threshold
 *      110  dry warning threshold, %
 *      111  air temperature / humidity sampling interval, s
 *      112  soil sampling interval, s
 *      102  soil moisture calibration, %
 *      104  temperature calibration, 0.1 °C
 *      105  humidity calibration, %
 *      9    temperature unit (0 Celsius, 1 Fahrenheit)
 *
 *  The sensor also mirrors temperature on 0x0402 and soil moisture on 0x0405; those copies
 *  are ignored so nothing is reported twice (and so soil moisture is not mistaken for air
 *  humidity).
 *
 *  It sleeps nearly all the time and only listens for a moment after it reports, so setting
 *  changes are queued and sent the next time it wakes.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.1.0"
@Field static final int CLUSTER_TUYA = 0xEF00

// datapoint: [setting name, Tuya type (2 = 4-byte value), scale]
@Field static final Map WRITABLE = [
    "soilWarning":            [dp: 110, scale: 1],
    "temperatureSampling":    [dp: 111, scale: 1],
    "soilSampling":           [dp: 112, scale: 1],
    "soilCalibration":        [dp: 102, scale: 1],
    "temperatureCalibration": [dp: 104, scale: 10],
    "humidityCalibration":    [dp: 105, scale: 1]
]

metadata {
    definition (name: "HOBEIAN ZG-303Z Soil Sensor", namespace: "benberlin", author: "Ben Fayershtain") {
        capability "Sensor"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Configuration"
        capability "HealthCheck"

        attribute "soilMoisture", "number"
        // Not the WaterSensor capability: everywhere in Hubitat and Apple Home its "wet" means
        // a water leak (red alarm), which is exactly backwards for a plant.
        attribute "soilStatus", "enum", ["moist", "dry"]
        attribute "soilWarning", "number"
        attribute "temperatureSampling", "number"
        attribute "soilSampling", "number"
        attribute "soilCalibration", "number"
        attribute "temperatureCalibration", "number"
        attribute "humidityCalibration", "number"
        attribute "pendingSettings", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "driverVersion", "string"

        command "setSoilWarning", [[name: "percent*", type: "NUMBER", description: "Report 'dry' below this soil moisture (0-100 %)"]]
        command "setSoilSampling", [[name: "seconds*", type: "NUMBER", description: "How often the soil is measured (5-3600 s). Longer saves battery."]]
        command "setTemperatureSampling", [[name: "seconds*", type: "NUMBER", description: "How often air temperature and humidity are measured (5-3600 s)"]]
        command "setSoilCalibration", [[name: "percent*", type: "NUMBER", description: "Offset added to soil moisture (-30 to 30 %)"]]
        command "setTemperatureCalibration", [[name: "degrees*", type: "NUMBER", description: "Offset added to temperature (-2 to 2 °C)"]]
        command "setHumidityCalibration", [[name: "percent*", type: "NUMBER", description: "Offset added to air humidity (-30 to 30 %)"]]

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,EF00,0402,0405,0001", outClusters: "0003",
                    model: "ZG-303Z", manufacturer: "HOBEIAN", deviceJoinName: "HOBEIAN ZG-303Z Soil Sensor"
        fingerprint model: "ZG-303Z", manufacturer: "HOBEIAN", deviceJoinName: "HOBEIAN ZG-303Z Soil Sensor"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// ==================== Lifecycle ====================

def installed() {
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
}

def updated() {
    log.info "${device.displayName}: settings updated"
    // 1.0.x reported the dry warning as a leak sensor ("water"); carry it over and drop it
    def oldWater = device.currentValue("water")
    if (oldWater != null) {
        if (device.currentValue("soilStatus") == null) sendEvent(name: "soilStatus", value: oldWater == "dry" ? "dry" : "moist")
        device.deleteCurrentState("water")
    }
    if (logEnable) runIn(86400, "logsOff")   // debug logging switches itself off after 24 h
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    runEvery1Hour("healthCheck")
}

def configure() {
    logInfo "configuring"
    sendEvent(name: "driverVersion", value: DRIVER_VERSION)
    runEvery1Hour("healthCheck")
    // The usual Tuya start-up read, then ask it to report every datapoint
    return zigbee.readAttribute(0x0000, [0x0004, 0x0000, 0x0001, 0x0005, 0x0007, 0xFFFE], [:], 200) +
           zigbee.command(CLUSTER_TUYA, 0x03)
}

// ==================== Settings (queued until the sensor wakes) ====================

def setSoilWarning(percent)            { queueSetting("soilWarning", percent, 0, 100) }
def setSoilSampling(seconds)           { queueSetting("soilSampling", seconds, 5, 3600) }
def setTemperatureSampling(seconds)    { queueSetting("temperatureSampling", seconds, 5, 3600) }
def setSoilCalibration(percent)        { queueSetting("soilCalibration", percent, -30, 30) }
def setTemperatureCalibration(degrees) { queueSetting("temperatureCalibration", degrees, -2, 2) }
def setHumidityCalibration(percent)    { queueSetting("humidityCalibration", percent, -30, 30) }

private void queueSetting(String name, value, BigDecimal min, BigDecimal max) {
    BigDecimal v = [[value as BigDecimal, min].max(), max].min()
    Map pending = state.pending ?: [:]
    pending[name] = v
    state.pending = pending
    sendEvent(name: "pendingSettings", value: pending.collect { k, x -> "${k}=${x}" }.join(", "))
    logInfo "${name} = ${v} queued; it is sent the next time the sensor wakes"
}

/** The sensor has just reported, so it is listening for a moment: send what is waiting. */
private void sendPending() {
    Map pending = state.pending
    if (!pending) return
    if (now() - (state.lastPendingSend ?: 0) < 20 * 1000) return
    state.lastPendingSend = now()
    List cmds = []
    pending.each { name, v ->
        Map def_ = WRITABLE[name]
        int raw = Math.round((v as BigDecimal) * def_.scale) as int
        cmds += tuyaValueCmd(def_.dp as int, raw)
    }
    logInfo "sensor is awake, sending ${pending.collect { k, x -> "${k}=${x}" }.join(', ')}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

/**
 * The sensor only sends a value when it changes, so after pairing (or a driver change) soil
 * moisture and battery can stay empty for hours. While any is missing, ask for everything
 * the next time it is awake.
 */
private void requestMissing() {
    if (["soilMoisture", "battery", "temperature", "humidity", "soilWarning"].every { device.currentValue(it) != null }) return
    if (now() - (state.lastQuery ?: 0) < 5 * 60 * 1000) return
    state.lastQuery = now()
    logDebug "asking the sensor for all its values"
    sendHubCommand(new hubitat.device.HubMultiAction(zigbee.command(CLUSTER_TUYA, 0x03), hubitat.device.Protocol.ZIGBEE))
}

private List tuyaValueCmd(int dp, int value) {
    state.tuyaSeq = ((state.tuyaSeq ?: 0) as int) + 1 & 0xFFFF
    String payload = hex(state.tuyaSeq as long, 4) + hex(dp, 2) + "02" + "0004" + hex(value, 8)
    return zigbee.command(CLUSTER_TUYA, 0x00, [:], 200, payload)
}

/** Fixed-width big-endian hex. (zigbee.convertToHexString only accepts an Integer, and negative
 *  calibrations or a long made it throw.) */
private static String hex(long value, int digits) {
    long mask = digits >= 16 ? -1L : (1L << (digits * 4)) - 1
    return String.format("%0${digits}X", value & mask)
}

/** A datapoint came back with the value we asked for: that setting is done. */
private void confirmSetting(String name, BigDecimal reported) {
    Map pending = state.pending
    if (!pending || !pending.containsKey(name)) return
    if ((pending[name] as BigDecimal).compareTo(reported) == 0) {
        pending.remove(name)
        state.pending = pending
        sendEvent(name: "pendingSettings", value: pending ? pending.collect { k, x -> "${k}=${x}" }.join(", ") : "none")
        logInfo "${name} confirmed at ${reported}"
    }
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "parse: ${description}"
    state.lastRx = now()
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online")

    def descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) return []

    if (descMap.clusterId?.toUpperCase() == "EF00" || descMap.cluster?.toUpperCase() == "EF00") {
        List data = descMap.data
        String cmd = descMap.command
        if (cmd == "24") {                     // time sync request
            sendTime(data)
        } else if (cmd in ["01", "02", "05", "06"] && data?.size() >= 7) {
            parseDatapoints(data)
        }
        sendPending()
        requestMissing()
    }
    // 0x0402 / 0x0405 / 0x0001 copies are deliberately ignored, see the header
    return []
}

private void parseDatapoints(List data) {
    int i = 2                                   // skip the 2-byte sequence number
    while (i + 4 <= data.size()) {
        int dp = Integer.parseInt(data[i], 16)
        int len = Integer.parseInt(data[i + 2] + data[i + 3], 16)
        if (i + 4 + len > data.size()) break
        long raw = len ? Long.parseLong(data[(i + 4)..(i + 3 + len)].join(), 16) : 0
        if (len == 4 && raw > 0x7FFFFFFF) raw -= 0x100000000L     // signed values (calibrations)
        handleDatapoint(dp, raw as int)
        i += 4 + len
    }
}

private void handleDatapoint(int dp, int v) {
    switch (dp) {
        case 3:
            event("soilMoisture", v, "%", "soil moisture is ${v}%")
            break
        case 5:
            BigDecimal t = (v / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP)
            event("temperature", t, "°C", "temperature is ${t}°C")
            break
        case 109:
            event("humidity", v, "%", "air humidity is ${v}%")
            break
        case 15:
            event("battery", v, "%", "battery is ${v}%")
            break
        case 106:
            event("soilStatus", v ? "dry" : "moist", null, "soil is ${v ? 'dry (below the warning threshold)' : 'moist enough'}")
            break
        case 110:
            event("soilWarning", v, "%", null); confirmSetting("soilWarning", v as BigDecimal)
            break
        case 111:
            event("temperatureSampling", v, "s", null); confirmSetting("temperatureSampling", v as BigDecimal)
            break
        case 112:
            event("soilSampling", v, "s", null); confirmSetting("soilSampling", v as BigDecimal)
            break
        case 102:
            event("soilCalibration", v, "%", null); confirmSetting("soilCalibration", v as BigDecimal)
            break
        case 104:
            BigDecimal c = (v / 10.0).setScale(1, BigDecimal.ROUND_HALF_UP)
            event("temperatureCalibration", c, "°C", null); confirmSetting("temperatureCalibration", c)
            break
        case 105:
            event("humidityCalibration", v, "%", null); confirmSetting("humidityCalibration", v as BigDecimal)
            break
        case 9:
            logDebug "temperature unit ${v ? 'Fahrenheit' : 'Celsius'}"
            break
        default:
            logDebug "unknown datapoint ${dp} = ${v}"
    }
}

private void event(String name, value, String unit, String text) {
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    if (text) {
        e.descriptionText = "${device.displayName} ${text}"
        if (device.currentValue(name)?.toString() != value.toString()) logInfo text
    }
    sendEvent(e)
}

/** Tuya devices ask the hub for the time; unanswered, some keep asking instead of sleeping. */
private void sendTime(List data) {
    String seq = (data?.size() >= 2) ? data[0] + data[1] : "0000"
    long utc = (now() / 1000) as long
    long local = utc + ((location.timeZone?.getOffset(now()) ?: 0) / 1000 as long)
    String payload = seq + hex(utc, 8) + hex(local, 8)
    sendHubCommand(new hubitat.device.HubMultiAction(zigbee.command(CLUSTER_TUYA, 0x24, [:], 0, payload), hubitat.device.Protocol.ZIGBEE))
    logDebug "answered time request"
}

// ==================== Health ====================

def healthCheck() {
    // It reports at least on every sampling interval; six silent hours means it is gone
    if (state.lastRx && now() - state.lastRx > 6 * 60 * 60 * 1000 && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline")
        log.warn "${device.displayName}: no report for 6 hours"
    }
}

def ping() { healthCheck() }

// ==================== Logging ====================

def logsOff() {
    log.info "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (txtEnable) log.info "${device.displayName}: ${msg}" }
