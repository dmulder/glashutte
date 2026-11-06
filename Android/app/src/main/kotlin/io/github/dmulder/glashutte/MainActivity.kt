package io.github.dmulder.glashutte

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import io.github.dmulder.glashutte.glasses.InputDiagnostics
import io.github.dmulder.glashutte.privileged.AdbDiscovery
import io.github.dmulder.glashutte.glasses.InputExperiments
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.dmulder.glashutte.databinding.ActivityMainBinding
import io.github.dmulder.glashutte.desktop.DesktopWallpaperStore
import io.github.dmulder.glashutte.desktop.WallpaperSource
import io.github.dmulder.glashutte.glasses.GlassesDisplay
import io.github.dmulder.glashutte.privileged.PairingNotifier
import io.github.dmulder.glashutte.privileged.PrivilegedService
import io.github.dmulder.glashutte.privileged.PrivilegedService.State
import io.github.dmulder.glashutte.spatial.WorkspaceController
import io.github.dmulder.glashutte.spatial.WorkspacePresentation
import io.github.dmulder.glashutte.spatial.WorkspaceRenderer

/**
 * The phone-side control panel — Glashutte's input device.
 *
 * One activity, three scenes swapped by state:
 *
 *  - **Wizard** (`PrivilegedService.state != READY`) walks the user through wireless-debugging
 *    activation: turn on Wireless Debugging, then enter the 6-digit pairing code. The splash
 *    image sits at the top as a hero banner.
 *  - **Waiting** (paired but no glasses) — centered "Connect your VITURE glasses".
 *  - **Main** (paired + glasses connected) — the existing toolbar (view mode, capture, screen
 *    layout, screen size, keyboard) over the trackpad. The phone shows no app list — the
 *    glasses do, and the phone drives them.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding

    /** The workspace shown on the glasses, while they are connected. */
    private var presentation: WorkspacePresentation? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Virtual display id whose text field is currently focused, or -1. Set by the
     *  accessibility service's focus events; drives the auto-open of the phone keyboard
     *  and the routing of typed text back via [PrivilegedService.text]. */
    private var focusedAppDisplayId: Int = -1

    /** Last value of [keyboardField] so [forwardKeyboardDelta] can compute insertions
     *  and deletions per text-change event. */
    private var lastKeyboardText: String = ""

    /** Desktop index waiting for a wallpaper pick result. −1 = none in flight. */
    private var pendingWallpaperDesktopIdx: Int = -1

    /** Caps diagnostic mouse logs so a steady stream of events doesn't drown logcat. */
    private var mouseDiagFrameCount: Int = 0

    /**
     * Phone-side photo picker for wallpaper. Triggered from the in-glasses settings
     * panel via [WorkspaceController.pickWallpaperFromDevice] (the panel lives on a
     * VirtualDisplay and can't host the picker dialog itself). On a successful pick
     * we take a persistable URI permission so the URI keeps working across restarts.
     */
    private val pickWallpaperLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val idx = pendingWallpaperDesktopIdx
        pendingWallpaperDesktopIdx = -1
        if (uri == null || idx < 0) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        DesktopWallpaperStore.setSource(idx, WallpaperSource.Uri(uri.toString()))
    }

    /** Reacts when the glasses are plugged in or out while the panel is open. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = syncGlasses()
        override fun onDisplayRemoved(displayId: Int) = syncGlasses()
        override fun onDisplayChanged(displayId: Int) = syncGlasses()
    }

    /** Re-evaluates whether any physical keyboard is attached whenever the input
     *  device set changes. The HotkeyMonitor in the shell-uid helper is gated on
     *  the result — no keyboard means we don't open any /dev/input/event* nodes. */
    private val keyboardPresenceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshKeyboardPresence()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshKeyboardPresence()
        override fun onInputDeviceChanged(deviceId: Int) = refreshKeyboardPresence()
    }

    private fun inputManager(): InputManager = getSystemService(InputManager::class.java)

    private fun describeDevice(d: InputDevice): String =
        "id=${d.id} '${d.name}' sources=0x${Integer.toHexString(d.sources)} kbType=${d.keyboardType}" +
            (if (d.isVirtual) " virtual" else "")

    /**
     * Scan every attached input device for a real physical keyboard and tell
     * [PrivilegedService] whether to keep the global hotkey spy alive.
     *
     * Naive checks (sources & SOURCE_KEYBOARD, keyboardType == ALPHABETIC) match
     * too aggressively: Android's "Virtual" soft keyboard (id=-1) and HID combo
     * mouse-receivers (e.g. Logitech "MX Anywhere 2S Keyboard") both advertise
     * ALPHABETIC even though neither has A-Z keys. Filter them out with
     *
     *   - skip [InputDevice.isVirtual] (kills the "Virtual" keyboard)
     *   - require [InputDevice.hasKeys] to return true for `KEYCODE_A` (kills the
     *     receivers whose only "keyboard" surface is media keys)
     *
     * Done as a full scan rather than tracking individual add/remove flips
     * because a single device can advertise multiple sources and the listener
     * can fire in any order.
     */
    private fun refreshKeyboardPresence() {
        val current = mutableSetOf<Int>()
        var anyMouse = false
        val mouseNames = mutableListOf<String>()
        val inventory = mutableListOf<String>()
        for (id in InputDevice.getDeviceIds()) {
            InputDevice.getDevice(id)?.let { inventory.add(describeDevice(it)) }
            val dev = InputDevice.getDevice(id) ?: continue
            val name = dev.name
            if (dev.isVirtual) {
                Log.d("Glashutte/Main", "kb scan: skip virtual id=$id name='$name'")
                continue
            }
            // Track real mice so the monitor stays alive for the raw-evdev mouse
            // delta path even when no keyboard is attached.
            if ((dev.sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                Log.i("Glashutte/Main", "mouse present: id=$id name='$name'")
                anyMouse = true
                mouseNames.add(name)
            }
            if ((dev.sources and InputDevice.SOURCE_KEYBOARD) == 0) continue
            if (dev.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                Log.d("Glashutte/Main", "kb scan: skip non-alphabetic id=$id name='$name'")
                continue
            }
            // Wireless mice (Logitech "MX Anywhere 2S Keyboard" etc.) expose a
            // media-keys HID interface that Android classifies as ALPHABETIC and
            // even claims KEYCODE_A — but the device's primary identity is a
            // mouse. Real keyboards never advertise SOURCE_MOUSE.
            if ((dev.sources and InputDevice.SOURCE_MOUSE) != 0) {
                Log.d(
                    "Glashutte/Main",
                    "kb scan: skip mouse-combo id=$id name='$name' (sources=0x${
                        dev.sources.toString(16)
                    })",
                )
                continue
            }
            val hasA = dev.hasKeys(KeyEvent.KEYCODE_A)
            if (hasA.isEmpty() || !hasA[0]) {
                Log.d(
                    "Glashutte/Main",
                    "kb scan: skip no-KEYCODE_A id=$id name='$name' (combo HID, not a real keyboard)",
                )
                continue
            }
            Log.i("Glashutte/Main", "keyboard present: id=$id name='$name'")
            current.add(id)
        }
        InputDiagnostics.deviceInventory(
            inventory,
            mouseNames,
            current.mapNotNull { InputDevice.getDevice(it)?.name },
        )
        PrivilegedService.setHotkeyMonitoringEnabled(current.isNotEmpty() || anyMouse)

        // Proactive glasses-USB rescan around keyboard-connect events. The first
        // scan after process start is the baseline — only later adds count.
        // Catches the Samsung quirk where pairing a BT keyboard wedges the
        // Carina endpoint; rebinding ~1.5s later (after the BT-stack USB churn
        // peaks) usually resurrects pose flow before the user sees a drop.
        if (!firstKeyboardScan) {
            val freshAdds = current - lastKnownKeyboardIds
            if (freshAdds.isNotEmpty() && WorkspaceController.isRunning) {
                Log.i(
                    "Glashutte/Main",
                    "keyboard add (ids=$freshAdds) — scheduling glasses USB rescan in ${KEYBOARD_CONNECT_RESCAN_MS}ms",
                )
                pendingKeyboardRescan?.let { mainHandler.removeCallbacks(it) }
                val r = Runnable {
                    if (!WorkspaceController.isRunning) return@Runnable
                    Log.i(
                        "Glashutte/Main",
                        "proactive rescanGlassesUsb (post-keyboard-connect)",
                    )
                    PrivilegedService.rescanGlassesUsb()
                }
                pendingKeyboardRescan = r
                mainHandler.postDelayed(r, KEYBOARD_CONNECT_RESCAN_MS)
            }
        }
        firstKeyboardScan = false
        lastKnownKeyboardIds = current
    }

    /** First-scan-after-onCreate sentinel: don't fire a proactive rescan for a keyboard
     *  that was already attached when the app launched — only for *new* attaches. */
    private var firstKeyboardScan = true
    private var lastKnownKeyboardIds: Set<Int> = emptySet()
    private var pendingKeyboardRescan: Runnable? = null

    private val privilegeListener: () -> Unit = { runOnUiThread { renderStatus() } }
    private var lastLoggedSetupScene: Pair<State, Boolean>? = null

    /**
     * Result-launcher for POST_NOTIFICATIONS (API 33+). The notification path is the
     * primary pairing UX; if the user denies, the form below the wizard still works as a
     * fallback so the activity ignores the result.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — the form fallback works without notifications */ }

    /**
     * Result-launcher for RECORD_AUDIO. Granted once at first launch so the OS doesn't
     * re-prompt every time an app launched into the workspace tries to use the mic.
     */
    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* ignored — launched apps will see the system prompt themselves if denied */ }

    /**
     * Result-launcher for CAMERA. Android withholds USB access to a device that exposes a
     * video-class interface (the glasses' Carina VIO cameras) until the app holds CAMERA, so
     * head tracking is restarted once the user answers.
     */
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Log.i("Glashutte/Main", "CAMERA permission result granted=$granted")
        io.github.dmulder.glashutte.glasses.GlassesDiagnostics.i(
            "Glashutte/Main", "CAMERA permission result granted=$granted",
        )
        presentation?.restartHeadTracking()
    }

    private var cameraPermissionAsked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // DO NOT add FLAG_NOT_FOCUSABLE here, and do NOT make this window hold input
        // focus by any other means (e.g. Pointer Capture). This activity sits on
        // display 0; the workspace + launched apps live on secondary/glasses displays.
        // The flag was originally added to stop a trackpad tap from flipping the
        // top-focused display to 0 (which made Samsung One UI's GameBooster pause the
        // launched app on its secondary display and tear down its input channel). But
        // holding/denying focus here breaks three things, so the flag is OFF and the
        // window stays focusable:
        //   1. Secondary-display apps — if THIS window takes input focus, display 0
        //      becomes top-focused, the app's secondary display loses focus, and
        //      GameBooster pauses it. (We now feed launched apps via *injected* input
        //      on trusted displays, not focus-based dispatch, so a momentary tap-flip
        //      no longer kills them — but a window that *holds* focus still would.)
        //   2. Input/ANR — the flag blocks mouse/keyboard dispatch, causing
        //      "Application does not have a focused window" ANRs when a BT mouse is up.
        //   3. Pseudo-root bootstrap — the privileged helper is launched via the
        //      wireless-debugging pairing flow (docs/PRIVILEGE.md); the in-app
        //      pairing-code field needs keyboard focus, which the flag starves.
        // The mouse-roaming problem the flag/Pointer-Capture would "solve" is instead
        // handled WITHOUT focus, by EVIOCGRAB in the privileged helper (PrivilegedServer
        // .HotkeyMonitor) — the only approach compatible with all three constraints.
        // window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide the Android system pointer while it's anywhere over our window.
        // The workspace cursor (rendered in the glasses) is the only cursor the
        // user looks at; the on-phone pointer is just noise once raw-evdev mouse
        // deltas drive the workspace from PrivilegedService.mouseDeltaHandler.
        window.decorView.pointerIcon =
            android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)

        // Sticky-immersive fullscreen — hide the status bar and navigation /
        // gesture bar. Two reasons:
        //  1. The mouse cursor riding past the top or bottom edge used to land
        //     clicks on system_server's status bar / Samsung gesture pill (those
        //     surfaces ignore FLAG_NOT_FOCUSABLE etc. because they aren't owned
        //     by our window). Now the area is ours and our event consumer eats
        //     the clicks before they go anywhere.
        //  2. The trackpad already takes the whole visible area; no reason to
        //     give up screen real estate to system chrome the user isn't looking
        //     at (they're looking at the glasses).
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        binding.setupButton.setOnClickListener { onSetupAction() }
        binding.wizardManualToggle.setOnClickListener {
            binding.wizardManualBox.visibility =
                if (binding.wizardManualBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.wizardPairNow.setOnClickListener { submitManualPair() }
        binding.wizardConnectNow.setOnClickListener { submitManualConnect() }
        binding.wizardDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.viewModeButton.setOnClickListener { toggleViewMode() }
        // Long-press = full glasses reconnect (helper + display + head tracking), a heavier
        // recovery than the tap's DOF-only retry.
        binding.viewModeButton.setOnLongClickListener { attemptGlassesReconnect(); true }
        // The in-view toolbar's DOF-retry button routes here too, so both report success /
        // failure the same way. (WorkspacePresentation no longer owns this hook.)
        WorkspaceController.retryHeadTracking = { mainHandler.post { attemptReconnectDof() } }
        WorkspaceController.requestCameraPermission = { mainHandler.post { askCameraPermissionOnce() } }
        // Tap = one-shot screenshot; long-press = start/stop frame-sequence recording.
        binding.captureButton.setOnClickListener { onSnapshot() }
        binding.captureButton.setOnLongClickListener {
            if (WorkspaceController.isRunning) {
                val on = WorkspaceController.toggleRecording()
                Toast.makeText(
                    this,
                    if (on) "Recording started" else "Recording stopped",
                    Toast.LENGTH_SHORT,
                ).show()
                true
            } else {
                Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
                false
            }
        }
        // Keep the capture button's icon in sync with recording state, whichever toolbar
        // toggled it (phone long-press or the in-view capture button).
        WorkspaceController.addRecordingListener(recordingListener)
        renderCaptureButton(WorkspaceController.isRecording)
        binding.layoutButton.setOnClickListener {
            // The button is grayed and isEnabled=false in PINNED via renderToolbarStates,
            // but isEnabled toggling alone doesn't block click in all paths — keep the
            // mode check as a belt-and-braces guard.
            if (WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.PINNED) return@setOnClickListener
            val next = WorkspaceController.cycleLayout()
            Toast.makeText(this, "Layout: ${next.displayName}", Toast.LENGTH_SHORT).show()
        }
        binding.screenHeightButton.setOnClickListener {
            val percent = (WorkspaceController.cycleScreenBand() * 100).toInt()
            Toast.makeText(this, "Zoom $percent%", Toast.LENGTH_SHORT).show()
        }
        binding.alignHorizonButton.setOnClickListener {
            Log.i("Glashutte/Main", "alignHorizon click: running=${WorkspaceController.isRunning}")
            if (WorkspaceController.isRunning) {
                WorkspaceController.alignVerticalToHead()
            } else {
                Toast.makeText(this, "Connect glasses first", Toast.LENGTH_SHORT).show()
            }
        }
        WorkspaceController.addZoomListener(zoomHudListener)
        binding.keyboardButton.setOnClickListener { toggleKeyboard() }
        // Long-press to jump to Accessibility settings — the auto-keyboard feature
        // needs the GlashutteAccessibilityService toggled on there.
        binding.keyboardButton.setOnLongClickListener {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this, "Enable Glashutte under Accessibility", Toast.LENGTH_LONG,
            ).show()
            true
        }
        renderViewModeButton()
        renderToolbarStates()
        // DOF flips async from the head-pose thread; viewMode flips from any toggle path.
        // Both must refresh the lock + layout buttons' enabled/alpha. Hop to main since
        // these listeners can fire on any thread.
        WorkspaceController.addDofListener { mainHandler.post { renderToolbarStates() } }
        WorkspaceController.addViewModeListener {
            mainHandler.post {
                renderViewModeButton()
                renderToolbarStates()
            }
        }

        val trackpadPath = InputDiagnostics.Path.TRACKPAD
        binding.trackpad.setOnTouchListener { _, ev ->
            InputDiagnostics.note(
                trackpadPath, "touch",
                "${MotionEvent.actionToString(ev.actionMasked)} (${ev.x.toInt()},${ev.y.toInt()}) ptrs=${ev.pointerCount}",
            )
            false
        }
        binding.trackpad.onMove = { dx, dy ->
            InputDiagnostics.note(trackpadPath, "move")
            WorkspaceController.moveCursor(dx, dy, trackpadPath)
        }
        binding.trackpad.onTap = {
            InputDiagnostics.note(trackpadPath, "tap", always = true)
            WorkspaceController.click(trackpadPath)
        }
        binding.trackpad.onTwoFingerDrag = { dx, dy ->
            InputDiagnostics.note(trackpadPath, "twoFingerDrag")
            WorkspaceController.twoFingerDrag(dx, dy)
        }
        binding.trackpad.onZoom = { scale ->
            InputDiagnostics.note(trackpadPath, "pinch")
            WorkspaceController.pinch(scale)
        }
        binding.trackpad.onDragStart = {
            InputDiagnostics.note(trackpadPath, "dragStart", always = true)
            WorkspaceController.beginDrag()
        }
        binding.trackpad.onDragEnd = {
            InputDiagnostics.note(trackpadPath, "dragEnd", always = true)
            WorkspaceController.endDrag()
        }
        binding.trackpad.onDragCancel = { WorkspaceController.cancelDrag() }
        binding.trackpad.onLongPress = {
            InputDiagnostics.note(trackpadPath, "longPress", always = true)
            WorkspaceController.longPress()
        }
        WorkspaceController.typingTargetChanged = { displayId, pkg ->
            InputDiagnostics.typingTarget(displayId, pkg)
        }
        // Cursor motion + clicks come from the privileged helper's raw-evdev path
        // (PrivilegedService.mouseDeltaHandler); only the wheel still routes through
        // dispatchGenericMotionEvent below. Pointer Capture would also give raw,
        // unclamped deltas — but it requires this window to HOLD input focus, which is
        // forbidden here (it pauses secondary-display apps via GameBooster, ANRs the
        // BT mouse, and starves the pseudo-root pairing field; see the onCreate note).
        // EVIOCGRAB in the helper achieves the same "system never sees the mouse"
        // without focus, so it is the path we use — Pointer Capture is NOT an option.

        WorkspaceController.pickWallpaperFromDevice = { desktopIdx ->
            pendingWallpaperDesktopIdx = desktopIdx
            // PickVisualMedia is the modern, permission-less path for picking images
            // (Android 13+; gracefully degrades to a system picker on older OS).
            pickWallpaperLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
        WorkspaceController.openSoundOutputPicker = {
            // Settings.Panel.ACTION_VOLUME is the documented "small panel" intent —
            // on Android 11+ it shows the per-stream volume sliders and the system's
            // output-device picker. Activity-context required; the panel can't host
            // it from inside a Presentation.
            runCatching {
                startActivity(
                    Intent(android.provider.Settings.Panel.ACTION_VOLUME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        // Forward IME keystrokes into the workspace drawer's search field while the drawer
        // is open, or into a focused text field on a launched app's virtual display
        // (detected by GlashutteAccessibilityService). The drawer / virtual displays can't
        // host an IME, so the phone-side keyboard field is the only practical input path.
        binding.keyboardField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString().orEmpty()
                when {
                    WorkspaceController.isDrawerOpen ->
                        WorkspaceController.setDrawerSearchQuery(current)
                    focusedAppDisplayId >= 0 ->
                        forwardKeyboardDelta(focusedAppDisplayId, lastKeyboardText, current)
                }
                lastKeyboardText = current
            }
        })

        // Accessibility service callbacks — opens / closes the phone keyboard when a
        // text field gains / loses focus on one of our virtual displays.
        WorkspaceController.onAppTextFieldFocused = { displayId ->
            mainHandler.post {
                focusedAppDisplayId = displayId
                InputDiagnostics.typingTarget(displayId, "text field focused (accessibility)")
                showKeyboard()
            }
        }
        WorkspaceController.onAppTextFieldUnfocused = { displayId ->
            mainHandler.post {
                if (focusedAppDisplayId == displayId) {
                    focusedAppDisplayId = -1
                    hideKeyboard()
                }
            }
        }

        // Keep the panel resumed during a session, so re-showing the workspace after a
        // glasses blip happens from a live window.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PrivilegedService.addListener(privilegeListener)
        // Watch for the glasses the whole time the panel exists — not just while resumed.
        displayManager().registerDisplayListener(displayListener, mainHandler)
        // Gate the shell-uid hotkey monitor on physical-keyboard presence — when no
        // keyboard is attached, the helper opens no /dev/input/event* nodes at all.
        // BT/USB keyboards arrive as InputDevice add events on this listener.
        inputManager().registerInputDeviceListener(keyboardPresenceListener, mainHandler)
        refreshKeyboardPresence()
        requestNotificationPermissionIfNeeded()
        requestMicrophonePermissionIfNeeded()
        renderStatus()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val perm = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermission.launch(perm)
        }
    }

    private fun askCameraPermissionOnce() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        if (cameraPermissionAsked) return
        cameraPermissionAsked = true
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun requestMicrophonePermissionIfNeeded() {
        val perm = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestMicrophonePermission.launch(perm)
        }
    }

    /**
     * Delivered when the glasses are connected while Glashutte is already running. Receiving the
     * attach intent also grants USB access; [syncGlasses] then shows the workspace if it is
     * not already up. It must NOT tear down a running workspace first — doing so spun up a
     * second [WorkspacePresentation], and its [HeadTracking][io.github.dmulder.glashutte.glasses.HeadTracking]
     * raced the first over the native SDK handle, crashing the process.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val dev = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.i(
                "Glashutte/Main",
                "USB_DEVICE_ATTACHED vid=0x${"%04x".format(dev?.vendorId ?: 0)} " +
                    "pid=0x${"%04x".format(dev?.productId ?: 0)} name=${dev?.productName}",
            )
            syncGlasses()
            // A VITURE attach that arrives while DOF is already live is a spurious
            // re-attach (e.g. a sibling USB interface enumerating) — force-restarting
            // then would needlessly tear down a healthy session and re-enter the SDK/USB
            // lifecycle, risking the very races the lifecycle lock now guards. Only act
            // when tracking is NOT currently streaming.
            if (!WorkspaceController.headTrackingActive) {
                // VITURE attach → force-restart: the post-rescan attach comes through here
                // with the prior SDK session bound to a USB device the kernel just unbound,
                // so a plain retry would early-out on started=true. Non-VITURE attaches just
                // need the cheap retry (covers the DisplayPort-before-IMU enumeration race).
                if (dev?.vendorId == VITURE_VENDOR_ID) presentation?.restartHeadTracking()
                else presentation?.retryHeadTracking()
            } else {
                Log.i("Glashutte/Main", "USB attach ignored — head tracking already live")
            }
        }
    }

    private fun toggleViewMode() {
        // DOF down → this button is the reconnect affordance (tap = retry DOF; long-press =
        // full glasses reconnect). Always reachable here on the phone control panel and
        // mirrored by the in-view toolbar's DOF-retry button.
        if (!WorkspaceController.headTrackingActive) {
            attemptReconnectDof()
            return
        }
        val next = when (WorkspaceController.currentViewMode) {
            WorkspaceRenderer.ViewMode.PINNED -> WorkspaceRenderer.ViewMode.FREE
            WorkspaceRenderer.ViewMode.FREE -> WorkspaceRenderer.ViewMode.PINNED
        }
        WorkspaceController.setViewMode(next)
        renderViewModeButton()
    }

    /** Pending DOF-reconnect outcome check; replaced on each new attempt. */
    private val dofReconnectOutcome = Runnable {
        if (WorkspaceController.headTrackingActive) {
            Toast.makeText(this, "Head tracking connected", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Head tracking reconnect failed — try unplugging and replugging the glasses",
                Toast.LENGTH_LONG,
            ).show()
            WorkspaceController.announceInView("Head tracking unavailable", 4_000L)
        }
    }

    /**
     * Retry head tracking (tap on the reconnect button). Full SDK + USB restart, then
     * report the outcome: if no pose stream has come up within [DOF_RECONNECT_TIMEOUT_MS]
     * we toast that the reconnect failed. Recovers a tracker that connected but never
     * produced a pose (LIBUSB_ERROR_NO_DEVICE, Carina VIO not converging, …).
     */
    private fun attemptReconnectDof() {
        if (WorkspaceController.headTrackingActive) return
        val pres = presentation
        if (pres == null) {
            Toast.makeText(this, "No glasses connected", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i("Glashutte/Main", "attemptReconnectDof: restarting head tracking")
        Toast.makeText(this, "Reconnecting head tracking…", Toast.LENGTH_SHORT).show()
        WorkspaceController.announceInView("Reconnecting head tracking…", DOF_RECONNECT_TIMEOUT_MS)
        pres.restartHeadTracking()
        mainHandler.removeCallbacks(dofReconnectOutcome)
        mainHandler.postDelayed(dofReconnectOutcome, DOF_RECONNECT_TIMEOUT_MS)
    }

    /**
     * Long-press on the reconnect button: a full glasses reconnect — re-bootstrap the
     * shell-uid helper (recovers a dead binder, which otherwise blocks the desktop's
     * trusted VirtualDisplay), re-claim the glasses display / rebuild the workspace
     * Presentation, then restart head tracking. The heavier hammer for when the desktop
     * itself didn't come up, not just DOF.
     */
    private fun attemptGlassesReconnect() {
        Log.i("Glashutte/Main", "attemptGlassesReconnect: helper + display + head tracking")
        Toast.makeText(this, "Reconnecting glasses…", Toast.LENGTH_SHORT).show()
        WorkspaceController.announceInView("Reconnecting glasses…", DOF_RECONNECT_TIMEOUT_MS)
        PrivilegedService.ensureRunning()
        syncGlasses()
        presentation?.restartHeadTracking()
        mainHandler.removeCallbacks(dofReconnectOutcome)
        mainHandler.postDelayed(dofReconnectOutcome, DOF_RECONNECT_TIMEOUT_MS)
    }

    /**
     * Refresh enabled/alpha of the buttons that depend on DOF availability and view
     * mode. Lock toggle requires DOF (FREE makes no sense without head tracking);
     * Layout button only does something in FREE (PINNED forces SINGLE). Called from
     * the WorkspaceController listeners and on resume.
     */
    private fun renderToolbarStates() {
        val free = WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.FREE
        // viewModeButton is always actionable: lock/unlock toggle when DOF is live, a
        // reconnect button when it is down — so it is never grayed out (the icon, set in
        // renderViewModeButton, signals which role it is in).
        binding.viewModeButton.isEnabled = true
        binding.viewModeButton.alpha = 1f
        renderViewModeButton()
        binding.layoutButton.isEnabled = free
        binding.layoutButton.alpha = if (free) 1f else DISABLED_ALPHA
    }

    private fun renderViewModeButton() {
        binding.viewModeButton.setImageResource(
            when {
                !WorkspaceController.headTrackingActive -> R.drawable.ic_dof_retry
                WorkspaceController.currentViewMode == WorkspaceRenderer.ViewMode.PINNED -> R.drawable.ic_pin
                else -> R.drawable.ic_pin_off
            },
        )
    }

    /** Tap on the capture button → save one screenshot of the current workspace frame. */
    private fun onSnapshot() {
        if (!WorkspaceController.isRunning) {
            Toast.makeText(this, R.string.waiting_for_glasses, Toast.LENGTH_SHORT).show()
            return
        }
        WorkspaceController.capture()
        Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show()
    }

    /** Recording-state listener — flips the capture button to a red dot while recording. */
    private val recordingListener: (Boolean) -> Unit = { rec ->
        mainHandler.post { renderCaptureButton(rec) }
    }

    private fun renderCaptureButton(recording: Boolean) {
        binding.captureButton.setImageResource(
            if (recording) R.drawable.ic_record_on else R.drawable.ic_capture,
        )
    }

    /** Show or hide the system keyboard. Keystroke routing into the focused app is M4. */
    private fun toggleKeyboard() {
        if (binding.keyboardField.visibility == View.VISIBLE) hideKeyboard()
        else showKeyboard()
    }

    /** Bring up the phone IME and focus the hidden keyboardField. Idempotent. */
    private fun showKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        if (field.visibility != View.VISIBLE) field.visibility = View.VISIBLE
        // Clear before showing so the delta tracking starts from empty; if the user
        // had been typing a draft we'd otherwise re-send the whole thing as new keys.
        if (field.text.isNotEmpty()) {
            field.text.clear()
            lastKeyboardText = ""
        }
        field.requestFocus()
        imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Hide the phone IME and clear keyboardField state. Idempotent. */
    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val field = binding.keyboardField
        imm.hideSoftInputFromWindow(field.windowToken, 0)
        field.visibility = View.GONE
        field.text.clear()
        lastKeyboardText = ""
    }

    /**
     * Forward the delta between [old] and [new] keyboardField text into the focused
     * app on [displayId] — backspaces for any characters removed, then the new tail
     * via `input text`. Lets a phone-side IME type into an out-of-process app on a
     * virtual display.
     */
    private fun forwardKeyboardDelta(displayId: Int, old: String, new: String) {
        val common = old.commonPrefixWith(new).length
        val toDelete = old.length - common
        val toAdd = new.substring(common)
        repeat(toDelete) {
            PrivilegedService.key(displayId, android.view.KeyEvent.KEYCODE_DEL)
        }
        if (toAdd.isNotEmpty()) {
            PrivilegedService.text(displayId, toAdd)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("Glashutte/Main", "onResume: privileged state=${PrivilegedService.state}")
        // Returning from Developer settings or the pairing dialog may have changed things —
        // re-attempt bring-up. Idempotent and no-op once READY.
        PrivilegedService.ensureRunning()
        syncGlasses()
    }

    override fun onDestroy() {
        displayManager().unregisterDisplayListener(displayListener)
        runCatching { inputManager().unregisterInputDeviceListener(keyboardPresenceListener) }
        presentation?.dismiss()
        presentation = null
        setWorkspaceServiceRunning(false)
        PrivilegedService.removeListener(privilegeListener)
        WorkspaceController.removeRecordingListener(recordingListener)
        WorkspaceController.removeZoomListener(zoomHudListener)
        if (WorkspaceController.pickWallpaperFromDevice != null) {
            WorkspaceController.pickWallpaperFromDevice = null
        }
        if (WorkspaceController.openSoundOutputPicker != null) {
            WorkspaceController.openSoundOutputPicker = null
        }
        super.onDestroy()
    }

    /** Hide the zoom HUD a short while after the last zoom change. */
    private val hudHideRunnable = Runnable { binding.zoomHud.visibility = View.GONE }

    /** Pops up a "Zoom NN%" HUD on the trackpad whenever the workspace zoom changes. */
    private val zoomHudListener: (Float) -> Unit = { zoom ->
        binding.zoomHud.text = "Zoom ${(zoom * 100).toInt()}%"
        binding.zoomHud.visibility = View.VISIBLE
        binding.zoomHud.removeCallbacks(hudHideRunnable)
        binding.zoomHud.postDelayed(hudHideRunnable, ZOOM_HUD_HIDE_MS)
    }

    private fun displayManager(): DisplayManager =
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Show the workspace on the glasses while they are connected; refresh the active scene. */
    private fun syncGlasses() {
        val display = GlassesDisplay.find(this)
        if (display != null) {
            val current = presentation
            if (current == null || !current.isShowing ||
                current.display.displayId != display.displayId
            ) {
                current?.dismiss()
                presentation = try {
                    WorkspacePresentation(this, display).also { it.show() }
                } catch (e: android.view.WindowManager.BadTokenException) {
                    // Common during a glasses connect/disconnect race — the display
                    // briefly exists but isn't trusted yet; the next syncGlasses retry
                    // succeeds. Don't log a full stack trace as if it were a crash.
                    Log.w("Glashutte/Main", "presentation rejected (display not trusted yet): ${e.message}")
                    null
                } catch (e: Exception) {
                    Log.e("Glashutte/Main", "could not show workspace on the glasses", e)
                    null
                }
            }
            // Foreground service brackets the glasses session — its onDestroy is the
            // safety net that force-stops launched apps if the activity is taken away
            // without the presentation's own dismiss cleanup running first.
            setWorkspaceServiceRunning(presentation != null)
        } else {
            presentation?.dismiss()
            presentation = null
            setWorkspaceServiceRunning(false)
        }
        renderStatus()
    }

    private var workspaceServiceRunning = false
    private fun setWorkspaceServiceRunning(running: Boolean) {
        if (running == workspaceServiceRunning) return
        if (running) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException if the
            // activity isn't in the foreground when the call fires — this happens
            // when the glasses are plugged in while the user is on another app.
            // Swallow it: onResume will re-run syncGlasses and try again from the
            // foreground state where it succeeds.
            try {
                WorkspaceService.start(this)
                workspaceServiceRunning = true
            } catch (e: android.app.ForegroundServiceStartNotAllowedException) {
                Log.w(
                    "Glashutte/Main",
                    "WorkspaceService start deferred — activity not in foreground (${e.message})",
                )
            } catch (e: Exception) {
                Log.e("Glashutte/Main", "WorkspaceService start failed", e)
            }
        } else {
            workspaceServiceRunning = false
            runCatching { WorkspaceService.stop(this) }
        }
    }

    /**
     * Pick which scene is visible from [PrivilegedService.state] and whether the
     * glasses are connected, and populate the wizard with its current step.
     */
    private fun renderStatus() {
        val glassesShowing = presentation?.isShowing == true
        val setupScene = PrivilegedService.state to glassesShowing
        if (setupScene != lastLoggedSetupScene) {
            Log.d(
                "Glashutte/Main",
                "renderStatus: privileged state=${setupScene.first}, glassesShowing=$glassesShowing",
            )
            lastLoggedSetupScene = setupScene
        }
        when {
            PrivilegedService.state != State.READY -> showWizard()
            !glassesShowing -> showScene(showWizard = false, showWaiting = true)
            else -> showScene(showWizard = false, showWaiting = false)
        }
        // The RemoteInput pairing notification is the primary path — post it when the user
        // is at the pair step, take it down otherwise.
        if (PrivilegedService.state == State.NEEDS_PAIRING) {
            PairingNotifier.showPairingPrompt(this)
        } else {
            PairingNotifier.cancel(this)
        }
    }

    private fun showWizard() {
        showScene(showWizard = true, showWaiting = false)
        when (PrivilegedService.state) {
            State.UNSUPPORTED -> populateWizard(
                R.string.privilege_title_unsupported,
                R.string.privilege_msg_unsupported,
                actionLabel = null,
            )
            State.NEEDS_WIRELESS_DEBUGGING -> populateWizard(
                R.string.privilege_title_wireless_debugging,
                R.string.privilege_msg_wireless_debugging,
                actionLabel = R.string.privilege_action_open_wireless_debugging,
            )
            State.NEEDS_PAIRING -> populateWizard(
                R.string.privilege_title_pair,
                R.string.privilege_msg_pair,
                actionLabel = R.string.privilege_action_open_wireless_debugging,
            )
            State.DISCOVERING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_discovering,
                actionLabel = null,
            )
            State.CONNECTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_connecting,
                actionLabel = null,
            )
            State.STARTING -> populateWizard(
                R.string.privilege_title_working,
                R.string.privilege_msg_starting,
                actionLabel = null,
            )
            State.READY -> Unit // showWizard would not have been called
        }
    }

    private fun showScene(showWizard: Boolean, showWaiting: Boolean) {
        InputDiagnostics.phoneScene(
            when {
                showWizard -> "setup wizard (helper state ${PrivilegedService.state}" +
                    "${if (PrivilegedService.lastFailure.isEmpty()) "" else ": " + PrivilegedService.lastFailure})"
                showWaiting -> "waiting for the glasses display (helper ready, no glasses workspace yet)"
                else -> "trackpad"
            },
            trackpadShown = !showWizard && !showWaiting,
        )
        binding.wizardScene.visibility = if (showWizard) View.VISIBLE else View.GONE
        binding.waitingScene.visibility = if (showWaiting) View.VISIBLE else View.GONE
        binding.mainScene.visibility =
            if (!showWizard && !showWaiting) View.VISIBLE else View.GONE
    }

    private fun submitManualPair() {
        val code = binding.wizardPairCode.text.toString().filter(Char::isDigit)
        val endpoint = AdbDiscovery.parseEndpoint(binding.wizardPairEndpoint.text.toString())
        if (code.length != 6) {
            Toast.makeText(this, "Enter the 6-digit pairing code", Toast.LENGTH_SHORT).show()
            return
        }
        if (endpoint == null) {
            Toast.makeText(this, "Enter the pairing IP address and port, like 192.168.1.23:37123", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Pairing…", Toast.LENGTH_SHORT).show()
        PrivilegedService.activate(code, endpoint) { ok ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Paired. Connecting…" else getString(R.string.privilege_pair_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun submitManualConnect() {
        val endpoint = AdbDiscovery.parseEndpoint(binding.wizardConnectEndpoint.text.toString())
        if (endpoint == null) {
            Toast.makeText(this, "Enter the connect IP address and port, like 192.168.1.23:41234", Toast.LENGTH_LONG).show()
            return
        }
        PrivilegedService.setManualConnectEndpoint(endpoint.host, endpoint.port)
        PrivilegedService.ensureRunning()
        Toast.makeText(this, "Connecting to ${endpoint.host}:${endpoint.port}…", Toast.LENGTH_SHORT).show()
    }

    private fun populateWizard(title: Int, message: Int, actionLabel: Int?) {
        binding.wizardTitle.setText(title)
        val state = PrivilegedService.state
        val actionable = state == State.NEEDS_PAIRING || state == State.NEEDS_WIRELESS_DEBUGGING
        val text = StringBuilder(getString(message))
        if (state != State.UNSUPPORTED) text.append("\n\n").append(getString(R.string.privilege_why_it_matters))
        if (state == State.NEEDS_PAIRING &&
            !androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            text.append("\n\n").append(getString(R.string.privilege_notifications_off))
        }
        binding.wizardMessage.text = text
        val failure = PrivilegedService.lastFailure
        binding.wizardFailure.visibility = if (failure.isEmpty() || state == State.UNSUPPORTED) View.GONE else View.VISIBLE
        binding.wizardFailure.text = getString(R.string.privilege_failure_prefix, failure)
        binding.wizardManualToggle.visibility = if (actionable) View.VISIBLE else View.GONE
        if (!actionable) binding.wizardManualBox.visibility = View.GONE
        if (actionLabel == null) {
            binding.setupButton.visibility = View.GONE
        } else {
            binding.setupButton.visibility = View.VISIBLE
            binding.setupButton.setText(actionLabel)
            binding.setupButton.isEnabled = true
        }
    }

    /**
     * The wizard button: each step sends the user to wherever the next action lives in
     * Android Settings. Actual pairing is done via the notification posted while the
     * NEEDS_PAIRING state is active — see [PairingNotifier].
     */
    private fun onSetupAction() {
        when (PrivilegedService.state) {
            State.NEEDS_WIRELESS_DEBUGGING, State.NEEDS_PAIRING -> openWirelessDebugging()
            else -> Unit
        }
    }

    /**
     * Open Developer Options and scroll to / highlight the Wireless Debugging row. The
     * `:settings:fragment_args_key` extra is the documented way to deep-link to a specific
     * preference inside a Settings page; on Samsung One UI it both scrolls there and
     * briefly highlights the row. Falls back to plain Developer Options, then to the top
     * of Settings.
     */
    private fun openWirelessDebugging() {
        val targeted = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY)
            putExtra(
                SETTINGS_SHOW_FRAGMENT_ARGS,
                Bundle().apply { putString(SETTINGS_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_PREF_KEY) },
            )
        }
        startFirstAvailable(
            targeted,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    /** Try each intent in order; stop at the first one that launches. */
    private fun startFirstAvailable(vararg intents: Intent) {
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * BT mouse → workspace. The cursor + clicks are driven via the privileged
     * helper's raw evdev path (no phone-edge clamping); only the wheel still
     * needs this `dispatchGenericMotionEvent` route — `ACTION_SCROLL` has no
     * clean evdev equivalent and the system already delivers it correctly,
     * including the modifier state used by Ctrl+Alt+Wheel zoom. Every other
     * mouse event is consumed (`return true`) so it doesn't double-fire on
     * the trackpad view or any phone-side UI.
     *
     * (Pointer Capture would have given us raw deltas directly, but it requires this
     * window to hold input focus — which is forbidden: a focused window on display 0
     * makes it top-focused, so GameBooster pauses secondary-display apps; it also ANRs
     * the BT mouse and starves the pseudo-root pairing field. See the onCreate note.
     * The privileged helper's EVIOCGRAB gets the raw deltas with no focus instead.)
     */
    /**
     * Physical (Bluetooth / USB) keyboard → the app window the cursor last clicked in.
     *
     * Android delivers hardware keys to the focused window, and the focused window is this
     * phone activity, never an app on the glasses' virtual display. Before this existed the
     * only route into an app was the on-screen-IME text mirror, which needs the accessibility
     * service to have seen a text field gain focus and cannot carry Enter, arrows, Tab, Esc or
     * Ctrl/Shift combinations. Here each key transition is injected into the target display
     * through the helper with its real meta state. Ctrl+Alt chords stay with the helper's
     * hotkey spy, system keys stay with the system, and the drawer's own search keeps using
     * the text field.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handlePhysicalKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handlePhysicalKey(event: KeyEvent): Boolean {
        val device = event.device
        val kbPath = InputDiagnostics.Path.KEYBOARD
        val physical = device != null && !device.isVirtual && event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        if (!physical) return false
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (isDown && event.repeatCount == 0) {
            InputDiagnostics.note(
                kbPath, "keyDown",
                "${KeyEvent.keyCodeToString(event.keyCode)} meta=0x${Integer.toHexString(event.metaState)} from '${device?.name}'",
            )
        }
        fun skip(reason: String): Boolean {
            if (isDown && event.repeatCount == 0) InputDiagnostics.note(kbPath, "skipped", reason, always = true)
            return false
        }
        if (!InputExperiments.forwardPhysicalKeys(this)) return skip("forwarding switched off in Diagnostics")
        val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
        if ((event.metaState and ctrlAlt) == ctrlAlt) return skip("Ctrl+Alt chord left to the hotkey monitor")
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_BRIGHTNESS_UP, KeyEvent.KEYCODE_BRIGHTNESS_DOWN,
            -> return skip("system key ${KeyEvent.keyCodeToString(event.keyCode)} left to Android")
        }
        if (WorkspaceController.isDrawerOpen) return skip("app drawer is open: typing goes to its search")
        val target = if (focusedAppDisplayId >= 0) focusedAppDisplayId else InputDiagnostics.typingTargetDisplay()
        if (target < 0) return skip("no app window selected: click inside an app first")
        if (!WorkspaceController.isGlashutteDisplay(target)) {
            InputDiagnostics.typingTarget(-1, "")
            return skip("the selected app window (display $target) no longer exists")
        }
        if (!InputDiagnostics.isHelperReady()) return skip("input helper not connected")
        PrivilegedService.injectKey(target, event.keyCode, event.action, event.metaState)
        if (isDown && event.repeatCount == 0) {
            InputDiagnostics.note(kbPath, "forwarded", "${KeyEvent.keyCodeToString(event.keyCode)} -> display $target")
        }
        return true
    }

    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN
    private var lastMouseButtons = 0

    /**
     * Fallback for a mouse the helper's raw `/dev/input` path is not delivering (helper
     * missing, nothing read, or the grab failed and the mouse is still reaching this window):
     * drive the workspace cursor from the pointer events this window receives. The system
     * pointer is clamped to the phone screen, so this is coarser than the raw path.
     */
    private fun mousePhoneWindowFallback(ev: MotionEvent) {
        val mousePath = InputDiagnostics.Path.MOUSE
        if (!InputExperiments.mousePhoneWindowFallback(this)) return
        val rawDelivering = InputDiagnostics.count(mousePath, "evdevDelta") +
            InputDiagnostics.count(mousePath, "evdevButton") > 0
        if (rawDelivering) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                if (!lastMouseX.isNaN()) {
                    val dx = (ev.x - lastMouseX).toInt()
                    val dy = (ev.y - lastMouseY).toInt()
                    if (dx != 0 || dy != 0) {
                        InputDiagnostics.note(mousePath, "activityDelta", "dx=$dx dy=$dy")
                        PrivilegedService.mouseDeltaHandler?.invoke(dx, dy, 0)
                    }
                }
                lastMouseX = ev.x
                lastMouseY = ev.y
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> {
                lastMouseX = Float.NaN
                lastMouseY = Float.NaN
            }
        }
        val buttons = ev.buttonState
        val changed = buttons xor lastMouseButtons
        if (changed != 0) {
            for ((flag, code) in listOf(MotionEvent.BUTTON_PRIMARY to 272, MotionEvent.BUTTON_SECONDARY to 273)) {
                if ((changed and flag) != 0) {
                    val pressed = (buttons and flag) != 0
                    InputDiagnostics.note(mousePath, "activityButton", "code=$code pressed=$pressed", always = true)
                    PrivilegedService.mouseButtonHandler?.invoke(code, pressed)
                }
            }
            lastMouseButtons = buttons
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) {
            @Suppress("ConstantConditionIf")
            if (MOUSE_VERBOSE_LOGS && mouseDiagFrameCount < 8) {
                Log.i(
                    "Glashutte/Mouse",
                    "uncaptured ev action=${ev.actionMasked} src=0x${Integer.toHexString(ev.source)} " +
                        "x=${ev.x} y=${ev.y}",
                )
                mouseDiagFrameCount++
            }
            mousePhoneWindowFallback(ev)
            handleMouseEvent(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    /**
     * Swallow mouse-as-touch events too — once the wheel works through
     * [dispatchGenericMotionEvent], we don't want the trackpad view to treat a
     * mouse click as a finger tap. Touchscreen events are passed through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun handleMouseEvent(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (v == 0f) return
            val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
            if ((ev.metaState and ctrlAlt) == ctrlAlt) {
                // Ctrl+Alt+Wheel → workspace zoom (matches the Windows companion's
                // combo). Was Win+Shift originally; switched because Samsung One UI
                // hard-binds Meta to the launcher.
                val factor = if (v > 0) 1f + v * MOUSE_ZOOM_GAIN
                             else        1f / (1f - v * MOUSE_ZOOM_GAIN)
                WorkspaceController.pinch(factor)
            } else {
                InputDiagnostics.note(InputDiagnostics.Path.MOUSE, "wheel", "v=$v")
                WorkspaceController.scroll(v * MOUSE_SCROLL_GAIN, InputDiagnostics.Path.MOUSE)
            }
        }
        // All other mouse events (HOVER_MOVE, MOVE, BUTTON_PRESS / RELEASE) fall
        // through to the dispatchGenericMotionEvent `return true` consumer — they
        // arrive twice for events the helper missed (no EVIOCGRAB), but we never
        // ACT on them here. Cursor motion + clicks happen via the raw-evdev path.
    }

    private companion object {
        /** Settings preference key for the Wireless Debugging row in Developer Options. */
        const val WIRELESS_DEBUGGING_PREF_KEY = "toggle_adb_wireless"

        /** Settings deep-link extras — preserved across most OEM Settings forks. */
        const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

        /** How long the trackpad's zoom HUD lingers after the last zoom event. */
        const val ZOOM_HUD_HIDE_MS = 1200L

        /** Alpha applied to phone-side toolbar buttons whose action isn't currently
         *  available (Lock toggle without DOF, Layout toggle in PINNED). */
        const val DISABLED_ALPHA = 0.35f

        /** USB Vendor ID assigned to VITURE Technology — used in [onNewIntent] to
         *  tell a glasses USB attach apart from any other device's attach. */
        const val VITURE_VENDOR_ID = 0x35ca

        /** How long after a reconnect attempt to check whether the pose stream came
         *  up; if [WorkspaceController.headTrackingActive] is still false we report the
         *  reconnect failed. Long enough to cover SDK re-init + Carina VIO warm-up. */
        const val DOF_RECONNECT_TIMEOUT_MS = 8_000L

        /** Delay between a real keyboard appearing and the proactive glasses USB
         *  rescan. ~1.5s lets the BT pairing's peak USB churn finish before we
         *  rebind, so the rebind doesn't race against the disruption itself. */
        const val KEYBOARD_CONNECT_RESCAN_MS = 1_500L

        /** Mouse-wheel notch → workspace-scroll fraction. */
        const val MOUSE_SCROLL_GAIN = 0.08f

        /** Ctrl+Alt+Wheel: zoom multiplier per notch (1 notch up = ×1.10, down = ÷1.10). */
        const val MOUSE_ZOOM_GAIN = 0.10f

        /** Flip to true while triaging mouse plumbing; otherwise spam-free. */
        const val MOUSE_VERBOSE_LOGS = false
    }
}
