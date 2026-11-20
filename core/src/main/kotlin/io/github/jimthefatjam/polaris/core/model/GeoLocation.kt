package io.github.jimthefatjam.polaris.core.model

/**
 * A geographic observing site.
 *
 * @property timeZoneId IANA zone id (e.g. "Europe/Berlin"). May be null when unknown, in which case
 *   callers fall back to the device time zone or the zone reported by the weather API.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double = 0.0,
    val name: String? = null,
    val timeZoneId: String? = null,
    val source: LocationSource = LocationSource.DEVICE,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
        require(elevationMeters.isFinite()) { "elevation must be finite" }
    }
}

enum class LocationSource { DEVICE, MANUAL }
