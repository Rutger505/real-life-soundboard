package com.rutger.soundboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Found device: ${result.device.address}")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server")
                    // Reset backoff so the next drop retries quickly again.
                    reconnectAttempts = 0
                    gatt.discoverServices()
                    onConnectionStateChanged(true)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    gatt.close()
                    bluetoothGatt = null
                    onConnectionStateChanged(false)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHAR_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Characteristic not found")
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            Log.i(TAG, "Subscribed to notifications")
        }

        @Suppress("DEPRECATION")
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
        if (!hasPermissions()) {
            Log.e(TAG, "Missing BLE permissions")
            return
        }
        if (scanning) return
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
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        Log.i(TAG, "BLE scan stopped")
    }

    private fun connect(device: BluetoothDevice) {
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.i(TAG, "Connecting to ${device.address}")
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
        handler.removeCallbacksAndMessages(null)
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    private fun hasPermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
