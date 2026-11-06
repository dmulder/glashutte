package io.github.dmulder.glashutte.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDevice() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val connection = if (granted) usbManager.openDevice(device) else null
            if (connection != null) onOpened(device, connection) else onDenied()
        }
    }

    /** The connected VITURE glasses' USB device, or `null` if none is attached. */
    fun find(): UsbDevice? = usbManager.deviceList.values.firstOrNull(::isVitureDevice)

    /**
     * Open [device], requesting USB permission if needed. The result is delivered to
     * `onOpened` / `onDenied` — synchronously if permission is already held, otherwise after
     * the user answers the system dialog.
     */
    fun open(device: UsbDevice) {
        registerReceiver()
        if (usbManager.hasPermission(device)) {
            val connection = usbManager.openDevice(device)
            if (connection != null) onOpened(device, connection) else onDenied()
            return
        }
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
