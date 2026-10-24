package com.breezy.music.model

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 实时步频（SPM，Steps Per Minute）采集器
 *
 * 使用计步传感器 [Sensor.TYPE_STEP_COUNTER]（开机累计步数，最省电、精度最高）：
 *  - 设备没有计步器时退化为 [Sensor.TYPE_ACCELEROMETER] 峰值检测（实现见 [AccelStepDetector]）；
 *  - 以最近 [WINDOW_MS] 毫秒的步数增量估算 SPM，并做指数平滑，避免读数剧烈跳动；
 *  - 长时间无步数（> [IDLE_RESET_MS]）时把读数归零，避免「站着不动仍显示 160 SPM」。
 *
 * Android 10（API 29）起读取计步器需要 `ACTIVITY_RECOGNITION` 运行时权限，
 * 未授权时 [start] 返回 false，UI 应引导用户改用「手动设置步频」。
 */
object CadenceTracker : SensorEventListener {

    private const val WINDOW_MS = 8_000L
    private const val IDLE_RESET_MS = 5_000L
    private const val SMOOTHING = 0.35f

    /** 当前步频（SPM），0 表示尚未测到 */
    var cadenceSpm by mutableStateOf(0)
        private set

    /** 是否正在采集 */
    var isTracking by mutableStateOf(false)
        private set

    /** 是否使用了加速度计兜底 */
    var usingAccelerometer by mutableStateOf(false)
        private set

    /** 最近一次错误（用于 UI 提示） */
    var error by mutableStateOf<String?>(null)
        private set

    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var accelDetector: AccelStepDetector? = null

    private var lastStepValue = -1f
    private var lastStepTimeMs = 0L
    private val stepTimestamps = ArrayDeque<Long>()   // 每次检测到步伐的时间
    private var smoothedSpm = 0f
    private var lastAccelStepMs = 0L

    /** 开始采集；返回 false 表示无可用传感器（应由 UI 提示改用手动步频） */
    fun start(context: Context): Boolean {
        if (isTracking) return true
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            error = "设备不支持传感器"
            return false
        }
        sensorManager = sm
        stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (stepSensor != null) {
            if (!sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)) {
                error = "计步传感器注册失败"
                return false
            }
            usingAccelerometer = false
        } else if (accelSensor != null) {
            if (!sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)) {
                error = "加速度传感器注册失败"
                return false
            }
            accelDetector = AccelStepDetector()
            usingAccelerometer = true
        } else {
            error = "设备没有计步/加速度传感器"
            return false
        }
        error = null
        isTracking = true
        cadenceSpm = 0
        lastStepValue = -1f
        stepTimestamps.clear()
        return true
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        isTracking = false
        usingAccelerometer = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = event.timestamp / 1_000_000L
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            handleStepCounter(event.values.firstOrNull() ?: return, nowMs)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val det = accelDetector ?: return
            val stepped = det.onAccel(event.values[0], event.values[1], event.values[2], nowMs)
            if (stepped) registerStep(nowMs)
        }
    }

    private fun handleStepCounter(total: Float, nowMs: Long) {
        if (lastStepValue < 0f) {
            lastStepValue = total
            lastStepTimeMs = nowMs
            return
        }
        val prevStepMs = lastStepTimeMs
        val delta = total - lastStepValue
        if (delta >= 1f) {
            // 一次性补上期间所有步伐时间点（近似均匀分布）
            val steps = delta.toInt().coerceIn(1, 20)
            for (i in 1..steps) {
                registerStep(nowMs - ((steps - i) * (nowMs - prevStepMs) / steps))
            }
            lastStepTimeMs = nowMs
        }
        lastStepValue = total
        // 静止超过 IDLE_RESET_MS 则清零，避免停跑后读数定型
        if (nowMs - lastStepTimeMs > IDLE_RESET_MS) {
            smoothedSpm = 0f
            cadenceSpm = 0
            stepTimestamps.clear()
        }
    }

    private fun registerStep(nowMs: Long) {
        if (nowMs - lastAccelStepMs < 180L) return   // 最小步间隔 180ms（约 200 步/分上限）
        lastAccelStepMs = nowMs
        stepTimestamps.addLast(nowMs)
        while (stepTimestamps.isNotEmpty() && nowMs - stepTimestamps.first() > WINDOW_MS) {
            stepTimestamps.removeFirst()
        }
        if (stepTimestamps.size >= 4) {
            val span = (stepTimestamps.last() - stepTimestamps.first()).coerceAtLeast(1L)
            val instant = (stepTimestamps.size - 1) * 60_000f / span
            smoothedSpm = if (smoothedSpm <= 0f) instant
            else smoothedSpm + (instant - smoothedSpm) * SMOOTHING
            cadenceSpm = smoothedSpm.toInt().coerceIn(0, 260)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * 加速度计兜底步态检测：检测垂直加速度的低通信号的“上穿阈值”事件。
     * 仅用于没有计步器的老设备，精度有限。
     */
    private class AccelStepDetector {
        private var gravity = FloatArray(3)
        private var lastStepMs = 0L
        private var threshold = 1.35f
        private var wasBelow = true

        fun onAccel(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
            if (Build.VERSION.SDK_INT >= 9) {
                gravity[0] = gravity[0] * 0.85f + x * 0.15f
                gravity[1] = gravity[1] * 0.85f + y * 0.15f
                gravity[2] = gravity[2] * 0.85f + z * 0.15f
            }
            val lx = x - gravity[0]
            val ly = y - gravity[1]
            val lz = z - gravity[2]
            val norm = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
            val isStep = when {
                norm > threshold && wasBelow -> {
                    wasBelow = false
                    if (nowMs - lastStepMs > 250L) {
                        lastStepMs = nowMs
                        true
                    } else false
                }
                norm < threshold * 0.5f -> {
                    wasBelow = true
                    false
                }
                else -> false
            }
            // 自适应阈值：跟随当前运动强度，避免慢跑/快跑均失败
            threshold = (threshold * 0.98f + norm * 0.02f).coerceIn(1.0f, 3.0f)
            return isStep
        }
    }
}
