# Stargazing Scoring Methodology

Implementation: [`core/src/main/kotlin/.../scoring/ScoringEngine.kt`](../core/src/main/kotlin/io/github/jimthefatjam/polaris/core/scoring/ScoringEngine.kt).
Tests: [`ScoringEngineTest.kt`](../core/src/test/kotlin/io/github/jimthefatjam/polaris/core/scoring/ScoringEngineTest.kt).
Background science: [`ASTRONOMY_RESEARCH.md`](ASTRONOMY_RESEARCH.md).

Polaris produces four independent 0–100 scores for every instant (now, and each hour of the
coming night):

| Assessment | Question it answers |
|---|---|
| **General stargazing** | Is the naked-eye / binocular sky good? |
| **Moon observation** | Is it worth looking at the Moon through a telescope? |
| **Planetary observation** | Will planets show fine detail? |
| **Deep-sky observation** | Can I see faint galaxies, nebulae and clusters? |

## 1. Formula

```
score = 100 × Π gates × ( Σ wᵢ·sᵢ / Σ wᵢ )        over weighted factors whose data are available
```

- **Gates** (0…1) model *show-stoppers*. They multiply, so a single fatal condition drives the
  score to ~0 regardless of everything else. A plain weighted average would be wrong here:
  a 100 % overcast but calm, dark night would still score ~60.
- **Weighted factors** (0…1) model *quality* once the sky is usable. Their weights sum to 1 per
  assessment. Factors with unavailable data are **excluded** (their weight is redistributed) and
  shown as *Unavailable*, and the confidence is lowered. They are never assumed ideal.
- If **cloud cover** is missing, no score is produced at all ("—" / "No data").

All interpolation uses piecewise-linear curves through the knots listed below (clamped at the ends).

## 2. Gates

### Cloud cover
Effective cloud `c` (0–1):
- Deep-sky and general: total cloud cover.
- Moon and planets: `max(low, mid, 0.5 × high)` when layers are available, because bright targets
  shine through thin cirrus (otherwise total).

`gate = (1 − c)^k`, with `k` = 1.5 (deep-sky), 1.2 (general), 1.0 (planets), 0.8 (Moon).
*Rationale*: any cloud costs faint-object observers the most (it blocks targets and scatters
skyglow). Bright targets only need gaps.

| Cloud | Deep-sky | General | Planets | Moon |
|---|---|---|---|---|
| 10 % | 0.85 | 0.88 | 0.90 | 0.92 |
| 30 % | 0.59 | 0.65 | 0.70 | 0.75 |
| 50 % | 0.35 | 0.44 | 0.50 | 0.57 |
| 80 % | 0.09 | 0.14 | 0.20 | 0.28 |

### Precipitation
- Base: `1 − 0.7 × p²` with `p` = precipitation probability (0–1). 30 % → 0.94, 60 % → 0.75, 100 % → 0.30.
- WMO code for fog (45/48) → at most 0.10. Drizzle, rain, snow or thunderstorm codes → at most 0.05.
- Precipitation ≥ 0.1 mm/h → **0**.

### Sky darkness (Sun altitude, degrees)
| Assessment | Knots (Sun altitude → gate) |
|---|---|
| Deep-sky | −18 → 1.0, −12 → 0.35, −6 → 0.05, −0.833 → 0 |
| General | −18 → 1.0, −12 → 0.8, −6 → 0.3, −0.833 → 0 |
| Planets | −12 → 1.0, −6 → 0.85, −3 → 0.5, −0.833 → 0.1, +5 → 0 |
| Moon | −6 → 1.0, −0.833 → 0.85, +10 → 0.6, +90 → 0.55 |

*Rationale*: USNO twilight definitions. Deep-sky needs astronomical night. Planets are bright
enough for civil/nautical twilight. The Moon is observable in daylight, with less contrast.

### Target above the horizon (Moon & planets only)
- Moon: 0 if below the horizon; 0.1 if illumination < 3 % (too close to new); else 1.
- Planets: 0 if none of Mercury–Saturn is above the horizon; else 1.

## 3. Weighted factors

| Factor | Data quality | Curve |
|---|---|---|
| **Transparency (est.)** | Estimated | RH knots 50 → 1.0, 70 → 0.85, 85 → 0.6, 95 → 0.3, 100 → 0.2; visibility km 1 → 0, 5 → 0.4, 10 → 0.7, 24 → 1.0; combined `0.6·RH + 0.4·vis` |
| **Seeing (est.)** | Estimated | 250 hPa wind km/h: ≤70 → 1.0, 110 → 0.75, 150 → 0.5, 200 → 0.25, 250 → 0.1; × gust penalty (35 → 1.0, 60 → 0.7 km/h). *Unavailable* if 250 hPa wind is missing |
| **Wind** | Forecast | `max(speed, 0.7·gust)` km/h: ≤10 → 1.0, 20 → 0.8, 30 → 0.55, 40 → 0.3, 55 → 0.1, 70 → 0 |
| **Dew risk** | Forecast | dew-point spread °C: 0 → 0.3, 2 → 0.5, 4 → 0.8, ≥6 → 1.0 |
| **Moonlight** | Calculated | `b = illum × √sin(alt)` (0 if Moon below horizon); deep-sky `1 − 0.9b`, general `1 − 0.6b` |
| **Light pollution** | User-provided | Bortle B: deep-sky `1 − (B−1)/8 × 0.85`, general `1 − (B−1)/8 × 0.7`; *Unavailable* if not set |
| **Target altitude** | Calculated | 0° → 0, 5° → 0.1, linear to 1.0 at 40° (airmass ≈ 1.6) |
| **Lunar phase** | Calculated | illumination 0.03 → 0.5, 0.25–0.75 → 1.0, 0.97 → 0.65 |

