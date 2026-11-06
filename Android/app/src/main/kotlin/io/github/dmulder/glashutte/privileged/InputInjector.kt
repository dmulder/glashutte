package io.github.dmulder.glashutte.privileged

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.dmulder.glashutte.glasses.InjectOutcome
import io.github.dmulder.glashutte.glasses.InputExperiments
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Submits input events from the shell-uid helper to the platform's input dispatcher.
 *
 * `InputManager.getInstance()` — the only route the helper used before — is a deprecated
 * unsupported-app-usage accessor on its way out; on a new Android release it can disappear
 * and every click would then be dropped without a trace. This class resolves several
 * independent routes to the same `injectInputEvent` entry point, remembers the first one that
 * works, can be pinned to one for A/B testing ([InputExperiments.Strategy]), and reports every
 * result as an [InjectOutcome] instead of swallowing it.
 *
 * Runs only inside the helper process (shell uid, which holds `INJECT_EVENTS`).
 */
internal class InputInjector(private val contextProvider: () -> Context?) {

    private class Route(val name: String, val call: (InputEvent, Int) -> Boolean)

    /** [InputExperiments.Strategy.wire] value; 0 = auto. */
    @Volatile var strategy: Int = 0

    /** Use the dispatcher's wait-for-result mode so a dropped event is reported, not silent. */
    @Volatile var waitForResult: Boolean = true

    private val lock = Any()
    private var routes: List<Route>? = null
    private val unavailable = LinkedHashMap<String, String>()
    @Volatile private var working: Route? = null

    private fun resolvedRoutes(): List<Route> = synchronized(lock) {
        routes ?: buildRoutes().also { routes = it }
    }

    private fun buildRoutes(): List<Route> {
        val out = ArrayList<Route>()
        unavailable.clear()

        build(InputExperiments.Strategy.GLOBAL.routeName) {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null) ?: error("getInstance() returned null")
            reflectInject(instance, cls)
        }?.let(out::add)

        build(InputExperiments.Strategy.SERVICE_CONTEXT.routeName) {
            val ctx = contextProvider() ?: error("no system context yet")
            val manager = ctx.getSystemService("input") ?: error("getSystemService(\"input\") returned null")
            reflectInject(manager, Class.forName("android.hardware.input.InputManager"))
        }?.let(out::add)

        build(InputExperiments.Strategy.LEGACY.routeName) {
            val cls = Class.forName("android.hardware.input.InputManager")
            val instance = cls.getMethod("getInstance").invoke(null) ?: error("getInstance() returned null")
            reflectInject(instance, cls)
        }?.let(out::add)

