package io.github.jimthefatjam.polaris.core.scoring

import io.github.jimthefatjam.polaris.core.model.DataQuality

enum class ObservationType(val label: String, val shortLabel: String) {
    GENERAL("General stargazing", "Stargazing"),
    MOON("Moon observation", "Moon"),
    PLANETS("Planetary observation", "Planets"),
    DEEP_SKY("Deep-sky observation", "Deep sky"),
}

enum class Rating(val label: String, val minScore: Int) {
    EXCELLENT("Excellent", 80),
    GOOD("Good", 60),
    FAIR("Fair", 40),
    POOR("Poor", 20),
    UNSUITABLE("Unsuitable", 0);

    companion object {
        fun fromScore(score: Int): Rating = entries.first { score >= it.minScore }
    }
}

enum class Impact { HELPS, NEUTRAL, HURTS, LIMITING, UNKNOWN }

enum class FactorKind { GATE, WEIGHTED }

/**
 * One contribution to an assessment.
 * @property value 0..1 (1 = ideal); null when the input data is unavailable.
 * @property weight weight within the weighted mean (0 for gates).
 */
data class Factor(
    val id: String,
    val name: String,
    val kind: FactorKind,
    val value: Double?,
    val weight: Double,
    val quality: DataQuality,
    val detail: String,
) {
    val impact: Impact
        get() = when {
            value == null -> Impact.UNKNOWN
            kind == FactorKind.GATE && value < 0.5 -> Impact.LIMITING
            kind == FactorKind.GATE && value >= 0.95 -> Impact.HELPS
            value >= 0.75 -> Impact.HELPS
            value >= 0.45 -> Impact.NEUTRAL
            else -> Impact.HURTS
        }
}

enum class Confidence(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low") }

data class Assessment(
    val type: ObservationType,
    /** 0..100, or null when essential data (cloud cover) is missing. */
    val score: Int?,
    val rating: Rating?,
    val factors: List<Factor>,
    val summary: String,
    val confidence: Confidence,
) {
    val gates: List<Factor> get() = factors.filter { it.kind == FactorKind.GATE }
    val weighted: List<Factor> get() = factors.filter { it.kind == FactorKind.WEIGHTED }
    val unavailable: List<Factor> get() = factors.filter { it.value == null }
}
