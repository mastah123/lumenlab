package com.lumenlab.app.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What kind of picture a saved file is, worked out from the folder it was saved in. */
enum class GalleryCategory { Photos, Stacked, Panorama, Blend, SuperRes, FocusStack, Frames, Other }

/** The filter chips on the Gallery screen. Results hides the many single frames of a burst. */
enum class GalleryFilter(val label: String, val categories: Set<GalleryCategory>) {
    Results(
        "Results",
        setOf(
            GalleryCategory.Photos, GalleryCategory.Stacked, GalleryCategory.Panorama, GalleryCategory.Blend,
            GalleryCategory.SuperRes, GalleryCategory.FocusStack,
        ),
    ),
    Photos("Photos", setOf(GalleryCategory.Photos)),
    Stacked("Stacked", setOf(GalleryCategory.Stacked)),
    Panorama("Panorama", setOf(GalleryCategory.Panorama)),
    Blend("Double", setOf(GalleryCategory.Blend)),
    SuperRes("Super res", setOf(GalleryCategory.SuperRes)),
    Focus("Focus", setOf(GalleryCategory.FocusStack)),
    Frames("Frames", setOf(GalleryCategory.Frames)),
    Other("Other", setOf(GalleryCategory.Other)),
}

/**
 * One saved picture. [folder] is the path under Pictures, for example "Lumenlab/STACKED".
 * [uri] is a content address on Android 10 and newer, or a file path on Android 9.
 */
data class GalleryItem(
    val uri: String,
    val name: String,
    val folder: String,
    val dateMs: Long,
    val sizeBytes: Long,
) {
    val category: GalleryCategory get() = galleryCategory(folder)
    val isFile: Boolean get() = !uri.startsWith("content:")
}

fun galleryCategory(folder: String): GalleryCategory {
    val parts = folder.split('/').filter { it.isNotEmpty() }
    if (parts.firstOrNull() != "Lumenlab") return GalleryCategory.Other
    val sub = parts.getOrNull(1) ?: return GalleryCategory.Photos
    return when {
        sub == "STACKED" -> if (parts.getOrNull(2) == "BATCHES") GalleryCategory.Frames else GalleryCategory.Stacked
        sub == "PANORAMA" -> GalleryCategory.Panorama
        sub == "DOUBLE" -> GalleryCategory.Blend
        sub == "SUPERRES" -> GalleryCategory.SuperRes
        sub == "FOCUS" -> GalleryCategory.FocusStack
        sub.startsWith("SESSION_") -> GalleryCategory.Frames
        else -> GalleryCategory.Other
    }
}

fun List<GalleryItem>.filtered(filter: GalleryFilter): List<GalleryItem> =
    filter { it.category in filter.categories }

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.US, bytes / 1e9)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.US, bytes / 1e6)
    bytes >= 1_000L -> "${(bytes + 500) / 1000} KB"
    else -> "$bytes B"
}

/** "Today", "Yesterday" or a date such as "23 Nov 2026". */
fun dayLabel(
    timeMs: Long,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    fun epochDay(ms: Long) = Math.floorDiv(ms + zone.getOffset(ms), 86_400_000L)
    return when (epochDay(nowMs) - epochDay(timeMs)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = zone }.format(Date(timeMs))
    }
}

fun dateTimeLabel(timeMs: Long, zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", locale).apply { timeZone = zone }.format(Date(timeMs))
