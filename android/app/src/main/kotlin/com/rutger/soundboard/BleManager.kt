package com.rutger.soundboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

private const val TAG = "BleManager"

val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")
val CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abc")
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

fun hasBlePermissions(context: Context): Boolean {
    val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val onButtonPressed: (Int) -> Unit,
    private val onConnectionStateChanged: (Boolean) -> Unit,
) {
    private companion object {
        const val FIRST_RECONNECT_DELAY_MS = 5_000L
        const val BACKOFF_RECONNECT_DELAY_MS = 10_000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // Reconnect backoff: the first scan after a drop is quick; if the ESP32
    // stays away we back off and keep scanning at a low-power cadence so we
    // don't drain the battery hunting for a device that's simply switched off.
    private var reconnectAttempts = 0

    // Turning Bluetooth off silently kills our scan and link, and turning it
    // back on tells us nothing, so without this we would wait forever.
    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onAdapterOff()
                BluetoothAdapter.STATE_ON -> {
                    reconnectAttempts = 0
                    startScan()
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found device: ${result.device.address}")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            scheduleReconnect()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server (status $status)")
                    gatt.close()
                    if (gatt == bluetoothGatt) bluetoothGatt = null
                    onConnectionStateChanged(false)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                gatt.disconnect()
                return
            }
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHAR_UUID)
            val descriptor = characteristic?.getDescriptor(CCCD_UUID)
            if (characteristic == null || descriptor == null ||
                !gatt.setCharacteristicNotification(characteristic, true) ||
                !enableNotifications(gatt, descriptor)
            ) {
                Log.e(TAG, "Could not subscribe to button notifications")
                gatt.disconnect()
            }
        }

        // Only now is the link useful, so only now do we report "connected".
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Subscribed to notifications")
                reconnectAttempts = 0
                onConnectionStateChanged(true)
            } else {
                Log.e(TAG, "Subscribing failed: $status")
                gatt.disconnect()
            }
        }

        // Android 12 and older only call this deprecated variant.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                val value = characteristic.value
                if (value != null && value.isNotEmpty()) {
                    val index = value[0].toInt() and 0xFF
                    Log.d(TAG, "BLE notification: button $index")
                    onButtonPressed(index)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == CHAR_UUID && value.isNotEmpty()) {
                val index = value[0].toInt() and 0xFF
                Log.d(TAG, "BLE notification: button $index")
                onButtonPressed(index)
            }
        }
    }

    fun startScan() {
        if (!hasBlePermissions(context)) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }
        if (scanning || bluetoothGatt != null) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        // First attempt scans aggressively for a fast reconnect; after that we
        // drop to LOW_POWER, which is far gentler on the battery while waiting.
        val scanMode = if (reconnectAttempts == 0) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()
        scanning = true
        scanner.startScan(listOf(filter), settings, scanCallback)
        Log.i(TAG, "BLE scan started")
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            // Thrown while the adapter is turning off, which stops the scan anyway.
        }
        Log.i(TAG, "BLE scan stopped")
    }

    private fun connect(device: BluetoothDevice) {
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.i(TAG, "Connecting to ${device.address}")
    }

    private fun enableNotifications(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }

    private fun onAdapterOff() {
        handler.removeCallbacksAndMessages(null)
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        onConnectionStateChanged(false)
    }

    private fun scheduleReconnect() {
        // 5s for the first retry, then back off to a steady 10s low-power cadence.
        val delayMs = if (reconnectAttempts == 0) FIRST_RECONNECT_DELAY_MS else BACKOFF_RECONNECT_DELAY_MS
        reconnectAttempts++
        handler.postDelayed({
            Log.i(TAG, "Attempting reconnect (attempt $reconnectAttempts)...")
            startScan()
        }, delayMs)
    }

    fun disconnect() {
        context.unregisterReceiver(adapterStateReceiver)
        handler.removeCallbacksAndMessages(null)
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
