package com.arslan.textgrab

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class ScreenTranslationRegressionTest {
    @Test fun recordingIsPlayableButDoesNotAutoplay() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "recording-test.wav")
        val recording = TranslationRecording(file)
        val player = MediaPlayer()
        try {
            recording.append(ByteArray(4800)) // 100 ms at 24 kHz, 16-bit mono.
            assertTrue(recording.finish())
            val data = file.readBytes()
            assertEquals("RIFF", String(data, 0, 4))
            assertEquals("WAVE", String(data, 8, 4))
            assertEquals(4800, ByteBuffer.wrap(data, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            player.setDataSource(file.absolutePath)
            player.prepare()
            assertFalse("Saving a translation must not start the speaker", player.isPlaying)
            assertTrue(player.duration in 90..110)
        } finally { player.release(); recording.discard() }
        assertFalse(file.exists())
    }

    @Test fun recordingIsBoundedAndCanBeDiscardedDuringRequest() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bounded-test.wav")
        val recording = TranslationRecording(file)
        try {
            repeat(TranslationRecording.MAX_BYTES / 4096 + 2) { recording.append(ByteArray(4096)) }
            assertTrue(recording.truncated)
            assertTrue(recording.finish())
            assertEquals(44L + TranslationRecording.MAX_BYTES, file.length())
        } finally { recording.discard() }
        assertFalse(file.exists())
        val canceled = TranslationRecording(file)
        canceled.append(ByteArray(100))
        canceled.discard()
        canceled.append(ByteArray(100))
        assertFalse(file.exists())
        assertFalse(canceled.finish())
    }
}
