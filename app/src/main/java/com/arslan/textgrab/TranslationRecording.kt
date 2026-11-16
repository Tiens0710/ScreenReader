package com.arslan.textgrab

import java.io.File
import java.io.RandomAccessFile

/** Bounded disk recording of Gemini's 24 kHz mono PCM. Never starts playback. */
internal class TranslationRecording(val file: File) {
    private var output: RandomAccessFile? = RandomAccessFile(file, "rw").apply {
        setLength(0); write(ByteArray(44))
    }
    private var bytes = 0
    var truncated = false; private set

    @Synchronized fun append(pcm: ByteArray) {
        val count = minOf(pcm.size, MAX_BYTES - bytes).let { it - it % 2 }
        if (count < pcm.size) truncated = true
        if (count > 0 && output != null) { output!!.write(pcm, 0, count); bytes += count }
    }

    @Synchronized fun finish(): Boolean {
        val stream = output ?: return bytes > 0
        stream.seek(0)
        fun number(value: Int, width: Int) { repeat(width) { stream.write(value ushr (it * 8) and 255) } }
        stream.writeBytes("RIFF"); number(36 + bytes, 4); stream.writeBytes("WAVEfmt ")
        number(16, 4); number(1, 2); number(1, 2); number(24000, 4)
        number(48000, 4); number(2, 2); number(16, 2)
        stream.writeBytes("data"); number(bytes, 4)
        stream.close(); output = null
        return bytes > 0
    }

    @Synchronized fun discard() {
        output?.close(); output = null; bytes = 0; file.delete()
    }

    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
