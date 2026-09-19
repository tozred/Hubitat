/**
 *  Aqara Climate Sensor W100
 *
 *  Model: lumi.sensor_ht.agl001
 *
 *  A driver for the Aqara Climate Sensor W100 with temperature, humidity,
 *  3 buttons (plus/center/minus), and optional external sensor support.
 *
 *  Version: 1.2.2
 *
 *  Clusters:
 *    0x0000 - Basic
 *    0x0001 - Power Configuration (battery)
 *    0x0003 - Identify
 *    0x0012 - Multistate Input (buttons)
 *    0x0402 - Temperature Measurement
 *    0x0405 - Relative Humidity
 *    0xFCC0 - Aqara Manufacturer Specific
 *
 *  Buttons:
 *    Button 1 - Plus (+) button - Endpoint 01
 *    Button 2 - Center button - Endpoint 02
 *    Button 3 - Minus (-) button - Endpoint 03
 *
 *  Actions: pushed, held, doubleTapped, released
 *
 *  References:
 *    - kkossev's W100 driver: https://github.com/kkossev/Hubitat
 *    - zigbee2mqtt: https://github.com/Koenkk/zigbee2mqtt/issues/27262
 *
 *  Author: Ben Fayershtein
 */

import groovy.transform.Field

metadata {
    definition (name: "Aqara Climate Sensor W100", namespace: "benberlin", author: "Ben Fayershtein") {
        capability "Configuration"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Battery"
        capability "PushableButton"
        capability "HoldableButton"
        capability "DoubleTapableButton"
        capability "ReleasableButton"
        capability "Sensor"

        // Additional attributes
        attribute "batteryVoltage", "number"
        attribute "externalTemperature", "number"
        attribute "externalHumidity", "number"
        attribute "powerOutageCount", "number"
        attribute "sensorMode", "string"

        // Commands for external sensor display
        command "readFirmwareInfo"
        command "setExternalTemperature", [[name: "temperature", type: "NUMBER", description: "Temperature in °C (-100 to 100)"]]
        command "setExternalHumidity", [[name: "humidity", type: "NUMBER", description: "Humidity in % (0 to 100)"]]
        command "setSensorMode", [[name: "mode", type: "ENUM", constraints: ["internal", "external"], description: "Display internal or external sensor"]]

        // Fingerprints
        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0012,0402,0405,FCC0",
                    outClusters: "0019",
                    model: "lumi.sensor_ht.agl001",
                    manufacturer: "Aqara",
                    deviceJoinName: "Aqara Climate Sensor W100"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable text logging", defaultValue: true
        input name: "tempOffset", type: "decimal", title: "Temperature offset", defaultValue: 0, range: "-10..10"
        input name: "humidityOffset", type: "decimal", title: "Humidity offset", defaultValue: 0, range: "-20..20"
    }
}

// ==================== Constants ====================

@Field static final int AQARA_MFG_CODE = 0x115F

// Button action values from cluster 0x0012, attribute 0x0055
@Field static final Map BUTTON_ACTIONS = [
    0: "held",
    1: "pushed",
    2: "doubleTapped",
    255: "released"
]

// Button mapping: endpoint -> button number
@Field static final Map ENDPOINT_TO_BUTTON = [
    "01": 1,  // Plus button
    "02": 2,  // Center button
    "03": 3   // Minus button
]

// ==================== Lifecycle ====================

def installed() {
    log.info "Aqara Climate Sensor W100 installed"
    initialize()
}

def updated() {
    log.info "Settings updated"
    if (logEnable) runIn(86400, "logsOff")   // debug logging switches itself off after 24 h
}

def initialize() {
    sendEvent(name: "numberOfButtons", value: 3, descriptionText: "W100 has 3 buttons")
    sendEvent(name: "temperature", value: 0, unit: "°C")
    sendEvent(name: "humidity", value: 0, unit: "%")
}

