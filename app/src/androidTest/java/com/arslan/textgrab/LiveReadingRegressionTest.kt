package com.arslan.textgrab

import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LiveReadingRegressionTest {
    private fun pcm(ms: Int, frequency: Double = 440.0): ByteArray = ByteArray(ms * 24 * 2).also { bytes ->
        for (i in 0 until bytes.size / 2) {
            val sample = if (frequency == 0.0) 0 else (sin(2 * Math.PI * frequency * i / 24000) * 4000).toInt()
            bytes[i * 2] = sample.toByte(); bytes[i * 2 + 1] = (sample shr 8).toByte()
        }
    }
    private fun waitUntil(timeout: Long = 5000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(10)
        assertTrue("Timed out waiting for playback", predicate())
    }

    @Test fun highlightWaitsForPresentedSpeechAndChangesWithSecondSentence() {
        val first = RectF(.1f, .1f, .4f, .2f)
        val second = RectF(.1f, .3f, .4f, .4f)
        val events = CopyOnWriteArrayList<Pair<Long, RectF>>()
        val audio = LiveAudio(onRegion = { boxes -> boxes.firstOrNull()?.let { events.add(SystemClock.elapsedRealtime() to it) } })
        try {
            val start = SystemClock.elapsedRealtime()
            audio.play(pcm(450, 0.0) + pcm(350), listOf(first))
            audio.play(pcm(350, 660.0), listOf(second))
            waitUntil { events.any { it.second == second } }
            val firstAt = events.first { it.second == first }.first - start
            val secondAt = events.first { it.second == second }.first - start
            Log.i("ReadingRegression", "presentation first=${firstAt}ms second=${secondAt}ms")
            assertTrue("Highlight appeared in leading silence: $firstAt ms", firstAt >= 430)
            assertTrue("Second sentence highlighted while first was still queued: $secondAt ms", secondAt >= 780)
            assertTrue("Highlight lagged too far behind audio", secondAt < 1700)
            waitUntil { !audio.hasPendingPlayback }
            Thread.sleep(350) // Exercise timestamp recovery after a real underrun.
            val restart = SystemClock.elapsedRealtime()
            audio.play(pcm(250, 0.0) + pcm(200), listOf(first))
            waitUntil { events.any { it.first > restart + 200 && it.second == first } }
            assertTrue(events.none { it.first in (restart + 1)..(restart + 200) })
        } finally { audio.close() }
    }

    @Test fun interruptAndMuteDoNotLeakQueuedHighlights() {
        val old = RectF(.1f, .1f, .4f, .2f)
        val fresh = RectF(.1f, .5f, .4f, .6f)
        val events = CopyOnWriteArrayList<Pair<Long, RectF>>()
        val audio = LiveAudio(onRegion = { boxes -> boxes.firstOrNull()?.let { events.add(SystemClock.elapsedRealtime() to it) } })
        try {
            audio.play(pcm(1500), listOf(old))
            waitUntil { events.isNotEmpty() }
            audio.interrupt()
            val cutoff = SystemClock.elapsedRealtime()
            audio.play(pcm(300), listOf(fresh))
            waitUntil { events.any { it.second == fresh } }
            assertTrue(events.none { it.first > cutoff && it.second == old })
            audio.voiceEnabled = false
            val mutedAt = SystemClock.elapsedRealtime()
            audio.play(pcm(500), listOf(old))
            Thread.sleep(250)
            assertTrue(events.none { it.first > mutedAt })
        } finally { audio.close() }
    }

    @Test fun prefetchWaitsForQueuedRegionToReachSpeaker() {
        val first = listOf(RectF(.1f, .1f, .4f, .2f))
        val second = listOf(RectF(.1f, .3f, .4f, .4f))
        val audio = LiveAudio()
        try {
            audio.play(pcm(1200), first)
            waitUntil { audio.activeRegions == first }
            assertTrue(ReadingPrefetchPolicy.canPrepare(true, audio.activeRegions, first, true, true))
            audio.play(pcm(600), second)
            // A second turn is queued, but still speaking the first: do not prepare a third.
            assertFalse(ReadingPrefetchPolicy.canPrepare(true, audio.activeRegions, second, true, true))
            waitUntil { audio.activeRegions == second }
            assertTrue(ReadingPrefetchPolicy.canPrepare(true, audio.activeRegions, second, true, true))
            assertFalse(ReadingPrefetchPolicy.canPrepare(true, audio.activeRegions, second, false, true))
            assertFalse(ReadingPrefetchPolicy.canPrepare(true, audio.activeRegions, second, true, false))
            audio.interrupt()
            assertTrue(audio.activeRegions.isEmpty())
            assertFalse(audio.hasPendingPlayback)
        } finally { audio.close() }
    }

    @Test fun screenAnimationRestartsQuietWindowBeforeOcr() {
        val gate = ScreenSettling()
        assertFalse(gate.isReady(1000))
        gate.changed(1000)
        assertFalse(gate.isReady(1000)) // Startup frames must settle too.
        gate.changed(1300)
        gate.changed(1650)
        assertFalse(gate.isReady(1700)) // First frame's window expired, latest did not.
        assertFalse(gate.isReady(2349))
        assertTrue(gate.isReady(2350))
        gate.changed(2400)
        assertFalse(gate.isReady(2500))
        assertTrue(gate.isReady(3100))
    }

    @Test fun stalePaddleFrameCancelsSafelyAndNextFrameStillWorks() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = android.graphics.Bitmap.createBitmap(800, 320, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).apply {
            drawColor(android.graphics.Color.WHITE)
            drawText("Hello 123", 40f, 180f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK; textSize = 96f
            })
        }
        try {
            var checks = 0
            try {
                PaddleLiveOcr.recognize(context, bitmap) { ++checks >= 2 }
                fail("Superseded OCR must stop after detection")
            } catch (_: java.util.concurrent.CancellationException) {
                assertTrue(checks >= 2)
            }
            assertTrue("Next frame should still recognize text", PaddleLiveOcr.recognize(context, bitmap).isNotEmpty())
        } finally {
            bitmap.recycle()
            PaddleLiveOcr.release()
        }
    }

    @Test fun geminiVisionBoundsUseFullScreenshotAndRejectInvalidBoxes() {
        val regions = ScreenRegions.parse(JSONObject("""
            {"regions":[
              {"left":100,"top":200,"right":700,"bottom":280},
              {"left":900,"top":100,"right":1200,"bottom":220},
              {"left":400,"top":400,"right":400,"bottom":500},
              {"left":0,"top":0,"right":100,"bottom":1000},
              {"left":20,"top":30,"right":80,"bottom":180}
            ]}
        """))
        assertEquals(2, regions.size)
        assertEquals(RectF(.1f, .2f, .7f, .28f), regions[0])
        assertEquals(RectF(.02f, .03f, .08f, .18f), regions[1])
    }
}
