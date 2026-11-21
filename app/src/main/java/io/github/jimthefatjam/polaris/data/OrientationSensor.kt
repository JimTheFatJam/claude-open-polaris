package io.github.jimthefatjam.polaris.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

enum class OrientationSource(val label: String) {
    ROTATION_VECTOR("Rotation vector (gyro + accel + magnetometer fusion)"),
    ACCEL_MAG("Accelerometer + magnetometer (no gyro fusion — noisier)"),
    NONE("No suitable sensors"),
}

data class SensorCapabilities(
    val rotationVector: Boolean,
    /** TYPE_GAME_ROTATION_VECTOR: gyro + accelerometer fusion without the magnetometer. */
    val gameRotationVector: Boolean = false,
    val accelerometer: Boolean,
    val magnetometer: Boolean,
    val gyroscope: Boolean,
) {
    val source: OrientationSource
        get() = when {
            rotationVector -> OrientationSource.ROTATION_VECTOR
            accelerometer && magnetometer -> OrientationSource.ACCEL_MAG
            else -> OrientationSource.NONE
        }
}

data class OrientationReading(
    /** Row-major 3×3 device→world (East, North, Up) rotation matrix. */
    val matrix: DoubleArray,
    /** SensorManager.SENSOR_STATUS_* of the magnetic/rotation sensor. */
    val accuracy: Int,
    /** Magnitude of the measured magnetic field in µT (null if not measured). Earth's field is ~25–65 µT. */
    val magneticFieldUt: Double?,
    val timestampNs: Long,
)

/**
 * Streams device orientation. Prefers TYPE_ROTATION_VECTOR (sensor fusion, drift-corrected by the
 * magnetometer); falls back to accelerometer + magnetometer. Missing hardware is reported through
 * [capabilities] instead of crashing.
 */
class OrientationSensor(context: Context) {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?

    val capabilities = SensorCapabilities(
        rotationVector = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null,
        gameRotationVector = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null,
        accelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
        magnetometer = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
        gyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
    )

    /**
     * @param gyroHeading use TYPE_GAME_ROTATION_VECTOR (no magnetometer): immune to magnetic
     *   interference, but heading is relative and drifts. Falls back to the normal source if absent.
     */
    fun readings(gyroHeading: Boolean = false): Flow<OrientationReading> = callbackFlow {
        val manager = sm
        if (manager == null || capabilities.source == OrientationSource.NONE) {
            close(); return@callbackFlow
        }
        val useGame = gyroHeading && capabilities.gameRotationVector
        val r = FloatArray(9)
        val gravity = FloatArray(3)
        val geomag = FloatArray(3)
        var haveGravity = false
        var haveMag = false
        var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var magStrength: Double? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(r, e.values)
                        trySend(OrientationReading(r.map { it.toDouble() }.toDoubleArray(), magAccuracy, magStrength, e.timestamp))
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(e.values, 0, geomag, 0, 3); haveMag = true
                        magStrength = sqrt((geomag[0] * geomag[0] + geomag[1] * geomag[1] + geomag[2] * geomag[2]).toDouble())
                        if (capabilities.source == OrientationSource.ACCEL_MAG && !useGame) emitAccelMag(e.timestamp)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Low-pass to isolate gravity.
                        for (i in 0..2) gravity[i] = if (haveGravity) 0.8f * gravity[i] + 0.2f * e.values[i] else e.values[i]
                        haveGravity = true
                        emitAccelMag(e.timestamp)
                    }
                }
            }

            private fun emitAccelMag(ts: Long) {
                if (useGame || capabilities.source != OrientationSource.ACCEL_MAG || !haveGravity || !haveMag) return
                if (SensorManager.getRotationMatrix(r, null, gravity, geomag)) {
                    trySend(OrientationReading(r.map { it.toDouble() }.toDoubleArray(), magAccuracy, magStrength, ts))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD || sensor.type == Sensor.TYPE_ROTATION_VECTOR) magAccuracy = accuracy
            }
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        if (useGame) {
            manager.registerListener(listener, manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), rate)
        } else if (capabilities.source == OrientationSource.ROTATION_VECTOR) {
            manager.registerListener(listener, manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), rate)
        } else {
            manager.registerListener(listener, manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), rate)
        }
        // The magnetometer is always registered (when present) to report field strength and accuracy.
        manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

        awaitClose { manager.unregisterListener(listener) }
    }
}
