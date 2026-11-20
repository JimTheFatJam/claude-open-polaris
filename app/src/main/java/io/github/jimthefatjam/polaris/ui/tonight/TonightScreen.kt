package io.github.jimthefatjam.polaris.ui.tonight

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jimthefatjam.polaris.core.model.DataQuality
import io.github.jimthefatjam.polaris.core.model.NightTimes
import io.github.jimthefatjam.polaris.core.model.WeatherSample
import io.github.jimthefatjam.polaris.core.model.WmoWeather
import io.github.jimthefatjam.polaris.core.scoring.Assessment
import io.github.jimthefatjam.polaris.core.scoring.ConditionsReport
import io.github.jimthefatjam.polaris.core.scoring.Factor
import io.github.jimthefatjam.polaris.core.scoring.FactorKind
import io.github.jimthefatjam.polaris.core.scoring.HourlyOutlook
import io.github.jimthefatjam.polaris.core.scoring.Impact
import io.github.jimthefatjam.polaris.core.scoring.ObservationType
import io.github.jimthefatjam.polaris.core.weather.WeatherException
import io.github.jimthefatjam.polaris.core.weather.WeatherSource
import io.github.jimthefatjam.polaris.data.LocationMode
import io.github.jimthefatjam.polaris.data.LocationStatus
import io.github.jimthefatjam.polaris.data.SkyData
import io.github.jimthefatjam.polaris.di.AppContainer
import io.github.jimthefatjam.polaris.ui.components.Banner
import io.github.jimthefatjam.polaris.ui.components.BannerKind
import io.github.jimthefatjam.polaris.ui.components.Dot
import io.github.jimthefatjam.polaris.ui.components.Formatter
import io.github.jimthefatjam.polaris.ui.components.KeyValue
import io.github.jimthefatjam.polaris.ui.components.MoonPhaseIcon
import io.github.jimthefatjam.polaris.ui.components.PolarisCard
import io.github.jimthefatjam.polaris.ui.components.QualityBadge
import io.github.jimthefatjam.polaris.ui.components.ScoreRing
import io.github.jimthefatjam.polaris.ui.components.SectionTitle
import io.github.jimthefatjam.polaris.ui.components.factoryOf
import io.github.jimthefatjam.polaris.ui.components.rememberFormatter
import io.github.jimthefatjam.polaris.ui.settings.LocationPickerDialog
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonightScreen(container: AppContainer, onOpenSettings: () -> Unit, onOpenSky: () -> Unit) {
    val vm: TonightViewModel = viewModel(factory = factoryOf { TonightViewModel(container.observing, container.settings) })
    val ui by vm.state.collectAsStateWithLifecycle()
    val o = ui.observing
    val fmt = rememberFormatter(o.zone, ui.settings.units)
    var showPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermissionResult(granted)
        if (!granted) showPicker = true
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh(force = false) }
    // Sky geometry changes continuously; recompute every 5 minutes while visible (weather comes from cache).
    LaunchedEffect(ui.settings.autoRefresh) {
        while (true) {
            delay(5 * 60_000L)
            if (ui.settings.autoRefresh) vm.refresh(force = false) else vm.tick()
        }
    }

    if (showPicker) {
        LocationPickerDialog(
            search = container.placeSearch,
            onDismiss = { showPicker = false },
            onPicked = { loc -> scope.launch { container.settings.setManualLocation(loc) }; showPicker = false },
        )
    }

    PullToRefreshBox(isRefreshing = o.isRefreshing, onRefresh = { vm.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(ui, fmt, onRefresh = { vm.refresh(force = true) }, onLocation = { showPicker = true }) }

            when {
                o.locationStatus == LocationStatus.NEEDS_PERMISSION && o.location == null -> item {
                    PermissionCard(
                        onGrant = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        onManual = { showPicker = true },
                    )
                }
                o.locationStatus == LocationStatus.UNAVAILABLE && o.location == null -> item {
                    PolarisCard(Modifier.fillMaxWidth()) {
                        Text("Couldn't determine your location", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (ui.settings.locationMode == LocationMode.MANUAL) "No manual location has been set yet."
                            else "Location services may be off, or no fix was available. You can retry or pick a place manually.",
                            color = PolarisColors.Muted,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.refresh(force = true) }) { Text("Retry") }
                            OutlinedButton(onClick = { showPicker = true }) { Text("Choose location") }
                        }
                    }
                }
                o.report == null -> item { LoadingCard(o.locationStatus) }
            }

            val report = o.report
            if (report != null) {
                statusBanners(o.weather?.error, o.weather?.source, o.weather?.forecast?.fetchedAt, o.computedAt, fmt, ui.settings.bortle == null, onOpenSettings)
                item { HeroCard(report, fmt) }
                item { CategoryRow(report, ui.selected, vm::select) }
                item { FactorBreakdown(report.now.getValue(ui.selected)) }
                item { BestWindows(report, fmt) }
                if (report.hourly.isNotEmpty()) item { HourlyStrip(report.hourly, ui.selected, fmt) }
                item { WeatherCard(report.nowWeather, fmt) }
                item { SunMoonCard(report.night, o.sky, report, fmt) }
                o.sky?.let { sky -> item { TopObjects(sky, fmt, onOpenSky) } }
                item { Footnote() }
            }
        }
    }
}

