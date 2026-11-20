package io.github.jimthefatjam.polaris.data

import io.github.jimthefatjam.polaris.core.astro.Catalog
import io.github.jimthefatjam.polaris.core.astro.SkyCalculator
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.MoonInfo
import io.github.jimthefatjam.polaris.core.model.ObjectPosition
import io.github.jimthefatjam.polaris.core.scoring.ConditionsReport
import io.github.jimthefatjam.polaris.core.scoring.NightForecaster
import io.github.jimthefatjam.polaris.core.weather.WeatherException
import io.github.jimthefatjam.polaris.core.weather.WeatherRepository
import io.github.jimthefatjam.polaris.core.weather.WeatherResult
import io.github.jimthefatjam.polaris.core.weather.WeatherSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

enum class LocationStatus { UNKNOWN, NEEDS_PERMISSION, ACQUIRING, READY, UNAVAILABLE }

data class SkyData(
    val moon: MoonInfo,
    /** Every catalog object, assessed for "now"; filter with [aboveHorizon]. */
    val objects: List<ObjectPosition>,
) {
    val aboveHorizon: List<ObjectPosition> get() = objects.filter { it.isAboveHorizon }
    /** Objects worth pointing at now, best first (the Sun is never included). */
    val recommended: List<ObjectPosition> get() = objects.filter { it.observability > 0.05 }.sortedByDescending { it.observability }
}

data class ObservingState(
    val locationStatus: LocationStatus = LocationStatus.UNKNOWN,
    val location: GeoLocation? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val report: ConditionsReport? = null,
    val sky: SkyData? = null,
    val weather: WeatherResult? = null,
    val isRefreshing: Boolean = false,
    val computedAt: Instant? = null,
    val bortle: Int? = null,
) {
    val weatherError: WeatherException? get() = weather?.error
    val isOfflineData: Boolean get() = weather?.source == WeatherSource.CACHE_STALE || (weather?.error != null && weather.forecast != null)
}

/**
 * Single source of truth for location + weather + sky state shared by all screens.
 * Astronomy is always computed locally, so the Sky/Explore screens work fully offline; only the
 * weather-dependent scores need the network (and fall back to cache).
 */
class ObservingRepository(
    private val settings: SettingsRepository,
    private val locationProvider: DeviceLocationProvider,
    private val weatherRepository: WeatherRepository,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val forecaster = NightForecaster()
    private val sky = SkyCalculator()
    private val _state = MutableStateFlow(ObservingState())
    val state: StateFlow<ObservingState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    init {
        // Re-run whenever location-relevant or scoring-relevant settings change.
        scope.launch {
            settings.settings
                .map { Triple(it.locationMode, it.manualLocation, it.bortle) }
                .distinctUntilChanged()
                .collect { refresh(force = false) }
        }
    }

    fun refresh(force: Boolean) {
        if (refreshJob?.isActive == true && !force) return
        refreshJob?.cancel()
        refreshJob = scope.launch { doRefresh(force) }
    }

    private suspend fun doRefresh(force: Boolean) {
        _state.update { it.copy(isRefreshing = true) }
        val s = settings.settings.first()
        val location: GeoLocation? = when (s.locationMode) {
            LocationMode.MANUAL -> s.manualLocation.also {
                _state.update { st -> st.copy(locationStatus = if (it != null) LocationStatus.READY else LocationStatus.UNAVAILABLE) }
            }
            LocationMode.DEVICE -> {
                if (!locationProvider.hasPermission()) {
                    _state.update { it.copy(locationStatus = LocationStatus.NEEDS_PERMISSION) }
                    null
                } else {
                    _state.update { it.copy(locationStatus = LocationStatus.ACQUIRING) }
                    // Show results for the last known device position immediately, then refine.
                    s.lastDeviceLocation?.let { compute(it, s.bortle, weatherNear(it), keepRefreshing = true) }
                    val fresh = locationProvider.currentLocation()
                    if (fresh != null) settings.setLastDeviceLocation(fresh)
                    val loc = fresh ?: s.lastDeviceLocation
                    _state.update { it.copy(locationStatus = if (loc != null) LocationStatus.READY else LocationStatus.UNAVAILABLE) }
                    loc
                }
            }
        }
        if (location == null) {
            _state.update { it.copy(isRefreshing = false, report = null, sky = null) }
            return
        }
        val weather = weatherRepository.get(location.latitude, location.longitude, force)
        compute(location, s.bortle, weather, keepRefreshing = false)
    }

    /** Recompute astronomy (positions move) using the latest weather without hitting the network. */
    fun recomputeNow() {
        val st = _state.value
        val loc = st.location ?: return
        scope.launch { compute(loc, st.bortle, st.weather, keepRefreshing = st.isRefreshing) }
    }

    private suspend fun compute(location: GeoLocation, bortle: Int?, weather: WeatherResult?, keepRefreshing: Boolean) {
        val now = clock.instant()
        val (report, skyData) = withContext(Dispatchers.Default) {
            val report = forecaster.build(location, weather?.forecast, bortle, now)
            val moon = sky.moonInfo(location, now)
            val cloud = report.nowWeather?.cloudCoverPct
            val objects = Catalog.all.map { obj ->
                sky.assess(obj, location, now, report.nowSky.sunAltitudeDeg, moon, bortle, cloud)
            }
            report to SkyData(moon, objects)
        }
        val zone = resolveZone(location, weather)
        _state.update {
            it.copy(
                location = location, zone = zone, report = report, sky = skyData, weather = weather,
                isRefreshing = keepRefreshing, computedAt = now, bortle = bortle,
            )
        }
    }

    /** Previously loaded weather, only if it was for (roughly) the same place. */
    private fun weatherNear(loc: GeoLocation): WeatherResult? = _state.value.weather?.takeIf { w ->
        val f = w.forecast ?: return@takeIf false
        kotlin.math.abs(f.latitude - loc.latitude) < 0.15 && kotlin.math.abs(f.longitude - loc.longitude) < 0.15
    }

    private fun resolveZone(location: GeoLocation, weather: WeatherResult?): ZoneId {
        val candidates = listOfNotNull(location.timeZoneId, weather?.forecast?.timeZoneId)
        for (id in candidates) runCatching { return ZoneId.of(id) }
        return ZoneId.systemDefault()
    }
}
