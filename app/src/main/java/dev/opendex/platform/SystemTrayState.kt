package dev.opendex.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Window
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class NetworkKind { Wifi, Ethernet, Cellular, None }

data class SystemStatus(
    val network: NetworkKind = NetworkKind.None,
    val wifiEnabled: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val batteryLevel: Int = -1,
    val batteryCharging: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 15,
    val muted: Boolean = false,
    val brightness: Float = 0.5f,
    val canWriteBrightness: Boolean = false,
)

class SystemTrayController(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val state = MutableStateFlow(readSnapshot())
    val status: StateFlow<SystemStatus> = state.asStateFlow()

    private var registered = false
    private var lastNonZeroVolume = 5

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        refresh()
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        runCatching {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    fun refresh() {
        state.value = readSnapshot()
    }

    fun setMediaVolume(level: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clamped = level.coerceIn(0, max)
        if (clamped > 0) lastNonZeroVolume = clamped
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0) }
        refresh()
    }

    fun toggleMute() {
        val am = audioManager ?: return
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = if (current > 0) {
            lastNonZeroVolume = current
            0
        } else {
            lastNonZeroVolume.coerceIn(1, max)
        }
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        refresh()
    }

    fun setBrightness(fraction: Float, window: Window?) {
        val clamped = fraction.coerceIn(0.05f, 1f)
        if (window != null) {
            runCatching {
                val lp = window.attributes
                lp.screenBrightness = clamped
                window.attributes = lp
            }
        }
        if (Settings.System.canWrite(appContext)) {
            runCatching {
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                Settings.System.putInt(
                    appContext.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    (clamped * 255f).roundToInt().coerceIn(1, 255),
                )
            }
        }
        state.value = state.value.copy(brightness = clamped)
    }

    fun openInternetPanel(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) ||
            launchSettingsIntent(activityContext, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun openBluetoothSettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    fun openCastSettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.ACTION_CAST_SETTINGS)) ||
            launchSettingsIntent(activityContext, Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun openDisplaySettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.ACTION_DISPLAY_SETTINGS))

    fun openSoundSettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.Panel.ACTION_VOLUME)) ||
            launchSettingsIntent(activityContext, Intent(Settings.ACTION_SOUND_SETTINGS))

    fun openSystemSettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.ACTION_SETTINGS))

    fun openNotificationAccessSettings(activityContext: Context): Boolean =
        launchSettingsIntent(activityContext, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

    private fun launchSettingsIntent(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun readSnapshot(): SystemStatus {
        val network = runCatching {
            val active = connectivity?.activeNetwork
            val caps = active?.let { connectivity.getNetworkCapabilities(it) }
            when {
                caps == null -> NetworkKind.None
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
                else -> NetworkKind.None
            }
        }.getOrDefault(NetworkKind.None)

        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(network == NetworkKind.Wifi)
        val btEnabled = runCatching { bluetoothManager?.adapter?.isEnabled == true }.getOrDefault(false)

        val batteryIntent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val batteryPct = if (level >= 0) ((level * 100f) / scale).roundToInt().coerceIn(0, 100) else -1
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val maxVol = runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15 }.getOrDefault(15).coerceAtLeast(1)
        val curVol = runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0).coerceIn(0, maxVol)
        if (curVol > 0) lastNonZeroVolume = curVol
        val ringerMuted = runCatching {
            audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT
        }.getOrDefault(false)

        val rawBrightness = runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        val brightnessFraction = (rawBrightness / 255f).coerceIn(0.05f, 1f)
        val canWrite = runCatching { Settings.System.canWrite(appContext) }.getOrDefault(false)

        return SystemStatus(
            network = network,
            wifiEnabled = wifiEnabled,
            bluetoothEnabled = btEnabled,
            batteryLevel = batteryPct,
            batteryCharging = charging,
            volume = curVol,
            maxVolume = maxVol,
            muted = curVol == 0 || (ringerMuted && curVol == 0),
            brightness = brightnessFraction,
            canWriteBrightness = canWrite,
        )
    }
}
