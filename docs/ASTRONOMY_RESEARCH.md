# Astronomy Research: What Makes a Good Observing Night

This document summarises the observing science behind Polaris and explains which parts of it
can and cannot be obtained from public weather data. The scoring model in
[`STARGAZING_SCORING.md`](STARGAZING_SCORING.md) is built on these findings.

> **Measured vs. forecast vs. estimated.** Open-Meteo (and every other free general-purpose
> weather API we evaluated) returns **numerical weather model output**, not observations.
> Even the "current" block is a model analysis for the current hour. None of these APIs
> provide astronomical **seeing** or **transparency**. Polaris therefore labels every value as
> *Model (current)*, *Forecast*, *Estimated*, *Calculated*, *User-provided* or *Unavailable*,
> and never substitutes an ideal value for missing data.

---

## 1. Atmospheric seeing

**What it is.** Seeing is the blurring and twinkling of star images caused by turbulent mixing
of air of different temperatures (and therefore refractive indices) along the line of sight.
It is usually expressed as the full width at half maximum (FWHM) of a star image in
arcseconds, or on amateur scales such as the Pickering 1–10 scale or the Antoniadi I–V scale.

**Where it comes from.**
- *Ground layer* (first ~1 km): heat released by the ground, buildings, the telescope and the
  observer; strongly influenced by local site and surface wind.
- *Free atmosphere / jet stream* (~8–12 km, ~250–200 hPa): wind shear around the jet stream
  generates high-altitude turbulence. Strong jet-stream winds correlate with poor seeing
  (e.g. Bely 1987; Vernin & Muñoz-Tuñón 1994; Sarazin & Tokovinin 2002 relate the coherence
  time and seeing to the 200 hPa wind speed).
- *Instrument seeing*: tube currents and an optic warmer than the air (cool-down time).

**Effect on observing.** Seeing limits the **resolution** usable at high magnification. It is
the dominant factor for **planets, the Moon and double stars**, where fine detail matters, and
is a minor factor for low-power deep-sky observing and naked-eye stargazing.

**Data availability.** Seeing is *not* a standard meteorological variable. Specialist products
(Meteoblue "astronomy seeing", 7Timer! ASTRO, the Clear Sky Chart from CMC data) derive it from
model temperature/wind profiles using proprietary or research turbulence models. Open-Meteo does
provide **wind speed at 250 hPa**, which Polaris uses as a documented *proxy*, labelled
**Estimated**. It is a correlation, not a measurement.

## 2. Atmospheric transparency

**What it is.** Transparency is how clear the air column is: how much starlight is absorbed or
scattered by water vapour, aerosols (haze, dust, smoke, pollen), thin cirrus and volcanic
material. It is often quoted as the zenith naked-eye limiting magnitude or as aerosol optical
depth.

**Effect on observing.** Transparency controls **contrast and limiting magnitude**. Faint,
extended, low-surface-brightness targets (galaxies, nebulae) suffer first; bright planets and the
Moon are barely affected. Seeing and transparency are largely independent: steady, hazy summer
nights often have *good seeing but poor transparency*, while clear air behind a cold front
often has *excellent transparency but poor seeing*.

**Data availability.** No free API reports transparency directly. Proxies:
- **Relative humidity**: hygroscopic aerosols swell as RH rises, increasing scattering; haze
  increases markedly above ~80–85 % RH (Hänel 1976; WMO guidance on visibility).
- **Visibility** (model diagnostic, metres): a horizontal measure dominated by near-surface
  aerosols and fog. Note that several models cap visibility at 24.1 km (15 miles), so it only
  discriminates poor conditions.
- **High cloud** (thin cirrus) — included in total cloud cover.
Polaris combines RH and visibility into a transparency estimate labelled **Estimated**.

## 3. Cloud cover (low, middle, high)

Clouds are the single most important factor: an overcast sky prevents all observing.
- **Low cloud** (< ~2 km; stratus, stratocumulus, cumulus) is opaque.
- **Middle cloud** (~2–6 km; altostratus, altocumulus) is usually opaque.
- **High cloud** (> ~6 km; cirrus, cirrostratus) is often thin: bright objects (Moon,
  planets, bright stars) shine through, but it destroys deep-sky contrast and brightens the sky
  over cities by reflecting artificial light.

Open-Meteo provides total, low, mid and high cloud cover (% of sky) as model output. Model cloud
forecasts have real skill limits, especially for small-scale convective cloud and thin cirrus;
forecasts beyond ~24–48 h should be read as tendencies.

## 4. Humidity, dew point and dew

