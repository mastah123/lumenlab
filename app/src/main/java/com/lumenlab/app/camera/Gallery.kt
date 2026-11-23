package com.lumenlab.app.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

class DeleteResult(val deleted: Int, val failed: Int)

/** Lists, previews, shares and deletes the pictures Lumenlab saved under Pictures/Lumenlab. */
object Gallery {

    fun list(context: Context): List<GalleryItem> {
        val items = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listMediaStore(context) else listFiles(context)
        }.getOrDefault(emptyList())
        return items.sortedWith(compareByDescending<GalleryItem> { it.dateMs }.thenByDescending { it.name })
    }

    private fun listMediaStore(context: Context): List<GalleryItem> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
        )
        val found = mutableListOf<GalleryItem>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("Pictures/Lumenlab%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                val folder = (c.getString(2) ?: "").removePrefix("Pictures/").trim('/')
                found += GalleryItem(uri.toString(), c.getString(1) ?: "", folder, c.getLong(3) * 1000L, c.getLong(4))
            }
        }
        return found
    }

    /** Android 9 keeps photos in the app's own Pictures folder. */
    private fun listFiles(context: Context): List<GalleryItem> {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return emptyList()
        val base = File(root, "Lumenlab")
        if (!base.exists()) return emptyList()
        return base.walkTopDown().filter { it.isFile }.map { f ->
            val folder = f.parentFile?.relativeTo(root)?.path?.replace(File.separatorChar, '/') ?: "Lumenlab"
            GalleryItem(f.absolutePath, f.name, folder, f.lastModified(), f.length())
        }.toList()
    }

    private fun open(context: Context, item: GalleryItem): InputStream? =
        if (item.isFile) FileInputStream(item.uri) else context.contentResolver.openInputStream(Uri.parse(item.uri))

    /** A picture scaled down to at most [maxPx] on its long side and turned upright, or null if it cannot be read. */
    fun loadBitmap(context: Context, item: GalleryItem, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, item)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = open(context, item)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
        val orientation = runCatching {
            open(context, item)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }.getOrNull()

    fun thumbnail(context: Context, item: GalleryItem): Bitmap? {
        if (!item.isFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(Uri.parse(item.uri), Size(256, 256), null)
            }
        }
        return loadBitmap(context, item, 256)
    }

    private fun shareUri(context: Context, item: GalleryItem): Uri =
        if (item.isFile) FileProvider.getUriForFile(context, "${context.packageName}.files", File(item.uri))
        else Uri.parse(item.uri)

    fun share(context: Context, items: List<GalleryItem>) {
        if (items.isEmpty()) return
        runCatching {
            val uris = ArrayList(items.map { shareUri(context, it) })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            send.setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens the picture in another app, where it can be zoomed. */
    fun openExternally(context: Context, item: GalleryItem) {
        runCatching {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(shareUri(context, item), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(view)
        }
    }

    /** Deletes the pictures. Files made by an earlier install of the app may be refused by Android. */
    fun delete(context: Context, items: List<GalleryItem>): DeleteResult {
        val removed = mutableSetOf<String>()
        var deleted = 0
        var failed = 0
        for (item in items) {
            val ok = runCatching {
                if (item.isFile) File(item.uri).delete()
                else context.contentResolver.delete(Uri.parse(item.uri), null, null) > 0
            }.getOrDefault(false)
            if (ok) { removed += item.name; deleted++ } else failed++
        }
        runCatching { CaptureLog.remove(context, removed) }
        return DeleteResult(deleted, failed)
    }
}
