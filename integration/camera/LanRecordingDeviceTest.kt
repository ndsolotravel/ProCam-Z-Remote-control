package com.rearviewpro.camera

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies an explicitly named take created through the companion, rather than an older clip. */
@RunWith(AndroidJUnit4::class)
class LanRecordingDeviceTest {
    @Test fun companionTakeIsPlayable() {
        val name = InstrumentationRegistry.getArguments().getString("clipName")
        assumeNotNull(name)
        require(name!!.matches(Regex("[A-Za-z0-9_.-]+\\.mp4")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clip = File(context.filesDir, "clips/$name")
        assertTrue("Remote take exists", clip.isFile)
        assertTrue("Remote take has video data", clip.length() > 10000)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(clip.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            assertTrue("Nonempty duration", duration > 1000)
            assertEquals(1920, width); assertEquals(1080, height)
            val frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            assertNotNull("A real encoded video frame decodes", frame)
            File(context.cacheDir, "lan-recording-frame.jpg").outputStream().use { frame!!.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            frame!!.recycle()
            File(context.cacheDir, "lan-recording-result.json").writeText(JSONObject()
                .put("clip",name).put("bytes",clip.length()).put("durationMs",duration)
                .put("width",width).put("height",height).put("decodedFrame",true).toString())
        } finally { retriever.release() }
    }
}
