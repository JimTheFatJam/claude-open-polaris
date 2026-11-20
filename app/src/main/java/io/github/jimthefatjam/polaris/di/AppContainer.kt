package io.github.jimthefatjam.polaris.di

import android.content.Context
import io.github.jimthefatjam.polaris.data.DeviceLocationProvider
import io.github.jimthefatjam.polaris.data.ObservingRepository
import io.github.jimthefatjam.polaris.data.OrientationSensor
import io.github.jimthefatjam.polaris.data.SettingsRepository
import io.github.jimthefatjam.polaris.core.weather.FileWeatherCache
import io.github.jimthefatjam.polaris.core.weather.OpenMeteoClient
import io.github.jimthefatjam.polaris.core.weather.PlaceSearch
import io.github.jimthefatjam.polaris.core.weather.WeatherProvider
import io.github.jimthefatjam.polaris.core.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock

/**
 * Manual dependency container. The graph is small, so this avoids an annotation-processing DI
 * framework while keeping construction in one place (swap for Hilt if the app grows).
 */
class AppContainer(
    context: Context,
    weatherProvider: WeatherProvider? = null,
    placeSearch: PlaceSearch? = null,
    clock: Clock = Clock.systemUTC(),
) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(appContext)
    private val openMeteoClient by lazy { OpenMeteoClient() }
    val placeSearch: PlaceSearch = placeSearch ?: openMeteoClient
    val weather = WeatherRepository(weatherProvider ?: openMeteoClient, FileWeatherCache(File(appContext.cacheDir, "weather")), clock)
    val location = DeviceLocationProvider(appContext)
    val orientation = OrientationSensor(appContext)
    val observing = ObservingRepository(settings, location, weather, appScope, clock)
}
