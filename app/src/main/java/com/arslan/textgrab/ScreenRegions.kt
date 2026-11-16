package com.arslan.textgrab

import android.graphics.RectF
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** OCR text blocks paired with their normalized bounds in the captured screen. */
data class OcrRegionCandidate(val id: Int, val text: String, val bounds: RectF)

/** Converts on-device OCR boxes and AI-selected candidate IDs to screen regions. */
object ScreenRegions {
    fun fromPaddleOcr(
        lines: List<PaddleLiveOcr.Line>,
        imageWidth: Int,
        imageHeight: Int,
        maxRegions: Int = 48,
    ): List<OcrRegionCandidate> {
        if (imageWidth <= 0 || imageHeight <= 0 || lines.isEmpty()) return emptyList()

        val ordered = lines.asSequence()
            .filter { it.text.isNotBlank() && it.bounds.width() > 0f && it.bounds.height() > 0f }
            .filter { !it.confidence.isFinite() || it.confidence >= 0.15f }
            .sortedWith(compareBy<PaddleLiveOcr.Line> { it.bounds.centerY() }.thenBy { it.bounds.left })
            .toList()

        val groups = mutableListOf<MutableList<PaddleLiveOcr.Line>>()
        for (line in ordered) {
            val previousGroup = groups.lastOrNull()
            if (previousGroup != null && continuesBlock(previousGroup.last().bounds, line.bounds)) {
                previousGroup.add(line)
            } else {
                groups.add(mutableListOf(line))
            }
        }

        return groups.mapNotNull { group ->
            val bounds = RectF(group.first().bounds)
            group.drop(1).forEach { bounds.union(it.bounds) }
            val text = group.joinToString("\n") { it.text.trim() }.trim()
            if (text.isBlank()) return@mapNotNull null

            val normalized = RectF(
                (bounds.left / imageWidth).coerceIn(0f, 1f),
                (bounds.top / imageHeight).coerceIn(0f, 1f),
                (bounds.right / imageWidth).coerceIn(0f, 1f),
                (bounds.bottom / imageHeight).coerceIn(0f, 1f),
            )
            if (normalized.width() < .008f || normalized.height() < .004f) return@mapNotNull null
            text to normalized
        }.sortedBy { it.second.top }
            .take(maxRegions)
            .mapIndexed { index, (text, bounds) -> OcrRegionCandidate(index + 1, text, bounds) }
    }

    private fun continuesBlock(previous: RectF, current: RectF): Boolean {
        val lineHeight = max(previous.height(), current.height()).coerceAtLeast(1f)
        val verticalGap = current.top - previous.bottom
        if (verticalGap < -0.65f * lineHeight || verticalGap > 1.8f * lineHeight) return false

        val overlap = (min(previous.right, current.right) - max(previous.left, current.left)).coerceAtLeast(0f)
        val minWidth = min(previous.width(), current.width()).coerceAtLeast(1f)
        val leftAligned = kotlin.math.abs(previous.left - current.left) <= 1.25f * lineHeight
        return overlap >= 0.28f * minWidth || leftAligned
    }

    fun fromOcr(result: OcrEngine.Result, imageWidth: Int, imageHeight: Int, maxRegions: Int = 48): List<OcrRegionCandidate> {
        if (imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val lineGroups = result.lineBlocks.indices.groupBy { result.lineBlocks[it] }
        val blocks = lineGroups.values.mapNotNull { lineIds ->
            val first = lineIds.firstOrNull() ?: return@mapNotNull null
            val bounds = RectF(result.lineBoxes[first])
            lineIds.drop(1).forEach { bounds.union(result.lineBoxes[it]) }
            val text = lineIds.sorted().mapNotNull { result.lineTexts.getOrNull(it)?.takeIf { line -> line.isNotBlank() } }
                .joinToString("\n").trim()
            if (text.isBlank()) return@mapNotNull null
            val normalized = RectF(
                (bounds.left / imageWidth).coerceIn(0f, 1f),
                (bounds.top / imageHeight).coerceIn(0f, 1f),
                (bounds.right / imageWidth).coerceIn(0f, 1f),
                (bounds.bottom / imageHeight).coerceIn(0f, 1f),
            )
            if (normalized.width() < .008f || normalized.height() < .004f) return@mapNotNull null
            text to normalized
        }.sortedBy { it.second.top }.take(maxRegions)
        return blocks.mapIndexed { index, (text, bounds) -> OcrRegionCandidate(index + 1, text, bounds) }
    }

    /** Prefer exact OCR geometry when candidates were supplied; use model boxes only as fallback. */
    fun parse(args: JSONObject, candidates: List<OcrRegionCandidate> = emptyList()): List<RectF> {
        if (candidates.isNotEmpty()) {
            val ids = args.optJSONArray("region_ids") ?: return emptyList()
            return (0 until ids.length()).mapNotNull { index ->
                val id = ids.optInt(index, -1)
                candidates.getOrNull(id - 1)?.bounds?.let { RectF(it) }
            }.distinct().take(1)
        }
        val values = args.optJSONArray("regions") ?: return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val left = item.optDouble("left", Double.NaN).toFloat() / 1000f
            val top = item.optDouble("top", Double.NaN).toFloat() / 1000f
            val right = item.optDouble("right", Double.NaN).toFloat() / 1000f
            val bottom = item.optDouble("bottom", Double.NaN).toFloat() / 1000f
            if (listOf(left,top,right,bottom).any { !it.isFinite() || it !in 0f..1f } ||
                right - left < .008f || bottom - top < .004f) return@mapNotNull null
            RectF(left, top, right, bottom)
        }.take(8)
    }
}
