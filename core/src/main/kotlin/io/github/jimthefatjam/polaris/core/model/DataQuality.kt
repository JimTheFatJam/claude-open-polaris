package io.github.jimthefatjam.polaris.core.model

/**
 * Where a displayed value comes from. Polaris never presents an estimate as a measurement.
 */
enum class DataQuality(val label: String) {
    /** Output of a numerical weather model for the current hour (model analysis, not a ground observation). */
    MODEL_CURRENT("Model (current)"),
    /** Output of a numerical weather model for a future hour. */
    FORECAST("Forecast"),
    /** Derived from other quantities by a documented heuristic (e.g. seeing from jet-stream wind). */
    ESTIMATED("Estimated"),
    /** Computed from celestial mechanics (positions, twilight, Moon phase). */
    CALCULATED("Calculated"),
    /** Supplied by the user (e.g. Bortle class). */
    USER_PROVIDED("User-provided"),
    /** Not available; excluded from scoring and flagged. */
    UNAVAILABLE("Unavailable"),
}