def configure() {
    logInfo "Configuring W100..."

    def cmds = []

    // Write magic byte to enable third-party hub
    cmds += zigbee.writeAttribute(0xFCC0, 0x0009, 0x20, 0x01, [mfgCode: AQARA_MFG_CODE])
    cmds += "delay 500"

    // Bind clusters
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}"  // Temperature
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}"  // Humidity
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0012 {${device.zigbeeId}} {}"  // Multistate (buttons)
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x02 0x01 0x0012 {${device.zigbeeId}} {}"  // Button EP2
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x03 0x01 0x0012 {${device.zigbeeId}} {}"  // Button EP3
    cmds += "delay 300"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0xFCC0 {${device.zigbeeId}} {}"  // Aqara
    cmds += "delay 500"

    // Configure temperature reporting
    cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 10, 3600, 10)  // min 10s, max 1hr, 0.1°C change
    cmds += "delay 300"

    // Configure humidity reporting
    cmds += zigbee.configureReporting(0x0405, 0x0000, 0x21, 10, 3600, 100)  // min 10s, max 1hr, 1% change
    cmds += "delay 300"

    // Read current state
    cmds += refresh()

    return cmds
}

def refresh() {
    logInfo "Refreshing W100..."

    def cmds = []

    // Basic info
    cmds += zigbee.readAttribute(0x0000, 0x0004)  // Manufacturer
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0000, 0x0005)  // Model
    cmds += "delay 200"

    // Temperature
    cmds += zigbee.readAttribute(0x0402, 0x0000)
    cmds += "delay 200"

    // Humidity
    cmds += zigbee.readAttribute(0x0405, 0x0000)
    cmds += "delay 200"

    // Battery
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // Voltage
    cmds += "delay 200"
    cmds += zigbee.readAttribute(0x0001, 0x0021)  // Percentage
    cmds += "delay 200"

    // Aqara F7 TLV data
    cmds += zigbee.readAttribute(0xFCC0, 0x00F7, [mfgCode: AQARA_MFG_CODE])

    return cmds
}

// Fills the device Data section (manufacturer, model, application, softwareBuild)
// so firmware can be compared against OTA indexes. Battery device: press a button on the
// sensor right before running this so it is awake to answer.
def readFirmwareInfo() {
    logInfo "Reading firmware info"
    def cmds = []
    [0x0001, 0x0004, 0x0005, 0x4000].each { attr ->
        cmds += zigbee.readAttribute(0x0000, attr)
        cmds += "delay 200"
    }
    return cmds
}

// ==================== External Sensor Commands ====================
// Uses the Aqara PMTSD frame protocol to write to attribute 0xFFF2
// Reference: zigbee-herdsman-converters lumiExternalSensor implementation

// Fictive sensor address used by zigbee2mqtt (required by protocol)
@Field static final List<Integer> FICTIVE_SENSOR = [0x00, 0x15, 0x8D, 0x00, 0x01, 0x9D, 0x1B, 0x98]

/**
 * Build a PMTSD protocol header for Aqara FFF2 attribute writes
 * Format: [0xAA, 0x71, length+3, 0x44, counter, integrity, action, 0x41, length]
 */
private List<Integer> buildLumiHeader(int counter, int paramsLength, int action) {
    def header = [0xAA, 0x71, paramsLength + 3, 0x44, counter]
    def integrity = 512 - header.sum()
    return header + [integrity & 0xFF, action, 0x41, paramsLength]
}

/**
 * Convert a float to big-endian IEEE 754 bytes
 */
private List<Integer> floatToBE(float value) {
    int bits = Float.floatToIntBits(value)
    return [
        (bits >> 24) & 0xFF,
        (bits >> 16) & 0xFF,
        (bits >> 8) & 0xFF,
        bits & 0xFF
    ]
}

/**
 * Get the device's IEEE address as a list of bytes
 */
