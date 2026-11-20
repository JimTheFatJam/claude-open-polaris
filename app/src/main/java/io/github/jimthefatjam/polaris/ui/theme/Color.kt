package io.github.jimthefatjam.polaris.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.jimthefatjam.polaris.core.scoring.Rating

object PolarisColors {
    val Night = Color(0xFF070B1A)
    val NightElevated = Color(0xFF0E1530)
    val Surface = Color(0xFF121B3A)
    val SurfaceHigh = Color(0xFF1A2550)
    val Outline = Color(0xFF2B3866)
    val Starlight = Color(0xFFE8EDFF)
    val Muted = Color(0xFFA4B0D8)   // ≥ 7:1 on Night
    val Faint = Color(0xFF7D89B3)   // ≥ 4.5:1 on Night
    val Accent = Color(0xFF8FB8FF)
    val AccentWarm = Color(0xFFFFD27A)
    val MoonGlow = Color(0xFFF3EBD3)

    val Excellent = Color(0xFF5BE3A8)
    val Good = Color(0xFF9AD86B)
    val Fair = Color(0xFFF2C14E)
    val Poor = Color(0xFFF08A4B)
    val Unsuitable = Color(0xFFE5566B)
    val Unknown = Color(0xFF7D89B3)

    fun forRating(r: Rating?): Color = when (r) {
        Rating.EXCELLENT -> Excellent
        Rating.GOOD -> Good
        Rating.FAIR -> Fair
        Rating.POOR -> Poor
        Rating.UNSUITABLE -> Unsuitable
        null -> Unknown
    }

    fun forScore(score: Int?): Color = forRating(score?.let { Rating.fromScore(it) })
}
