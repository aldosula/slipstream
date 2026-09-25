package com.slipstream.wheel.pad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.slipstream.wheel.input.SteeringMath
import com.slipstream.wheel.protocol.Slp

/**
 * Raw motion for the PAD packet and gyro aim. TYPE_GYROSCOPE and TYPE_ACCELEROMETER at
 * SENSOR_DELAY_FASTEST on a dedicated HandlerThread at URGENT_DISPLAY priority, rotated into
 * the controller frame and scaled to the wire units. [sendRaw] (PlayStation style) sets the
 * MOTION flag once both sensors have delivered a sample. Gyro aim, when active, is written
 * on every gyroscope event. Nothing on the event path allocates.
 */
class MotionSource(
    context: Context,
    private val state: PadState,
    private val sendRaw: Boolean,
    private val aimMode: GyroAim.Mode,
    private val aimSensitivity: Float,
    private val aimInvertY: Boolean,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val v = FloatArray(3)
    private val clearTask = Runnable {
        state.setFlag(Slp.FLAG_MOTION, false)
        state.setGyroAim(0)
    }
    private var gotGyro = false
    private var gotAccel = false

    /** Display rotation (Surface.ROTATION_*), set by the activity. */
    @Volatile var rotation: Int = SteeringMath.ROTATION_90

    val hasGyroscope: Boolean get() = gyroSensor != null

    /** Anything to do: raw motion for the hub, or gyro aim. */
    val needed: Boolean get() = (sendRaw || aimMode != GyroAim.Mode.OFF) && gyroSensor != null

    fun start() {
        val sm = sensorManager ?: return
        if (!needed || thread != null) return
        gotGyro = false
        gotAccel = false
        val t = HandlerThread("slip-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        gyroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        if (sendRaw) accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
    }

    /**
     * Unregisters, then clears MOTION and gyro aim on the sensor thread itself, after any
     * event already queued there, so a late event cannot set MOTION again behind the stop.
     */
    fun stop() {
        sensorManager?.unregisterListener(this)
        val h = handler
        if (h != null) h.post(clearTask) else clearTask.run()
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        MotionMath.toController(rotation, values[0], values[1], values[2], v)
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (sendRaw) state.setGyro(MotionMath.gyroUnits(v[0]), MotionMath.gyroUnits(v[1]), MotionMath.gyroUnits(v[2]))
                if (aimMode != GyroAim.Mode.OFF) {
                    val aim = if (GyroAim.active(aimMode, state.rightStickTouched)) {
                        GyroAim.deflection(MotionMath.radToDps(v[0]), MotionMath.radToDps(v[1]), aimSensitivity, aimInvertY)
                    } else {
                        0
                    }
                    state.setGyroAim(aim)
                }
                gotGyro = true
            }
            Sensor.TYPE_ACCELEROMETER -> {
                state.setAccel(MotionMath.accelUnits(v[0]), MotionMath.accelUnits(v[1]), MotionMath.accelUnits(v[2]))
                gotAccel = true
            }
        }
        if (sendRaw && gotGyro && gotAccel && state.flagBits and Slp.FLAG_MOTION == 0) {
            state.setFlag(Slp.FLAG_MOTION, true)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