@Composable
private fun Header(ui: TonightUiState, fmt: Formatter, onRefresh: () -> Unit, onLocation: () -> Unit) {
    val o = ui.observing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tonight", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onLocation, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.LocationOn, null, Modifier.size(16.dp), tint = PolarisColors.Accent)
                Spacer(Modifier.width(4.dp))
                val name = o.location?.let { it.name ?: Formatter.coords(it.latitude, it.longitude) } ?: "No location"
                val src = when {
                    o.location == null -> ""
                    ui.settings.locationMode == LocationMode.MANUAL -> " · manual"
                    else -> " · device"
                }
                Text(name + src, style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Accent, maxLines = 1)
            }
            val updated = o.weather?.forecast?.fetchedAt
            val label = when {
                o.isRefreshing && o.report == null -> "Loading…"
                updated == null -> "Sky computed ${fmt.time(o.computedAt)} · no weather data"
                else -> "Weather updated ${fmt.time(updated)} (${fmt.ago(updated, o.computedAt ?: java.time.Instant.now())}) · sky ${fmt.time(o.computedAt)}"
            }
            Text(label, style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint)
        }
        if (o.isRefreshing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statusBanners(
    error: WeatherException?, source: WeatherSource?, fetchedAt: java.time.Instant?, now: java.time.Instant?, fmt: Formatter,
    bortleUnknown: Boolean, onOpenSettings: () -> Unit,
) {
    if (error != null) item {
        val msg = when (error) {
            is WeatherException.Network -> "Offline — "
            is WeatherException.RateLimited -> "Weather service busy (rate limit) — "
            is WeatherException.Http -> "Weather service error (${error.code}) — "
            is WeatherException.Parse -> "Unexpected weather data — "
        } + if (fetchedAt != null) "showing cached forecast from ${fmt.ago(fetchedAt, now ?: java.time.Instant.now())}." else "weather-based scores are unavailable."
        Banner(msg, if (fetchedAt != null) BannerKind.WARNING else BannerKind.ERROR)
    } else if (source == WeatherSource.CACHE_STALE) item {
        Banner("Showing cached forecast from ${fmt.ago(fetchedAt, now ?: java.time.Instant.now())}.", BannerKind.WARNING)
    }
    if (bortleUnknown) item {
        Banner("Light pollution unknown. Set your Bortle class for better deep-sky scores.", BannerKind.INFO) {
            TextButton(onClick = onOpenSettings) { Text("Set") }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit, onManual: () -> Unit) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.LocationOn, null, tint = PolarisColors.Accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text("Where are you observing from?", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Polaris uses your approximate location (about 3 km) to fetch the local forecast and compute what's in your sky. " +
                "Your location is sent only to the Open-Meteo weather service and is never shared otherwise.",
            color = PolarisColors.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) { Text("Use my approximate location") }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Choose a place manually") }
    }
}

@Composable
private fun LoadingCard(status: LocationStatus) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(14.dp))
            Text(
                when (status) {
                    LocationStatus.ACQUIRING -> "Finding your location…"
                    else -> "Reading the sky and the forecast…"
                },
                color = PolarisColors.Muted,
            )
        }
    }
}

