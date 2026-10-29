package com.carhealth.checkup

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * One spoken question at a time through the car's on-device speech recognition (Pleos STT SDK,
 * CaaS engine and microphone; the app itself needs no RECORD_AUDIO).
 *
 * Reports [onEvent] on the main thread with a state and text:
 *  starting  the engine is warming up (the first time takes a few seconds)
 *  listening the microphone is open
 *  partial   words so far
 *  final     the question; also sent when speech stops without a final result
 *  timeout   nothing was heard
 *  error     the engine failed (text is the SDK error type)
 *  cancel    stopped by [stop]
 */
class VoiceInput(context: Context, private val onEvent: (state: String, text: String) -> Unit) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var ready = false
    private var starting = false
    private var listening = false
    private var cancelled = false
    private var heard = ""

    // The engine keeps recognizing silence, so the app decides when the question is over.
    private val endOfSpeech = Runnable { finish(if (heard.isBlank()) "timeout" else "final", heard) }

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            main.post {
                if (!listening) return@post
                if (isFinal && text.isNotBlank()) {
                    finish("final", text)
                } else if (text.isNotBlank()) {
                    heard = text
                    onEvent("partial", text)
                    restartTimer(SILENCE_AFTER_SPEECH_MS)
                }
            }
        }

        override fun onReady() {
            main.post { if (listening) onEvent("listening", "") }
        }

        override fun onError(error: ErrorType) {
            main.post {
                Log.w(TAG, "Pleos STT error: $error")
                if (listening) finish("error", error.name)
            }
        }

        override fun onEndedRecognition() {
            // A final result may still be on its way; give it a moment.
            main.post { if (listening) restartTimer(FINAL_GRACE_MS) }
        }

        override fun onStartedRecognition() {}
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}
        override fun onUpdatedRms(rms: Double) {}
    }

    fun listen() {
        cancelled = false
        if (listening || starting) return
        val engine = stt
        if (engine != null && ready) {
            begin(engine)
            return
        }
        starting = true
        onEvent("starting", "")
        val created = try {
            SpeechToText(appContext, Mode.ON_DEVICE)
        } catch (t: Throwable) {
            Log.w(TAG, "Pleos STT SDK is not available", t)
            starting = false
            onEvent("error", "UNAVAILABLE")
            return
        }
        stt = created
        val fail = { reason: String ->
            Log.w(TAG, "Pleos STT could not start: $reason")
            runCatching { created.release() }
            stt = null
            starting = false
            onEvent("error", reason)
        }
        try {
            created.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        try {
                            // Like the TTS SDK, listeners and settings are accepted only after initialize succeeds.
                            created.addListener(listener)
                            created.setLocale(Locale.KOREA)
                            ready = true
                            starting = false
                            if (cancelled) onEvent("cancel", "") else begin(created)
                        } catch (e: Exception) {
                            fail(e.toString())
                        }
                    }
                }

                override fun onFailure(error: ErrorType) {
                    main.post { fail(error.name) }
                }
            })
        } catch (e: Exception) {
            fail(e.toString())
        }
    }

    fun stop() {
        cancelled = true
        finish("cancel", "")
    }

    fun release() {
        stop()
        runCatching { stt?.release() }
        stt = null
        ready = false
    }

    private fun begin(engine: SpeechToText) {
        listening = true
        heard = ""
        try {
            engine.request()
        } catch (e: Exception) {
            finish("error", e.toString())
            return
        }
        restartTimer(LISTEN_TIMEOUT_MS)
    }

    private fun restartTimer(delayMs: Long) {
        main.removeCallbacks(endOfSpeech)
        main.postDelayed(endOfSpeech, delayMs)
    }

    private fun finish(state: String, text: String) {
        main.removeCallbacks(endOfSpeech)
        if (!listening) return
        listening = false
        runCatching { stt?.stop() }
        onEvent(state, text)
    }

    companion object {
        private const val TAG = "CarHealth"
        private const val LISTEN_TIMEOUT_MS = 10_000L
        private const val SILENCE_AFTER_SPEECH_MS = 1_800L
        private const val FINAL_GRACE_MS = 600L
    }
}
