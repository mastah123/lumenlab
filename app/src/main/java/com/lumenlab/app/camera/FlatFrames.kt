package com.lumenlab.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** The saved flat frame. One is kept, for the camera and picture size it was made with. */
object FlatFrames {
    const val FRAMES = 16

    private val NAME = Regex("""flat_(\d+)x(\d+)_f(\d+)_(.*)\.png""")

    private fun files(context: Context): List<File> =
        context.filesDir.listFiles { f -> NAME.matches(f.name) }?.toList().orEmpty()

    fun exists(context: Context): Boolean = files(context).isNotEmpty()

    /** What is saved, for the screen: camera id and picture size. Null when there is none. */
    fun describe(context: Context): Triple<String, Int, Int>? =
        files(context).firstOrNull()?.let { f ->
            NAME.matchEntire(f.name)?.let { Triple(it.groupValues[4], it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        }

    fun delete(context: Context) {
        files(context).forEach { it.delete() }
    }

    /** Loads the flat. When [cameraId] is given, a flat made with another camera is not used. */
    fun load(context: Context, cameraId: String? = null): FlatField? {
        val f = files(context).firstOrNull() ?: return null
        val m = NAME.matchEntire(f.name) ?: return null
        if (cameraId != null && m.groupValues[4] != cameraId) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = runCatching { BitmapFactory.decodeFile(f.path, options) }.getOrNull() ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        return FlatField.fromFlat(px, w, h, m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    fun save(context: Context, low: IntArray, lowWidth: Int, lowHeight: Int, fullWidth: Int, fullHeight: Int, cameraId: String): Boolean {
        delete(context)
        val name = "flat_${fullWidth}x${fullHeight}_f${FlatField.FACTOR}_${cameraId.replace(Regex("[^A-Za-z0-9]"), "")}.png"
        val bmp = Bitmap.createBitmap(lowWidth, lowHeight, Bitmap.Config.ARGB_8888)
        bmp.setPixels(low, 0, lowWidth, 0, 0, lowWidth, lowHeight)
        val ok = runCatching {
            FileOutputStream(File(context.filesDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.getOrDefault(false)
        bmp.recycle()
        return ok
    }
}
