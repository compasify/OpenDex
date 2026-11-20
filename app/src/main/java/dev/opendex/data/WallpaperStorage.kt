package dev.opendex.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_WALLPAPER_EDGE = 2560

/**
 * Imports a user-selected image into app-private storage (auto-oriented and bounded in resolution)
 * so custom wallpapers persist reliably across reboots without temporary URI permission expiry.
 */
object WallpaperStorage {
    suspend fun importWallpaper(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                val maxDim = maxOf(w, h)
                if (maxDim > MAX_WALLPAPER_EDGE) {
                    val scale = MAX_WALLPAPER_EDGE.toFloat() / maxDim.toFloat()
                    decoder.setTargetSize(
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
            val dir = File(context.applicationContext.filesDir, "wallpapers").apply { mkdirs() }
            val target = File(dir, "custom_${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(target).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                        target.delete()
                        return@withContext null
                    }
                }
            } finally {
                bitmap.recycle()
            }
            // Remove older copies after the new file is safely written.
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("custom_") && file != target) file.delete()
            }
            target.absolutePath
        } catch (e: IOException) {
            Log.w("OpenDex", "Failed to import custom wallpaper", e)
            null
        } catch (e: RuntimeException) {
            Log.w("OpenDex", "Failed to decode custom wallpaper", e)
            null
        }
    }

    suspend fun loadBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        val file = File(path)
        if (!file.isFile) return@withContext null
        runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
}
