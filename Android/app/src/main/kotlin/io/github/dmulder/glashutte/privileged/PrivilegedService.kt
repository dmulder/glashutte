package io.github.dmulder.glashutte.privileged

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import io.github.dmulder.glashutte.glasses.InjectOutcome
import io.github.dmulder.glashutte.glasses.InputDiagnostics
import io.github.dmulder.glashutte.glasses.InputExperiments
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Orchestrates Glashutte's own shell-uid helper: pairs with the device's wireless-debugging
 * dialog, discovers and connects to the ADB connect service, starts a [PrivilegedServer]
 * over `app_process`, and exposes the privileged-call surface Glashutte uses to launch apps,
 * inject input, and create trusted virtual displays.
 *
 * Built in stage 4 of [docs/PRIVILEGE.md]; not yet wired by GlashutteApp — the app still goes
 * through [ShizukuManager]. Stage 5 swaps the wiring and removes the Shizuku path.
 *
 * Lifecycle:
 *
 *  - First run, after [activate] pairs with a 6-digit code, the ADB identity (key + cert)
 *    is persisted; the device trusts it permanently.
 *  - Every launch, [ensureRunning] discovers the connect port via mDNS, connects, and
 *    starts the server. The returned [AdbStream] is held open so the server stays alive;
 *    closing it (or the app dying) tears the server down.
 *  - The server sends its Binder back through [BinderReceiverProvider]; that arrives at
 *    [onPrivilegedBinder] and moves the state machine to [State.READY].
 */
object PrivilegedService {

    private const val TAG = "Glashutte/Privileged"

    /** Steps the user (or Glashutte) must clear before privileged calls work. */
    enum class State {
        /** Android < 11 — the wireless-debugging path does not exist. */
        UNSUPPORTED,

        /** Paired before, but the connect service is not on mDNS (wireless debugging off). */
        NEEDS_WIRELESS_DEBUGGING,

        /**
         * Never paired (or the device forgot our key — connect failed with
         * AdbPairingRequiredException, which clears the paired marker). The wizard asks for
         * a pairing code.
         */
        NEEDS_PAIRING,

        /** mDNS / TCP work in progress. */
        DISCOVERING,
        CONNECTING,
        STARTING,

        /** Helper bound — privileged calls go through. */
        READY,
    }

    @Volatile
    var state: State = State.NEEDS_PAIRING
        private set

    /**
     * Why the last bring-up attempt did not reach [State.READY] (empty while it has not
     * failed). Shown in the wizard and mirrored into the diagnostics log.
     */
    @Volatile
    var lastFailure: String = ""
        private set

    /**
     * Endpoint typed in by the user when mDNS discovery cannot find the connect service
     * (some networks block multicast). Used in place of discovery results when set.
     */
    @Volatile
    private var manualConnect: AdbDiscovery.Endpoint? = null

    fun setManualConnectEndpoint(host: String, port: Int) {
        manualConnect = AdbDiscovery.Endpoint(host, port)
        InputDiagnostics.timelineEvent("manual connect endpoint set: $host:$port")
    }

    @Volatile
    private var service: IPrivilegedService? = null

    /** Held to keep the helper alive — closing the stream brings the server down with it. */
    @Volatile
    private var adbStream: AdbStream? = null

