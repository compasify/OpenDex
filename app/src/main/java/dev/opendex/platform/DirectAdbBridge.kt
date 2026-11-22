package dev.opendex.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

internal object DirectAdbBridge {
    const val METHOD_REGISTER = "register_binder"
    const val EXTRA_BINDER = "binder"
    const val EXTRA_APP_TOKEN = "app_token"

    val appToken: IBinder = Binder()

    private val lock = Any()
    @Volatile private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val activeBinder: IBinder?
        get() = binder?.takeIf { it.isBinderAlive }

    fun setBinder(newBinder: IBinder) {
        val previous: IBinder?
        synchronized(lock) {
            previous = binder
            if (previous === newBinder) return
            val oldRecipient = deathRecipient
            if (previous != null && oldRecipient != null) {
                runCatching { previous.unlinkToDeath(oldRecipient, 0) }
            }
            val recipient = IBinder.DeathRecipient {
                synchronized(lock) {
                    if (binder === newBinder) {
                        binder = null
                        deathRecipient = null
                    }
                }
                notifyListeners()
            }
            val linked = runCatching { newBinder.linkToDeath(recipient, 0) }.isSuccess
            if (!linked || !newBinder.isBinderAlive) {
                binder = null
                deathRecipient = null
            } else {
                binder = newBinder
                deathRecipient = recipient
            }
        }
        if (previous != null && previous !== newBinder && previous.isBinderAlive) {
            // Gracefully shut down any superseded Direct ADB server instance.
            runCatching {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    previous.transact(DESTROY_USER_SERVICE, data, reply, IBinder.FLAG_ONEWAY)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }
}

class DirectAdbProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!isAuthorizedBackendUid(Binder.getCallingUid())) {
            Log.w("OpenDex", "Rejected DirectAdbProvider call from uid=${Binder.getCallingUid()}")
            throw SecurityException("Direct ADB/Root bridge rejected caller uid=${Binder.getCallingUid()}")
        }
        if (method != DirectAdbBridge.METHOD_REGISTER) return null
        val serverBinder = extras?.getBinder(DirectAdbBridge.EXTRA_BINDER) ?: return null
        DirectAdbBridge.setBinder(serverBinder)
        return Bundle().apply {
            putBinder(DirectAdbBridge.EXTRA_APP_TOKEN, DirectAdbBridge.appToken)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
