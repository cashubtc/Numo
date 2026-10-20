package com.electricdreams.numo.ui.animation

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs

/**
 * Native, place-aligned number motion. Static digits never move; changing digits roll through a
 * clipped baseline with a soft opacity falloff. Layout is interpolated too, including separators.
 * The caller owns the clock, so multiple amounts can share one lifecycle-bound animator.
 */
internal class RollingAmountPainter {
    internal data class Glyph(val character: Char, val key: String)

    private var previous = ""
    private var next = ""
    private var oldGlyphs = emptyList<Glyph>()
    private var newGlyphs = emptyList<Glyph>()
    private var oldIndices = emptyMap<String, Int>()
    private var newIndices = emptyMap<String, Int>()
    private var oldPositions = FloatArray(0)
    private var newPositions = FloatArray(0)
    private var rollDelays = emptyMap<String, Float>()
    private var rollSpan = 1f

    fun draw(
        canvas: Canvas,
        from: String,
        to: String,
        fraction: Float,
        x: Float,
        baseline: Float,
        paint: Paint,
        decimalSeparator: Char = '.',
    ) {
        val progress = fraction.coerceIn(0f, 1f)
        if (from == to || progress >= 1f) {
            canvas.drawText(to, x, baseline, paint)
            return
        }
        if (from != previous || to != next) {
            previous = from
            next = to
            oldGlyphs = glyphs(from, decimalSeparator)
            newGlyphs = glyphs(to, decimalSeparator)
            oldIndices = oldGlyphs.mapIndexed { i, glyph -> glyph.key to i }.toMap()
            newIndices = newGlyphs.mapIndexed { i, glyph -> glyph.key to i }.toMap()
            oldPositions = FloatArray(from.length + 1)
            newPositions = FloatArray(to.length + 1)
            // Odometer cascade: rolling columns start rightmost-first, like a counter
            // carrying upward, instead of every changed digit flipping in lockstep.
            val changed = mutableMapOf<String, Int>()
            newGlyphs.forEachIndexed { i, glyph ->
                val origin = oldIndices[glyph.key]
                if (origin == null || oldGlyphs[origin].character != glyph.character) {
                    changed[glyph.key] = i
                }
            }
            oldGlyphs.forEachIndexed { i, glyph ->
                if (glyph.key !in newIndices) changed[glyph.key] = i
            }
            val ordered = changed.entries.sortedByDescending { it.value }
            val step = if (ordered.size <= 1) 0f else minOf(.09f, .32f / (ordered.size - 1))
            rollSpan = 1f - step * (ordered.size - 1)
            rollDelays = ordered.mapIndexed { order, entry -> entry.key to step * order }.toMap()
        }
        // Reuse buffers: the same painter may be rendered at a different size after rotation.
        for (i in from.indices) oldPositions[i + 1] =
            oldPositions[i] + paint.measureText(from, i, i + 1)
        for (i in to.indices) newPositions[i + 1] =
            newPositions[i] + paint.measureText(to, i, i + 1)

        val originalAlpha = paint.alpha
        val travel = paint.textSize * 0.9f
        val save = canvas.save()
        canvas.clipRect(
            x - 2f, baseline + paint.ascent() - 2f,
            x + maxOf(oldPositions.last(), newPositions.last()) + 2f,
            baseline + paint.descent() + 2f,
        )
        oldGlyphs.forEachIndexed { i, glyph ->
            val destination = newIndices[glyph.key]
            val endX = destination?.let { newPositions[it] } ?: oldPositions[i]
            // Layout slides stay in lockstep; only the vertical roll and fade cascade.
            val drawX = x + oldPositions[i] + (endX - oldPositions[i]) * progress
            val unchanged = destination != null && newGlyphs[destination].character == glyph.character
            if (unchanged) {
                paint.alpha = originalAlpha
                canvas.drawText(from, i, i + 1, drawX, baseline, paint)
            } else {
                val roll = columnProgress(glyph.key, progress)
                paint.alpha = (originalAlpha * (1f - roll)).toInt()
                canvas.drawText(from, i, i + 1, drawX, baseline - travel * roll, paint)
            }
        }
        newGlyphs.forEachIndexed { i, glyph ->
            val origin = oldIndices[glyph.key]
            if (origin == null || oldGlyphs[origin].character != glyph.character) {
                val startX = origin?.let { oldPositions[it] } ?: newPositions[i]
                val drawX = x + startX + (newPositions[i] - startX) * progress
                val roll = columnProgress(glyph.key, progress)
                paint.alpha = (originalAlpha * roll).toInt()
                canvas.drawText(to, i, i + 1, drawX, baseline + travel * (1f - roll), paint)
            }
        }
        paint.alpha = originalAlpha
        canvas.restoreToCount(save)
    }

    private fun columnProgress(key: String, progress: Float): Float {
        val delay = rollDelays[key] ?: 0f
        return ((progress - delay) / rollSpan).coerceIn(0f, 1f)
    }

    companion object {
        /** Match integers from the decimal point, fractions from the left, and symbols by role. */
        internal fun glyphs(value: String, decimalSeparator: Char = '.'): List<Glyph> {
            val decimalIndex = value.indexOf(decimalSeparator).let { if (it < 0) value.length else it }
            val integerDigits = value.take(decimalIndex).count(Char::isDigit)
            var integerPlace = integerDigits - 1
            var fractionPlace = -1
            val symbolCounts = mutableMapOf<Char, Int>()
            return value.mapIndexed { index, char ->
                val key = when {
                    char.isDigit() && index < decimalIndex -> "digit:${integerPlace--}"
                    char.isDigit() -> "digit:${fractionPlace--}"
                    char == decimalSeparator -> "decimal"
                    else -> {
                        val count = symbolCounts.getOrDefault(char, 0)
                        symbolCounts[char] = count + 1
                        // Grouping marks travel with the integer place immediately to their right.
                        if (index < decimalIndex && index > 0 && value[index - 1].isDigit()) {
                            "separator:$char:${abs(integerPlace)}"
                        } else {
                            "symbol:$char:$count"
                        }
                    }
                }
                Glyph(char, key)
            }
        }
    }
}
