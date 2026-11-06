// Interface to Glashutte's shell-uid privileged helper — the process that launches apps onto
// the workspace's virtual displays, injects input, and creates the displays themselves
// (trusted, so apps don't escape to the phone). See docs/PRIVILEGE.md.
package io.github.dmulder.glashutte.privileged;

import android.view.Surface;
import io.github.dmulder.glashutte.privileged.IPrivilegedHotkeyListener;

interface IPrivilegedService {

    // Transaction id used while the helper is still bound through Shizuku, which uses this
    // specific id to tear the service down. Kept after we move to our own ADB bootstrap so a
    // Shizuku-bound build remains interchangeable.
    void destroy() = 16777114;

    void exit() = 1;

    /** Launch packageName/activityName onto the given display. Returns true on success. */
    boolean launchOnDisplay(int displayId, String packageName, String activityName) = 2;

    /** Inject a tap at (x, y) on the given display. */
    void tap(int displayId, int x, int y) = 3;

    /** Inject a swipe on the given display, lasting durationMs. */
    void swipe(int displayId, int fromX, int fromY, int toX, int toY, int durationMs) = 4;

    /** Inject a key event (a KeyEvent key code) on the given display. */
    void key(int displayId, int keyCode) = 5;

    /** Type text into the focused field on the given display. */
    void text(int displayId, String value) = 6;

    /** Force-stop a package — closes its activities and kills its process. */
    void forceStop(String packageName) = 7;

    /** Whether the given display currently has an activity on it. */
    boolean displayHasActivity(int displayId) = 8;

    /**
     * Create a *trusted* virtual display rendering into surface. Created from this shell-uid
     * process so the TRUSTED flag is honoured — only a trusted display lets a launched app
     * follow its own splash-screen / new-task launches instead of escaping to the phone.
     * Returns the new display's id, or -1 on failure.
     */
    int createVirtualDisplay(String name, int width, int height, int densityDpi, in Surface surface) = 9;

    /** Release a virtual display previously created via createVirtualDisplay. */
    void releaseVirtualDisplay(int displayId) = 10;

    /**
     * Inject a two-finger pinch on the given display, centred at (centerX, centerY), with
     * the pointer spread going from fromSpan to toSpan over durationMs. The shell uid has
     * INJECT_EVENTS, so the helper can build a multi-pointer MotionEvent and submit it
     * through InputManager directly — `input` shell-outs only do one pointer at a time.
     */
    void pinchOnDisplay(int displayId, int centerX, int centerY, int fromSpan, int toSpan, int durationMs) = 11;

    /**
     * Inject an ACTION_SCROLL motion event at (x, y) on the given display, with the given
     * vertical wheel value (mouse-wheel convention: positive = scroll up). Mirrors what
     * a USB mouse wheel sends to a scrollable view — fast and identical to the drawer's
     * in-process AXIS_VSCROLL dispatch, no synthesized touch-swipe latency.
     */
    void injectScroll(int displayId, int x, int y, float vScroll) = 12;

    /**
     * Inject one frame of a touch stream on the given display. The action codes mirror
     * MotionEvent: 0 = ACTION_DOWN, 1 = ACTION_MOVE, 2 = ACTION_UP, 3 = ACTION_CANCEL.
     * The helper tracks the down timestamp per display so subsequent MOVE / UP frames
     * carry the right downAt and the app sees a consistent touch sequence.
     */
    void injectTouch(int displayId, int x, int y, int action) = 13;

    /**
     * Install a global Ctrl+Alt+X hotkey spy, mirroring the Windows app's
     * WH_KEYBOARD_LL hook. The helper reads /dev/input/event* directly from shell uid
     * and forwards matching combos as PrivilegedHotkeys.HK_* codes through
     * [listener] (oneway). Passing null tears the monitor down. Replaces any previously
     * registered listener. Events are observed passively — the focused app still sees
     * the original key presses.
     */
    void setHotkeyListener(IPrivilegedHotkeyListener listener) = 14;