@Composable
private fun HeroCard(report: ConditionsReport, fmt: Formatter) {
    val general = report.headline
    val best = report.bestNow
    PolarisCard(Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(general.score, general.rating, diameter = 132.dp, stroke = 10.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Right now", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
                Text(verdict(report), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(general.summary, style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Muted)
            }
        }
        if (best != null && best.type != ObservationType.GENERAL && (best.score ?: 0) > (general.score ?: 0) + 10) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Best bet now: ${best.type.shortLabel.lowercase()} (${best.score}).",
                style = MaterialTheme.typography.bodyMedium, color = PolarisColors.forRating(best.rating),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Confidence: ${general.confidence.label}", style = MaterialTheme.typography.labelSmall, color = PolarisColors.Faint)
            Spacer(Modifier.width(8.dp))
            Text("· ${report.nowSky.twilight.label}", style = MaterialTheme.typography.labelSmall, color = PolarisColors.Faint)
        }
    }
}

private fun verdict(report: ConditionsReport): String {
    val best = report.bestNow?.score ?: return "Not enough data"
    return when {
        best >= 80 -> "Get the telescope out!"
        best >= 60 -> "Worth setting up"
        best >= 40 -> "Possible, with compromises"
        best >= 20 -> "Marginal — probably not"
        else -> {
            val next = report.bestWindows.values.filterNotNull().minByOrNull { it.start }
            if (next != null) "Not now — better later tonight" else "Not tonight"
        }
    }
}

@Composable
private fun CategoryRow(report: ConditionsReport, selected: ObservationType, onSelect: (ObservationType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ObservationType.entries.forEach { t ->
            val a = report.now.getValue(t)
            val isSel = t == selected
            val color = PolarisColors.forRating(a.rating)
            Surface(
                onClick = { onSelect(t) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = if (isSel) PolarisColors.SurfaceHigh else PolarisColors.Surface.copy(alpha = 0.7f),
                border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) color else PolarisColors.Outline.copy(alpha = 0.6f)),
            ) {
                Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.shortLabel, style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted, maxLines = 1)
                    Text(a.score?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall, color = color)
                    Text(a.rating?.label ?: "No data", style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FactorBreakdown(a: Assessment) {
    PolarisCard(Modifier.fillMaxWidth()) {
        AnimatedContent(a, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "factors") { assessment ->
            Column {
                Text(assessment.type.label, style = MaterialTheme.typography.titleMedium)
                Text(assessment.summary, style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Muted)
                Spacer(Modifier.height(10.dp))
                val gates = assessment.factors.filter { it.kind == FactorKind.GATE }
                val weighted = assessment.factors.filter { it.kind == FactorKind.WEIGHTED }
                Text("Must-haves", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Faint)
                gates.forEach { FactorRow(it) }
                Spacer(Modifier.height(8.dp))
                Text("Quality factors (weight)", style = MaterialTheme.typography.labelMedium, color = PolarisColors.Faint)
                weighted.forEach { FactorRow(it) }
            }
        }
    }
}

@Composable
private fun FactorRow(f: Factor) {
    val (icon, tint) = when (f.impact) {
        Impact.HELPS -> Icons.Outlined.CheckCircle to PolarisColors.Excellent
        Impact.NEUTRAL -> Icons.Outlined.RemoveCircleOutline to PolarisColors.Fair
        Impact.HURTS -> Icons.Outlined.Warning to PolarisColors.Poor
        Impact.LIMITING -> Icons.Outlined.Warning to PolarisColors.Unsuitable
        Impact.UNKNOWN -> Icons.Outlined.HelpOutline to PolarisColors.Faint
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (f.kind == FactorKind.WEIGHTED) Text("${(f.weight * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = PolarisColors.Faint)
                Spacer(Modifier.width(6.dp))
                QualityBadge(f.quality)
            }
            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
        }
    }
}

@Composable
private fun BestWindows(report: ConditionsReport, fmt: Formatter) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Text("Best times tonight", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        ObservationType.entries.forEach { t ->
            val w = report.bestWindows[t]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(PolarisColors.forScore(w?.peakScore))
                Spacer(Modifier.width(10.dp))
                Text(t.shortLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (w == null) (if (report.hourly.all { it.weather == null }) "No forecast" else "No good window")
                    else "${fmt.time(w.start)}–${fmt.time(w.end)} · peak ${w.peakScore}",
                    style = MaterialTheme.typography.bodyMedium, color = if (w == null) PolarisColors.Faint else PolarisColors.Starlight,
                )
            }
        }
    }
}

