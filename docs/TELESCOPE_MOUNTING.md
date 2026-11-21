# Telescope mounting model and persistent calibration

Status: implemented in v0.2.0 (`core/.../telescope/MountingCalibration.kt`, Telescope screen ›
"Mounting profile" mode). The v0.1.0 "Simple" mode remains available as a fallback.

## 1. The problem

The mount this was designed for: a refractor-style tube with a 90° star diagonal, observed by
looking down into the eyepiece. The phone is strapped **flat on a surface parallel to the tube**.
The phone's top edge does **not** necessarily point along the tube. The only assumption is that
the phone's screen plane is (approximately) parallel to the tube axis.

The diagonal mirror doesn't matter. The pointing direction is the axis of the objective, and
calibration measures it directly, whatever the eyepiece arrangement.

## 2. What v0.1.0 did, and why it isn't enough

| v0.1.0 | Limitation for this mount |
|---|---|
| The tube is assumed to be the phone's top edge (+Y) or rear-camera axis (−Z) | Wrong whenever the phone is rotated in its plane relative to the tube |
| "Alignment" adds a constant Δazimuth/Δaltitude in the **world** frame | A mounting angle is a rotation in the **phone's** frame. Its effect on az/alt changes as the tube moves in altitude and as the tube/phone rolls, so a constant world offset is only right near the alignment star |
| Re-done every session | Discards the mounting geometry, which doesn't change |

## 3. Can we compute the tube's direction from the phone's IMU? Yes.

Android's rotation-vector sensors give a rotation matrix **R** mapping phone (device) coordinates
to world coordinates (X = east, Y = north, Z = up). If the tube axis expressed **in phone
coordinates** is the unit vector **d**, then wherever the telescope points, and however the tube
and phone are rolled,

```
w = R · d          (world direction of the tube)
altitude = asin(w_z)
azimuth  = atan2(w_x, w_y) + heading correction
```

**d is a property of the mounting only**, so it can be measured once and reused.

Because the screen plane is parallel to the tube, **d** is almost in the phone's XY plane:

```
d = ( cos e · sin θ,  cos e · cos θ,  sin e )
```

- **θ — in-plane mounting angle**: the angle from the phone's top edge to the tube direction,
  measured clockwise when looking at the screen (0° = top edge points along the tube, 90° = right
  edge, 180° = bottom edge…). It can take any value.
- **e — out-of-plane tilt**: how far the tube leans out of the screen plane (a wedge-shaped strap,
  a curved tube). Ideally ≈ 0°. It is fitted rather than assumed, and the app warns if it exceeds 5°.

Rolling the tube about its own axis (common when a diagonal is rotated for comfort, or on
equatorial mounts) changes **R**, but **R·d** still gives the right direction. Phone rotation is
handled automatically.

## 4. What else has to be estimated: heading

The rotation matrix's "north" is only as good as its heading reference:

- **Compass mode** (`TYPE_ROTATION_VECTOR`: gyro + accelerometer + magnetometer). This gives
  absolute heading relative to **magnetic** north. Polaris corrects declination with the World
  Magnetic Model (`GeomagneticField`). The remaining error is local magnetic interference: steel
  mounts and tripods, magnets in phone cases, car bodies. It is typically 2–15° and **changes from
  site to site and session to session**.
- **Gyro mode** (`TYPE_GAME_ROTATION_VECTOR`: gyro + accelerometer, no magnetometer). This is
  immune to magnetic interference, but heading is relative to an arbitrary starting direction and
  **drifts slowly** (gyro bias), typically a few degrees per 10–30 minutes depending on the phone.
  It needs a star sync at the start of a session, and occasionally during it.

Altitude depends only on gravity (accelerometer), in both modes. It is unaffected by magnetic
interference and is the most trustworthy number.

So the model has one per-session unknown: a **heading correction β** (rotation about the vertical).
That gives two calibrations with different lifetimes:

| Calibration | Parameters | Lifetime | Stars needed |
|---|---|---|---|
| **Mounting calibration** | θ, e (the vector **d**) | Persistent: saved as a named profile, valid until the phone is re-strapped differently | 2–5 (≥ 3 recommended) |
| **Session sync** (optional in compass mode, required in gyro mode) | β | One session or site. Redo if heading drifts | 1 |

## 5. Calibration procedure (done once per mounting)

1. Strap the phone flat on the tube (or a surface parallel to it), screen up, as it will stay. Mark
   its position if the bracket is removable.
2. Choose the heading source. Calibrating in **compass mode** is easiest. Stay away from cars and
   metal, and do the figure-8 compass calibration first.
3. Pick a bright star, planet or the Moon from the list (only objects currently above the horizon
   are offered).