private List<Integer> getDeviceIeeeBytes() {
    def ieee = device.zigbeeId
    if (ieee.startsWith("0x") || ieee.startsWith("0X")) {
        ieee = ieee.substring(2)
    }
    def bytes = []
    for (int i = 0; i < ieee.length(); i += 2) {
        bytes << Integer.parseInt(ieee.substring(i, i + 2), 16)
    }
    return bytes
}

/**
 * Get a 4-byte big-endian Unix timestamp
 */
private List<Integer> getTimestampBytes() {
    long ts = (long)(now() / 1000)
    return [
        (int)((ts >> 24) & 0xFF),
        (int)((ts >> 16) & 0xFF),
        (int)((ts >> 8) & 0xFF),
        (int)(ts & 0xFF)
    ]
}

/**
 * Write a PMTSD frame to attribute 0xFFF2 on cluster 0xFCC0
 * Uses "he wattr" raw command because zigbee.writeAttribute() doesn't handle
 * long octet strings (0x41) properly. Length byte must be prepended per ZCL spec.
 */
private List<String> writeFFF2Frame(List<Integer> frameBytes) {
    def hexPayload = frameBytes.collect { String.format('%02X', it & 0xFF) }.join('')
    logDebug "FFF2 frame (${frameBytes.size()} bytes): ${hexPayload}"

    // Build the length byte (octet string requires length prefix per ZCL spec)
    def lengthByte = String.format('%02X', frameBytes.size())
    def mfgHex = String.format('%04X', AQARA_MFG_CODE)

    def cmd = "he wattr 0x${device.deviceNetworkId} 0x01 0xFCC0 0xFFF2 0x41 {${lengthByte}${hexPayload}} {${mfgHex}}"
    logDebug "FFF2 cmd: ${cmd}"
    return [cmd]
}

/**
 * Set the sensor display mode
 * @param mode "internal" to show built-in sensor, "external" to show values from setExternalTemperature/Humidity
 */
def setSensorMode(String mode) {
    logInfo "Setting sensor mode to: ${mode}"
    sendEvent(name: "sensorMode", value: mode, descriptionText: "Sensor mode set to ${mode}")

    def cmds = []
    def deviceBytes = getDeviceIeeeBytes()
    def timestamp = getTimestampBytes()

    if (mode == "external") {
        // Register external temperature sensor (action 0x02)
        def params1 = timestamp + [0x15] + deviceBytes + FICTIVE_SENSOR + [
            0x00, 0x02, 0x00, 0x55, 0x15, 0x0A, 0x01, 0x00,
            0x00, 0x01, 0x06, 0xE6, 0xB9, 0xBF, 0xE5, 0xBA,
            0xA6, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02,
            0x08, 0x65
        ]
        def frame1 = buildLumiHeader(0x12, params1.size(), 0x02) + params1
        cmds += writeFFF2Frame(frame1)
        cmds += "delay 500"

        // Register external humidity sensor (action 0x02)
        def params2 = timestamp + [0x14] + deviceBytes + FICTIVE_SENSOR + [
            0x00, 0x01, 0x00, 0x55, 0x15, 0x0A, 0x01, 0x00,
            0x00, 0x01, 0x06, 0xE6, 0xB8, 0xA9, 0xE5, 0xBA,
            0xA6, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02,
            0x07, 0x63
        ]
        def frame2 = buildLumiHeader(0x13, params2.size(), 0x02) + params2
        cmds += writeFFF2Frame(frame2)
    } else {
        // Unregister external sensors (action 0x04)
        def params1 = timestamp + [0x15] + deviceBytes + [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        ]
        def frame1 = buildLumiHeader(0x12, params1.size(), 0x04) + params1
        cmds += writeFFF2Frame(frame1)
        cmds += "delay 500"

        def params2 = timestamp + [0x14] + deviceBytes + [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        ]
        def frame2 = buildLumiHeader(0x13, params2.size(), 0x04) + params2
        cmds += writeFFF2Frame(frame2)
    }

    cmds += "delay 500"
    // Read back sensor mode to confirm
    cmds += zigbee.readAttribute(0xFCC0, 0x0172, [mfgCode: AQARA_MFG_CODE])

    return cmds
}

