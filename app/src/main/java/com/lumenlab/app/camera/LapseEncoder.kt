package com.lumenlab.app.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Turns a list of small JPEG frames into an H.264 MP4 file. */
object LapseEncoder {
    private const val TIMEOUT_US = 10_000L

    private fun load(file: File, width: Int, height: Int): IntArray? {
        val bmp = BitmapFactory.decodeFile(file.path) ?: return null
        val fitted = if (bmp.width == width && bmp.height == height) bmp else Bitmap.createScaledBitmap(bmp, width, height, true)
        val px = IntArray(width * height)
        fitted.getPixels(px, 0, width, 0, 0, width, height)
        if (fitted !== bmp) fitted.recycle()
        bmp.recycle()
        return px
    }

    /** Writes [frames] to [out]. Returns false if no frame could be read. [onProgress] gets 0 to 1. */
    fun encode(
        frames: List<File>,
        out: File,
        width: Int,
        height: Int,
        fps: Int,
        deflicker: Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        if (frames.isEmpty()) return false
        val gains: List<Double> = if (deflicker) {
            val means = frames.mapIndexed { i, f ->
                onProgress(0.2f * i / frames.size)
                load(f, width, height)?.let { Deflicker.meanLuma(it) } ?: 0.0
            }
            Deflicker.gains(means)
        } else List(frames.size) { 1.0 }
        val base = if (deflicker) 0.2f else 0f

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, LapsePlan.bitrate(width, height, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var track = -1
        var muxing = false
        var written = 0
        val info = MediaCodec.BufferInfo()

        fun drain(untilEnd: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer!!.addTrack(codec.outputFormat)
                        muxer!!.start()
                        muxing = true
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && muxing && buf != null) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }
        }

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for ((i, file) in frames.withIndex()) {
                val px = load(file, width, height) ?: continue
                if (deflicker) Deflicker.apply(px, gains[i])
                val planes = YuvConvert.convert(px, width, height)
                var inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                while (inIndex < 0) {
                    drain(false)
                    inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                }
                val image = codec.getInputImage(inIndex) ?: error("The encoder gave no input image")
                val p = image.planes
                YuvConvert.writePlane(planes.y, width, height, p[0].buffer, p[0].rowStride, p[0].pixelStride)
                YuvConvert.writePlane(planes.u, width / 2, height / 2, p[1].buffer, p[1].rowStride, p[1].pixelStride)
                YuvConvert.writePlane(planes.v, width / 2, height / 2, p[2].buffer, p[2].rowStride, p[2].pixelStride)
                codec.queueInputBuffer(inIndex, 0, width * height * 3 / 2, written * 1_000_000L / fps, 0)
                written++
                drain(false)
                onProgress(base + (1f - base) * (i + 1) / frames.size)
            }
            if (written == 0) return false
            var eos = codec.dequeueInputBuffer(TIMEOUT_US)
            while (eos < 0) {
                drain(false)
                eos = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            codec.queueInputBuffer(eos, 0, 0, written * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            return muxing
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            try { if (muxing) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }
}
