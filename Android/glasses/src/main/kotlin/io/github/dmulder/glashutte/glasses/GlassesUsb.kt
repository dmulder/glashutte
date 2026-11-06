package io.github.dmulder.glashutte.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Finds the VITURE glasses on USB and opens a connection to them.
 *
 * The glasses expose two things over USB-C: a DisplayPort video stream (handled elsewhere)
 * and a USB device for control + IMU. The native SDK needs a file descriptor for that USB
 * device — obtained here from [UsbDeviceConnection.getFileDescriptor] — plus the product id.
 */
class GlassesUsb(
    private val context: Context,
    private val onOpened: (device: UsbDevice, connection: UsbDeviceConnection) -> Unit,
    private val onDenied: () -> Unit,
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var registered = false

    /**
     * Fired (once per [open]) when the glasses expose a USB video-class interface and the app
     * does not hold the CAMERA permission. Android withholds USB access to such devices until
     * CAMERA is granted, so the owner of the activity should ask for it and then restart.
     */
    var onCameraPermissionNeeded: (() -> Unit)? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            GlassesDiagnostics.i(TAG, "USB permission dialog result for ${describe(device)}: granted=$granted")
            val connection = if (granted) openSafely(device) else null
            if (connection != null) {
                reportOpened(device, connection)
                onOpened(device, connection)
            } else {
                reportDenied(device, granted)
                onDenied()
            }
        }
    }

    /**
     * The connected VITURE glasses' USB device, or `null` if none is attached. Also refreshes
     * [GlassesDiagnostics.usbInventory] with every USB device Android can see, so a wrong PID
     * or a missing device is visible without a debugger.
     */
    fun find(): UsbDevice? {
        val inventory = inventory(context)
        GlassesDiagnostics.usbInventory = inventory
        inventory.forEach { GlassesDiagnostics.d(TAG, "usb: $it") }
        val vitureOnBus = usbManager.deviceList.values.filter { it.vendorId == VITURE_VID }
        val found = usbManager.deviceList.values.firstOrNull(::isVitureDevice)
        if (found == null && vitureOnBus.isNotEmpty()) {
            GlassesDiagnostics.w(
                TAG,
                "VITURE vendor id 0x35ca is on the bus but no product id is in the allow-list: " +
                    vitureOnBus.joinToString { "pid=0x${it.productId.toString(16)}" } +
                    " — a firmware with a new PID? Add it to VITURE_PIDS.",
            )
        }
        return found
    }

    /**
     * Open [device], requesting USB permission if needed. The result is delivered to
     * `onOpened` / `onDenied` — synchronously if permission is already held, otherwise after
     * the user answers the system dialog.
     */
    fun open(device: UsbDevice) {
        registerReceiver()
        val hasVideo = hasVideoInterface(device)
        val cameraGranted = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val permitted = usbManager.hasPermission(device)
        GlassesDiagnostics.cameraPermission = if (cameraGranted) "granted" else "NOT granted"
        GlassesDiagnostics.usbPermission =
            "usbManager.hasPermission=$permitted, device has USB video interface=$hasVideo"
        GlassesDiagnostics.i(
            TAG,
            "open ${describe(device)}: hasPermission=$permitted videoInterface=$hasVideo cameraPermission=$cameraGranted",
        )
        if (hasVideo && !cameraGranted) {
            GlassesDiagnostics.w(
                TAG,
                "the glasses expose a USB video interface and CAMERA is not granted: Android will not " +
                    "grant USB access until it is. Requesting CAMERA.",
            )
            GlassesDiagnostics.stage(
                GlassesDiagnostics.Stage.USB_PERMISSION, GlassesDiagnostics.Status.WARN,
                "device has a USB video interface but the CAMERA permission is not granted; asking for it",
            )
            runCatching { onCameraPermissionNeeded?.invoke() }
        }
        if (permitted) {
            val connection = openSafely(device)
            if (connection != null) {
                reportOpened(device, connection)
                onOpened(device, connection)
            } else {
                reportDenied(device, true)
                onDenied()
            }
            return
        }
        GlassesDiagnostics.i(TAG, "requesting USB permission (system dialog on the phone screen)")
        GlassesDiagnostics.stage(
            GlassesDiagnostics.Stage.USB_PERMISSION, GlassesDiagnostics.Status.PENDING,
            "waiting for the USB permission dialog on the phone (tap OK)",
        )
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        usbManager.requestPermission(device, pending)
    }

    private fun openSafely(device: UsbDevice): UsbDeviceConnection? = try {
        usbManager.openDevice(device)
    } catch (e: SecurityException) {
        GlassesDiagnostics.e(TAG, "UsbManager.openDevice threw SecurityException (no USB/camera permission)", e)
        null
    } catch (e: Exception) {
        GlassesDiagnostics.e(TAG, "UsbManager.openDevice failed", e)
        null
    }

    private fun reportOpened(device: UsbDevice, connection: UsbDeviceConnection) {
        GlassesDiagnostics.put("usb.fd", connection.fileDescriptor)
        GlassesDiagnostics.stage(
            GlassesDiagnostics.Stage.USB_PERMISSION, GlassesDiagnostics.Status.OK,
            "opened ${describe(device)}, fd=${connection.fileDescriptor}",
        )
    }

    private fun reportDenied(device: UsbDevice, grantedByUser: Boolean) {
        val cameraGranted = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val why = when {
            !grantedByUser && hasVideoInterface(device) && !cameraGranted ->
                "USB access refused and CAMERA is not granted (Android blocks USB video devices without it)"
            !grantedByUser -> "USB permission dialog was dismissed or denied"
            else -> "openDevice returned null / threw (permission revoked or device vanished)"
        }
        GlassesDiagnostics.stage(
            GlassesDiagnostics.Stage.USB_PERMISSION, GlassesDiagnostics.Status.FAIL,
            "$why for ${describe(device)}",
        )
    }

    fun release() {
        if (registered) {
            runCatching { context.unregisterReceiver(permissionReceiver) }
            registered = false
        }
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
        registered = true
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "Glashutte/Usb"
        private const val ACTION_USB_PERMISSION = "io.github.dmulder.glashutte.USB_PERMISSION"

        /** VITURE's USB vendor id. */
        private const val VITURE_VID = 0x35CA

        /** Product ids across the One/Lite, Pro, Luma, Luma Ultra, and Beast lines. */
        private val VITURE_PIDS = setOf(
            0x1011, 0x1013, 0x1015, 0x1017, 0x101B, // Viture One / Lite
            0x1019, 0x101D,                         // Viture Pro
            0x1121, 0x1131, 0x1141, 0x1151,         // Luma / Luma Pro
            0x1101, 0x1104,                         // Luma Ultra
            0x1201, 0x1211,                         // Beast
        )

        /** True if any interface is USB video class (a UVC camera). */
        fun hasVideoInterface(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

        fun describe(device: UsbDevice): String =
            "vid=0x%04x pid=0x%04x".format(device.vendorId, device.productId)

        /** One line per USB device Android currently exposes, including interface classes. */
        fun inventory(context: Context): List<String> {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return listOf("(no UsbManager)")
            if (usb.deviceList.isEmpty()) return listOf("(no USB devices attached)")
            return usb.deviceList.values.sortedBy { it.deviceName }.map { d ->
                val ifaces = (0 until d.interfaceCount).joinToString(",") {
                    val i = d.getInterface(it)
                    "#${i.id}:class${i.interfaceClass}/${i.interfaceSubclass}"
                }
                val product = runCatching { d.productName }.getOrNull()
                val permitted = runCatching { usb.hasPermission(d) }.getOrDefault(false)
                "${d.deviceName} ${describe(d)} class=${d.deviceClass} product='${product ?: "?"}' " +
                    "ifaces=[$ifaces] hasPermission=$permitted" +
                    (if (d.vendorId == VITURE_VID) "  <-- VITURE" else "") +
                    (if (isVitureDevice(d)) " (pid in allow-list)" else "")
            }
        }

        fun isVitureDevice(device: UsbDevice): Boolean =
            device.vendorId == VITURE_VID && device.productId in VITURE_PIDS

        /** Whether VITURE glasses are currently attached over USB. */
        fun isConnected(context: Context): Boolean {
            val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                ?: return false
            return usb.deviceList.values.any(::isVitureDevice)
        }
    }
}
