package io.github.dmulder.glashutte

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.dmulder.glashutte.spatial.WorkspaceController

/**
 * Listens for focus changes across all windows and, when a text-input field on one of
 * Glashutte's own virtual displays gains focus, opens the phone-side keyboard so the
 * user can type without an IME on the glasses.
 *
 * Requires a one-time enable in Settings > Accessibility — the MainActivity setup card
 * deep-links there.
 */
class GlashutteAccessibilityService : AccessibilityService() {

    private var lastFocusedDisplayId: Int = -1

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleFocusOrClick(event)
        }
    }

    private fun handleFocusOrClick(event: AccessibilityEvent) {
        val source: AccessibilityNodeInfo? = try { event.source } catch (_: Throwable) { null }
        if (source == null) {
            // Nothing focused on this window — drop any pending keyboard.
            maybeCloseKeyboard()
            return
        }
        try {
            val node = findEditableAncestor(source) ?: run {
                maybeCloseKeyboard()
                return
            }
            // Filter to Glashutte-owned virtual displays — typing into the phone's own
            // apps already gets the system IME on display 0, no override needed.
            val displayId = nodeDisplayId(node)
            if (displayId < 0 || !WorkspaceController.isGlashutteDisplay(displayId)) {
                maybeCloseKeyboard()
                return
            }
            Log.d(TAG, "text field focused on display=$displayId class=${node.className}")
            lastFocusedDisplayId = displayId
            WorkspaceController.onAppTextFieldFocused?.invoke(displayId)
        } finally {
            source.recycle()
        }
    }

    private fun maybeCloseKeyboard() {
        if (lastFocusedDisplayId < 0) return
        WorkspaceController.onAppTextFieldUnfocused?.invoke(lastFocusedDisplayId)
        lastFocusedDisplayId = -1
    }

    /** Walk up the node tree looking for the first editable text-input ancestor. */
    private fun findEditableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth < 8) {
            if (looksLikeTextInput(node)) return node
            val parent = try { node.parent } catch (_: Throwable) { null }
            if (node !== start) node.recycle()
            node = parent
            depth++
        }
        if (node !== start) node?.recycle()
        return null
    }

    /** Heuristic: editable flag OR class name says it's a text input. */
    private fun looksLikeTextInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls.contains("EditText") || cls.contains("Edit") && cls.contains("Text")
    }

    /** Walk up to the window node and read its display id; -1 if unavailable. */
    private fun nodeDisplayId(node: AccessibilityNodeInfo): Int {
        // AccessibilityWindowInfo has getDisplayId since API 30; reflect-fallback for
        // older platforms keeps the build clean even if the device returns the call.
        val window = try { node.window } catch (_: Throwable) { return -1 } ?: return -1
        return try { window.displayId } catch (_: NoSuchMethodError) { -1 }
    }

    override fun onInterrupt() {
        maybeCloseKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        maybeCloseKeyboard()
    }

    private companion object {
        const val TAG = "Glashutte/A11y"
    }
}
