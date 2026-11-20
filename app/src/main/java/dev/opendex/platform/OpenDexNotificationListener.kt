package dev.opendex.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

data class TrayNotification(
    val key: String,
    val packageName: String,
    val appName: String,
    val appIcon: Drawable?,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
)

object NotificationCenterStore {
    private val items = MutableStateFlow<List<TrayNotification>>(emptyList())
    val notifications: StateFlow<List<TrayNotification>> = items.asStateFlow()

    private val listenerConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = listenerConnected.asStateFlow()

    @Volatile private var serviceRef: WeakReference<OpenDexNotificationListener>? = null

    fun isAccessGranted(context: Context): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        }.getOrNull().orEmpty()
        val expected = ComponentName(context, OpenDexNotificationListener::class.java).flattenToString()
        val shortExpected = ComponentName(context, OpenDexNotificationListener::class.java).flattenToShortString()
        return enabled.split(':').any { it == expected || it == shortExpected || it.startsWith("${context.packageName}/") }
    }

    internal fun attach(service: OpenDexNotificationListener) {
        serviceRef = WeakReference(service)
        listenerConnected.value = true
        updateFromService(service)
    }

    internal fun detach(service: OpenDexNotificationListener) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            listenerConnected.value = false
            items.value = emptyList()
        }
    }

    internal fun updateFromService(service: OpenDexNotificationListener) {
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        val pm = service.packageManager
        val selfPkg = service.packageName
        val mapped = active
            .asSequence()
            .filter { sbn ->
                sbn.packageName != selfPkg &&
                    (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .mapNotNull { sbn ->
                val extras = sbn.notification.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
                val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
                if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
                val appInfo = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
                val appName = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                    ?: sbn.packageName
                val appIcon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
                TrayNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    appIcon = appIcon,
                    title = title.ifEmpty { appName },
                    text = text,
                    postTime = sbn.postTime,
                    clearable = sbn.isClearable,
                    autoCancel = (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0,
                    contentIntent = sbn.notification.contentIntent,
                )
            }
            .sortedByDescending { it.postTime }
            .take(40)
            .toList()
        items.value = mapped
    }

    fun dismiss(key: String) {
        runCatching { serviceRef?.get()?.cancelNotification(key) }
    }

    fun clearAll() {
        val service = serviceRef?.get() ?: return
        runCatching {
            items.value.filter { it.clearable }.forEach { service.cancelNotification(it.key) }
        }
    }

    fun open(item: TrayNotification): Boolean {
        val intent = item.contentIntent ?: return false
        val launched = runCatching { intent.send(); true }.getOrDefault(false)
        if (launched && item.autoCancel && item.clearable) dismiss(item.key)
        return launched
    }
}

class OpenDexNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationCenterStore.attach(this)
    }

    override fun onListenerDisconnected() {
        NotificationCenterStore.detach(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NotificationCenterStore.updateFromService(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationCenterStore.updateFromService(this)
    }
}