    /**
     * Force-detach and re-attach the VITURE glasses' USB device. Locates the VID 0x35ca
     * device under /sys/bus/usb/devices, writes its bus-port id to the usb-driver's
     * unbind file, sleeps briefly, then writes the same id to bind. The kernel
     * re-enumerates the device, which surfaces back to the app as USB_DEVICE_ATTACHED.
     * Idempotent — silent no-op when no VITURE device is present.
     */
    void rescanGlassesUsb() = 15;

    // --- Diagnostics-friendly variants (ids 16+). These return an encoded InjectOutcome,
    // "<code>\t<strategy>\t<detail>" (see glasses InjectOutcome), instead of swallowing the
    // result, so the app can show exactly why an event did not reach an app.

    /** Like injectTouch, but reports whether the dispatcher accepted the event. */
    String injectTouchEx(int displayId, int x, int y, int action) = 16;

    /** Like injectScroll, but reports whether the dispatcher accepted the event. */
    String injectScrollEx(int displayId, int x, int y, float vScroll) = 17;

    /**
     * Inject one KeyEvent (action 0 = down, 1 = up; metaState = KeyEvent.META_*) on the
     * display, so shifted letters and modifier combinations from a physical keyboard arrive intact.
     */
    String injectKeyEx(int displayId, int keyCode, int action, int metaState) = 18;

    /** Like key, but reports the `input` command's result. */
    String keyEx(int displayId, int keyCode) = 19;

    /** Like text, but reports the `input` command's result. */
    String textEx(int displayId, String value) = 20;

    /**
     * Self-report of the helper: uid, Android build, which InputManager routes resolve, recent
     * injections and shell commands, the display list, and the raw-input monitor's counters
     * (STATS line). Safe to call at any time; never throws.
     */
    String probe() = 21;

    /** `dumpsys input` / `dumpsys window` excerpts for one display: windows, focus, touchable regions. */
    String describeDisplay(int displayId) = 22;

    /**
     * Input behaviour switches chosen on the diagnostics screen: strategy (0 auto, 1 service
     * context, 2 InputManagerGlobal, 3 legacy getInstance, 4 IInputManager binder, 5 shell
     * input), wait for the dispatcher's result, and whether new displays get OWN_FOCUS.
     */
    void setInputConfig(int strategy, boolean waitForResult, boolean ownFocus) = 23;

    // --- Native handoff and DRM / secure-display probe (ids 24+, additive). Each returns text
    // (or "<code>\t<strategy>\t<detail>" where noted) so the app can show exactly what the
    // platform said. See docs/DRM-NATIVE-HANDOFF.md.

    /**
     * What the shell uid can and cannot do about secure video: held permissions
     * (CAPTURE_SECURE_VIDEO_OUTPUT, ADD_TRUSTED_DISPLAY, ...), whether it can create secure and
     * trusted virtual displays, SurfaceFlinger / display HDCP and secure lines, and desktop-mode
     * and XR feature flags. Read-only apart from a short-lived test display. Never throws.
     */
    String probeSecureDisplay() = 24;

    /**
     * Prepare a physical display (the glasses) for a flat app: remember its current size and
     * density overrides, set the density to densityDpi and, when forcePanelSize is true and the
     * display does not report 16:10, set the panel's native 1920x1200. Returns what it saw and did.
     */
    String handoffPrepare(int displayId, int densityDpi, boolean forcePanelSize) = 25;

    /**
     * `am start` the app fullscreen on displayId (a real display, not a Glashutte virtual one).
     * Returns "<code>\t<strategy>\t<detail>" like the input calls.
     */
    String handoffLaunch(int displayId, String packageName, String activityName) = 26;

    /** Undo handoffPrepare's size and density changes for displayId. Idempotent. */
    String handoffRestore(int displayId) = 27;

    /**
     * Display facts used for the aspect-ratio and UI-size checks: `wm size/density -d`, the
     * display's own dump section and the activity bounds on it. Never throws.
     */
    String displayFacts(int displayId) = 28;
}
