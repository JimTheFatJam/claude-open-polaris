package io.github.jimthefatjam.polaris.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import io.github.jimthefatjam.polaris.core.model.GeoLocation
import io.github.jimthefatjam.polaris.core.model.LocationSource
import io.github.jimthefatjam.polaris.core.model.WeatherForecast
import io.github.jimthefatjam.polaris.core.weather.OpenMeteoClient
import io.github.jimthefatjam.polaris.core.weather.Place
import io.github.jimthefatjam.polaris.core.weather.PlaceSearch
import io.github.jimthefatjam.polaris.core.weather.WeatherProvider
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.theme.PolarisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Renders every top-level screen under Robolectric with a deterministic, test-only forecast.
 * Serves as a crash/smoke test for composition and produces PNGs in app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 21:30 local time in Tenerife (UTC+0) on 2025-01-10: dark, Jupiter high, waxing gibbous Moon. */
    private val now = Instant.parse("2025-01-10T21:30:00Z")
    private val site = GeoLocation(28.30, -16.51, 2390.0, "Izaña, Tenerife (test)", "Atlantic/Canary", LocationSource.MANUAL)

    private class FixtureProvider(private val start: Instant) : WeatherProvider {
        override val name = "fixture"
        private val delegate = OpenMeteoClient()
        override suspend fun fetchRaw(latitude: Double, longitude: Double): String {
            val t0 = start.epochSecond / 3600 * 3600 - 3 * 3600
            val hours = (0 until 36).map { t0 + it * 3600L }
            fun series(f: (Int) -> Number?) = hours.indices.joinToString(",", "[", "]") { f(it)?.toString() ?: "null" }
            // Clear early, high cirrus building after ~02:00, stable air.
            val cloud = { i: Int -> if (i < 8) 5 else minOf(90, 5 + (i - 8) * 12) }
            return """{"latitude":$latitude,"longitude":$longitude,"timezone":"Atlantic/Canary","utc_offset_seconds":0,
              "current":{"time":${start.epochSecond / 900 * 900},"temperature_2m":4.2,"relative_humidity_2m":38,"dew_point_2m":-8.6,
                "cloud_cover":4,"cloud_cover_low":0,"cloud_cover_mid":0,"cloud_cover_high":4,"wind_speed_10m":9.0,"wind_gusts_10m":16.0,
                "precipitation":0.0,"weather_code":0,"visibility":24140.0},
              "hourly":{"time":${hours.joinToString(",", "[", "]")},
                "temperature_2m":${series { 4.5 - it * 0.1 }},"relative_humidity_2m":${series { 38 + it }},
                "dew_point_2m":${series { -8.5 }},"cloud_cover":${series(cloud)},"cloud_cover_low":${series { 0 }},
                "cloud_cover_mid":${series { 0 }},"cloud_cover_high":${series(cloud)},"wind_speed_10m":${series { 9 }},
                "wind_gusts_10m":${series { 16 }},"wind_speed_250hPa":${series { 60 + it * 4 }},
                "precipitation_probability":${series { if (it < 20) 0 else 10 }},"precipitation":${series { 0.0 }},
                "visibility":${series { 24140.0 }},"weather_code":${series { if (cloud(it) > 50) 2 else 0 }}}}"""
        }
        override fun parse(raw: String, fetchedAt: Instant): WeatherForecast = delegate.parse(raw, fetchedAt)
    }

    private val noSearch = object : PlaceSearch { override suspend fun search(query: String) = emptyList<Place>() }

    private fun capture(name: String) {
        repeat(6) { compose.mainClock.advanceTimeBy(200); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pumpUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "condition not met in time: ${lastState()}" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(20)
        }
    }

    private var lastState: () -> String = { "" }

    private fun tab(label: String) {
        compose.onAllNodesWithText(label)[0].performClick()
        compose.mainClock.advanceTimeBy(800)
    }

    @Test fun allScreensRender() {
        // Simulated sensors so the Telescope screen renders its full UI.
        val sm = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val ssm = org.robolectric.Shadows.shadowOf(sm)
        listOf(android.hardware.Sensor.TYPE_ROTATION_VECTOR, android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR,
            android.hardware.Sensor.TYPE_ACCELEROMETER, android.hardware.Sensor.TYPE_MAGNETIC_FIELD, android.hardware.Sensor.TYPE_GYROSCOPE)
            .forEach { ssm.addSensor(org.robolectric.shadows.ShadowSensor.newInstance(it)) }
        val container = AppContainer(
            ApplicationProvider.getApplicationContext(), FixtureProvider(now), noSearch, Clock.fixed(now, ZoneOffset.UTC),
        )
        runBlocking {
            container.settings.setManualLocation(site)
            container.settings.setBortle(2)
        }
        lastState = { container.observing.state.value.let { "${it.locationStatus} loc=${it.location} report=${it.report != null} w=${it.weather}" } }
        compose.mainClock.autoAdvance = false // the decorative starfield animates forever
        compose.setContent { PolarisTheme { PolarisRoot(container) } }

        pumpUntil { container.observing.state.value.report?.nowWeather != null && container.observing.state.value.sky != null }
        val st = container.observing.state.value
        assertNotNull(st.report!!.headline.score)
        assertTrue(st.sky!!.recommended.isNotEmpty())
        capture("01_tonight.png")

        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(4)
        capture("02_tonight_factors.png")
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(6)
        capture("03_tonight_hourly.png")
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(8)
        capture("04_tonight_sun_moon.png")

        tab("Sky"); capture("05_sky.png")
        tab("Explore"); capture("06_explore.png")
        tab("Telescope"); capture("07_telescope.png")

        // Profile mode with a saved mounting (tube 65° clockwise from the phone's top edge).
        runBlocking {
            container.settings.saveProfile(io.github.jimthefatjam.polaris.core.telescope.MountingProfile(
                "test", "Refractor (test)", kotlin.math.sin(Math.toRadians(65.0)), kotlin.math.cos(Math.toRadians(65.0)), 0.0,
                4, 0.8, now.toEpochMilli()))
        }
        pumpUntil { container.observing.state.value.location != null }
        repeat(5) {
            // Phone tilted 40° up about its X axis: rotation-vector quaternion (sin 20°, 0, 0, cos 20°).
            val ev = org.robolectric.shadows.ShadowSensorManager.createSensorEvent(4, android.hardware.Sensor.TYPE_ROTATION_VECTOR)
            ev.values[0] = 0.342f; ev.values[1] = 0f; ev.values[2] = 0f; ev.values[3] = 0.940f
            ssm.sendSensorEventToListeners(ev)
            compose.mainClock.advanceTimeBy(300)
        }
        capture("09_telescope_profile.png")
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(4)
        capture("10_telescope_profile_cards.png")
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(7)
        capture("11_telescope_calibration.png")
        tab("Settings"); capture("08_settings.png")
    }
}