@Composable
private fun HourlyStrip(hours: List<HourlyOutlook>, type: ObservationType, fmt: Formatter) {
    Column {
        SectionTitle("Hour by hour · ${type.shortLabel}")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hours, key = { it.time.epochSecond }) { h ->
                val score = h.score(type)
                val color = PolarisColors.forScore(score)
                Surface(shape = RoundedCornerShape(14.dp), color = PolarisColors.Surface.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, PolarisColors.Outline.copy(alpha = 0.6f))) {
                    Column(Modifier.width(64.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fmt.hour(h.time), style = MaterialTheme.typography.labelMedium, color = PolarisColors.Muted)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(36.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Text(score?.toString() ?: "—", style = MaterialTheme.typography.titleMedium, color = color)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("☁ ${fmt.pct(h.weather?.cloudCoverPct)}", style = MaterialTheme.typography.labelSmall, color = PolarisColors.Muted)
                        Text(twilightShort(h.sky.sunAltitudeDeg), style = MaterialTheme.typography.labelSmall, color = PolarisColors.Faint)
                        if (h.sky.moon.altitudeDeg > 0) Text("☾ ${h.sky.moon.altitudeDeg.roundToInt()}°", style = MaterialTheme.typography.labelSmall, color = PolarisColors.MoonGlow)
                        else Text(" ", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun twilightShort(sunAlt: Double) = when {
    sunAlt > -0.833 -> "Day"
    sunAlt > -6 -> "Civil"
    sunAlt > -12 -> "Naut."
    sunAlt > -18 -> "Astro."
    else -> "Dark"
}

@Composable
private fun WeatherCard(w: WeatherSample?, fmt: Formatter) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Current weather", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            QualityBadge(w?.quality ?: DataQuality.UNAVAILABLE)
        }
        if (w == null) {
            Spacer(Modifier.height(6.dp))
            Text("No weather data available. Scores that depend on weather are not shown.", color = PolarisColors.Muted)
            return@PolarisCard
        }
        WmoWeather.describe(w.weatherCode)?.let { Text(it, color = PolarisColors.Muted, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(6.dp))
        KeyValue("Cloud cover", fmt.pct(w.cloudCoverPct))
        if (w.cloudCoverLowPct != null || w.cloudCoverMidPct != null || w.cloudCoverHighPct != null) {
            KeyValue("  Low / mid / high", "${fmt.pct(w.cloudCoverLowPct)} / ${fmt.pct(w.cloudCoverMidPct)} / ${fmt.pct(w.cloudCoverHighPct)}")
        }
        KeyValue("Temperature", fmt.temp(w.temperatureC))
        KeyValue("Humidity", fmt.pct(w.relativeHumidityPct))
        KeyValue("Dew point", fmt.temp(w.dewPointC) + (w.dewPointSpreadC?.let { " (spread ${fmt.tempDelta(it)})" } ?: ""))
        KeyValue("Wind", fmt.speed(w.windSpeedKmh) + (w.windGustsKmh?.let { ", gusts ${fmt.speed(it)}" } ?: ""))
        KeyValue("Jet-stream wind (250 hPa)", fmt.speed(w.jetStreamWindKmh))
        KeyValue("Precipitation chance", fmt.pct(w.precipitationProbabilityPct))
        KeyValue("Precipitation", fmt.precip(w.precipitationMm))
        KeyValue("Visibility", fmt.distanceM(w.visibilityM))
        Spacer(Modifier.height(6.dp))
        Text(
            "Values are weather-model output from Open-Meteo, not ground measurements. Seeing and transparency are not measured by any public weather API; Polaris estimates them (see the factor list).",
            style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint,
        )
    }
}

@Composable
private fun SunMoonCard(n: NightTimes, sky: SkyData?, report: ConditionsReport, fmt: Formatter) {
    PolarisCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sun & Moon", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            QualityBadge(DataQuality.CALCULATED)
        }
        Spacer(Modifier.height(6.dp))
        when {
            n.polarDay -> Text("The Sun does not set today (polar day).", color = PolarisColors.Muted)
            n.polarNight -> Text("The Sun does not rise today (polar night). Sunrise: ${fmt.dayTime(n.sunrise)}", color = PolarisColors.Muted)
            else -> {
                KeyValue("Sunset", fmt.time(n.sunset))
                KeyValue("Civil dusk (−6°)", fmt.time(n.civilDusk))
                KeyValue("Nautical dusk (−12°)", fmt.time(n.nauticalDusk))
                if (n.noAstronomicalDarkness) KeyValue("Astronomical darkness", "None tonight", PolarisColors.Fair)
                else {
                    KeyValue("Astronomical dusk (−18°)", fmt.time(n.astronomicalDusk))
                    KeyValue("Astronomical dawn", fmt.time(n.astronomicalDawn))
                }
                KeyValue("Sunrise", fmt.time(n.sunrise))
            }
        }
        val moon = sky?.moon ?: return@PolarisCard
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoonPhaseIcon(moon.phaseAngleDeg, size = 52.dp, southern = report.location.latitude < 0)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(moon.phaseName, style = MaterialTheme.typography.titleMedium)
                Text("${(moon.illuminatedFraction * 100).roundToInt()}% illuminated · " +
                    if (moon.altitudeDeg > 0) "up, ${moon.altitudeDeg.roundToInt()}° ${Formatter.compassPoint(moon.azimuthDeg)}" else "below horizon",
                    style = MaterialTheme.typography.bodyMedium, color = PolarisColors.Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        KeyValue("Moonrise", fmt.time(moon.riseSet.rise))
        KeyValue("Moonset", fmt.time(moon.riseSet.set))
        KeyValue("Next full Moon", fmt.dayTime(moon.nextFullMoon))
        KeyValue("Next new Moon", fmt.dayTime(moon.nextNewMoon))
    }
}

@Composable
private fun TopObjects(sky: SkyData, fmt: Formatter, onOpenSky: () -> Unit) {
    val top = sky.recommended.take(6)
    PolarisCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Worth a look now", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSky) { Text("Sky map") }
        }
        if (top.isEmpty()) {
            Text("Nothing is well placed right now (daylight, clouds or objects below the horizon).", color = PolarisColors.Muted)
        }
        top.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(PolarisColors.forScore((p.observability * 100).roundToInt()))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.obj.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${p.obj.type.label} · ${p.visibility.label}", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${p.altitudeDeg.roundToInt()}° ${Formatter.compassPoint(p.azimuthDeg)}", style = MaterialTheme.typography.bodyMedium)
                    p.magnitude?.let { Text("mag ${"%.1f".format(it)}", style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint) }
                }
            }
        }
    }
}

@Composable
private fun Footnote() {
    Text(
        "Weather: Open-Meteo.com (CC BY 4.0). Astronomy: Astronomy Engine. Scores are guidance, not guarantees — " +
            "see Settings › About for the method.",
        style = MaterialTheme.typography.bodySmall, color = PolarisColors.Faint, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