    private var appContext: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "glashutte-privileged") }
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "glashutte-privileged-sched")
    }

    /** Call once, from the Application. Computes the initial state from persisted identity. */
    fun init(context: Context) {
        appContext = context.applicationContext
        Log.d(
            TAG,
            "init: sdk=${Build.VERSION.SDK_INT}, targetSdk=${context.applicationInfo.targetSdkVersion}, " +
                "uid=${android.os.Process.myUid()}",
        )
        logDeveloperOptions(context)
        state = computeInitialState(context)
        Log.d(TAG, "init: state=$state")
        InputDiagnostics.helperState(
            state.name, false,
            if (state == State.NEEDS_PAIRING) "never paired on this device" else "not connected yet",
        )
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun computeInitialState(context: Context): State = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> State.UNSUPPORTED
        hasPaired(context) -> State.NEEDS_WIRELESS_DEBUGGING
        else -> State.NEEDS_PAIRING
    }

    /**
     * Diagnostic only: the app can receive 0 even when the shell reads 1 on the same device.
     * Pairing and connecting to ADB determine availability; this value must not block setup.
     */
    private fun logDeveloperOptions(context: Context) {
        runCatching {
            val value = Settings.Global.getString(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            )
            Log.d(TAG, "Developer options (advisory): development_settings_enabled=$value")
        }.onFailure {
            Log.w(TAG, "Cannot read Developer options; continuing setup", it)
        }
    }

    /**
     * Whether Glashutte has previously paired successfully. A separate marker file from the key
     * — the key is generated and persisted on first use of [AdbConnectionManager], whether or
     * not the device ever accepted it, so the key file's existence proves nothing.
     */
    private fun hasPaired(context: Context): Boolean {
        val paired = File(context.filesDir, PAIRED_MARKER).exists()
        Log.d(TAG, "Pairing marker present: $paired")
        return paired
    }

    private fun markPaired(context: Context, paired: Boolean) {
        val marker = File(context.filesDir, PAIRED_MARKER)
        if (paired) {
            marker.parentFile?.mkdirs()
            runCatching { marker.createNewFile() }
        } else {
            runCatching { marker.delete() }
        }
    }

    private fun setState(next: State, failure: String? = null) {
        if (failure != null) {
            lastFailure = failure
            Log.w(TAG, "bring-up: $failure")
        } else if (next == State.READY) {
            lastFailure = ""
        }
        if (next == state) {
            InputDiagnostics.helperState(next.name, next == State.READY, lastFailure)
            return
        }
        Log.i(TAG, "state: $state -> $next")
        state = next
        InputDiagnostics.helperState(next.name, next == State.READY, if (next == State.READY) "" else lastFailure)
        if (next == State.STARTING) armStartWatchdog()
        notifyListeners()
    }

    private fun armStartWatchdog() {
        scheduler.schedule({
            if (state == State.STARTING) {
                try { adbStream?.close() } catch (_: Exception) {}
                adbStream = null
                setState(
                    State.NEEDS_WIRELESS_DEBUGGING,
                    "the helper process was started but never reported back within ${START_TIMEOUT_MS / 1000}s " +
                        "(its output: ${bootOutput.ifEmpty { "none" }})",
                )
            }
        }, START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** First lines the helper printed on its shell stream while starting, for the failure message. */
    @Volatile
    private var bootOutput: String = ""

    private fun notifyListeners() {
        listeners.forEach { listener ->
            runCatching { listener() }.onFailure {
                Log.w(TAG, "Privileged state listener failed (state=$state)", it)
            }
        }
    }

    /**
     * Try to make the helper live — discover the connect port via mDNS, connect, start the
     * server. Idempotent; safe to call from anywhere. The state machine reflects progress.
     */
    fun ensureRunning() {
        Log.d(TAG, "ensureRunning requested: state=$state, initialized=${appContext != null}")
        worker.execute {
            val ctx = appContext ?: run {
                Log.w(TAG, "ensureRunning skipped: service has not been initialized")
                return@execute
            }
            if (state == State.UNSUPPORTED) {
                Log.d(TAG, "ensureRunning skipped: wireless debugging unsupported")
                return@execute
            }
            if (state == State.READY && service != null) {
                Log.d(TAG, "ensureRunning skipped: helper already ready")
                return@execute
            }
            // Bring-up already in flight: a previous ensureRunning() task is spawning
            // a server and its binder hasn't published yet. Without this guard, every
            // call between the spawn and the READY transition fires another server,
            // and each one runs its own /dev/input/event* HotkeyMonitor — observed in
            // logcat as three identical "hotkey monitor started" lines and 3× FD use
            // on every input device. The dropped binders are leaked; the displaced
            // server processes never exit.
            if (state == State.DISCOVERING ||
                state == State.CONNECTING ||
                state == State.STARTING
            ) {
                Log.d(TAG, "ensureRunning ignored — bring-up already in flight (state=$state)")
                return@execute
            }
            logDeveloperOptions(ctx)
            if (!hasPaired(ctx)) {
                Log.d(TAG, "ensureRunning blocked: pairing required")
                setState(State.NEEDS_PAIRING)
                return@execute
            }

            setState(State.DISCOVERING)
            val discovered = AdbDiscovery.discoverConnect(ctx, DISCOVERY_TIMEOUT_MS)
            val endpoints = discovered.ifEmpty { listOfNotNull(manualConnect) }
            InputDiagnostics.timelineEvent(
                "connect endpoints: ${if (endpoints.isEmpty()) "none (mDNS found nothing, no manual endpoint)" else endpoints.joinToString { "${it.host}:${it.port}" }}",
            )
            if (endpoints.isEmpty()) {
                setState(
                    State.NEEDS_WIRELESS_DEBUGGING,
                    "Wireless debugging is not advertising a connect port (turn it on, and make sure the phone is on Wi-Fi)",
                )
                return@execute
            }

            val adb = AdbConnectionManager.getInstance(ctx)
            if (!adb.isConnected) {
                setState(State.CONNECTING)
                // Android can advertise a stale `_adb-tls-connect._tcp` next to the active
                // one; try each until one accepts the connect, so a refused stale endpoint
                // doesn't masquerade as "wireless debugging off".
                var connected = false
                var lastError: Exception? = null
                for (endpoint in endpoints) {
                    try {
                        if (adb.connect(endpoint.host, endpoint.port)) {
                            connected = true
                            break
                        }
                        Log.w(TAG, "ADB connect to ${endpoint.host}:${endpoint.port} returned false")
                    } catch (e: AdbPairingRequiredException) {
                        // The device no longer trusts our key — wipe the marker and walk
                        // the user back through pairing. No point trying other endpoints.
                        Log.w(TAG, "device requires (re-)pairing — invalidating the paired marker")
                        markPaired(ctx, false)
                        setState(State.NEEDS_PAIRING, "the phone no longer trusts this app's key: pair again")
                        return@execute
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(
                            TAG,
                            "ADB connect to ${endpoint.host}:${endpoint.port} failed: ${e.message}",
                        )
                    }
                }
                if (!connected) {
                    Log.e(
                        TAG,
                        "all ${endpoints.size} connect endpoint(s) refused — wireless debugging off?",
                        lastError,
                    )
                    setState(
                        State.NEEDS_WIRELESS_DEBUGGING,
                        "could not connect to ${endpoints.joinToString { "${it.host}:${it.port}" }}: " +
                            "${lastError?.javaClass?.simpleName ?: "refused"} ${lastError?.message.orEmpty()}".trim(),
                    )
                    return@execute
                }
            }

            setState(State.STARTING)
            bootOutput = ""
            val stream = ServerBootstrap.start(ctx, adb)
            if (stream == null) {
                setState(State.NEEDS_WIRELESS_DEBUGGING, "could not open the ADB shell stream to start the helper")
                return@execute
            }
            adbStream = stream
            captureBootOutput(stream)
            // BinderReceiverProvider.onPrivilegedBinder() drives us to READY when the server
            // publishes its Binder back to the app.
        }
    }

    /**
     * Pair using the 6-digit [pairingCode] from Wireless Debugging's "Pair device with a
     * pairing code" dialog. The pairing port is discovered via mDNS — this only works
     * while the dialog is still in the foreground, which is why Glashutte reads the code
     * from a notification's `RemoteInput` (the shade overlays the dialog instead of
     * backgrounding it). [done] runs on the worker thread.
     */
    fun activate(pairingCode: String, done: (Boolean) -> Unit) = activate(pairingCode, null, done)

    /**
     * As [activate], but with the pairing endpoint typed in by the user (the "IP address &
     * port" line of the pairing dialog) instead of found over mDNS. Needed on networks that
     * block multicast, which is also why the notification route can fail.
     */
    fun activate(pairingCode: String, manualEndpoint: AdbDiscovery.Endpoint?, done: (Boolean) -> Unit) {
        worker.execute {
            val ctx = appContext ?: run { done(false); return@execute }
            try {
                InputDiagnostics.timelineEvent(
                    "pairing requested (${if (manualEndpoint != null) "manual ${manualEndpoint.host}:${manualEndpoint.port}" else "mDNS"})",
                )
                val endpoint = manualEndpoint ?: AdbDiscovery.discoverPairing(ctx, DISCOVERY_TIMEOUT_MS)
                if (endpoint == null) {
                    markPaired(ctx, false)
                    lastFailure = "pairing: the pairing dialog was not found on the network (is it still open? " +
                        "otherwise type its IP address and port in manually)"
                    InputDiagnostics.helperState(state.name, false, lastFailure)
                    notifyListeners()
                    done(false); return@execute
                }
                val adb = AdbConnectionManager.getInstance(ctx)
                val paired = adb.pair(endpoint.host, endpoint.port, pairingCode)
                Log.i(TAG, "ADB pair ${endpoint.host}:${endpoint.port} ok=$paired")
                InputDiagnostics.timelineEvent("pairing ${endpoint.host}:${endpoint.port} -> ${if (paired) "OK" else "REJECTED (wrong code or dialog closed)"}")
                markPaired(ctx, paired)
                if (!paired) {
                    lastFailure = "pairing was rejected: check the 6-digit code and that the dialog is still open"
                    InputDiagnostics.helperState(state.name, false, lastFailure)
                    notifyListeners()
                    done(false); return@execute
                }
                done(true)
                ensureRunning()
            } catch (e: Exception) {
                Log.e(TAG, "activate failed", e)
                markPaired(ctx, false)
                lastFailure = "pairing failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
                InputDiagnostics.helperState(state.name, false, lastFailure)
                notifyListeners()
                done(false)
            }
        }
    }

    /**
     * Called by [BinderReceiverProvider] when [PrivilegedServer] hands its Binder back. The
     * provider gates this on the calling uid (shell or self), so this is reachable only
     * from the server we started.
     */
    fun onPrivilegedBinder(binder: IBinder) {
        if (!binder.pingBinder()) {
            Log.w(TAG, "onPrivilegedBinder: ping failed")
            return
        }
        // Displace any previously-bound server. Without this, the spawn-race window
        // can hand us a second (or third) binder for a separate PrivilegedServer
        // process — each one running its own /dev/input/event* HotkeyMonitor and
        // its own native input injector. The orphans never exit on their own.
        val previous = service
        if (previous != null) {
            Log.w(TAG, "displacing previously-bound privileged service — calling exit()")
            runCatching { previous.setHotkeyListener(null) }
            runCatching { previous.exit() }
        }
        service = IPrivilegedService.Stub.asInterface(binder)
        Log.i(TAG, "privileged binder bound — READY")
        runCatching { binder.linkToDeath({ onHelperDied() }, 0) }
            .onFailure { Log.w(TAG, "linkToDeath failed", it) }
        pushInputConfig()
        refreshHelperReport()
        // Only install the hotkey listener if a keyboard is actually attached. With
        // no keyboard the helper opens no /dev/input/event* nodes at all — minimum
        // footprint, no contention against the glasses' USB IMU endpoint on OEMs
        // (Samsung) where BT input attach disturbs USB hosts.
        if (hotkeyMonitoringRequested) installHotkeyListener()
        setState(State.READY)
    }

    private fun onHelperDied() {
        if (service == null) return
        service = null
        try { adbStream?.close() } catch (_: Exception) {}
        adbStream = null
        InputDiagnostics.helperDied("binder death")
        setState(State.NEEDS_WIRELESS_DEBUGGING, "the helper process died (killed by the system, or Wireless Debugging was switched off)")
        ensureRunning()
    }

    private fun captureBootOutput(stream: io.github.muntashirakon.adb.AdbStream) {
        Thread({
            try {
                val reader = stream.openInputStream().bufferedReader()
                val sb = StringBuilder()
                var lines = 0
                while (lines < BOOT_OUTPUT_LINES) {
                    val line = reader.readLine() ?: break
                    lines++
                    sb.append(line.take(160)).append(" | ")
                    bootOutput = sb.toString()
                    InputDiagnostics.timelineEvent("helper stdout: ${line.take(160)}")
                }
            } catch (_: Exception) {
                // Stream closed — normal on teardown.
            }
        }, "glashutte-boot-output").apply { isDaemon = true }.start()
    }

    /** Send the diagnostics-screen input switches to the helper. */
    fun pushInputConfig() {
        val ctx = appContext ?: return
        val helper = service ?: return
        val strategy = InputExperiments.strategy(ctx)
        val wait = InputExperiments.waitForResult(ctx)
        val ownFocus = InputExperiments.ownFocusFlag(ctx)
        worker.execute {
            runCatching { helper.setInputConfig(strategy.wire, wait, ownFocus) }
                .onFailure { Log.w(TAG, "setInputConfig failed", it) }
        }
    }

    /**
     * Ask the helper to describe itself (its routes into InputManager, the raw-input monitor's
     * counters, recent injections) and feed the answer to the diagnostics. Safe to call often;
     * [done] runs on the worker thread.
     */
    fun refreshHelperReport(done: (() -> Unit)? = null) {
        val helper = service
        if (helper == null) {
            InputDiagnostics.helperPing(false)
            InputDiagnostics.helperReport("(no helper connected: state=$state)")
            done?.invoke()
            return
        }
        worker.execute {
            val alive = helper.asBinder().pingBinder()
            InputDiagnostics.helperPing(alive)
            val report = runCatching { helper.probe() }
                .getOrElse { "probe failed: ${it.javaClass.simpleName}: ${it.message}" }
            InputDiagnostics.helperReport(report)
            val routeOk = report.contains("route=ok")
            InputDiagnostics.injector(
                routeOk,
                report.lineSequence().firstOrNull { it.startsWith("injector:") } ?: "no injector line in the helper's report",
            )
            report.lineSequence().firstOrNull { it.startsWith("uid=") }?.let { InputDiagnostics.helperInfo(it) }
            done?.invoke()
        }
    }

    /** `dumpsys` excerpts for one display (windows, focus, activities), or a reason it is unavailable. */
    fun describeDisplay(displayId: Int): String {
        val helper = service ?: return "(no helper connected: state=$state)"
        return runCatching { helper.describeDisplay(displayId) }
            .getOrElse { "describeDisplay failed: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /**
     * App-level dispatcher for the Ctrl+Alt+X global hotkeys the shell-uid server captures
     * off `/dev/input/event*`. Set once by the app at startup (alongside the other
     * `WorkspaceController.*` lambdas in `GlashutteApp.onCreate`). Invoked on the main thread.
     * Codes are [PrivilegedHotkeys].HK_*.
     */
    @Volatile
    var hotkeyHandler: ((code: Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hotkeyListenerStub = object : IPrivilegedHotkeyListener.Stub() {
        override fun onHotkey(code: Int) {
            // Binder thread → main thread; controller methods aren't always main-only,
            // but the actions (zoom presets, view mode, recenter) ultimately touch
            // the renderer and UI, so jumping once here is safest.
            mainHandler.post { hotkeyHandler?.invoke(code) }
        }
        override fun onMouseDelta(dx: Int, dy: Int, wheel: Int) {
            // Raw evdev mouse deltas — never quantised to the phone-screen edge.
            mainHandler.post { mouseDeltaHandler?.invoke(dx, dy, wheel) }
        }
        override fun onMouseButton(code: Int, pressed: Boolean) {
            // With the helper holding EVIOCGRAB on the mouse, button events never
            // reach `dispatchGenericMotionEvent` either — so we have to forward
            // them here and the app dispatches workspace clicks ourselves.
            mainHandler.post { mouseButtonHandler?.invoke(code, pressed) }
        }
    }

    /**
     * App-level handler for raw evdev mouse motion (REL_X, REL_Y, REL_WHEEL batched
     * per SYN_REPORT). Set once by the app at startup. dx/dy are kernel ticks;
     * the handler is responsible for any scaling to workspace NDC units.
     */
    @Volatile
    var mouseDeltaHandler: ((dx: Int, dy: Int, wheel: Int) -> Unit)? = null

    /**
     * App-level handler for mouse button transitions (BTN_LEFT/RIGHT/MIDDLE
     * press + release). [code] is the kernel BTN_* number, [pressed] true on
     * press / false on release.
     */
    @Volatile
    var mouseButtonHandler: ((code: Int, pressed: Boolean) -> Unit)? = null

    private fun installHotkeyListener() {
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(hotkeyListenerStub)
        }.onFailure { Log.w(TAG, "setHotkeyListener failed", it) }
    }

    /**
     * Whether the app has currently asked the helper to monitor hotkeys. Controlled by
     * [setHotkeyMonitoringEnabled] from the keyboard-presence listener in MainActivity;
     * stays true once any keyboard appears so the next privileged-binder bind installs
     * the listener immediately.
     */
    @Volatile
    private var hotkeyMonitoringRequested: Boolean = false

    /**
     * Toggle the shell-uid HotkeyMonitor based on whether a physical keyboard is
     * attached. When `enabled = false` the listener Binder is nulled out, the helper
     * tears down its FileObserver + reader threads + every `/dev/input/event*` FD.
     * When `enabled = true` it re-opens them and resumes Ctrl+Alt+X dispatch.
     */
    fun setHotkeyMonitoringEnabled(enabled: Boolean) {
        if (hotkeyMonitoringRequested == enabled) return
        hotkeyMonitoringRequested = enabled
        Log.i(TAG, "hotkey monitoring requested -> $enabled")
        val helper = service ?: return
        runCatching {
            helper.setHotkeyListener(if (enabled) hotkeyListenerStub else null)
        }.onFailure { Log.w(TAG, "setHotkeyListener toggle failed", it) }
    }

    /**
     * Force-detach and re-attach the VITURE glasses' USB device via the helper, so the
     * kernel re-enumerates it. Triggered by the app's DOF-stall watchdog after the SDK
     * stops producing poses for an extended period — observed empirically that a stale
     * Carina USB endpoint sometimes only recovers from a bus reset, which the BT-pair
     * USB churn occasionally does on its own. Returns immediately; the actual rescan
     * runs on the helper's worker thread and the resulting USB_DEVICE_ATTACHED intent
     * re-triggers [io.github.dmulder.glashutte.spatial.WorkspacePresentation.retryHeadTracking].
     */
    fun rescanGlassesUsb() {
        val helper = service ?: run {
            Log.w(TAG, "rescanGlassesUsb ignored — helper not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching { helper.rescanGlassesUsb() }
                .onFailure { Log.w(TAG, "rescanGlassesUsb failed", it) }
        }
    }

    // region Privileged-call surface (mirrors ShizukuManager so GlashutteApp swaps cleanly)

    fun launchApp(displayId: Int, packageName: String, activityName: String) {
        Log.i(
            "Glashutte/Launch",
            "7) PrivilegedService.launchApp pkg=$packageName display=$displayId state=$state",
        )
        val helper = service
        if (helper == null) {
            Log.w(TAG, "launchApp ignored — not READY (state=$state)")
            return
        }
        worker.execute {
            runCatching {
                Log.i(
                    "Glashutte/Launch",
                    "8) helper.launchOnDisplay AIDL pkg=$packageName display=$displayId",
                )
                val ok = helper.launchOnDisplay(displayId, packageName, activityName)
                Log.i(
                    "Glashutte/Launch",
                    "9) helper returned ok=$ok pkg=$packageName display=$displayId",
                )
            }.onFailure { Log.e(TAG, "launchApp failed", it) }
        }
    }

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
    ): Int? {
        val helper = service
        if (helper == null) {
            Log.w(TAG, "createVirtualDisplay ignored — not READY (state=$state)")
            return null
        }
        return runCatching {
            helper.createVirtualDisplay(name, width, height, densityDpi, surface)
                .takeIf { it >= 0 }
        }.onFailure { Log.e(TAG, "createVirtualDisplay failed", it) }.getOrNull()
    }

    fun releaseVirtualDisplay(displayId: Int) =
        onWorker { service?.releaseVirtualDisplay(displayId) }

    /**
     * Inject an ACTION_SCROLL motion event into the app on [displayId] at content
     * pixel (x, y). Identical path to `UiScreen.dispatchScroll` but routed through the
     * shell-uid helper so the event reaches an out-of-process VirtualDisplay; one
     * AIDL call per frame, no swipe synthesis.
     */
    fun scrollOnDisplay(displayId: Int, x: Int, y: Int, vScroll: Float) {
        val helper = service
        onWorker {
            val outcome = if (helper == null) noHelper() else callOutcome { helper.injectScrollEx(displayId, x, y, vScroll) }
            InputDiagnostics.recordInject("scroll", "display=$displayId ($x,$y) v=%.2f".format(vScroll), outcome)
        }
    }

    private fun noHelper(): InjectOutcome =
        InjectOutcome(InjectOutcome.NO_HELPER, "", "state=$state${if (lastFailure.isEmpty()) "" else " — $lastFailure"}")

    private inline fun callOutcome(block: () -> String?): InjectOutcome =
        try {
            InjectOutcome.parse(block())
        } catch (t: Throwable) {
            InjectOutcome(InjectOutcome.HELPER_CALL_FAILED, "", "${t.javaClass.simpleName}: ${t.message}")
        }

    /**
     * Streamed touch injection: one frame at a time, action ∈ {DOWN=0, MOVE=1, UP=2,
     * CANCEL=3}. Lets the trackpad surface a touch-move-lift gesture into an app's
     * VirtualDisplay so taps and swipes work the same way they would on a real screen.
     */
    fun injectTouch(displayId: Int, x: Int, y: Int, action: Int) {
        val name = when (action) {
            0 -> "DOWN"; 1 -> "MOVE"; 2 -> "UP"; 3 -> "CANCEL"; else -> "?($action)"
        }
        Log.d(TAG, "injectTouch $name @ ($x,$y) display=$displayId")
        val helper = service
        onWorker {
            val outcome = if (helper == null) noHelper() else callOutcome { helper.injectTouchEx(displayId, x, y, action) }
            if (name != "MOVE" || !outcome.ok) {
                InputDiagnostics.recordInject("touch", "$name display=$displayId ($x,$y)", outcome)
            }
        }
    }

    fun key(displayId: Int, keyCode: Int) {
        val helper = service
        onWorker {
            val outcome = if (helper == null) noHelper() else callOutcome { helper.keyEx(displayId, keyCode) }
            InputDiagnostics.recordInject("key", "${KeyEvent.keyCodeToString(keyCode)} display=$displayId", outcome)
        }
    }

    /** One physical-keyboard key transition ([action] is KeyEvent.ACTION_DOWN / ACTION_UP). */
    fun injectKey(displayId: Int, keyCode: Int, action: Int, metaState: Int) {
        val helper = service
        onWorker {
            val outcome = if (helper == null) noHelper() else callOutcome { helper.injectKeyEx(displayId, keyCode, action, metaState) }
            InputDiagnostics.recordInject(
                "key",
                "${KeyEvent.keyCodeToString(keyCode)} ${if (action == KeyEvent.ACTION_DOWN) "down" else "up"} meta=0x${Integer.toHexString(metaState)} display=$displayId",
                outcome,
            )
        }
    }

    fun text(displayId: Int, value: String) {
        val helper = service
        onWorker {
            val outcome = if (helper == null) noHelper() else callOutcome { helper.textEx(displayId, value) }
            InputDiagnostics.recordInject("text", "${value.length} chars display=$displayId", outcome)
        }
    }

    fun forceStop(packageName: String) = onWorker { service?.forceStop(packageName) }

    /** Whether the given VirtualDisplay currently has any activity stacked on it. */
    fun displayHasActivity(displayId: Int): Boolean =
        runCatching { service?.displayHasActivity(displayId) == true }.getOrDefault(false)

    /**
     * Injects KEYCODE_MEDIA_PAUSE at the given display so any media playback on
     * the activity (YouTube audio, etc.) stops when the window is minimised.
     */
    fun sendMediaPause(displayId: Int) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_MEDIA_PAUSE) }
    }

    fun sendBack(displayId: Int, onEmptied: () -> Unit) {
        onWorker { service?.key(displayId, KeyEvent.KEYCODE_BACK) }
        scheduler.schedule(
            {
                runCatching {
                    if (service?.displayHasActivity(displayId) == false) onEmptied()
                }.onFailure { Log.e(TAG, "back-empty check failed", it) }
            },
            BACK_SETTLE_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    // endregion

    private fun onWorker(block: () -> Unit) {
        worker.execute { runCatching { block() } }
    }

    /** How long to wait for an mDNS hit before giving up. */
    private const val DISCOVERY_TIMEOUT_MS = 5_000L

    /** How long to wait for the started helper to hand its binder back before declaring failure. */
    private const val START_TIMEOUT_MS = 20_000L

    /** How many lines of the helper's shell output to keep for the failure message. */
    private const val BOOT_OUTPUT_LINES = 12

    /** How long to wait after a Back press before checking whether it closed the app. */
    private const val BACK_SETTLE_MS = 800L

    /** Path of the marker file created on a successful [activate] (relative to filesDir). */
    private const val PAIRED_MARKER = "adb/paired.flag"
}
