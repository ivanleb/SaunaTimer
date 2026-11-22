package com.sauna.tracker.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sauna.tracker.shared.HrSample
import kotlin.math.ceil
import kotlin.math.floor

data class ChartSeries(val samples: List<HrSample>, val color: Color)

/**
 * Heart rate over time. X = minutes since start, Y = bpm.
 * Several series are overlaid on the same time axis (used for comparing sessions).
 */
@Composable
fun HrChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    hrLimit: Int? = null,
    timeLimitMin: Int? = null,
    gridColor: Color = Color(0x33888888),
    labelColor: Color = Color(0xFF888888),
    limitColor: Color = Color(0xFFE53935),
    showLabels: Boolean = true,
    labelSize: TextUnit = 10.sp,
    minDurationMs: Long = 60_000L,
) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = labelColor, fontSize = labelSize)

    Canvas(modifier) {
        val all = series.flatMap { it.samples }
        val maxT = maxOf(
            all.maxOfOrNull { it.t } ?: 0L,
            minDurationMs,
            (timeLimitMin ?: 0) * 60_000L,
        ).toFloat()
        val lo = minOf(all.minOfOrNull { it.bpm } ?: 60, hrLimit ?: Int.MAX_VALUE, 200)
        val hi = maxOf(all.maxOfOrNull { it.bpm } ?: 120, hrLimit ?: 0, 60)
        val yMin = (floor((lo - 5) / 10.0) * 10).toFloat().coerceAtLeast(30f)
        val yMax = (ceil((hi + 5) / 10.0) * 10).toFloat().coerceAtLeast(yMin + 20f)

        val left = if (showLabels) 30.dp.toPx() else 0f
        val bottom = if (showLabels) 16.dp.toPx() else 0f
        val w = size.width - left
        val h = size.height - bottom
        fun x(t: Float) = left + t / maxT * w
        fun y(b: Float) = h - (b - yMin) / (yMax - yMin) * h

        // Horizontal grid + bpm labels
        val bpmStep = if (yMax - yMin > 80) 20 else 10
        var b = (ceil(yMin / bpmStep) * bpmStep)
        while (b <= yMax) {
            drawLine(gridColor, Offset(left, y(b)), Offset(size.width, y(b)), 1.dp.toPx())
            if (showLabels) {
                val layout = measurer.measure(b.toInt().toString(), style)
                drawText(layout, topLeft = Offset(left - layout.size.width - 4.dp.toPx(), y(b) - layout.size.height / 2f))
            }
            b += bpmStep
        }

        // Vertical grid + minute labels
        val totalMin = maxT / 60_000f
        val minStep = listOf(1, 2, 5, 10, 15, 30, 60).first { totalMin / it <= 6 }
        var m = 0
        while (m <= totalMin) {
            val px = x(m * 60_000f)
            drawLine(gridColor, Offset(px, 0f), Offset(px, h), 1.dp.toPx())
            if (showLabels) {
                val layout = measurer.measure(m.toString(), style)
                drawText(layout, topLeft = Offset(px - layout.size.width / 2f, h + 2.dp.toPx()))
            }
            m += minStep
        }

        // Limits
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        hrLimit?.let {
            drawLine(limitColor, Offset(left, y(it.toFloat())), Offset(size.width, y(it.toFloat())), 1.5.dp.toPx(), pathEffect = dash)
        }
        timeLimitMin?.let {
            val px = x(it * 60_000f)
            drawLine(limitColor.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, h), 1.5.dp.toPx(), pathEffect = dash)
        }

        // Series
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (s in series) {
            if (s.samples.isEmpty()) continue
            val path = Path()
            var prevT = Long.MIN_VALUE
            for (p in s.samples) {
                val px = x(p.t.toFloat())
                val py = y(p.bpm.toFloat())
                // Leave a visible gap where readings are missing (e.g. sensor lost contact).
                if (prevT == Long.MIN_VALUE || p.t - prevT > GAP_MS) path.moveTo(px, py) else path.lineTo(px, py)
                prevT = p.t
            }
            if (s.samples.size == 1) {
                drawCircle(s.color, 2.dp.toPx(), Offset(x(s.samples[0].t.toFloat()), y(s.samples[0].bpm.toFloat())))
            }
            drawPath(path, s.color, style = stroke)
        }
    }
}

private const val GAP_MS = 20_000L