/**
 * Set external temperature to display on W100
 * Must call setSensorMode("external") first
 * @param temperature Temperature in degrees Celsius (-100 to 100)
 */
def setExternalTemperature(BigDecimal temperature) {
    if (temperature == null) {
        log.warn "setExternalTemperature: temperature cannot be null"
        return
    }

    if (temperature < -100) temperature = -100
    if (temperature > 100) temperature = 100

    logInfo "Setting external temperature to: ${temperature}°C"
    sendEvent(name: "externalTemperature", value: temperature, unit: "°C", descriptionText: "External temperature set to ${temperature}°C")

    // Temperature encoded as IEEE 754 float, value * 100, big-endian
    def floatBytes = floatToBE((float) (temperature * 100).toInteger())

    // params: fictiveSensor + 00 01 00 55 + float32BE(temp*100)
    def params = FICTIVE_SENSOR + [0x00, 0x01, 0x00, 0x55] + floatBytes
    def frame = buildLumiHeader(0x12, params.size(), 0x05) + params

    logDebug "External temp frame: ${frame.collect { String.format('%02X', it & 0xFF) }.join('')}"

    return writeFFF2Frame(frame)
}

/**
 * Set external humidity to display on W100
 * Must call setSensorMode("external") first
 * @param humidity Humidity percentage (0 to 100)
 */
def setExternalHumidity(BigDecimal humidity) {
    if (humidity == null) {
        log.warn "setExternalHumidity: humidity cannot be null"
        return
    }

    if (humidity < 0) humidity = 0
    if (humidity > 100) humidity = 100

    logInfo "Setting external humidity to: ${humidity}%"
    sendEvent(name: "externalHumidity", value: humidity, unit: "%", descriptionText: "External humidity set to ${humidity}%")

    // Humidity encoded as IEEE 754 float, value * 100, big-endian
    def floatBytes = floatToBE((float) (humidity * 100).toInteger())

    // params: fictiveSensor + 00 02 00 55 + float32BE(hum*100)
    def params = FICTIVE_SENSOR + [0x00, 0x02, 0x00, 0x55] + floatBytes
    def frame = buildLumiHeader(0x12, params.size(), 0x05) + params

    logDebug "External humidity frame: ${frame.collect { String.format('%02X', it & 0xFF) }.join('')}"

    return writeFFF2Frame(frame)
}

// ==================== Parse ====================

def parse(String description) {
    logDebug "Parsing: ${description?.take(120)}..."

    try {
        def descMap = zigbee.parseDescriptionAsMap(description)

        if (!descMap) {
            logDebug "Could not parse description"
            return []
        }

        // Handle all attribute reports (read attr, or reports with cluster/attrId)
        if (description.startsWith("read attr -") || descMap.attrId != null) {
            parseReadAttr(descMap)
        } else if (description.startsWith("catchall:")) {
            parseCatchall(descMap)
        }
    } catch (e) {
        log.error "Parse error: ${e.message}"
    }

    return []
}

private void parseReadAttr(Map descMap) {
    def cluster = descMap.cluster?.toUpperCase()
    def attrId = descMap.attrId?.toUpperCase()
    def value = descMap.value
    def endpoint = descMap.endpoint ?: "01"

    logDebug "Attribute: cluster=0x${cluster}, attr=0x${attrId}, value=${value}, ep=${endpoint}"

    switch (cluster) {
        case "0000":  // Basic
            parseBasicCluster(attrId, value, descMap)
            break
        case "0001":  // Power Configuration
            parsePowerCluster(attrId, value)
            break
        case "0012":  // Multistate Input (buttons)
            parseMultistateCluster(attrId, value, endpoint)
            break
        case "0402":  // Temperature
            parseTemperatureCluster(attrId, value)
            break
        case "0405":  // Humidity
            parseHumidityCluster(attrId, value)
            break
        case "FCC0":  // Aqara
            parseAqaraCluster(attrId, value, descMap)
            break
    }
}

