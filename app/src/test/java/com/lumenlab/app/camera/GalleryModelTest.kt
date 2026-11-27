package com.lumenlab.app.camera

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryModelTest {
    private fun item(folder: String, name: String = "a.jpg", uri: String = "content://media/1") =
        GalleryItem(uri, name, folder, 0L, 0L)

    @Test
    fun categoryFromFolder() {
        assertEquals(GalleryCategory.Photos, galleryCategory("Lumenlab"))
        assertEquals(GalleryCategory.Stacked, galleryCategory("Lumenlab/STACKED"))
        assertEquals(GalleryCategory.Frames, galleryCategory("Lumenlab/STACKED/BATCHES"))
        assertEquals(GalleryCategory.Panorama, galleryCategory("Lumenlab/PANORAMA"))
        assertEquals(GalleryCategory.Blend, galleryCategory("Lumenlab/DOUBLE"))
        assertEquals(GalleryCategory.SuperRes, galleryCategory("Lumenlab/SUPERRES"))
        assertEquals(GalleryCategory.FocusStack, galleryCategory("Lumenlab/FOCUS"))
        assertEquals(GalleryCategory.Hdr, galleryCategory("Lumenlab/HDR"))
        assertEquals(GalleryCategory.Night, galleryCategory("Lumenlab/NIGHT"))
        assertEquals(GalleryCategory.Moon, galleryCategory("Lumenlab/MOON"))
        assertEquals(GalleryCategory.Paint, galleryCategory("Lumenlab/PAINT"))
        assertEquals(GalleryCategory.MilkyWay, galleryCategory("Lumenlab/MILKYWAY"))
        assertEquals(GalleryCategory.Meteor, galleryCategory("Lumenlab/METEOR"))
        assertEquals(GalleryCategory.Frames, galleryCategory("Lumenlab/SESSION_20261123_140000"))
        assertEquals(GalleryCategory.Other, galleryCategory("Lumenlab/RAW_TEST"))
        assertEquals(GalleryCategory.Other, galleryCategory("Camera"))
        assertEquals(GalleryCategory.Photos, galleryCategory("/Lumenlab/"))
    }

    @Test
    fun resultsFilterHidesFramesAndOther() {
        val list = listOf(
            item("Lumenlab"), item("Lumenlab/STACKED"), item("Lumenlab/PANORAMA"), item("Lumenlab/DOUBLE"),
            item("Lumenlab/SESSION_1"), item("Lumenlab/RAW_TEST"), item("Lumenlab/SUPERRES"), item("Lumenlab/FOCUS"),
        )
        assertEquals(6, list.filtered(GalleryFilter.Results).size)
        assertEquals(1, list.filtered(GalleryFilter.Frames).size)
        assertEquals(1, list.filtered(GalleryFilter.Other).size)
        assertEquals(1, list.filtered(GalleryFilter.Blend).size)
    }

    @Test
    fun legacyFilesAreRecognised() {
        assertTrue(item("Lumenlab", uri = "/storage/emulated/0/Android/data/x/files/Pictures/Lumenlab/a.jpg").isFile)
        assertFalse(item("Lumenlab").isFile)
    }

    @Test
    fun bytesAreFormatted() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(1_600))
        assertEquals("3.4 MB", formatBytes(3_400_000))
        assertEquals("1.2 GB", formatBytes(1_200_000_000))
    }

    @Test
    fun dayLabels() {
        val zone = TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val now = 20_000 * day + 12 * 3_600_000L
        assertEquals("Today", dayLabel(now - 3_600_000L, now, zone, Locale.US))
        assertEquals("Yesterday", dayLabel(now - day, now, zone, Locale.US))
        assertEquals("Yesterday", dayLabel(now - 13 * 3_600_000L, now, zone, Locale.US))
        assertEquals("26 Jun 2024", dayLabel(19_900L * day, now, zone, Locale.US))
        assertEquals("1 Jan 1970", dayLabel(0L, now, zone, Locale.US))
    }

    @Test
    fun dateTime() {
        assertEquals("1 Jan 1970, 00:00", dateTimeLabel(0L, TimeZone.getTimeZone("UTC"), Locale.US))
    }
}
