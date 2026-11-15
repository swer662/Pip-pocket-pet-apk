package com.pippocket.pet

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * События физического мира, поступающие в движок питомца
 */
sealed class PetEvent {
    data object ShakeDetected : PetEvent()
    data object FreeFallDetected : PetEvent()
    data class FaceDownChanged(val isFaceDown: Boolean) : PetEvent()
    data object MicBlowDetected : PetEvent()
    data object LoudClapDetected : PetEvent()
    data object StepTaken : PetEvent()
}

/**
 * Телеметрия всех датчиков в реальном времени (для UI, игр и Debug Drawer)
 */
data class SensorTelemetry(
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 9.8f,
    val tiltX: Float = 0f,          // Нормализованный наклон (-1..1)
    val tiltY: Float = 0f,          // Нормализованный наклон (-1..1)
    val totalG: Float = 1.0f,
    val isFaceDown: Boolean = false,
    val soundDb: Float = 32f,
    val peakDb: Float = 32f,
    val isBlowing: Boolean = false,
    val batteryPct: Int = 85,
    val isCharging: Boolean = false,
    val sessionSteps: Int = 0
)

/**
 * Тактильный движок (HapticEngine) для физического отклика Пипа
 */
class HapticEngine(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun purr() {
        vibrateWaveform(longArrayOf(0, 25, 35, 30, 40, 20), intArrayOf(0, 65, 0, 90, 0, 50))
    }

    fun pokeBump() {
        vibrateOneShot(45L, 180)
    }

    fun alertPanic() {
        vibrateWaveform(longArrayOf(0, 60, 40, 80, 40, 90), intArrayOf(0, 220, 0, 255, 0, 220))
    }

    fun victoryFanfare() {
        vibrateWaveform(longArrayOf(0, 40, 50, 40, 50, 110), intArrayOf(0, 150, 0, 180, 0, 255))
    }

    fun tick() {
        vibrateOneShot(15L, 90)
    }

    private fun vibrateOneShot(ms: Long, amplitude: Int) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(ms)
        }
    }

    private fun vibrateWaveform(timings: LongArray, amplitudes: IntArray) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(timings, -1)
        }
    }
}

/**
 * Единый контроллер аппаратных сенсоров, микрофона и батареи
 */
