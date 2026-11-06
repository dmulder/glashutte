package io.github.dmulder.glashutte.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide read-only view of the phone's status — what the workspace's status tray
 * (and, later, the quick-settings panel) renders.
 *
 * For now: battery level + charging, and music-stream volume. Wi-Fi, signal, brightness,
 * and notifications come as the panel grows; see docs/TASKBAR.md.
 *
 * Receivers and observers are registered once in [init] and are tied to the app process —
 * the desktop UI just adds listeners that fire on the main thread when something changes.
 */
object SystemStatus {

    /** Notified on the main thread when any tracked status changes. */
    interface Listener {
        fun onSystemStatusChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    @Volatile
    var batteryPercent: Int = 100
        private set

    @Volatile
    var batteryCharging: Boolean = false
        private set

    /** Music volume as a 0..1 fraction. */
    @Volatile
    var volumeFraction: Float = 1f
        private set

    /**
     * The currently-routing audio output as we estimate it. Android doesn't expose
     * "which device am I really using" for general media, so we apply a priority
     * heuristic over the set of connected output devices: BT > USB > wired > builtin.
     * Matches what the system itself does in practice.
     */
    enum class AudioOutput(val displayName: String) {
        SPEAKER("Speaker"),
        WIRED("Headphones"),
        BLUETOOTH("Bluetooth"),
        USB("USB / Glasses"),
    }

    @Volatile
    var activeOutput: AudioOutput = AudioOutput.SPEAKER
        private set

    /**
     * Human-readable name for the active output device (e.g. "Pixel Buds Pro" for BT,
     * or "VITURE One USB Audio" for the glasses). Falls back to the [activeOutput]
     * display name when the device doesn't report a product name.
     */
    @Volatile
    var activeOutputName: String = AudioOutput.SPEAKER.displayName
        private set

    /** Wire up the system listeners. Call once from the Application. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        registerBatteryReceiver()
        registerVolumeObserver()
        registerAudioDeviceCallback()
        readVolume()
        readActiveOutput()
    }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    private fun notifyChanged() {
        listeners.forEach { runCatching { it.onSystemStatusChanged() } }
    }

    /**
     * Set the music-stream volume to [fraction] (0..1). Returns the actual new fraction the
     * `AudioManager` accepted (stream volumes are discrete steps).
     */
    fun setVolume(fraction: Float): Float {
        val ctx = appContext ?: return volumeFraction
        val am = ctx.getSystemService(AudioManager::class.java) ?: return volumeFraction
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return volumeFraction
        val target = (fraction.coerceIn(0f, 1f) * max).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        // The observer will fire and update volumeFraction — but update eagerly too.
        volumeFraction = target.toFloat() / max
        return volumeFraction
    }

    private fun registerBatteryReceiver() {
        val ctx = appContext ?: return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ctx.registerReceiver(batteryReceiver, filter)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) {
                batteryPercent = level * 100 / scale
            }
            val status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN,
            )
            batteryCharging =
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            notifyChanged()
        }
    }

    private fun registerVolumeObserver() {
        val ctx = appContext ?: return
        ctx.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    readVolume()
                    notifyChanged()
                }
            },
        )
    }

    private fun readVolume() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) volumeFraction = cur.toFloat() / max
    }

    private fun registerAudioDeviceCallback() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    readActiveOutput()
                    notifyChanged()
                }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    readActiveOutput()
                    notifyChanged()
                }
            },
            mainHandler,
        )
    }

    /**
     * Pick the most-likely active media output from `getDevices(GET_DEVICES_OUTPUTS)`
     * using Android's own priority order: BT > USB > wired > builtin. There is no
     * public API for "the one Android is actually using" outside of voice calls, so
     * this mirrors the routing the system itself does.
     */
    private fun readActiveOutput() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var best: AudioDeviceInfo? = null
        var bestRank = -1
        for (d in devices) {
            val rank = rankFor(d.type)
            if (rank > bestRank) {
                bestRank = rank
                best = d
            }
        }
        val (kind, name) = if (best != null) {
            classify(best)
        } else AudioOutput.SPEAKER to AudioOutput.SPEAKER.displayName
        activeOutput = kind
        activeOutputName = name
    }

    private fun rankFor(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> 4
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 1
        else -> 0
    }

    private fun classify(d: AudioDeviceInfo): Pair<AudioOutput, String> {
        val productName = runCatching { d.productName?.toString() }.getOrNull().orEmpty()
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioOutput.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioOutput.USB
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutput.WIRED
            else -> AudioOutput.SPEAKER
        }
        val name = productName.ifBlank { kind.displayName }
        return kind to name
    }
}
