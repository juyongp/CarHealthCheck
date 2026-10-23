package com.carhealth.checkup.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Follows the platform's driving restrictions (CarUxRestrictions), as the Pleos
 * Driving Restriction Development Guide describes.
 *
 * android.car is loaded by reflection, so the project builds without extra SDK components.
 * On a device without the car service, onUnavailable() is called and the screen keeps
 * its manual "주행 화면 미리보기" button instead.
 */
class DrivingStateMonitor(
    context: Context,
    private val onChange: (Boolean) -> Unit,
    private val onUnavailable: () -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var car: Any? = null
    private var manager: Any? = null

    fun start() {
        try {
            val carClass = Class.forName("android.car.Car")
            val carInstance = carClass.getMethod("createCar", Context::class.java).invoke(null, appContext)
                ?: throw IllegalStateException("Car service is not available")
            car = carInstance

            val serviceName = carClass.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
            val uxManager = carClass.getMethod("getCarManager", String::class.java).invoke(carInstance, serviceName)
                ?: throw IllegalStateException("CarUxRestrictionsManager is not available")
            manager = uxManager

            val listenerType = Class.forName(
                "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener"
            )
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "CarHealthUxListener"
                    "onUxRestrictionsChanged" -> {
                        val restrictions = args?.firstOrNull()
                        main.post { onChange(requiresOptimization(restrictions)) }
                        null
                    }
                    else -> null
                }
            }
            uxManager.javaClass.getMethod("registerListener", listenerType).invoke(uxManager, listener)

            val current = uxManager.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(uxManager)
            onChange(requiresOptimization(current))
        } catch (t: Throwable) {
            Log.i(TAG, "Driving restrictions are not available: ${t.message}")
            stop()
            onUnavailable()
        }
    }

    fun stop() {
        try {
            manager?.let { it.javaClass.getMethod("unregisterListener").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterListener failed", t)
        }
        try {
            car?.let { it.javaClass.getMethod("disconnect").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Car.disconnect failed", t)
        }
        manager = null
        car = null
    }

    private fun requiresOptimization(restrictions: Any?): Boolean = try {
        restrictions?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(restrictions) as? Boolean ?: false
    } catch (t: Throwable) {
        false
    }

    companion object {
        private const val TAG = "CarHealth"
    }
}
