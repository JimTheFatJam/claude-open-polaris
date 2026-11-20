package io.github.jimthefatjam.polaris.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jimthefatjam.polaris.core.scoring.Rating
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors

/** Animated 0–100 gauge. A null score renders an empty ring with "—" (data unavailable). */
@Composable
fun ScoreRing(score: Int?, rating: Rating?, modifier: Modifier = Modifier, diameter: Dp = 168.dp, stroke: Dp = 12.dp, showLabel: Boolean = true) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) { progress.animateTo((score ?: 0) / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val color = PolarisColors.forRating(rating)
    val desc = if (score == null) "Score unavailable" else "Score $score out of 100, ${rating?.label}"
    Box(modifier.size(diameter).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val arc = Size(size.width - sw, size.height - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(PolarisColors.Outline, 135f, 270f, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (score != null) drawArc(color, 135f, 270f * progress.value, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—", style = if (diameter > 100.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge, color = PolarisColors.Starlight)
            if (showLabel) Text(rating?.label ?: "No data", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}
