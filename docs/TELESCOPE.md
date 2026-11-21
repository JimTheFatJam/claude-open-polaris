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

## Mode 2 — Phone plane

Picture the phone lying on a sheet of glass: the plane of its screen. The plane moves with the
phone when it **tilts** (top edge up and bottom edge down, or left edge up and right edge down). It
does **not** move when the phone **spins within its own plane** (top edge to the right, bottom edge
to the left).

The goal is for the plane to **pass through the selected object**: the object's direction lies in
the plane, at any spin angle.

- Express the object's direction **t** in phone coordinates: **t'** = **R**ᵀ·**t**.
- **Off-plane angle** = asin(t'_z). This is the single number to bring to 0. Spinning the phone
  within its plane rotates t' about Z, which leaves t'_z unchanged. That is exactly the property
  described above.
  - Positive means the object is on the **screen side** of the plane. Negative means it's behind.
- **Which way to tilt**: the object's direction within the plane is atan2(t'_x, t'_y), clockwise
  from the top edge. The fastest correction swings the edge facing the object toward the side the
  object is on. Polaris names that edge (top, top-right, right, …) and uses **gravity** to phrase it:
  - screen facing mostly up → "**Raise** the top-left edge 29°"
  - screen facing mostly down → "**Lower** the … edge"
  - screen close to vertical (up/down is ambiguous) → "Tilt the … edge toward the screen side"
- The screen shows a phone diagram (an arrow to the object's direction within the plane, the
  edge to move highlighted), the signed off-plane angle, and a meter. It reads aligned within 1°.

Plane mode fixes only one condition, so it tells you the object is **somewhere in the plane**, not
where in it. This suits a phone strapped flat on a surface parallel to a telescope tube. Once the
plane contains the object, swing the tube *within that plane* to it. The arrow on the diagram shows
which way that is.

## Heading: compass, gyro and star sync

Tilt (altitude, and the off-plane angle for objects near the zenith) depends on **gravity only**.
Azimuth depends on the heading reference:

| Heading source | Pros | Cons |
|---|---|---|
| **Compass** (rotation vector) | Absolute, no drift. Declination corrected with the World Magnetic Model | Steel mounts, tripods, magnets, cars and electronics bias it by 2–15° |
| **Gyro only** (game rotation vector) | Immune to magnetic interference | Arbitrary starting direction. Drifts a few degrees per 10–30 min. Needs a sync |

**Star sync** (optional with the compass, required with the gyro): aim the back of the phone at a
bright object, hold still for 1.5 s and tap *Sync*. The azimuth difference is stored as this
session's heading correction and used by **both** modes. The altitude difference is shown as a
sanity check. Readings are averaged, and the sync is rejected if the phone moved more than 0.6°.
After 15 minutes in gyro mode the app suggests re-syncing.

## Accuracy (consumer phone sensors; estimates)

| Quantity | Typical |
|---|---|
| Tilt / altitude / off-plane angle, gravity part | ±0.5–1° |
| Azimuth with compass, no sync | ±3–15° (site dependent) |
| Azimuth with compass, after a sync | ±1–3° near the sync object |
| Azimuth with gyro, after a sync | ±1–2°, then drifting a few degrees per 10–30 min |

Not telescope-grade pointing or plate solving: use it to get close, then a finder or a low-power
eyepiece.

## Changes from v0.2.0

The *Simple* and *Mounting profile* models were replaced by these two modes at the user's request.
The multi-star `MountingCalibration` solver remains in `core` (tested) but is no longer exposed in
the UI. v0.2.0 settings for the old modes are ignored.
