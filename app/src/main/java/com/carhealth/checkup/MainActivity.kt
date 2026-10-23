package com.carhealth.checkup

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import androidx.activity.ComponentActivity
import com.carhealth.checkup.vehicle.DrivingStateMonitor
import com.carhealth.checkup.vehicle.UsageStore
import com.carhealth.checkup.vehicle.VehicleReader
import org.json.JSONObject
import java.util.Locale

/**
 * Hosts the health-check screen (assets/web/index.html) in a WebView and feeds it
 * vehicle data from the Pleos Vehicle SDK and the platform's driving state.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private val pendingScripts = ArrayList<String>()
    private var pageReady = false
    private var sourcesStarted = false

    private lateinit var usage: UsageStore
    private var vehicleReader: VehicleReader? = null
    private var drivingMonitor: DrivingStateMonitor? = null

    // Voice guidance: the car's own engine through the Pleos TTS SDK, Android TextToSpeech if that fails.
    private var pleosTts: ai.pleos.playground.tts.TextToSpeech? = null
    private var pleosReady = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceStarting = false
    private var pendingSpeech: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usage = UsageStore(this)

        webView = WebView(this).apply {
            setBackgroundColor(getColor(R.color.app_bg))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "web: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "AndroidBridge")
        }
        setContentView(webView)

        vehicleReader = VehicleReader(this, usage) { json ->
            callPage("onVehicleData", JSONObject.quote(json))
        }
        drivingMonitor = DrivingStateMonitor(
            this,
            onChange = { requiresOptimization -> callPage("onDrivingState", requiresOptimization.toString()) },
            onUnavailable = { callPage("onUxUnavailable", "") }
        )

        webView.loadUrl("file:///android_asset/web/index.html")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (sourcesStarted) vehicleReader?.resume()
    }

    override fun onPause() {
        vehicleReader?.pause()
        webView.onPause()
        super.onPause()
    }

    /** uiMode is in configChanges, so day/night switches arrive here instead of recreating the page. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        webView.setBackgroundColor(getColor(R.color.app_bg))
        callPage("onTheme", JSONObject.quote(themeName()))
    }

    private fun themeName(): String =
        if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"

    override fun onDestroy() {
        vehicleReader?.release()
        drivingMonitor?.stop()
        pleosTts?.release()
        tts?.shutdown()
        webView.removeJavascriptInterface("AndroidBridge")
        webView.destroy()
        super.onDestroy()
    }

    private fun startSources() {
        if (sourcesStarted) return
        sourcesStarted = true
        vehicleReader?.start()
        drivingMonitor?.start()
        // Warm up the voice engine so the first alert speaks without a delay.
        if (!voiceStarting && !pleosReady && !ttsReady) startPleosVoice()
    }

    /** Calls window.PleosBridge.<function>(<argument>) on the main thread; queued until the page is ready. */
    private fun callPage(function: String, argument: String) {
        val script = "window.PleosBridge && window.PleosBridge.$function($argument);"
        main.post {
            if (pageReady) webView.evaluateJavascript(script, null) else pendingScripts.add(script)
        }
    }

    private fun speak(text: String) {
        when {
            pleosReady -> pleosTts?.speak(text, true)
            ttsReady -> say(text)
            else -> {
                pendingSpeech = text
                if (!voiceStarting) startPleosVoice()
            }
        }
    }

    private fun guidanceAudio(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** The car's speech engine (Pleos CaaS). Android's TextToSpeech can't reach it, so it goes through the SDK. */
    private fun startPleosVoice() {
        voiceStarting = true
        val engine = try {
            ai.pleos.playground.tts.TextToSpeech(applicationContext, Mode.ON_DEVICE)
        } catch (e: Throwable) {
            Log.w(TAG, "Pleos TTS SDK is not available", e)
            startAndroidVoice()
            return
        }
        pleosTts = engine
        val fallback = { reason: String ->
            Log.w(TAG, "Pleos TTS could not start: $reason")
            runCatching { engine.release() }
            pleosTts = null
            startAndroidVoice()
        }
        try {
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        try {
                            // The SDK only accepts listeners and settings once it is initialized.
                            engine.addEventListener(object : EventListener {
                                override fun onStart() {}
                                override fun onReady() {}
                                override fun onDone() {}
                                override fun onStop() {}
                                override fun onUpdatedRms(rms: Double) {}
                                override fun onError(error: ErrorType) {
                                    Log.w(TAG, "Pleos TTS error: $error")
                                }
                            })
                            engine.setLocale(Locale.KOREA)  // the engine needs ko-KR; plain "ko" is rejected
                            engine.setAudioAttributes(guidanceAudio())
                            voiceStarting = false
                            pleosReady = true
                            pendingSpeech?.let { engine.speak(it, true) }
                            pendingSpeech = null
                        } catch (e: Exception) {
                            fallback(e.toString())
                        }
                    }
                }

                override fun onFailure(error: ErrorType) {
                    main.post { fallback(error.toString()) }
                }
            })
        } catch (e: Exception) {
            fallback(e.toString())
        }
    }

    private fun startAndroidVoice() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(applicationContext) { status ->
            main.post {
                voiceStarting = false
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts = engine
                    engine?.setLanguage(Locale.KOREAN)
                    engine?.setAudioAttributes(guidanceAudio())
                    pendingSpeech?.let { say(it) }
                } else {
                    // Left unset so the next tap tries again.
                    Log.w(TAG, "Text-to-speech is not available on this device (status=$status)")
                    engine?.shutdown()
                }
                pendingSpeech = null
            }
        }
    }

    private fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "car-health")
    }

    /** Methods the page calls through window.AndroidBridge. They arrive on a WebView background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            main.post {
                pageReady = true
                pendingScripts.forEach { webView.evaluateJavascript(it, null) }
                pendingScripts.clear()
                startSources()
            }
        }

        @JavascriptInterface
        fun recordReplacement(itemId: String) {
            main.post {
                usage.recordReplacement(itemId)
                vehicleReader?.publishNow()
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            main.post { this@MainActivity.speak(text) }
        }

        /** Read synchronously by the page's first script so it paints in the right colors. */
        @JavascriptInterface
        fun theme(): String = themeName()

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "page: $message")
        }
    }

    companion object {
        private const val TAG = "CarHealth"
    }
}