The moonlight form follows the dependence in Krisciunas & Schaefer (1991): scattered moonlight
grows with phase and with lunar altitude (shorter path through the atmosphere). The square root
softens the altitude dependence.

### Weights

| Factor | General | Moon | Planets | Deep-sky |
|---|---|---|---|---|
| Transparency | **0.30** | 0.05 | 0.05 | **0.30** |
| Light pollution | **0.25** | — | — | **0.25** |
| Moonlight | **0.25** | — | — | **0.25** |
| Seeing | 0.05 | **0.35** | **0.45** | 0.05 |
| Target altitude | — | 0.25 | 0.25 | — |
| Lunar phase | — | 0.15 | — | — |
| Wind | 0.05 | 0.15 | 0.15 | 0.10 |
| Dew | 0.10 | 0.05 | 0.10 | 0.05 |

**Why these weights**
- *Deep-sky / general*: detection of faint, extended objects is a contrast problem. Sky background
  (light pollution + moonlight) and atmospheric extinction (transparency) dominate, so they
  carry 80 % between them. Seeing hardly affects low-power views (5 %). Wind matters more with a
  telescope (10 %) than for naked-eye viewing (5 %).
- *Planets*: resolution-limited, so seeing is the largest single factor (45 %). Altitude
  (25 %) matters both for airmass and because low-altitude air is more turbulent. Wind shake is
  amplified at the high magnifications used (15 %). Light pollution and moonlight do not affect
  bright disks and are excluded.
- *Moon*: like planets but slightly less seeing-critical (the Moon is bright and large), plus a
  phase term rewarding terminator detail (15 %).
- Dew has a small weight everywhere because it is manageable with heaters but can end a session.

These weights are informed judgements based on the observing literature and common practice
(see the research document). They are not fitted to data. They are kept in one function
(`weightsFor`) so they can be tuned, and a unit test checks that each row sums to 1.

## 4. Categories

| Score | Rating |
|---|---|
| 80–100 | Excellent |
| 60–79 | Good |
| 40–59 | Fair |
| 20–39 | Poor |
| 0–19 | Unsuitable |
| no cloud data | No data |

**Confidence** reflects the weighted share of factors with data: High ≥ 85 % (and all gates
known), Medium ≥ 60 %, otherwise Low. Example: an unset Bortle class plus a missing jet-stream
wind lowers confidence.

## 5. Explanations

Each factor carries a human-readable detail string and an impact (Helps ≥ 0.75, Neutral ≥ 0.45,
Hurts < 0.45; a gate below 0.5 is Limiting). The summary names the limiting gate if one exists,
otherwise the most helpful and the most harmful weighted factor. For example: *"Good for deep sky:
likely transparent, but bright moonlight."*

## 6. Night forecast & best window

For each hour from the current hour (or from one hour before sunset if it is still daytime) to
sunrise (capped at 16 h), Polaris evaluates the sky at the hour's midpoint against that hour's
forecast. The **best window** per assessment is the peak hour, extended in both directions while
scores stay within 10 points of the peak. No window is reported if the peak is below 40 (Fair).

## 7. Object ranking (Sky / Explore / "Worth a look now")

Separate from the 0–100 scores. Observability (0–1) is calculated as
`altitude factor × brightness margin × Moon-proximity factor × (1 − cloud) × type appeal`:
- Limiting magnitude: Bortle NELM (unknown → 5.5), capped by twilight (−4 day, 1.5 at civil
  dusk, 4.0 at nautical dusk), minus up to ~1.8 mag of moonlight. Telescope reach is
  NELM + 4.5 mag (≈100 mm aperture).
- Extended objects: +0.8 mag per doubling of size beyond 5′ (a crude surface-brightness penalty;
  open clusters exempt).
- Deep-sky objects within 30° of a > 30 %-lit Moon above the horizon are penalised.
- The Sun is never recommended, and its card always carries a solar-filter warning.

## 8. Limitations

- **Seeing and transparency are estimates.** No free weather API measures them. The 250 hPa wind
  proxy captures jet-stream turbulence, but not the ground layer, local terrain or telescope
  thermal effects.
- **Model, not observation.** "Current" weather is model analysis at ~1–11 km grid resolution.
  Local fog, valley inversions and small showers can be missed.
- **Visibility caps.** Many models cap visibility at ~24 km, so it only flags hazy/foggy air.
- **Light pollution is user-supplied.** Without a Bortle class that factor is excluded.
- **Weights are expert judgement**, not calibrated against observer reports. Future versions could
  calibrate against logged sessions.
- **Single-instant scoring.** The score does not account for how long a clear spell lasts. The
  best-window view helps here.
- The object model ignores the observer's actual instrument and their dark adaptation.