        build(InputExperiments.Strategy.BINDER.routeName) {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, "input") as? IBinder ?: error("ServiceManager has no 'input' service")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: error("asInterface returned null")
            reflectInject(proxy, Class.forName("android.hardware.input.IInputManager"))
        }?.let(out::add)

        return out
    }

    private fun build(name: String, block: () -> (InputEvent, Int) -> Boolean): Route? =
        try {
            Route(name, block())
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            unavailable[name] = "${cause.javaClass.simpleName}: ${cause.message}"
            Log.w(TAG, "input route '$name' unavailable: ${unavailable[name]}")
            null
        }

    private fun reflectInject(target: Any, lookupClass: Class<*>): (InputEvent, Int) -> Boolean {
        val int = Int::class.javaPrimitiveType!!
        val method: Method
        val extra: Array<Any?>
        val direct = lookupClass.methods.firstOrNull {
            it.name == "injectInputEvent" && it.parameterTypes.contentEquals(arrayOf(InputEvent::class.java, int))
        }
        if (direct != null) {
            method = direct
            extra = emptyArray()
        } else {
            val toTarget = lookupClass.methods.firstOrNull {
                it.name == "injectInputEventToTarget" &&
                    it.parameterTypes.contentEquals(arrayOf(InputEvent::class.java, int, int))
            } ?: lookupClass.declaredMethods.firstOrNull {
                it.name == "injectInputEvent" && it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == InputEvent::class.java
            } ?: error("no injectInputEvent* on ${lookupClass.name}")
            toTarget.isAccessible = true
            method = toTarget
            extra = if (toTarget.parameterTypes.size == 3) arrayOf(-1) else emptyArray()
        }
        method.isAccessible = true
        return { event, mode ->
            val args = arrayOf<Any?>(event, mode, *extra)
            (method.invoke(target, *args) as? Boolean) ?: true
        }
    }

    /** Inject [event] by the configured strategy. Never throws. */
    fun inject(event: InputEvent): InjectOutcome {
        val s = strategy
        val mode = if (waitForResult) MODE_WAIT_FOR_RESULT else MODE_ASYNC
        val modeName = if (waitForResult) "wait-for-result" else "async"
        val all = resolvedRoutes()
        val chosen: List<Route> = if (s == 0) {
            listOfNotNull(working) + all.filter { it !== working }
        } else {
            val name = InputExperiments.Strategy.fromWire(s).routeName
            all.filter { it.name == name }
        }
        if (chosen.isEmpty()) {
            val why = if (s == 0) {
                unavailable.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            } else {
                unavailable[InputExperiments.Strategy.fromWire(s).routeName] ?: "strategy $s has no route"
            }
            return InjectOutcome(InjectOutcome.NO_INJECTOR, "", why.ifEmpty { "no routes resolved" })
        }
        var lastError = ""
        for (route in chosen) {
            try {
                val accepted = route.call(event, mode)
                if (s == 0) working = route
                return if (accepted) {
                    InjectOutcome.ok(route.name, modeName)
                } else {
                    InjectOutcome(InjectOutcome.REJECTED, route.name, "injectInputEvent returned false ($modeName)")
                }
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                lastError = "${route.name}: ${cause.javaClass.simpleName}: ${cause.message}"
                if (working === route) working = null
                Log.w(TAG, "inject via $lastError")
            }
        }
        return InjectOutcome(InjectOutcome.EXCEPTION, "", lastError)
    }

    /** First usable route name and a per-route availability list, for the probe. */
    fun describe(): String {
        val all = resolvedRoutes()
        val sb = StringBuilder()
        sb.append("injector: strategy=").append(InputExperiments.Strategy.fromWire(strategy))
            .append(" waitForResult=").append(waitForResult)
            .append(" working=").append(working?.name ?: "(none yet)").append('\n')
        all.forEach { sb.append("  route ").append(it.name).append(": resolved\n") }
        synchronized(lock) {
            unavailable.forEach { (k, v) -> sb.append("  route ").append(k).append(": UNAVAILABLE ").append(v).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    /** True when at least one route resolved. */
    fun anyRoute(): Boolean = resolvedRoutes().isNotEmpty()

    /** One-line summary for the app: which route works / what failed. */
    fun summary(): String {
        val all = resolvedRoutes()
        if (all.isEmpty()) {
            return "NO route to InputManager resolved: " + synchronized(lock) {
                unavailable.entries.joinToString("; ") { "${it.key}: ${it.value}" }
            }
        }
        return "routes ok: " + all.joinToString { it.name } + (working?.let { "; in use: ${it.name}" } ?: "")
    }

    companion object {
        private const val TAG = "Glashutte/Privileged"
        private const val MODE_ASYNC = 0
        private const val MODE_WAIT_FOR_RESULT = 1

        fun recycle(event: InputEvent) {
            (event as? MotionEvent)?.recycle()
        }

        /** Result of building an event for a specific display. */
        class Built(val event: InputEvent?, val error: String?)

        /**
         * Aim [event] at [displayId]. Without a working `setDisplayId` the event would fall to
         * display 0 — the phone itself — so a failure here must stop the injection.
         */
        fun aim(event: InputEvent, displayId: Int): String? {
            return try {
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                    .invoke(event, displayId)
                val got = InputEvent::class.java.getMethod("getDisplayId").invoke(event) as? Int
                if (got == displayId) null else "displayId readback $got != $displayId"
            } catch (t: Throwable) {
                val cause = (t as? InvocationTargetException)?.targetException ?: t
                "setDisplayId failed: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }

        fun motion(
            displayId: Int,
            downAt: Long,
            eventAt: Long,
            action: Int,
            points: List<FloatArray>,
            source: Int,
            toolType: Int,
            vScroll: Float? = null,
        ): Built {
            val count = points.size
            val props = Array(count) { idx ->
                MotionEvent.PointerProperties().apply {
                    id = idx
                    this.toolType = toolType
                }
            }
            val coords = Array(count) { idx ->
                MotionEvent.PointerCoords().apply {
                    x = points[idx][0]
                    y = points[idx][1]
                    pressure = 1f
                    size = 1f
                    if (vScroll != null && idx == 0) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                }
            }
            val event = MotionEvent.obtain(
                downAt, eventAt, action, count, props, coords,
                0, 0, 1f, 1f, 0, 0, source, 0,
            )
            event.source = source
            val error = aim(event, displayId)
            if (error != null) {
                event.recycle()
                return Built(null, error)
            }
            return Built(event, null)
        }

        fun key(displayId: Int, downAt: Long, action: Int, keyCode: Int, metaState: Int): Built {
            val now = SystemClock.uptimeMillis()
            val event = KeyEvent(
                downAt, now, action, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            val error = aim(event, displayId)
            return if (error != null) Built(null, error) else Built(event, null)
        }
    }
}
