package io.github.jimthefatjam.polaris.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.jimthefatjam.polaris.ui.theme.PolarisColors
import kotlin.math.cos

/**
 * Draws the Moon's phase from the Sun–Moon elongation (0 = new, 180 = full).
 * The lit limb is on the right while waxing (northern-hemisphere view); [southern] mirrors it.
 */
@Composable
fun MoonPhaseIcon(phaseAngleDeg: Double, modifier: Modifier = Modifier, size: Dp = 48.dp, southern: Boolean = false) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2
        val c = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(Color(0xFF2A2F45), r, c)
        val phase = ((phaseAngleDeg % 360) + 360) % 360
        val waxing = phase < 180
        // Terminator is an ellipse with semi-axis r·cos(phase).
        val k = cos(Math.toRadians(phase)).toFloat()
        val path = Path().apply {
            val rect = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
            val litRight = waxing xor southern
            // Lit half
            arcTo(rect, -90f, if (litRight) 180f else -180f, false)
            // Terminator back to top
            val ex = r * k
            val termRect = Rect(c.x - kotlin.math.abs(ex), c.y - r, c.x + kotlin.math.abs(ex), c.y + r)
            val sweep = if (litRight) { if (k > 0) -180f else 180f } else { if (k > 0) 180f else -180f }
            arcTo(termRect, 90f, sweep, false)
            close()
        }
        drawPath(path, PolarisColors.MoonGlow)
    }
}
