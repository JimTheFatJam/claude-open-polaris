package io.github.jimthefatjam.polaris.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlin.math.sin
import kotlin.random.Random

private data class BgStar(val x: Float, val y: Float, val r: Float, val base: Float, val phase: Float)

/**
 * Decorative background: a deep gradient with a fixed, seeded field of faint stars. A small subset
 * twinkles very slowly. Purely ornamental — it does not depict the real sky.
 */
@Composable
fun Starfield(modifier: Modifier = Modifier) {
    val stars = remember {
        val rnd = Random(1054)
        List(160) {
            BgStar(rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat() * rnd.nextFloat() * 1.8f,
                0.15f + rnd.nextFloat() * 0.45f, rnd.nextFloat() * 6.28f)
        }
    }
    val t by rememberInfiniteTransition(label = "twinkle").animateFloat(
        initialValue = 0f, targetValue = 6.2832f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1230), PolarisColors.Night, Color(0xFF05070F))))
        drawCircle(
            Brush.radialGradient(listOf(Color(0x22556CFF), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.1f), radius = size.width * 0.9f),
            radius = size.width * 0.9f, center = Offset(size.width * 0.85f, size.height * 0.1f),
        )
        stars.forEachIndexed { i, s ->
            val tw = if (i % 7 == 0) 0.5f + 0.5f * sin(t + s.phase) else 1f
            drawCircle(PolarisColors.Starlight.copy(alpha = s.base * tw), radius = s.r * density * 0.6f,
                center = Offset(s.x * size.width, s.y * size.height))
        }
    }
}