Dew forms on a surface when its temperature drops below the **dew point**. Telescope optics,
finders and eyepieces cool by radiating to the clear night sky, often reaching 1–3 °C *below*
air temperature. Practical observing guidance (e.g. Sky & Telescope and manufacturer dew-control
guidance) is therefore expressed in terms of the **dew-point depression** (temperature − dew point):
- spread < ~2 °C: dew very likely on exposed optics — dew heaters/shields needed;
- 2–5 °C: dew possible later in the night;
- > ~5 °C: low risk.

Dew is a nuisance that can end a session, but it can be managed, so Polaris treats it as a
low-weight quality factor rather than a show-stopper. High RH also degrades transparency (§2).

## 5. Wind

Surface wind shakes the telescope (especially long refractors and large Dobsonians acting as
sails), blurring high-power views. Light breeze helps by flushing tube currents and lowering dew
risk. Gusts matter more than mean speed. Typical amateur experience: < 10 km/h unnoticeable,
15–25 km/h shake at high power, > 30–40 km/h observing becomes difficult. Strong surface gusts
also indicate ground-layer turbulence, slightly degrading seeing.

## 6. Precipitation and general weather

Any precipitation means optics must be covered; fog (WMO codes 45/48) blocks the sky entirely.
Polaris uses Open-Meteo's precipitation amount (mm/h), precipitation probability (%) and WMO
weather code. Probability is a forecast likelihood; even 30–40 % indicates unsettled conditions.

## 7. Light pollution and the Bortle scale

Artificial skyglow raises the sky background brightness and drastically reduces the visibility
of low-contrast objects. John Bortle's nine-class scale (Bortle 2001, *Sky & Telescope*) relates
sky darkness to observable phenomena and naked-eye limiting magnitude (NELM):

| Class | Description | NELM (approx.) |
|---|---|---|
| 1 | Excellent dark-sky site | 7.6–8.0 |
| 2 | Typical truly dark site | 7.1–7.5 |
| 3 | Rural sky | 6.6–7.0 |
| 4 | Rural/suburban transition | 6.1–6.5 |
| 5 | Suburban sky | 5.6–6.0 |
| 6 | Bright suburban sky | 5.1–5.5 |
| 7 | Suburban/urban transition | 4.6–5.0 |
| 8 | City sky | 4.1–4.5 |
| 9 | Inner-city sky | ≤ 4.0 |

Quantitative sky brightness is mapped by the *New World Atlas of Artificial Night Sky Brightness*
(Falchi et al. 2016, *Science Advances*) from VIIRS satellite data. There is no free, keyless
API for it suitable for an app, so Polaris asks the user to choose their Bortle class (and
treats "unknown" as unavailable rather than dark).

Light pollution matters greatly for **deep-sky** and **naked-eye** observing, and almost not at
all for the **Moon and planets**.

## 8. The Moon: phase, illumination, altitude, separation

- **Phase / illumination**: the illuminated fraction rises from 0 (new) to 1 (full). A full Moon
  raises the sky background by several magnitudes per square arcsecond over much of the sky.
  Krisciunas & Schaefer (1991, *PASP* 103, 1033) give the standard model for scattered
  moonlight: brightness grows with lunar phase and altitude and falls with angular distance from
  the Moon.
- **For deep-sky observing**, the best nights are around new Moon, or when the Moon is below the
  horizon. Targets close to the Moon (< ~30°) suffer most.
- **For lunar observing**, the Moon must be up, and the most detail is seen along the
  **terminator**, where low-angle sunlight casts long shadows: crescent to gibbous phases are
  more rewarding than full Moon, which looks flat and glaring.
- Polaris computes illumination, phase angle, altitude/azimuth, rise/set and next new/full Moon
  with Astronomy Engine.

## 9. Twilight

Definitions are by the Sun's geometric centre altitude (US Naval Observatory):
- **Civil twilight**: Sun 0° to −6° (sunset/sunrise use −0.833° to include refraction and the
  solar radius). Bright planets and first-magnitude stars appear.
- **Nautical twilight**: −6° to −12°. Horizon still visible; most bright stars visible.
- **Astronomical twilight**: −12° to −18°. Sky is dark to the eye but residual twilight still
  affects faint deep-sky objects.
- **Astronomical night**: Sun below −18°. Fully dark sky.

At latitudes above ~48.5° the Sun does not reach −18° around the June solstice (no
astronomical darkness), and above the polar circles there is polar day/night. Polaris handles
these cases explicitly (verified in unit tests for Oslo and Tromsø).

## 10. Altitude, azimuth, rise/set and visibility

- **Altitude** is the angle above the horizon; **azimuth** is measured from true north through
  east. Polaris uses topocentric coordinates with standard atmospheric refraction.
- **Airmass** ≈ 1/sin(altitude) for altitudes above ~10°. At 30° the light passes through twice
  as much air as at the zenith; below ~20° extinction, dispersion and turbulence rise sharply.
  Objects are best observed near **transit** (highest point, due south in the northern
  hemisphere).
