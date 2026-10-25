package com.xander.files.data.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.xander.files.data.open.FileOpener
import com.xander.files.di.AppScope
import com.xander.files.domain.fs.FileEntry
import com.xander.files.domain.fs.FileSystemRegistry
import com.xander.files.domain.fs.FsPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BtDevice(val address: String, val name: String, val bonded: Boolean, val type: Int)

data class BtTransfer(val device: String, val file: String, val done: Long, val total: Long, val incoming: Boolean, val finished: Boolean = false, val error: String? = null)

@SuppressLint("MissingPermission")
@Singleton
class BluetoothRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: FileSystemRegistry,
    private val opener: FileOpener,
    @AppScope private val scope: CoroutineScope,
) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Files waiting for the user to pick a target device. */
    val pendingSend = MutableStateFlow<List<FileEntry>>(emptyList())
    private val _transfer = MutableStateFlow<BtTransfer?>(null)
    val transfer: StateFlow<BtTransfer?> = _transfer
    private val _receiving = MutableStateFlow(false)
    val receiving: StateFlow<Boolean> = _receiving
    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null

    val supported get() = adapter != null
    val enabled get() = adapter?.isEnabled == true

    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun enableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    fun discoverableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)

    fun bonded(): List<BtDevice> = runCatching { adapter?.bondedDevices.orEmpty().map { it.toBt() } }.getOrDefault(emptyList()).sortedBy { it.name.lowercase() }

    private fun BluetoothDevice.toBt() = BtDevice(address, runCatching { name }.getOrNull() ?: address, bondState == BluetoothDevice.BOND_BONDED, runCatching { bluetoothClass?.majorDeviceClass ?: 0 }.getOrDefault(0))

    /** Classic discovery; emits each device found until the flow is cancelled (~12 s per round). */
    fun scan(): Flow<BtDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> deviceExtra(i)?.let { trySend(it.toBt()) }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> close()
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        ContextCompat.registerReceiver(context, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        runCatching { adapter?.cancelDiscovery(); adapter?.startDiscovery() }.onFailure { close(it) }
        awaitClose { runCatching { adapter?.cancelDiscovery() }; context.unregisterReceiver(receiver) }
    }

    /** Bond state changes (pairing progress) as (address, state). */
    fun bondChanges(): Flow<Pair<String, Int>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = deviceExtra(i) ?: return
                trySend(d.address to i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    fun pair(address: String) = runCatching { adapter?.getRemoteDevice(address)?.createBond() == true }.getOrDefault(false)

    fun unpair(address: String) = runCatching {
        val d = adapter?.getRemoteDevice(address) ?: return@runCatching false
        d.javaClass.getMethod("removeBond").invoke(d) as Boolean
    }.getOrDefault(false)

    private fun deviceExtra(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** Hands files to the phone's built-in Bluetooth sharing (OBEX Object Push) – works with any phone or PC. */
    suspend fun systemShareIntent(entries: List<FileEntry>): Intent {
        val uris = ArrayList(entries.map { opener.shareableUri(it.path) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = entries.map { it.mimeType ?: "*/*" }.distinct().singleOrNull() ?: "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val bt = context.packageManager.queryIntentActivities(i, 0).firstOrNull { it.activityInfo.packageName.contains("bluetooth", true) }
        if (bt != null) i.setClassName(bt.activityInfo.packageName, bt.activityInfo.name) else return Intent.createChooser(i, null)
        return i
    }

    /** Folder where files received by Xander's direct transfer are stored. */
    fun receiveDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Xander Bluetooth").apply { mkdirs() }

    /** Folder the system Bluetooth service saves received files to. */
    fun systemReceiveDir(): FsPath = FsPath.local(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Bluetooth").let { if (it.exists()) it.path else File(Environment.getExternalStorageDirectory(), "bluetooth").path })

    /** Starts listening for direct transfers from another phone running Xander. */
    fun startReceiving() {
        if (serverJob?.isActive == true) return
        val a = adapter ?: return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = a.listenUsingRfcommWithServiceRecord("Xander Files", SERVICE_UUID)
                serverSocket = ss; _receiving.value = true
                while (true) {
                    val sock = ss.accept() ?: break
                    val from = runCatching { sock.remoteDevice.name }.getOrNull() ?: sock.remoteDevice.address
                    try {
                        BtProtocol.receive(sock.inputStream, sock.outputStream, receiveDir()) { name, done, total ->
                            _transfer.value = BtTransfer(from, name, done, total, incoming = true)
                        }
                        _transfer.value = _transfer.value?.copy(finished = true)
                    } catch (e: Exception) {
                        _transfer.value = BtTransfer(from, "", 0, 0, true, finished = true, error = e.message ?: "Transfer failed")
                    } finally { runCatching { sock.close() } }
                }
            } catch (_: Exception) {
            } finally { _receiving.value = false; serverSocket = null }
        }
    }

    fun stopReceiving() { runCatching { serverSocket?.close() }; serverJob?.cancel(); _receiving.value = false }

    /** Direct transfer to a phone that has Xander's receiver turned on. */
    suspend fun sendDirect(address: String, entries: List<FileEntry>): Result<Unit> = withContext(Dispatchers.IO) {
        val a = adapter ?: return@withContext Result.failure(IllegalStateException("Bluetooth is not available"))
        val device = a.getRemoteDevice(address)
        val name = runCatching { device.name }.getOrNull() ?: address
        runCatching {
            a.cancelDiscovery()
            device.createRfcommSocketToServiceRecord(SERVICE_UUID).use { sock ->
                try { sock.connect() } catch (e: Exception) { throw IllegalStateException("$name is not receiving. Open Xander → Bluetooth on that phone and turn on Receive.") }
                val files = entries.filter { !it.isDirectory }
                BtProtocol.send(sock.outputStream, sock.inputStream, files.map { e ->
                    BtProtocol.Outgoing(e.name, e.size) { registry.provider(e.path).openInput(e.path) }
                }) { file, done, total -> _transfer.value = BtTransfer(name, file, done, total, incoming = false) }
            }
            _transfer.value = _transfer.value?.copy(finished = true)
        }.onFailure { _transfer.value = BtTransfer(name, "", 0, 0, false, finished = true, error = it.message) }
    }

    fun clearTransfer() { _transfer.value = null }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f1c7b2e-4a8d-4f6e-9c1d-58a3e0b7d201")
    }
}
