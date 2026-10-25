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
val BATTERY_RAW_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789abd")
val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
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
    /** Battery Level, 0-100 %. Called on the main thread. */
    private val onBatteryLevel: (Int) -> Unit,
    /** Battery (raw): cell mV and charging state. Called on the main thread. */
    private val onBatteryRaw: (millivolts: Int, charging: Int) -> Unit,
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

    // Android runs one GATT operation at a time, so subscriptions and reads
    // are queued and each one starts from the previous one's callback. Only
    // touched on the main thread (callbacks are posted to [handler]).
    private sealed interface GattOp {
        val characteristic: BluetoothGattCharacteristic
        data class Subscribe(override val characteristic: BluetoothGattCharacteristic) : GattOp
        data class Read(override val characteristic: BluetoothGattCharacteristic) : GattOp
    }
    private val gattOps = ArrayDeque<GattOp>()
    private var gattOpInFlight: GattOp? = null

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

    // GATT callbacks arrive on a binder thread; everything is handed to the
    // main thread so the op queue and the callbacks above stay single-threaded.
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.i(TAG, "Connected to GATT server")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from GATT server (status $status)")
                        gatt.close()
                        if (gatt == bluetoothGatt) {
                            bluetoothGatt = null
                            clearGattOps()
                        }
                        onConnectionStateChanged(false)
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { onServicesReady(gatt, status) }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            handler.post { onSubscribeDone(gatt, descriptor.characteristic, status) }
        }

        // Android 12 and older only call the deprecated variants below.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val value = characteristic.value?.copyOf()
            handler.post { onReadDone(gatt, characteristic, value, status) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handler.post { onReadDone(gatt, characteristic, value, status) }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value?.copyOf() ?: return
            handler.post { onValue(characteristic.uuid, value) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post { onValue(characteristic.uuid, value) }
        }
    }

    private fun onServicesReady(gatt: BluetoothGatt, status: Int) {
        if (gatt != bluetoothGatt) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "Service discovery failed: $status")
            gatt.disconnect()
            return
        }
        val button = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
        if (button == null) {
            Log.e(TAG, "Button characteristic missing")
            gatt.disconnect()
            return
        }
        clearGattOps()
        // Button first, so presses work even if the battery part fails.
        gattOps += GattOp.Subscribe(button)
        // Firmware before 0.3.0 has no battery characteristics; they're optional.
        // The firmware only notifies on a change, so read each once as well.
        val level = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_UUID)
        val raw = gatt.getService(SERVICE_UUID)?.getCharacteristic(BATTERY_RAW_UUID)
        for (c in listOfNotNull(level, raw)) {
            gattOps += GattOp.Subscribe(c)
            gattOps += GattOp.Read(c)
        }
        if (level == null && raw == null) Log.i(TAG, "No battery characteristics (old firmware)")
        runNextGattOp(gatt)
    }

    private fun runNextGattOp(gatt: BluetoothGatt) {
        while (gattOpInFlight == null) {
            val op = gattOps.removeFirstOrNull() ?: return
            val started = when (op) {
                is GattOp.Subscribe -> {
                    val cccd = op.characteristic.getDescriptor(CCCD_UUID)
                    cccd != null &&
                        gatt.setCharacteristicNotification(op.characteristic, true) &&
                        enableNotifications(gatt, cccd)
                }
                is GattOp.Read -> gatt.readCharacteristic(op.characteristic)
            }
            if (started) {
                gattOpInFlight = op
            } else if (!onGattOpFailed(gatt, op, "could not start")) {
                return
            }
        }
    }

    /** Returns false when the link is being torn down and the queue must stop. */
    private fun onGattOpFailed(gatt: BluetoothGatt, op: GattOp, reason: String): Boolean {
        val uuid = op.characteristic.uuid
        Log.e(TAG, "${op::class.simpleName} $uuid failed: $reason")
        if (op is GattOp.Subscribe && uuid == CHAR_UUID) {
            clearGattOps()
            gatt.disconnect()
            return false
        }
        return true
    }

    private fun onSubscribeDone(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        if (gatt != bluetoothGatt) return
        val op = gattOpInFlight
        if (op !is GattOp.Subscribe || op.characteristic.uuid != characteristic.uuid) return
        gattOpInFlight = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (!onGattOpFailed(gatt, op, "status $status")) return
        } else if (characteristic.uuid == CHAR_UUID) {
            // Only now is the link useful, so only now do we report "connected".
            Log.i(TAG, "Subscribed to button notifications")
            reconnectAttempts = 0
            onConnectionStateChanged(true)
        }
        runNextGattOp(gatt)
    }

    private fun onReadDone(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        status: Int,
    ) {
        if (gatt != bluetoothGatt) return
        val op = gattOpInFlight
        if (op !is GattOp.Read || op.characteristic.uuid != characteristic.uuid) return
        gattOpInFlight = null
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            onValue(characteristic.uuid, value)
        } else {
            onGattOpFailed(gatt, op, "status $status")
        }
        runNextGattOp(gatt)
    }

    private fun onValue(uuid: UUID, value: ByteArray) {
        when (uuid) {
            CHAR_UUID -> if (value.isNotEmpty()) {
                val index = value[0].toInt() and 0xFF
                Log.d(TAG, "BLE notification: button $index")
                onButtonPressed(index)
            }
            BATTERY_LEVEL_UUID -> if (value.isNotEmpty()) {
                onBatteryLevel((value[0].toInt() and 0xFF).coerceIn(0, 100))
            }
            // Cell mV as a little-endian u16, then the charging state.
            BATTERY_RAW_UUID -> if (value.size >= 3) {
                val mv = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
                onBatteryRaw(mv, value[2].toInt() and 0xFF)
            }
        }
    }

    private fun clearGattOps() {
        gattOps.clear()
        gattOpInFlight = null
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
        clearGattOps()
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
        clearGattOps()
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
        clearGattOps()
    }
}