- **Rise/set** for solar-system bodies use Astronomy Engine's searches; fixed stars and deep-sky
  objects use the standard hour-angle formula
  cos H₀ = (sin h₀ − sin φ sin δ)/(cos φ cos δ) with h₀ = −0.5667°, which also identifies
  circumpolar and never-rising objects.

---

## 11. Which factors matter most for each kind of observing

| Factor | Lunar | Planetary | Deep-sky | Naked-eye |
|---|---|---|---|---|
| Cloud cover | Essential (thin cirrus tolerable) | Essential (thin cirrus tolerable) | Essential (any cloud hurts) | Essential |
| Precipitation / fog | Show-stopper | Show-stopper | Show-stopper | Show-stopper |
| Darkness (twilight) | Not needed (Moon visible in daylight) | Needs ~civil/nautical dusk | Needs astronomical night | Needs nautical/astronomical night |
| Target above horizon | Moon must be up | A planet must be up | — | — |
| **Seeing** | **High** | **Highest** | Low | Low |
| **Transparency** | Low | Low | **High** | **High** |
| **Moonlight** | Required (it *is* the target) | Irrelevant | **High (detrimental)** | Moderate (detrimental) |
| **Light pollution** | Irrelevant | Irrelevant | **High** | **High** |
| Target altitude | High | High | Moderate | Low |
| Wind | Moderate | Moderate | Moderate | Low |
| Dew | Low–moderate | Low–moderate | Low–moderate | Low |
| Lunar phase (terminator) | Moderate | — | — | — |

Summary:
1. **Lunar**: Moon up and reasonably high, steady air; phase near quarter is ideal. Moonlight,
   light pollution and darkness barely matter.
2. **Planetary**: a planet high in the sky and good seeing dominate. Light pollution and moonlight
   are irrelevant; thin high cloud is tolerable.
3. **Deep-sky**: dark sky (astronomical night, no Moon, low light pollution) and transparency
   dominate; seeing hardly matters at low power.
4. **Naked-eye stargazing**: clear, transparent, dark sky; moonlight and light pollution matter;
   seeing and wind barely matter.

## 12. Data sources evaluated

| Source | What it offers | Used? |
|---|---|---|
| **Open-Meteo Forecast API** | Global best-match NWP blend; cloud total/low/mid/high, RH, dew point, wind & gusts, 250 hPa wind, precipitation & probability, visibility, WMO code; hourly; no key; CC BY 4.0 | **Yes** |
| Open-Meteo Geocoding API | Place search with coordinates, elevation and IANA time zone | **Yes** (manual location) |
| 7Timer! ASTRO | Seeing/transparency indices (3-hourly, coarse) | No (HTTP only, coarse, availability) |
| Meteoblue astronomy seeing | Seeing, jet-stream, bad layers | No (commercial key) |
| Clear Sky Chart (CMC) | Seeing/transparency for North America | No (region-limited, no API) |
| Light-pollution atlases (Falchi 2016, lightpollutionmap.info) | Sky brightness | No public keyless API → user-selected Bortle class |

## References

- Bortle, J. E. (2001). "Introducing the Bortle Dark-Sky Scale." *Sky & Telescope*, 101(2), 126.
- Bely, P. Y. (1987). "Weather and seeing on Mauna Kea." *PASP*, 99, 560.
- Vernin, J. & Muñoz-Tuñón, C. (1994). "Optical seeing at La Palma Observatory." *A&A*, 284, 311.
- Sarazin, M. & Tokovinin, A. (2002). "The statistics of isoplanatic angle and adaptive optics time constant derived from DIMM data." ESO Conf. Proc. 58.
- Krisciunas, K. & Schaefer, B. E. (1991). "A model of the brightness of moonlight." *PASP*, 103, 1033.
- Falchi, F. et al. (2016). "The new world atlas of artificial night sky brightness." *Science Advances*, 2(6), e1600377.
- Hänel, G. (1976). "The properties of atmospheric aerosol particles as functions of the relative humidity." *Advances in Geophysics*, 19, 73.
- U.S. Naval Observatory, Astronomical Applications Dept. — "Rise, Set, and Twilight Definitions."
- Meeus, J. (1998). *Astronomical Algorithms*, 2nd ed. Willmann-Bell (rise/set hour-angle method, airmass).
- Cross, D. *Astronomy Engine* — https://github.com/cosinekitty/astronomy (VSOP87/NOVAS-validated ephemerides, MIT).
- Open-Meteo documentation — https://open-meteo.com/en/docs (variables, models, WMO codes).
- World Meteorological Organization, *Manual on Codes* (WMO-No. 306), weather interpretation codes 4677.
- Pickering, W. H. (1900s) seeing scale; Antoniadi, E. M. (1909) seeing scale — as summarised in standard observing handbooks (e.g. *Sky & Telescope* observing guides).
