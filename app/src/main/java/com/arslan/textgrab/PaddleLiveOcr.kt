package com.arslan.textgrab

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Local PP-OCRv5 Mobile Latin detection/recognition used to localize text during Gemini Live. */
object PaddleLiveOcr {
    data class Line(
        val text: String,
        val bounds: RectF,
        val confidence: Float,
    )

    private val initializationMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile
    private var engine: PaddleOCR? = null

    suspend fun warmUp(context: Context) = inferenceMutex.withLock {
        withContext(NonCancellable) { getEngine(context); Unit }
    }

    suspend fun release() = inferenceMutex.withLock {
        initializationMutex.withLock {
            engine?.release()
            engine = null
        }
    }

    suspend fun recognize(context: Context, bitmap: Bitmap, shouldCancel: () -> Boolean = { false }): List<Line> = inferenceMutex.withLock {
        withContext(NonCancellable) {
        val result = getEngine(context).recognize(bitmap, shouldCancel)
        Log.i(TAG, "OCR timing total=${result.totalTimeMs}ms det=${result.detectionTimeMs}ms rec=${result.recognitionTimeMs}ms " +
            "lines=${result.lineCount} recBatches=${result.recInputShapes.size} detShape=${result.detInputShape}")
        result.results.mapNotNull { recognized ->
            val text = recognized.text.trim()
            if (text.isBlank()) return@mapNotNull null

            val points = recognized.box.points
            if (points.size != 4) return@mapNotNull null
            val bounds = RectF(
                points.minOf { it.x },
                points.minOf { it.y },
                points.maxOf { it.x },
                points.maxOf { it.y },
            ).apply {
                intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            if (bounds.width() < 1f || bounds.height() < 1f) return@mapNotNull null
            Line(text, bounds, recognized.confidence)
        }
    }

    }

    private suspend fun getEngine(context: Context): PaddleOCR {
        engine?.let { return it }
        return initializationMutex.withLock {
            engine?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                check(OpenCVUtils.init(appContext)) { "Không khởi tạo được OpenCV cho PaddleOCR" }
                val lowRam = appContext.getSystemService(ActivityManager::class.java).isLowRamDevice
                val threadCount = min(if (lowRam) 1 else 2, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
                PaddleOCR.create(
                    context = appContext,
                    config = PaddleOCRConfig(
                        // Detect at a bounded size for full-screen captures. Recognition crops are
                        // still extracted from the original bitmap, so small text retains detail.
                        detLimitSideLen = if (lowRam) 960 else 1280,
                        detLimitType = "max",
                        recScoreThresh = 0f,
                        recBatchSize = if (lowRam) 1 else 2,
                    ),
                    engineConfig = EngineConfig(numThreads = threadCount),
                )
            }.also { engine = it }
        }
    }

    private const val TAG = "PaddleLiveOCR"
}