private void parseCatchall(Map descMap) {
    def clusterId = (descMap.clusterId ?: descMap.cluster)?.toUpperCase()
    def command = descMap.command
    def data = descMap.data
    def endpoint = descMap.sourceEndpoint ?: "01"

    logDebug "Catchall: cluster=0x${clusterId}, cmd=${command}, ep=${endpoint}, data=${data}"

    // Handle button reports from multistate cluster
    if (clusterId == "0012" && command == "0A" && data?.size() >= 4) {
        // Attribute report: [attrLow, attrHigh, type, value]
        if (data[0] == "55" && data[1] == "00") {
            def actionValue = Integer.parseInt(data[3], 16)
            handleButtonAction(endpoint, actionValue)
        }
    }

    // Handle Aqara FCC0 reports
    if (clusterId == "FCC0" && command == "0A" && data?.size() >= 4) {
        // Check for F7 attribute
        if (data[0] == "F7" && data[1] == "00") {
            def dataType = data[2]
            if (dataType == "41" && data.size() > 4) {
                def len = Integer.parseInt(data[3], 16)
                if (data.size() >= 4 + len) {
                    def hexValue = data[4..<(4 + len)].join("")
                    parseF7TLVData(hexValue)
                }
            }
        }
    }
}

// ==================== Cluster Parsers ====================

private void parseBasicCluster(String attrId, String value, Map descMap) {
    def encoding = descMap.encoding

    switch (attrId) {
        case "0001":  // Application version
            updateDataValue("application", value)
            logInfo "Application version: 0x${value} (${Integer.parseInt(value, 16)})"
            break
        case "4000":  // Software build id
            def build = (encoding == "42") ? value : decodeString(value)
            if (build) {
                logInfo "Software build: ${build}"
                updateDataValue("softwareBuild", build)
            }
            break
        case "0004":  // Manufacturer
            def mfg = (encoding == "42") ? value : decodeString(value)
            if (mfg) {
                logInfo "Manufacturer: ${mfg}"
                updateDataValue("manufacturer", mfg)
            }
            break
        case "0005":  // Model
            def model = (encoding == "42") ? value : decodeString(value)
            if (model) {
                logInfo "Model: ${model}"
                updateDataValue("model", model)
            }
            break
    }
}

private void parsePowerCluster(String attrId, String value) {
    switch (attrId) {
        case "0020":  // Battery voltage (in 100mV units)
            def voltage = Integer.parseInt(value, 16)
            def volts = voltage / 10.0
            logInfo "Battery voltage: ${volts}V"
            sendEvent(name: "batteryVoltage", value: volts, unit: "V")
            // Calculate percentage from voltage (3.0V = 100%, 2.7V = 0%)
            def percent = ((volts - 2.7) / 0.3 * 100).toInteger()
            if (percent < 0) percent = 0
            if (percent > 100) percent = 100
            sendEvent(name: "battery", value: percent, unit: "%", descriptionText: "Battery is ${percent}%")
            break
        case "0021":  // Battery percentage
            def percent = Integer.parseInt(value, 16)
            if (percent > 100) percent = percent / 2  // Some devices report 0-200
            logInfo "Battery: ${percent}%"
            sendEvent(name: "battery", value: percent, unit: "%", descriptionText: "Battery is ${percent}%")
            break
    }
}

private void parseMultistateCluster(String attrId, String value, String endpoint) {
    if (attrId == "0055") {
        def actionValue = Integer.parseInt(value, 16)
        handleButtonAction(endpoint, actionValue)
    }
}

private void parseTemperatureCluster(String attrId, String value) {
    if (attrId == "0000") {
        // Value comes in little-endian, swap bytes
        def rawTemp = zigbee.convertHexToInt(value)
        // Handle signed value
        if (rawTemp > 32767) rawTemp -= 65536
        def tempC = rawTemp / 100.0
        def offset = settings?.tempOffset ?: 0
        tempC = tempC + offset
        tempC = formatDecimal(tempC, 1)

        logInfo "Temperature: ${tempC}°C"
        sendEvent(name: "temperature", value: tempC, unit: "°C", descriptionText: "Temperature is ${tempC}°C")
    }
}

