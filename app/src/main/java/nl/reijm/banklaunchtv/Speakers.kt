package nl.reijm.banklaunchtv

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * The Bluetooth speaker (the Sonos) versus the TV's own speakers.
 *
 * While a Bluetooth speaker is connected, all sound goes there; disconnect it and sound goes
 * back to the TV. Android 12 lets an app with BLUETOOTH_CONNECT connect and disconnect it
 * (BluetoothA2dp.connect/disconnect are hidden but allowed for apps).
 */
@SuppressLint("MissingPermission")
class Speakers(private val ctx: Context, private val onChange: () -> Unit) {

    enum class State { SPEAKER, TV, SWITCHING }

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private var a2dp: BluetoothA2dp? = null
    private var switching = false
    private val prefs = ctx.getSharedPreferences("speakers", Context.MODE_PRIVATE)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    val permitted: Boolean
        get() = Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (s == BluetoothProfile.STATE_CONNECTED || s == BluetoothProfile.STATE_DISCONNECTED) switching = false
            if (s == BluetoothProfile.STATE_CONNECTED) {
                i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.let { remember(it) }
            }
            onChange()
        }
    }

    fun start() {
        val f = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        connectProxy()
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
    }

    /** Ask for the proxy again once the permission is granted. */
    fun connectProxy() {
        if (a2dp != null || adapter == null || !permitted) return
        adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as BluetoothA2dp
                connected()?.let { remember(it) }
                onChange()
            }
            override fun onServiceDisconnected(profile: Int) { a2dp = null; onChange() }
        }, BluetoothProfile.A2DP)
    }

    /** False when there's nothing to switch: no Bluetooth or no paired speaker. */
    val available: Boolean get() = adapter != null && (!permitted || speaker() != null)

    val state: State
        get() = when {
            switching -> State.SWITCHING
            connected() != null -> State.SPEAKER
            else -> State.TV
        }

    /** Short name for the speaker: "Sonos", or its own Bluetooth name. */
    val speakerName: String
        get() {
            val n = runCatching { speaker()?.name }.getOrNull() ?: return "Speaker"
            return if (n.contains("sonos", ignoreCase = true)) "Sonos" else n
        }

    /** Switch between the speaker and the TV. Returns a message when it can't. */
    fun toggle(): String? {
        val p = a2dp ?: return "Bluetooth isn't ready yet"
        if (adapter?.isEnabled != true) return "Bluetooth is off"
        val on = connected()
        val target = on ?: speaker() ?: return "No Bluetooth speaker paired"
        val ok = runCatching {
            BluetoothA2dp::class.java.getMethod(if (on != null) "disconnect" else "connect", BluetoothDevice::class.java)
                .invoke(p, target) as Boolean
        }.onFailure { Log.w(TAG, "speaker switch failed", it) }.getOrDefault(false)
        if (!ok) return "Couldn't switch the speaker"
        switching = true
        onChange()
        // If the speaker never answers (switched off, out of range), stop showing "Switching".
        handler.postDelayed({ if (switching) { switching = false; onChange() } }, 12_000)
        return null
    }

    private fun connected(): BluetoothDevice? =
        runCatching { a2dp?.connectedDevices?.firstOrNull() }.getOrNull()

    /** The speaker to switch to: the last one that was connected, else a paired audio device. */
    private fun speaker(): BluetoothDevice? {
        connected()?.let { return it }
        val bonded = runCatching { adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
        val last = prefs.getString("address", null)
        return bonded.firstOrNull { it.address == last }
            ?: bonded.firstOrNull { it.name?.contains("sonos", ignoreCase = true) == true }
            ?: bonded.firstOrNull { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
    }

    private fun remember(d: BluetoothDevice) = prefs.edit().putString("address", d.address).apply()

    companion object { private const val TAG = "BankLaunch" }
}
