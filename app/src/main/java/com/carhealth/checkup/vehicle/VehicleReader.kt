package com.carhealth.checkup.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.EvBattery
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.Tire
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.MirrorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.MirrorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions

/**
 * Reads the values the health check needs from the Pleos Vehicle SDK and reports them as JSON.
 *
 * SDK methods are looked up by name and called through Kotlin reflection, the same way the
 * official Pleos sample app's API catalog calls them. This keeps the file compiling even if a
 * method's exact parameter list differs between SDK versions. A method that is missing, not
 * supported by the car, or not permitted just records an error for its key, and the screen
 * shows "데이터 없음" for that item.
 *
 * Values read (all public, read-only):
 *  Safety   getBrakeFluidLevelState, hasLowWasherFluidWarning, getTransmissionOilLevelState
 *  Tire     getLowTirePressure (per wheel)
 *  Odometer getTripCumulativeDrivingDistance
 *  EvBattery getCumulativeEvEfficiency, getEvBatteryStateOfCharge
 *  HVAC     getHvacPowerState, getHvacFanSpeed (air-conditioning hours for the cabin filter)
 *  Wiper    getCurrentWiperState (wiper hours)
 *  Brake    registerBrakePedalStatus (press count for the pad-wear estimate)
 */
class VehicleReader(
    context: Context,
    private val usage: UsageStore,
    private val onUpdate: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val values = ConcurrentHashMap<String, Any>()
    private val errors = ConcurrentHashMap<String, String>()
    private val functionCache = HashMap<String, KFunction<*>>()

    private var vehicle: Vehicle? = null
    private var connected = false
    private var started = false
    private var running = false
    private var status = ""
    private var brakeListener: Any? = null
    private var lastBrakePressed: Boolean? = null

    private val publishTask = Runnable { publish() }
    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            pollOnce()
            main.removeCallbacks(publishTask)
            main.postDelayed(publishTask, PUBLISH_DELAY_MS)
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (started) {
            resume()
            return
        }
        started = true
        connected = try {
            val v = Vehicle(appContext)
            v.initialize()
            vehicle = v
            true
        } catch (t: Throwable) {
            status = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Vehicle SDK is not available on this device", t)
            false
        }
        if (connected) registerBrakePedal()
        publish()
        resume()
    }

    fun resume() {
        if (!connected || running) return
        running = true
        main.post(pollTask)
    }

    fun pause() {
        running = false
        main.removeCallbacks(pollTask)
    }

    fun release() {
        pause()
        main.removeCallbacks(publishTask)
        unregisterBrakePedal()
        try {
            vehicle?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle release failed", t)
        }
        vehicle = null
        connected = false
        started = false
    }

    fun publishNow() {
        main.post { publish() }
    }

    private fun pollOnce() {
        val v = vehicle ?: return

        val safety = v.getSafety()
        read("brakeFluid", safety, Safety::class, "getBrakeFluidLevelState")
        read("washerLow", safety, Safety::class, "hasLowWasherFluidWarning")
        read("transOil", safety, Safety::class, "getTransmissionOilLevelState")

        val tire = v.getTire()
        WheelValue.entries.forEach { wheel ->
            read("lowTire_${wheel.name}", tire, Tire::class, "getLowTirePressure", WheelArea(wheel))
        }

        read("odometer", v.getOdometer(), Odometer::class, "getTripCumulativeDrivingDistance")

        val battery = v.getEvBattery()
        read("evEfficiency", battery, EvBattery::class, "getCumulativeEvEfficiency")
        read("soc", battery, EvBattery::class, "getEvBatteryStateOfCharge")

        val hvac = v.getHVAC()
        read("hvacPower", hvac, HVAC::class, "getHvacPowerState")
        read("fanSpeed", hvac, HVAC::class, "getHvacFanSpeed")

        read("wiper", v.getWiper(), Wiper::class, "getCurrentWiperState")

        // Usage counters use the latest values received so far.
        val seconds = (POLL_INTERVAL_MS / 1000L).toInt()
        if (isActive(values["hvacPower"]) || isActive(values["fanSpeed"])) usage.addHvacSeconds(seconds)
        if (isActive(values["wiper"])) usage.addWiperSeconds(seconds)
        (values["evEfficiency"] as? Number)?.let { usage.recordEfficiency(it.toDouble()) }
    }

    private fun publish() {
        val root = JSONObject()
        root.put("connected", connected)
        root.put("status", status)

        val valueJson = JSONObject()
        for ((key, value) in values) valueJson.put(key, toJsonValue(value))
        root.put("values", valueJson)

        val errorJson = JSONObject()
        for ((key, message) in errors) errorJson.put(key, message)
        root.put("errors", errorJson)

        val odometer = (values["odometer"] as? Number)?.toDouble()
        if (odometer != null && !odometer.isNaN() && !odometer.isInfinite()) root.put("odometerKm", odometer)

        val hasHvac = values.containsKey("hvacPower") || values.containsKey("fanSpeed")
        root.put("usage", usage.toJson(hasHvac, values.containsKey("wiper"), brakeListener != null))

        onUpdate(root.toString())
    }

    private fun toJsonValue(value: Any): Any = when (value) {
        is Boolean -> value
        is Number -> {
            val number = value.toDouble()
            if (number.isNaN() || number.isInfinite()) value.toString() else number
        }
        is Enum<*> -> value.name
        else -> value.toString()
    }

    private fun read(key: String, target: Any, apiClass: KClass<*>, name: String, area: Any? = null) {
        invoke(
            target = target,
            apiClass = apiClass,
            name = name,
            area = area,
            onValue = { value ->
                if (value == null || value == Unit) values.remove(key) else values[key] = value
                errors.remove(key)
            },
            onError = { error -> errors[key] = error.message ?: error.javaClass.simpleName }
        )
    }

    private fun findFunction(apiClass: KClass<*>, name: String): KFunction<*>? {
        val cacheKey = "${apiClass.qualifiedName}.$name"
        functionCache[cacheKey]?.let { return it }
        val function = apiClass.declaredMemberFunctions.firstOrNull { it.name == name } ?: return null
        functionCache[cacheKey] = function
        return function
    }

    /** Calls an SDK method by name, filling callbacks, area and plain parameters with sensible values. */
    private fun invoke(
        target: Any,
        apiClass: KClass<*>,
        name: String,
        area: Any?,
        onValue: (Any?) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val function = findFunction(apiClass, name)
        if (function == null) {
            onError(NoSuchMethodException("${apiClass.simpleName}.$name"))
            return
        }
        try {
            val arguments = HashMap<KParameter, Any?>()
            var usesCallback = false
            for (parameter in function.parameters) {
                when (parameter.kind) {
                    KParameter.Kind.INSTANCE -> arguments[parameter] = target
                    KParameter.Kind.VALUE -> {
                        val classifier = parameter.type.classifier as? KClass<*>
                        val parameterName = parameter.name.orEmpty()
                        val isFailure = parameterName.contains("fail", ignoreCase = true) ||
                            parameterName.contains("error", ignoreCase = true)
                        val argument: Any? = when (classifier) {
                            Function0::class -> {
                                usesCallback = true
                                val callback: () -> Unit = { if (!isFailure) onValue(null) }
                                callback
                            }
                            Function1::class -> {
                                usesCallback = true
                                val callback: (Any?) -> Unit = { first ->
                                    if (isFailure) onError(asThrowable(first)) else onValue(first)
                                }
                                callback
                            }
                            Function2::class -> {
                                usesCallback = true
                                val callback: (Any?, Any?) -> Unit = { first, second ->
                                    val last = second ?: first
                                    if (isFailure) onError(asThrowable(last)) else onValue(last)
                                }
                                callback
                            }
                            Function3::class -> {
                                usesCallback = true
                                val callback: (Any?, Any?, Any?) -> Unit = { first, second, third ->
                                    val last = third ?: second ?: first
                                    if (isFailure) onError(asThrowable(last)) else onValue(last)
                                }
                                callback
                            }
                            else -> defaultArgument(classifier, area)
                        }
                        if (argument != null) {
                            arguments[parameter] = argument
                        } else if (!parameter.isOptional) {
                            if (parameter.type.isMarkedNullable) {
                                arguments[parameter] = null
                            } else {
                                throw IllegalArgumentException("Unsupported parameter '$parameterName' in $name")
                            }
                        }
                    }
                    else -> Unit
                }
            }
            val result = function.callBy(arguments)
            if (!usesCallback && result != null && result != Unit) onValue(result)
        } catch (e: InvocationTargetException) {
            onError(e.targetException ?: e)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun defaultArgument(classifier: KClass<*>?, area: Any?): Any? {
        if (classifier == null) return null
        if (area != null && classifier.isInstance(area)) return area
        return when (classifier) {
            WheelArea::class -> WheelArea(preferred(WheelValue.entries))
            SeatArea::class -> SeatArea(preferred(SeatValue.entries))
            DoorArea::class -> DoorArea(preferred(DoorValue.entries))
            WindowArea::class -> WindowArea(preferred(WindowValue.entries))
            MirrorArea::class -> MirrorArea(preferred(MirrorValue.entries))
            Boolean::class -> false
            Int::class -> 0
            Long::class -> 0L
            Float::class -> 0f
            Double::class -> 0.0
            else -> classifier.java.enumConstants?.firstOrNull()
        }
    }

    private fun <T : Enum<T>> preferred(entries: List<T>): T =
        entries.firstOrNull { it.name in PREFERRED_AREAS } ?: entries.first()

    private fun asThrowable(value: Any?): Throwable =
        value as? Throwable ?: IllegalStateException(value?.toString() ?: "unknown error")

    /** Counts brake presses to estimate pad wear (the SDK has no pad thickness value). */
    private fun registerBrakePedal() {
        val v = vehicle ?: return
        val function = findFunction(Brake::class, "registerBrakePedalStatus") ?: return
        try {
            val brake = v.getBrake()
            val arguments = HashMap<KParameter, Any?>()
            var listener: Any? = null
            for (parameter in function.parameters) {
                when (parameter.kind) {
                    KParameter.Kind.INSTANCE -> arguments[parameter] = brake
                    KParameter.Kind.VALUE -> {
                        val classifier = parameter.type.classifier as? KClass<*>
                        val isListener = classifier?.qualifiedName?.startsWith(LISTENER_PACKAGE) == true
                        val argument: Any? = if (classifier != null && isListener) {
                            createListener(classifier.java) { methodName, args -> onBrakeEvent(methodName, args) }
                                .also { listener = it }
                        } else {
                            defaultArgument(classifier, null)
                        }
                        if (argument != null) arguments[parameter] = argument
                    }
                    else -> Unit
                }
            }
            if (listener == null) return
            function.callBy(arguments)
            brakeListener = listener
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            errors["brakePedal"] = cause.message ?: cause.javaClass.simpleName
        }
    }

    private fun onBrakeEvent(methodName: String, args: List<Any?>) {
        if (methodName.contains("fail", ignoreCase = true)) return
        val value = args.lastOrNull() ?: return
        values["brakePedal"] = value
        val pressed = isActive(value)
        if (pressed && lastBrakePressed == false) usage.addBrakePress()
        lastBrakePressed = pressed
    }

    private fun unregisterBrakePedal() {
        val v = vehicle ?: return
        val listener = brakeListener ?: return
        brakeListener = null
        val function = findFunction(Brake::class, "unregisterBrakePedalStatus") ?: return
        try {
            val arguments = HashMap<KParameter, Any?>()
            for (parameter in function.parameters) {
                when (parameter.kind) {
                    KParameter.Kind.INSTANCE -> arguments[parameter] = v.getBrake()
                    KParameter.Kind.VALUE -> {
                        val classifier = parameter.type.classifier as? KClass<*>
                        val argument = if (classifier != null && classifier.isInstance(listener)) {
                            listener
                        } else {
                            defaultArgument(classifier, null)
                        }
                        if (argument != null) arguments[parameter] = argument
                    }
                    else -> Unit
                }
            }
            function.callBy(arguments)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterBrakePedalStatus failed", t)
        }
    }

    private fun createListener(type: Class<*>, onEvent: (String, List<Any?>) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CarHealthListener(${type.simpleName})"
                else -> {
                    val list = args?.toList() ?: emptyList()
                    main.post { onEvent(method.name, list) }
                    null
                }
            }
        }

    private fun isActive(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() > 0.0
        is Enum<*> -> isActiveName(value.name)
        else -> isActiveName(value.toString())
    }

    private fun isActiveName(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        if (upper.isBlank() || upper == "NULL" || upper == "0") return false
        return INACTIVE_WORDS.none { upper.contains(it) }
    }

    companion object {
        private const val TAG = "CarHealth"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val PUBLISH_DELAY_MS = 1_200L
        private const val LISTENER_PACKAGE = "ai.pleos.playground.vehicle.listener."
        private val PREFERRED_AREAS = setOf("ROW_1_LEFT", "FRONT_LEFT", "FRONT_WINDSHIELD")
        private val INACTIVE_WORDS = listOf("OFF", "FALSE", "NONE", "STOP", "RELEASE", "NOT_", "IDLE", "UNKNOWN", "INACTIVE")
    }
}
