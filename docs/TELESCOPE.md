# Telescope navigation modes

Implementation: [`PhoneGuidance.kt`](../core/src/main/kotlin/io/github/jimthefatjam/polaris/core/telescope/PhoneGuidance.kt) ·
tests: [`PhoneGuidanceTest.kt`](../core/src/test/kotlin/io/github/jimthefatjam/polaris/core/telescope/PhoneGuidanceTest.kt).

Android's rotation-vector sensor gives a rotation matrix **R** mapping phone coordinates
(X = right edge, Y = top edge, Z = out of the screen) to world coordinates (east, north, up).
Gravity defines "up". The compass (or a star sync) defines "north".

## Mode 1 — Camera

The **back of the phone** (−Z, the rear-camera direction) should point straight at the object,
like photographing it.

- Pointing = **R**·(0, 0, −1), converted to azimuth/altitude.
- The guide shows the azimuth and altitude change needed ("Turn right 12°, raise 5°") and the
  separation. It's on target within 2°.

## Mode 2 — Phone plane (with its most vertical line)

Picture the phone lying on a sheet of glass: the plane of its screen. The plane moves with the
phone when it **tilts**. It does **not** move when the phone **spins within its own plane** (top
edge to the right, bottom edge to the left).

For pinpoint accuracy, two conditions must hold:

1. **The object lies in the plane.** Off-plane angle = asin(t'_z), where **t'** = **R**ᵀ·**t** is
   the object's direction in phone coordinates.
2. **The object lies on the plane's most vertical line**: the steepest line across the tilted plane,
   i.e. world-up projected onto the plane,

   ```
   v = u − (u·n)·n        (u = up, n = screen normal = R·(0,0,1))
   ```

   v points uphill within the plane. Its altitude is **90° − the phone's tilt**, and it points away
   from the direction the screen faces (when the screen faces upward).

Condition 2 implies condition 1 (v lies in the plane), so together they define a single pointing
direction, **v**. Both depend only on n, so **spinning the phone within its plane changes nothing**.

Guidance:
- **Pinpoint error** = angle between v and the target. It reads "on target" within 1°.
- **Primary instruction**: move v onto the target, for example "Turn left 84°, make the phone
  flatter 16°".
  - *Turn* = rotate the phone about the vertical. v's azimuth changes by exactly that amount.
  - *Steeper / flatter* = more upright or more level, because v's altitude is 90° − tilt.
- **Secondary**: the off-plane angle with a meter, and which edge to raise or lower to get the
  object into the plane at all (wording uses gravity; "toward the screen side" when the screen is
  near vertical).
- **Diagram**: a phone outline with a dashed line along the plane's vertical line, an arrow toward
  the target's direction within the plane, and the edge to tilt highlighted. At pinpoint alignment
  the arrow lies on the dashed line.

Limits of this mode:
- A phone lying **level** has no vertical line (it's undefined within 1.5° of level). The app asks
  you to tilt it.
- Near the **zenith** (phone almost upright) the vertical line points almost straight up, so its
  azimuth becomes sensitive to small tilts. Objects high overhead are better found in Camera mode.

## Telescope sync (plane mode): follow the real line of sight

The phone's plane, and its most vertical line, is rarely exactly parallel to the telescope's line of
sight. A few degrees of mounting offset is enough to miss a small planet like Saturn, even though
the Sun and Moon are easy. **Telescope sync** measures the real line of sight:

1. Track a bright object (the Moon is easiest) until it's centred in the telescope, or in the phone
   camera looking through it.
2. Choose that object and tap **Sync** while holding still. Polaris averages 1.5 s of sensor data
   (and rejects it if the telescope moved) and computes the object's position **at that exact
   moment**.
3. The telescope's axis in phone coordinates is solved: **d** = **R**ᵀ·**t** for one sync, or a
   least-squares fit with a heading correction for several syncs (`TelescopeSync`, reusing the
   tested `MountingCalibration` solver).
4. From then on, plane mode guides with the **telescope's line of sight** **R**·**d** ("Turn left
   3.2°, raise the telescope 1.1°", pinpoint error to 0.1°). The plane view stays below for reference.

Accuracy and lifetime:
- **One sync** removes the mounting offset *and* the current compass error. It's most accurate near
  the sync object, typically within ~1–2° for objects 15–20° away.
- **Two or more syncs at different altitudes** (e.g. the Moon, then Saturn or a bright star) separate
  heading error from mounting offset and extend accuracy across the sky. A per-sync residual and
  fit RMS are shown.
- If a new sync disagrees with the earlier ones by more than 5°, the phone has probably moved on the
  tube. The earlier syncs are replaced automatically.
- In compass mode, syncs are **saved** and reused next session, as long as the phone stays in the
  same place on the telescope (keeping the 5 most recent). In gyro mode they last for the session,
  because the gyro's heading reference restarts each time. Use **Reset telescope sync** after
  re-mounting the phone.

## Tracking moving objects

Objects drift across the sky with Earth's rotation, up to 0.25°/min (15°/hour × cos δ). The Moon
also moves against the stars. The Telescope screen recomputes every object's position **every
second**, so guidance follows the target continuously. The target card shows its live motion
(total rate plus azimuth and altitude components). Syncs use the position at the middle of the
capture window, so even a slow sync doesn't bias the calibration.

## Heading: compass, gyro and star sync

Tilt (altitude, and the off-plane angle for objects near the zenith) depends on **gravity only**.
Azimuth depends on the heading reference:

| Heading source | Pros | Cons |
|---|---|---|
| **Compass** (rotation vector) | Absolute, no drift. Declination corrected with the World Magnetic Model | Steel mounts, tripods, magnets, cars and electronics bias it by 2–15° |
| **Gyro only** (game rotation vector) | Immune to magnetic interference | Arbitrary starting direction. Drifts a few degrees per 10–30 min. Needs a sync |

**Star sync (camera mode)**: aim the back of the phone at a bright object, hold still for 1.5 s and
tap *Sync*. The azimuth difference is stored as this session's heading correction. (In plane mode,
use the telescope sync above instead. It corrects heading and mounting together.) The altitude difference is shown as a
sanity check. Readings are averaged, and the sync is rejected if the phone moved more than 0.6°.
After 15 minutes in gyro mode the app suggests re-syncing.

## Accuracy (consumer phone sensors; estimates)

| Quantity | Typical |
|---|---|
| Tilt / altitude / off-plane angle / vertical-line altitude (gravity) | ±0.5–1° |
| Azimuth with compass, no sync | ±3–15° (site dependent) |
| Azimuth with compass, after a sync | ±1–3° near the sync object |
| Azimuth with gyro, after a sync | ±1–2°, then drifting a few degrees per 10–30 min |

Not telescope-grade pointing or plate solving: use it to get close, then a finder or a low-power
eyepiece.

## History

- v0.5.0: telescope sync (follow the measured line of sight; saved; multi-object) and live tracking of moving objects with motion rates.
- v0.4.0: plane mode pinpoints with the plane's most vertical line (previously only "in the plane").

### v0.3.0 changes from v0.2.0

The *Simple* and *Mounting profile* models were replaced by these two modes at the user's request.
The multi-star `MountingCalibration` solver remains in `core` (tested) but is no longer exposed in
the UI. v0.2.0 settings for the old modes are ignored.