class HardwareSensorHub(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _telemetry = MutableStateFlow(SensorTelemetry())
    val telemetry: StateFlow<SensorTelemetry> = _telemetry.asStateFlow()

    private val _events = MutableSharedFlow<PetEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<PetEvent> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioJob: Job? = null
    private var isReceiverRegistered = false

    // Тайминги анти-дребезга событий
    private var lastShakeTime = 0L
    private var lastFreeFallTime = 0L
    private var lastStepPeakTime = 0L
    private var lastBlowTime = 0L
    private var lastClapTime = 0L
    private var initialHardwareSteps: Int? = null
    private var fallbackSteps = 0

    // Мониторинг батареи
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 85
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            _telemetry.value = _telemetry.value.copy(
                batteryPct = pct,
                isCharging = charging
            )
        }
    }

    fun startSensors(hasAudioPermission: Boolean) {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (!isReceiverRegistered) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            isReceiverRegistered = true
        }
        if (hasAudioPermission) {
            startAudioAnalysis()
        }
    }

    fun stopSensors() {
        sensorManager.unregisterListener(this)
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(batteryReceiver) }
            isReceiverRegistered = false
        }
        audioJob?.cancel()
        audioJob = null
    }

    fun resetPeakDb() {
        _telemetry.value = _telemetry.value.copy(peakDb = _telemetry.value.soundDb)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                val mag = sqrt(ax * ax + ay * ay + az * az)
                val gForce = mag / SensorManager.GRAVITY_EARTH

                // Сглаженный наклон (-1..1) для взгляда Пипа и Гиро-Лабиринта
                val normTiltX = (-ax / 7.5f).coerceIn(-1f, 1f)
                val normTiltY = (ay / 7.5f).coerceIn(-1f, 1f)
                val prev = _telemetry.value
                val smoothTiltX = prev.tiltX * 0.8f + normTiltX * 0.2f
                val smoothTiltY = prev.tiltY * 0.8f + normTiltY * 0.2f

                // 1. Распознавание переворота экраном вниз (FaceDown)
                val faceDownNow = az < -8.2f && abs(ax) < 3.5f && abs(ay) < 3.5f
                if (faceDownNow != prev.isFaceDown) {
                    _events.tryEmit(PetEvent.FaceDownChanged(faceDownNow))
                }

                // 2. Детектор свободного падения (почти невесомость < 0.22 G)
                if (gForce < 0.22f && now - lastFreeFallTime > 2500L) {
                    lastFreeFallTime = now
                    _events.tryEmit(PetEvent.FreeFallDetected)
                }

                // 3. Детектор тряски (> 2.15 G)
                if (gForce > 2.15f && now - lastShakeTime > 1200L) {
                    lastShakeTime = now
                    _events.tryEmit(PetEvent.ShakeDetected)
                }

                // 4. Программный шагомер (если нет аппаратного или для мгновенного отклика)
                if (gForce in 1.28f..1.95f && now - lastStepPeakTime > 420L) {
                    lastStepPeakTime = now
                    if (stepSensor == null) {
                        fallbackSteps++
                    }
                    _events.tryEmit(PetEvent.StepTaken)
                }

                _telemetry.value = prev.copy(
                    accelX = ax,
                    accelY = ay,
                    accelZ = az,
                    tiltX = smoothTiltX,
                    tiltY = smoothTiltY,
                    totalG = gForce,
                    isFaceDown = faceDownNow,
                    sessionSteps = if (stepSensor == null) fallbackSteps else prev.sessionSteps
                )
            }

            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values[0].toInt()
                val base = initialHardwareSteps ?: totalSteps.also { initialHardwareSteps = it }
                val delta = (totalSteps - base).coerceAtLeast(0)
                if (delta > _telemetry.value.sessionSteps) {
                    _events.tryEmit(PetEvent.StepTaken)
                }
                _telemetry.value = _telemetry.value.copy(sessionSteps = delta)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Поток анализа микрофона: вычисляет уровень шума в dB SPL
     * и отличает дуновение (ветровой шум) от резкого хлопка
     */
    @SuppressLint("MissingPermission")
    fun startAudioAnalysis() {
        if (audioJob?.isActive == true) return
        audioJob = scope.launch {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return@launch

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull() ?: return@launch

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return@launch
            }

            val buffer = ShortArray(1024)
            var blowFramesCount = 0

            try {
                recorder.startRecording()
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sumSquares = 0.0
                        var zeroCrossings = 0
                        var prevSample = buffer[0].toInt()

                        for (i in 0 until read) {
                            val s = buffer[i].toInt()
                            sumSquares += (s * s).toDouble()
                            if ((s >= 0 && prevSample < 0) || (s < 0 && prevSample >= 0)) {
                                zeroCrossings++
                            }
                            prevSample = s
                        }

                        val rms = sqrt(sumSquares / read).coerceAtLeast(1.0)
                        // Калибровка RMS 16-бит PCM в шкалу 25..100 dB
                        val rawDb = (20.0 * log10(rms / 32767.0) + 96.0).toFloat().coerceIn(22f, 105f)
                        val prevDb = _telemetry.value.soundDb
                        val smoothDb = prevDb * 0.65f + rawDb * 0.35f
                        val zcr = zeroCrossings.toFloat() / read.toFloat()

                        // Дуновение в микрофон дает высокий уровень сигнала (> 68 dB)
                        // при низкой частоте пересечений нуля (турбулентный низкочастотный гул)
                        val isBlowFrame = rawDb > 68f && zcr < 0.19f
                        if (isBlowFrame) {
                            blowFramesCount++
                        } else {
                            blowFramesCount = (blowFramesCount - 1).coerceAtLeast(0)
                        }

                        val now = System.currentTimeMillis()
                        val blowingActive = blowFramesCount >= 3
                        if (blowingActive && now - lastBlowTime > 2000L) {
                            lastBlowTime = now
                            _events.tryEmit(PetEvent.MicBlowDetected)
                        } else if (rawDb - prevDb > 23f && rawDb > 76f && now - lastClapTime > 2200L) {
                            // Резкий скачок громкости -> хлопок или стук
                            lastClapTime = now
                            _events.tryEmit(PetEvent.LoudClapDetected)
                        }

                        _telemetry.value = _telemetry.value.copy(
                            soundDb = smoothDb,
                            peakDb = maxOf(_telemetry.value.peakDb, smoothDb),
                            isBlowing = blowingActive
                        )
                    }
                    delay(45L)
                }
            } finally {
                runCatching {
                    recorder.stop()
                    recorder.release()
                }
            }
        }
    }
}