4. Centre it in the eyepiece (use a high power for best accuracy) and hold still. Tap **Record
   sighting**. Polaris averages about 1.5 s of sensor data and rejects the sample if the phone was
   moving.
5. Repeat for **2–5 objects**, preferably:
   - **at clearly different altitudes** (≥ 20° apart): this is what separates the mounting angle
     from heading error, and
   - in different directions (spread in azimuth), and
   - optionally with the tube rolled differently, which helps fit the tilt *e*.
6. Tap **Compute**. Polaris shows the fitted θ and e, the heading correction found, and the
   residual of each sighting (RMS and worst case).
7. Name the profile and **Save**. It becomes the active profile and is used in every future session.

### Each session afterwards

- **Compass mode**: open the Telescope screen. Pointing works immediately using the saved profile.
  For better accuracy, do a one-star **Sync**: centre any listed object and tap Sync. This measures
  today's heading correction. Its altitude error is also shown, as a "has the mount shifted?" check.
- **Gyro mode**: Sync on one object first (required), then re-sync if pointing drifts. The app shows
  the time since the last sync.

## 6. How the fit works

For N sightings, each with an averaged rotation **Rᵢ** and the object's apparent direction **tᵢ**
(converted to the sensor's world frame: true azimuth − declination), Polaris minimises

```
Σᵢ angle( Rz(β) · Rᵢ · d , tᵢ )²
```

over **d** (unit vector) and **β**:

- For a fixed β, the best **d** has a closed form: `d = normalise(Σ Rᵢᵀ · Rz(−β) · tᵢ)`, the
  maximiser of the summed cosine similarity.
- β is found by a coarse 1° scan over 360°, followed by golden-section refinement.
- **Degeneracy check**: if all sightings were taken at similar tube altitudes and phone
  orientations, a change in β can be absorbed by a change in θ, so heading and mounting can't be
  separated. Polaris detects this by checking how much the RMS rises when β moves ±5° from the
  optimum. In compass mode it then fixes β = 0 (trusting the compass) and tells you to add a
  sighting at a different altitude. In gyro mode it asks for more varied sightings.
- With **one** sighting the result is exact but includes that session's compass error. The app
  allows saving it but labels it low-confidence.

## 7. Error sources and how each is handled

| Source | Effect | Mitigation in Polaris |
|---|---|---|
| Phone not perfectly parallel to tube | Tilt *e* | Fitted (two-parameter axis model). Warning if \|e\| > 5° |
| Phone rotated in its plane (the main issue) | θ | Fitted, any angle |
| Tube/phone rolled during the night | Changes **R** only | Handled exactly by **R·d** |
| Magnetic interference | Heading bias, varies by site | Session sync (β); field-strength check (25–65 µT expected); gyro mode as an alternative |
| Magnetic declination | Constant heading offset per location | World Magnetic Model correction |
| Compass not calibrated | Large, erratic heading errors | Accuracy indicator. Calibration sightings warn when accuracy is low |
| Gyroscope drift (gyro mode) | Heading drifts slowly | Time-since-sync shown, re-sync prompt after 15 min. Compass mode has no drift (it trades drift for interference) |
| Sensor noise / hand shake while recording | Random error per sighting | 1.5 s averaging, rejection if motion exceeds 0.6°. Residuals shown per sighting |
| Phone moved or re-strapped | Profile no longer valid | Sync shows the altitude error. A large altitude residual (> 2°) suggests re-calibrating |
| Atmospheric refraction | Up to 0.5° near the horizon | Target positions are apparent (refracted) positions |
| Mount not level, flexure, cone error between optical and mechanical axis | Small systematic errors | Calibration measures the optical axis directly. Flexure is not modelled |

## 8. Expected accuracy

Phone IMUs are consumer-grade, so these are realistic expectations, not guarantees:

- **Altitude**: typically **±0.5–1.5°** after calibration (accelerometer-based).
- **Azimuth, compass mode, after a session sync**: typically **±1–3°** near the sync star, growing
  with distance from it if the magnetic distortion isn't uniform. **±3–10°** without a sync.
- **Azimuth, gyro mode**: ±1–2° right after a sync, degrading by roughly a few degrees per 10–30
  min of drift.
- The residuals shown after calibration are the best estimate for your own phone and mount.

That puts targets in a low-power eyepiece (1–2° field) or a finder field most of the time.
Small faint objects still need star-hopping for the final step. **This is not telescope-grade
pointing or plate solving.**

## 9. Fallback

The v0.1.0 behaviour ("Simple" mode: top edge or rear camera plus a one-star az/alt offset) is
unchanged and selectable. Profiles are optional. With no saved profile, the Telescope screen
behaves exactly as before.
