/**
 *  Fridge Logger
 *
 *  Appends fridge temperature / humidity readings and compressor (plug) on-off changes to a
 *  monthly CSV file in the hub's File Manager, and keeps one summary line per day.
 *  Everything runs on the hub, so history is collected even when nothing else is connected.
 *
 *  Files (http://<hub>/local/<name>):
 *    fridge-YYYY-MM.csv   timestamp,attribute,value
 *    fridge-daily.csv     date,minTemp,maxTemp,avgTemp,readings,minutesAbove6,compressorOnMinutes,cycles
 *
 *  Version: 1.0.0
 */

definition(
    name: "Fridge Logger",
    namespace: "benberlin",
    author: "Ben Fayershtein",
    description: "Logs fridge temperature and compressor cycles to CSV files on the hub",
    category: "Convenience",
    singleThreaded: true,
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage", title: "Fridge Logger", install: true, uninstall: true) {
        section("Devices") {
            input "tempSensor", "capability.temperatureMeasurement", title: "Fridge temperature sensor", required: true
            input "plug", "capability.switch", title: "Fridge plug / breaker", required: true
        }
        section("Options") {
            input "warmLimit", "decimal", title: "Count minutes above this temperature (°C)", defaultValue: 6.0
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
        section("Files") {
            paragraph "Readings: <a href='/local/${monthFile()}' target='_blank'>${monthFile()}</a><br>" +
                      "Daily summary: <a href='/local/fridge-daily.csv' target='_blank'>fridge-daily.csv</a>"
        }
    }
}

def installed() { initialize() }

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(tempSensor, "temperature", tempHandler)
    subscribe(tempSensor, "humidity", genericHandler)
    subscribe(plug, "switch", switchHandler)
    schedule("0 1 0 * * ?", closeDay)      // 00:01 every night
    if (!state.day) resetDay()
    if (plug.currentValue("switch") == "on" && !state.onSince) state.onSince = now()
    log.info "Fridge Logger initialized"
}

// ---------- event handlers ----------

def tempHandler(evt) {
    BigDecimal t = evt.value as BigDecimal
    rollDayIfNeeded()
    Map d = state.day
    Long nowMs = now()
    // time-weight "minutes above limit" using the previous reading
    if (d.lastTemp != null && d.lastTempAt && (d.lastTemp as BigDecimal) > limit()) {
        d.minutesAbove = (d.minutesAbove ?: 0) + ((nowMs - (d.lastTempAt as Long)) / 60000.0)
    }
    d.min = (d.min == null) ? t : [d.min as BigDecimal, t].min()
    d.max = (d.max == null) ? t : [d.max as BigDecimal, t].max()
    d.sum = ((d.sum ?: 0) as BigDecimal) + t
    d.count = (d.count ?: 0) + 1
    d.lastTemp = t
    d.lastTempAt = nowMs
    state.day = d
    appendLine(monthFile(), "${stamp()},temperature,${t}")
}

def genericHandler(evt) {
    appendLine(monthFile(), "${stamp()},${evt.name},${evt.value}")
}

def switchHandler(evt) {
    rollDayIfNeeded()
    Map d = state.day
    if (evt.value == "on") {
        state.onSince = now()
        d.cycles = (d.cycles ?: 0) + 1
    } else if (state.onSince) {
        d.onMinutes = (d.onMinutes ?: 0) + ((now() - (state.onSince as Long)) / 60000.0)
        state.onSince = null
    }
    state.day = d
    appendLine(monthFile(), "${stamp()},compressor,${evt.value}")
}

// ---------- daily summary ----------

def closeDay() {
    Map d = state.day ?: [:]
    if (state.onSince) {                       // compressor running across midnight
        d.onMinutes = (d.onMinutes ?: 0) + ((now() - (state.onSince as Long)) / 60000.0)
        state.onSince = now()
    }
    if (d.count) {
        BigDecimal avg = ((d.sum as BigDecimal) / (d.count as Integer)).setScale(2, BigDecimal.ROUND_HALF_UP)
        if (!fileExists("fridge-daily.csv")) {
            appendLine("fridge-daily.csv", "date,minTemp,maxTemp,avgTemp,readings,minutesAbove${limit()},compressorOnMinutes,cycles")
        }
        appendLine("fridge-daily.csv", [d.date, d.min, d.max, avg, d.count,
                   Math.round((d.minutesAbove ?: 0) as double), Math.round((d.onMinutes ?: 0) as double), d.cycles ?: 0].join(","))
    }
    resetDay()
}

private void rollDayIfNeeded() {
    if (state.day?.date != today()) closeDay()
}

private void resetDay() {
    state.day = [date: today(), min: null, max: null, sum: 0, count: 0, minutesAbove: 0, onMinutes: 0, cycles: 0,
                 lastTemp: null, lastTempAt: null]
}

// ---------- file helpers ----------

private String monthFile() { "fridge-${new Date().format('yyyy-MM', location.timeZone)}.csv" }
private String today()     { new Date().format('yyyy-MM-dd', location.timeZone) }
private String stamp()     { new Date().format("yyyy-MM-dd'T'HH:mm:ss", location.timeZone) }
private BigDecimal limit() { (warmLimit ?: 6.0) as BigDecimal }

private boolean fileExists(String name) {
    try { return downloadHubFile(name) != null } catch (e) { return false }
}

private void appendLine(String name, String line) {
    String existing = ""
    try {
        byte[] bytes = downloadHubFile(name)
        if (bytes) existing = new String(bytes, "UTF-8")
    } catch (e) {
        if (name.startsWith("fridge-2")) existing = "timestamp,attribute,value\n"   // new monthly file
    }
    if (!existing && name.startsWith("fridge-2")) existing = "timestamp,attribute,value\n"
    uploadHubFile(name, (existing + line + "\n").getBytes("UTF-8"))
    if (logEnable) log.debug "Fridge Logger: ${name} <- ${line}"
}