private void parseHumidityCluster(String attrId, String value) {
    if (attrId == "0000") {
        // Value comes in little-endian, use zigbee helper
        def rawHumidity = zigbee.convertHexToInt(value)
        def humidity = rawHumidity / 100.0
        def offset = settings?.humidityOffset ?: 0
        humidity = humidity + offset
        // Clamp to 0-100 range
        if (humidity < 0) humidity = 0
        if (humidity > 100) humidity = 100
        humidity = formatDecimal(humidity, 1)

        logInfo "Humidity: ${humidity}%"
        sendEvent(name: "humidity", value: humidity, unit: "%", descriptionText: "Humidity is ${humidity}%")
    }
}

private void parseAqaraCluster(String attrId, String value, Map descMap) {
    logDebug "Aqara FCC0 attr 0x${attrId} = ${value?.take(60)}..."

    switch (attrId) {
        case "00F7":
        case "F7":
            parseF7TLVData(value)
            break
        case "00EE":
        case "EE":
            // Unknown attribute, likely device info
            logDebug "FCC0 attr 0xEE: ${value}"
            break
        case "0020":
            // Power outage count or similar
            def count = zigbee.convertHexToInt(value)
            logDebug "FCC0 attr 0x20: ${count}"
            break
        case "0172":
            // Sensor mode: 0/1 = internal, 2/3 = external
            def modeVal = zigbee.convertHexToInt(value)
            def sensorMode = (modeVal >= 2) ? "external" : "internal"
            logInfo "Sensor mode confirmed: ${sensorMode} (raw: ${modeVal})"
            sendEvent(name: "sensorMode", value: sensorMode, descriptionText: "Sensor mode is ${sensorMode}")
            break
        case "FFF2":
            logDebug "FCC0 attr 0xFFF2 response: ${value?.take(60)}"
            break
        default:
            logDebug "Unhandled FCC0 attribute 0x${attrId}: ${value?.take(40)}"
    }

    // Also handle additionalAttrs if present
    if (descMap.additionalAttrs) {
        descMap.additionalAttrs.each { attr ->
            logDebug "FCC0 additional attr 0x${attr.attrId}: ${attr.value?.take(40)}"
        }
    }
}

// ==================== Button Handling ====================

private void handleButtonAction(String endpoint, int actionValue) {
    def buttonNum = ENDPOINT_TO_BUTTON[endpoint] ?: 1
    def action = BUTTON_ACTIONS[actionValue] ?: "unknown"

    def buttonNames = [1: "Plus (+)", 2: "Center", 3: "Minus (-)"]
    def buttonName = buttonNames[buttonNum] ?: "Button ${buttonNum}"

    logInfo "Button ${buttonNum} (${buttonName}): ${action}"

    switch (action) {
        case "pushed":
            sendEvent(name: "pushed", value: buttonNum, descriptionText: "${buttonName} button pushed", isStateChange: true)
            break
        case "held":
            sendEvent(name: "held", value: buttonNum, descriptionText: "${buttonName} button held", isStateChange: true)
            break
        case "doubleTapped":
            sendEvent(name: "doubleTapped", value: buttonNum, descriptionText: "${buttonName} button double-tapped", isStateChange: true)
            break
        case "released":
            sendEvent(name: "released", value: buttonNum, descriptionText: "${buttonName} button released", isStateChange: true)
            break
    }
}

// ==================== F7 TLV Parser ====================

