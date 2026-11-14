package com.scleracheck.guidance

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Audio + haptic guidance (CLAUDE.md §0): with the rear camera facing them, the patient can't
 * see the screen, so every instruction is spoken and confirmed with a beep/vibration.
 */
class Guide(context: Context) {

    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.language = Locale.UK
        }
    }
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastCue: String? = null
    private var lastCueAt = 0L

    /** Speak a positional cue, throttled so the patient isn't flooded ("closer… closer…"). */
    fun cue(text: String, repeatAfterMs: Long = 2500) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastCue && now - lastCueAt < repeatAfterMs) return
        if (text != lastCue && now - lastCueAt < 1200 && lastCue != null) return
        lastCue = text; lastCueAt = now
        speak(text, flush = true)
    }

    /** Speak an important instruction immediately. */
    fun say(text: String) {
        lastCue = text; lastCueAt = SystemClock.elapsedRealtime()
        speak(text, flush = true)
    }

    private fun speak(text: String, flush: Boolean) {
        if (!ready) return
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text)
    }

    fun tick() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
        vibrate(30)
    }

    fun done() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        vibrate(150)
    }

    private fun vibrate(ms: Long) {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun release() {
        runCatching { tts.stop(); tts.shutdown() }
        tone?.release()
    }
}

/** Ambient light sensor (lux), used for "too bright / too dark" advice before capture. */
class AmbientLight(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    @Volatile var lux: Float? = null; private set
    val available get() = sensor != null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() = sm.unregisterListener(this)
    override fun onSensorChanged(e: SensorEvent) { lux = e.values.firstOrNull() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /**
     * Advice thresholds: typical indoor lighting is 100–500 lux; daylight by a window 1,000–10,000+.
     * (CIBSE/SLL Code for Lighting ranges.) Above ~2,000 lux the torch's share of the light on the eye
     * becomes small; below ~15 lux the preview is too dark for landmark detection.
     */
    fun advice(): String? {
        val l = lux ?: return null
        return when {
            l > 2000f -> "It's very bright here. Please move away from the window or out of direct sunlight."
            l < 15f -> "It's too dark here. Please turn on a room light."
            else -> null
        }
    }
}
