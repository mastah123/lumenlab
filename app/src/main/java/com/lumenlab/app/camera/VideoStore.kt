package com.lumenlab.app.camera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Saves finished timelapse videos where the phone's gallery and video apps can find them. */
object VideoStore {
    /** Copies [source] into Movies/Lumenlab. Returns where it went (a content address or a file path), or null. */
    fun save(context: Context, source: File, name: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Lumenlab")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { o -> source.inputStream().use { it.copyTo(o) } } ?: return null
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Lumenlab").also { it.mkdirs() }
                val target = File(dir, name)
                source.copyTo(target, overwrite = true)
                target.path
            }
        } catch (e: Exception) {
            CrashLog.record(context, e)
            null
        }
    }

    fun open(context: Context, location: String) {
        try {
            val uri = if (location.startsWith("content:")) Uri.parse(location)
            else FileProvider.getUriForFile(context, "${context.packageName}.files", File(location))
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            CrashLog.record(context, e)
        }
    }
}
