package com.swift.download.player

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * v2.7 Stage2.3：TS → MP4 无重编码混流（Android 原生 MediaExtractor + MediaMuxer）。
 * 只做容器转换，不解码/不重编码，速度快且无损。
 *
 * 任何不兼容（特殊编码、MediaMuxer 拒绝某 track）都抛出异常，由调用方静默回退保留原 .ts，
 * 保证"零失败回退"——封装失败绝不影响已下载内容的可用性。
 */
object TsToMp4Muxer {

    /** 混流 [src] → [dst]；返回是否产出非空文件。失败时清理临时产物并抛异常。 */
    fun remux(src: File, dst: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)
            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackCount = extractor.trackCount
            if (trackCount <= 0) throw IllegalStateException("TS 中没有可封装的轨道")
            val trackIndexMap = HashMap<Int, Int>() // extractor track -> muxer track
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                // 跳过未知/不支持的轨道（如图文电视数据），只封装音视频
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                val outIndex = muxer.addTrack(format)
                trackIndexMap[i] = outIndex
            }
            if (trackIndexMap.isEmpty()) throw IllegalStateException("TS 中没有音视频轨道")
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((inTrack, outTrack) in trackIndexMap) {
                extractor.selectTrack(inTrack)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    // MediaExtractor 的 SAMPLE_FLAG_* 需转换成 MediaCodec.BUFFER_FLAG_*（lint WrongConstant）
                    val flags = extractor.sampleFlags.let { f ->
                        var out = 0
                        if (f and MediaExtractor.SAMPLE_FLAG_SYNC != 0) out = out or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        if (f and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) out = out or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        out
                    }
                    info.set(0, size, extractor.sampleTime, flags)
                    buffer.limit(size)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                extractor.unselectTrack(inTrack)
            }
            muxer.stop()
            return dst.isFile && dst.length() > 0L
        } finally {
            try { muxer?.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
            if (!(dst.isFile && dst.length() > 0L)) try { dst.delete() } catch (e: Exception) {}
        }
    }
}
