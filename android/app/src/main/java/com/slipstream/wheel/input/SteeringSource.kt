package com.slipstream.wheel.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.slipstream.wheel.protocol.Slp

/**
 * Turns the phone's attitude into the steer value.
 *
 * Preferred sensor is TYPE_GAME_ROTATION_VECTOR (gyro fused, no magnetometer, so no drift
 * from nearby metal), sampled at SENSOR_DELAY_FASTEST on a dedicated HandlerThread at
 * URGENT_DISPLAY priority. Without a gyroscope it falls back to TYPE_GRAVITY, then to
 * TYPE_ACCELEROMETER with a stronger filter. [kind] says which one is active.
 */
class SteeringSource(
    context: Context,
    private val state: ControllerState,
    config: SteeringProcessor.Config,
) : SensorEventListener {

    enum class Kind { ROTATION_VECTOR, GRAVITY, ACCELEROMETER, NONE }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor?
    val kind: Kind

    init {
        val sm = sensorManager
        val rv = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val gravity = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        when {
            rv != null -> { sensor = rv; kind = Kind.ROTATION_VECTOR }
            gravity != null -> { sensor = gravity; kind = Kind.GRAVITY }
            accel != null -> { sensor = accel; kind = Kind.ACCELEROMETER }
            else -> { sensor = null; kind = Kind.NONE }
        }
    }

    private val processor = SteeringProcessor(config.copy(strongFilter = kind == Kind.ACCELEROMETER))
    private val matrix = FloatArray(9)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var first = true

    /** Display rotation (Surface.ROTATION_*), set by the activity. */
    @Volatile
    var rotation: Int = SteeringMath.ROTATION_90

    /** Calibrated center in radians, published after [recenter] for persisting. */
    @Volatile
    var center: Double = config.centerRad
        private set

    /** Normalized steering, -1..1, for the gauge. */
    @Volatile
    var normalized: Double = 0.0
        private set

    /** Measured sensor event rate over the last second. */
    @Volatile
    var rateHz: Int = 0
        private set

    private var rateCount = 0
    private var rateWindowStartNs = 0L

    private val recenterTask = Runnable {
        processor.recenter()
        center = processor.center
    }

    fun start() {
        val s = sensor
        if (s == null || sensorManager == null) {
            state.setFlag(Slp.FLAG_CALIBRATING, false)
            return
        }
        first = true
        state.setFlag(Slp.FLAG_CALIBRATING, true)
        val t = HandlerThread("slip-steer", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        t.start()
        thread = t
        val h = Handler(t.looper)
        handler = h
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, h)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** The current physical angle becomes straight ahead. Runs on the sensor thread. */
    fun recenter() {
        handler?.post(recenterTask)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx: Float
        val gy: Float
        val gz: Float
        if (kind == Kind.ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            gx = matrix[6]
            gy = matrix[7]
            gz = matrix[8]
        } else {
            gx = event.values[0]
            gy = event.values[1]
            gz = event.values[2]
        }
        val v = processor.process(gx.toDouble(), gy.toDouble(), gz.toDouble(), rotation, event.timestamp)
        normalized = processor.lastNormalized
        state.setSteer(v)
        if (first && processor.hasSample) {
            first = false
            state.setFlag(Slp.FLAG_CALIBRATING, false)
        }
        rateCount++
        val dt = event.timestamp - rateWindowStartNs
        if (dt >= 1_000_000_000L) {
            rateHz = if (rateWindowStartNs == 0L) 0 else (rateCount * 1_000_000_000L / dt).toInt()
            rateCount = 0
            rateWindowStartNs = event.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
