package com.arslan.textgrab

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Google Live uses 16-kHz PCM input and 24-kHz PCM output, both mono 16-bit. */
class LiveAudio(
    private val onRegion: (List<android.graphics.RectF>) -> Unit = {},
    private val onLevel: (Float) -> Unit = {},
    private val onIdle: () -> Unit = {},
    private val onError: () -> Unit = {},
) {
    private data class Chunk(val generation: Int, val bytes: ByteArray, val regions: List<android.graphics.RectF>)
    private val output = LinkedBlockingQueue<Chunk>(128)
    private data class Cue(val start: Long, val end: Long, val regions: List<android.graphics.RectF>, val level: Float)
    private data class Position(val nanos: Long, val frames: Long)
    private val playbackLock = Any()
    private val cues = ArrayDeque<Cue>()
    private val headHistory = ArrayDeque<Position>()
    private val timestamp = AudioTimestamp()
    private var writtenFrames = 0L
    private var presentedFrames = 0L
    private var epochNanos = System.nanoTime()
    @Volatile var activeRegions: List<android.graphics.RectF> = emptyList(); private set
    @Volatile var hasPendingPlayback = false; private set
    private var idleReported = true
    @Volatile private var stopped = false
    @Volatile private var generation = 0
    @Volatile var voiceEnabled = true
        set(value) { field = value; if (!value) interrupt() }
    @Volatile private var microphoneEnabled = false
    private var recorder: AudioRecord? = null
    private var echo: AcousticEchoCanceler? = null
    private var microphoneThread: Thread? = null
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(9_600)).setTransferMode(AudioTrack.MODE_STREAM).build().also { output ->
            try {
                check(output.state == AudioTrack.STATE_INITIALIZED)
                output.play()
            } catch (error: Exception) {
                runCatching { output.release() }
                throw error
            }
        }
    private val playbackThread = thread(name = "GeminiLiveAudio") {
        try {
            var current: Chunk? = null
            var offset = 0
            while (!stopped) {
                synchronized(playbackLock) {
                    if (current?.generation != generation || !voiceEnabled) { current = null; offset = 0 }
                    if (current == null) { current = output.poll(); offset = 0 }
                    val chunk = current
                    if (chunk != null && voiceEnabled && chunk.generation == generation) {
                        // Nonblocking writes keep presentation tracking responsive even when the buffer is full.
                        val written = track.write(chunk.bytes, offset, (chunk.bytes.size - offset).coerceAtMost(960), AudioTrack.WRITE_NON_BLOCKING)
                        if (written > 0) {
                            var energy = 0.0
                            for (i in offset until offset + written - 1 step 2) {
                                val sample = ((chunk.bytes[i].toInt() and 255) or (chunk.bytes[i + 1].toInt() shl 8)).toShort().toDouble()
                                energy += sample * sample
                            }
                            val level = (kotlin.math.sqrt(energy / (written / 2).coerceAtLeast(1)) / 6000.0).toFloat().coerceIn(0f, 1f)
                            cues.addLast(Cue(writtenFrames, writtenFrames + written / 2, chunk.regions, level))
                            writtenFrames += written / 2
                            offset += written
                            if (offset >= chunk.bytes.size) current = null
                        } else if (written < 0) { error("AudioTrack write failed ($written)") }
                    }
                    val position = presentationPosition()
                    while (cues.firstOrNull()?.let { it.end <= position } == true) cues.removeFirst()
                    val cue = cues.firstOrNull()?.takeIf { position > it.start && position < it.end }
                    // Leading silence must not illuminate text before speech starts.
                    val audible = cue?.takeIf { it.level >= .012f && voiceEnabled }
                    activeRegions = audible?.regions.orEmpty()
                    onRegion(activeRegions)
                    onLevel(audible?.level ?: 0f)
                    hasPendingPlayback = current != null || output.isNotEmpty() || presentedFrames < writtenFrames
                    if (hasPendingPlayback) idleReported = false
                    else if (!idleReported) { idleReported = true; onIdle() }
                }
                Thread.sleep(if (hasPendingPlayback) 10 else 40)
            }
        } catch (error: Exception) {
            if (!stopped) {
                android.util.Log.w("ScreenTranslate", "Audio output stopped (${error.javaClass.simpleName})")
                onError()
            }
        } finally {
            hasPendingPlayback = false
            activeRegions = emptyList()
            onRegion(emptyList()); onLevel(0f)
            runCatching { track.release() }
        }
    }

    /** AudioTimestamp refers to presentation at the output, unlike a successful write to the buffer. */
    private fun presentationPosition(): Long {
        val now = System.nanoTime()
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        headHistory.addLast(Position(now, head))
        while (headHistory.size > 1 && headHistory.elementAt(1).nanos <= now - 60_000_000L) headHistory.removeFirst()
        val hasTimestamp = track.getTimestamp(timestamp) && timestamp.nanoTime >= epochNanos &&
            timestamp.nanoTime <= now && now - timestamp.nanoTime < 500_000_000L && timestamp.framePosition <= writtenFrames
        val position = if (hasTimestamp) timestamp.framePosition + (now - timestamp.nanoTime) * 24_000 / 1_000_000_000L
            else headHistory.firstOrNull()?.takeIf { it.nanos <= now - 60_000_000L }?.frames ?: 0L
        // An old timestamp after an underrun must never advance past samples consumed by the track.
        presentedFrames = maxOf(presentedFrames, position.coerceIn(0L, minOf(head, writtenFrames)))
        return presentedFrames
    }

    fun play(bytes: ByteArray, regions: List<android.graphics.RectF> = emptyList()) {
        if (stopped || !voiceEnabled || bytes.size < 2) return
        synchronized(playbackLock) {
        hasPendingPlayback = true
        idleReported = false
        if (!output.offer(Chunk(generation, bytes, regions.map { android.graphics.RectF(it) }))) {
            // Drop old speech instead of letting latency grow without bound.
            interrupt()
            output.offer(Chunk(generation, bytes, regions))
            hasPendingPlayback = true
            idleReported = false
        }
        }
    }
    fun interrupt() {
        synchronized(playbackLock) {
        activeRegions = emptyList()
        onRegion(emptyList())
        onLevel(0f)
        generation++
        output.clear()
        cues.clear(); headHistory.clear()
        writtenFrames = 0; presentedFrames = 0; epochNanos = System.nanoTime()
        hasPendingPlayback = false
        idleReported = true
        runCatching { track.pause(); track.flush(); if (!stopped) track.play() }
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicrophone(onChunk: (ByteArray) -> Unit, onError: () -> Unit) {
        if (microphoneEnabled || stopped) return
        try {
            val bufferSize = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(6_400)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) { audioRecord.release(); onError(); return }
            recorder = audioRecord
            if (AcousticEchoCanceler.isAvailable()) {
                echo = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            microphoneEnabled = true
            audioRecord.startRecording()
            microphoneThread = thread(name = "GeminiLiveMic") {
                val buffer = ByteArray(3_200)
                try {
                    while (!stopped && microphoneEnabled) {
                        val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count > 0) onChunk(buffer.copyOf(count))
                        else if (count < 0 && !stopped) { onError(); break }
                    }
                } catch (_: Exception) { if (!stopped) onError() }
            }
        } catch (_: Exception) { stopMicrophone(); onError() }
    }
    fun stopMicrophone() {
        microphoneEnabled = false
        runCatching { recorder?.stop() }
        runCatching { microphoneThread?.join(500) }
        runCatching { recorder?.release() }
        recorder = null
        echo?.release()
        echo = null
    }
    fun close() {
        stopped = true
        interrupt()
        stopMicrophone()
        output.clear()
        playbackThread.interrupt()
        runCatching { track.pause(); track.flush() }
    }
}
