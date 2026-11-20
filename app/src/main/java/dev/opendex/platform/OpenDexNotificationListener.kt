package dev.opendex.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

data class NotificationBanner(
    val id: Long,
    val notification: TrayNotification,
)

object NotificationCenterStore {
    private val items = MutableStateFlow<List<TrayNotification>>(emptyList())
    val notifications: StateFlow<List<TrayNotification>> = items.asStateFlow()

    private val bannerItems = MutableStateFlow<List<NotificationBanner>>(emptyList())
    val banners: StateFlow<List<NotificationBanner>> = bannerItems.asStateFlow()

    private val listenerConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = listenerConnected.asStateFlow()

    private val bannerSeq = AtomicLong(0L)
    private val lastContentByKey = ConcurrentHashMap<String, Pair<String, String>>()

    @Volatile private var initialized = false
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
        val mapped = updateFromService(service)
        lastContentByKey.clear()
        mapped.forEach { lastContentByKey[it.key] = it.title to it.text }
        initialized = true
    }

    internal fun detach(service: OpenDexNotificationListener) {
        if (serviceRef?.get() === service) {
            serviceRef = null
            listenerConnected.value = false
            initialized = false
            items.value = emptyList()
            bannerItems.value = emptyList()
            lastContentByKey.clear()
        }
    }

    internal fun onPosted(service: OpenDexNotificationListener, sbn: StatusBarNotification?) {
        updateFromService(service)
        if (!initialized || sbn == null) return
        val flags = sbn.notification.flags
        if (sbn.packageName == service.packageName) return
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.isOngoing || (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return

        val mapped = mapNotification(sbn, service.packageManager) ?: return
        val content = mapped.title to mapped.text
        val previous = lastContentByKey.put(mapped.key, content)
        if (previous != null) {
            if (previous == content) return
            if ((flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0) return
        }
        val banner = NotificationBanner(id = bannerSeq.incrementAndGet(), notification = mapped)
        bannerItems.update { current ->
            val withoutSameKey = current.filterNot { it.notification.key == mapped.key }
            (listOf(banner) + withoutSameKey).take(3)
        }
    }

    internal fun onRemoved(service: OpenDexNotificationListener, sbn: StatusBarNotification?) {
        updateFromService(service)
        val key = sbn?.key ?: return
        lastContentByKey.remove(key)
        bannerItems.update { current -> current.filterNot { it.notification.key == key } }
    }

    internal fun updateFromService(service: OpenDexNotificationListener): List<TrayNotification> {
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        val pm = service.packageManager
        val selfPkg = service.packageName
        val mapped = active
            .asSequence()
            .filter { sbn ->
                sbn.packageName != selfPkg &&
                    (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .mapNotNull { sbn -> mapNotification(sbn, pm) }
            .sortedByDescending { it.postTime }
            .take(40)
            .toList()
        items.value = mapped
        return mapped
    }

    private fun mapNotification(sbn: StatusBarNotification, pm: PackageManager): TrayNotification? {
        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isEmpty() && text.isEmpty()) return null
        val appInfo = runCatching { pm.getApplicationInfo(sbn.packageName, 0) }.getOrNull()
        val appName = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            ?: sbn.packageName
        val appIcon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        return TrayNotification(
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

    fun dismissBanner(id: Long) {
        bannerItems.update { current -> current.filterNot { it.id == id } }
    }

    fun dismiss(key: String) {
        bannerItems.update { current -> current.filterNot { it.notification.key == key } }
        runCatching { serviceRef?.get()?.cancelNotification(key) }
    }

    fun clearAll() {
        val service = serviceRef?.get() ?: return
        bannerItems.value = emptyList()
        runCatching {
            items.value.filter { it.clearable }.forEach { service.cancelNotification(it.key) }
        }
    }

    fun open(item: TrayNotification): Boolean {
        bannerItems.update { current -> current.filterNot { it.notification.key == item.key } }
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
        NotificationCenterStore.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NotificationCenterStore.onRemoved(this, sbn)
    }
}