private void parseF7TLVData(String hexData) {
    if (!hexData || hexData.length() < 4) return

    logDebug "Parsing F7 TLV (${hexData.length() / 2} bytes)..."

    def bytes = []
    for (int i = 0; i < hexData.length(); i += 2) {
        bytes << Integer.parseInt(hexData.substring(i, i + 2), 16)
    }

    int idx = 0
    while (idx < bytes.size() - 2) {
        def tag = bytes[idx]
        def type = bytes[idx + 1]
        idx += 2

        def valueLen = getTypeLength(type)
        if (valueLen == 0 || idx + valueLen > bytes.size()) break

        def rawBytes = bytes[idx..<(idx + valueLen)]
        idx += valueLen

        def value = interpretValue(type, rawBytes)

        switch (tag) {
            case 0x01:  // Battery voltage (mV)
                def volts = value / 1000.0
                logDebug "F7 Battery voltage: ${volts}V"
                sendEvent(name: "batteryVoltage", value: formatDecimal(volts, 2), unit: "V")
                break
            case 0x03:  // Temperature
                logDebug "F7 Temperature: ${value}°C"
                break
            case 0x05:  // Power outage count
                logInfo "F7 Power outage count: ${value}"
                sendEvent(name: "powerOutageCount", value: value)
                break
            case 0x66:  // External temperature (if connected)
                def extTemp = formatDecimal(value / 100.0, 1)
                logInfo "F7 External temperature: ${extTemp}°C"
                sendEvent(name: "externalTemperature", value: extTemp, unit: "°C")
                break
            case 0x67:  // External humidity (if connected)
                def extHum = formatDecimal(value / 100.0, 1)
                logInfo "F7 External humidity: ${extHum}%"
                sendEvent(name: "externalHumidity", value: extHum, unit: "%")
                break
            case 0x69:  // Battery percentage
                logInfo "F7 Battery: ${value}%"
                sendEvent(name: "battery", value: value, unit: "%", descriptionText: "Battery is ${value}%")
                break
            default:
                logDebug "F7 Tag 0x${String.format('%02X', tag)}: ${value}"
        }
    }
}

private int getTypeLength(int type) {
    switch (type) {
        case 0x10: return 1  // Boolean
        case 0x20: return 1  // Uint8
        case 0x21: return 2  // Uint16
        case 0x23: return 4  // Uint32
        case 0x28: return 1  // Int8
        case 0x29: return 2  // Int16
        case 0x39: return 4  // Float
        default: return 0
    }
}

private Object interpretValue(int type, List<Integer> bytes) {
    switch (type) {
        case 0x10: return bytes[0] != 0
        case 0x20: return bytes[0]
        case 0x21: return bytes[0] + (bytes[1] << 8)
        case 0x23: return bytes[0] + (bytes[1] << 8) + (bytes[2] << 16) + (bytes[3] << 24)
        case 0x28:
            def v = bytes[0]
            return v > 127 ? v - 256 : v
        case 0x29:
            def v = bytes[0] + (bytes[1] << 8)
            return v > 32767 ? v - 65536 : v
        case 0x39:
            int bits = bytes[0] + (bytes[1] << 8) + (bytes[2] << 16) + (bytes[3] << 24)
            return Float.intBitsToFloat(bits)
        default:
            return bytes.collect { String.format('%02X', it) }.join('')
    }
}

// ==================== Utility Functions ====================

private String decodeString(String hex) {
    if (!hex) return ""
    if (hex =~ /[g-zG-Z.]/) return hex  // Already decoded
    def result = ""
    try {
        for (int i = 0; i < hex.length(); i += 2) {
            def endIdx = i + 2
            if (endIdx > hex.length()) endIdx = hex.length()
            def charCode = Integer.parseInt(hex.substring(i, endIdx), 16)
            if (charCode >= 32 && charCode < 127) result += (char)charCode
        }
    } catch (e) {
        return hex
    }
    return result
}

private BigDecimal formatDecimal(Number value, int places) {
    return new BigDecimal(String.format("%.${places}f", value))
}

private void logDebug(String msg) {
    if (settings?.logEnable) log.debug msg
}

private void logInfo(String msg) {
    if (settings?.txtEnable) log.info msg
}

def logsOff() {
    log.info "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}
