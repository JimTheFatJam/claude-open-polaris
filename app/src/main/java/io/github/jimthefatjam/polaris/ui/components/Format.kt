package io.github.jimthefatjam.polaris.ui.components

import io.github.jimthefatjam.polaris.data.UnitSystem
import io.github.jimthefatjam.polaris.core.model.RiseSet
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** All user-facing formatting in one place so units and the observing site's time zone are applied consistently. */
class Formatter(val zone: ZoneId, val units: UnitSystem, is24h: Boolean) {
    private val timeFmt = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", Locale.getDefault()).withZone(zone)
    private val hourFmt = DateTimeFormatter.ofPattern(if (is24h) "HH" else "h a", Locale.getDefault()).withZone(zone)
    private val dayTimeFmt = DateTimeFormatter.ofPattern(if (is24h) "EEE d MMM, HH:mm" else "EEE d MMM, h:mm a", Locale.getDefault()).withZone(zone)

    fun time(i: Instant?): String = i?.let { timeFmt.format(it) } ?: "—"
    fun hour(i: Instant): String = hourFmt.format(i)
    fun dayTime(i: Instant?): String = i?.let { dayTimeFmt.format(it) } ?: "—"

    fun ago(i: Instant?, now: Instant = Instant.now()): String {
        i ?: return "never"
        val d = Duration.between(i, now)
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            else -> "${d.toDays()} d ago"
        }
    }

    fun temp(c: Double?): String = c?.let {
        if (units == UnitSystem.IMPERIAL) "${(it * 9 / 5 + 32).roundToInt()}°F" else "${it.roundToInt()}°C"
    } ?: "—"

    fun tempDelta(c: Double?): String = c?.let {
        if (units == UnitSystem.IMPERIAL) "${(it * 9 / 5).roundToInt()}°F" else "%.1f°C".format(it)
    } ?: "—"

    fun speed(kmh: Double?): String = kmh?.let {
        if (units == UnitSystem.IMPERIAL) "${(it * 0.621371).roundToInt()} mph" else "${it.roundToInt()} km/h"
    } ?: "—"

    fun distanceM(m: Double?): String = m?.let {
        if (units == UnitSystem.IMPERIAL) "%.0f mi".format(it / 1609.34) else "%.0f km".format(it / 1000)
    } ?: "—"

    fun precip(mm: Double?): String = mm?.let {
        if (units == UnitSystem.IMPERIAL) "%.2f in".format(it / 25.4) else "%.1f mm".format(it)
    } ?: "—"

    fun pct(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"

    fun deg(v: Double): String = "${v.roundToInt()}°"

    fun riseSetLine(rs: RiseSet?, up: Boolean): String = when {
        rs == null -> ""
        rs.alwaysUp -> "Circumpolar — never sets"
        rs.neverUp -> "Never rises here"
        up -> "Sets ${time(rs.set)}" + (rs.transit?.let { " · highest ${time(it)}" } ?: "")
        else -> "Rises ${time(rs.rise)}"
    }

    companion object {
        private val compass = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        fun compassPoint(az: Double): String = compass[(((az % 360 + 360) % 360) / 22.5 + 0.5).toInt() % 16]

        fun ra(hours: Double): String {
            val h = hours.toInt()
            val m = ((hours - h) * 60)
            return "%02dh %04.1fm".format(h, m)
        }

        fun dec(deg: Double): String {
            val sign = if (deg < 0) "−" else "+"
            val a = abs(deg)
            val d = a.toInt()
            val m = ((a - d) * 60).roundToInt()
            return "$sign$d° ${"%02d".format(m)}′"
        }

        fun coords(lat: Double, lon: Double): String =
            "%.3f°%s, %.3f°%s".format(abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")
    }
}
