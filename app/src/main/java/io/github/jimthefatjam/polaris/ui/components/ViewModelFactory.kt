package io.github.jimthefatjam.polaris.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.jimthefatjam.polaris.data.UnitSystem
import java.time.ZoneId

/** Builds a ViewModel factory from a lambda (used with the manual AppContainer). */
inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

@Composable
fun rememberFormatter(zone: ZoneId, units: UnitSystem): Formatter {
    val ctx = LocalContext.current
    val is24 = DateFormat.is24HourFormat(ctx)
    return remember(zone, units, is24) { Formatter(zone, units, is24) }
}
