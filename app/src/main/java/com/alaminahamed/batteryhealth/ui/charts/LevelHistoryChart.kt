package com.alaminahamed.batteryhealth.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.alaminahamed.batteryhealth.domain.LevelPoint

/**
 * Battery level over time, drawn directly. Two chart forms do not justify a charting
 * dependency, and drawing them here keeps the One UI styling exact.
 *
 * The y axis is pinned to 0..100 rather than fitted to the data, so a flat night of
 * discharge looks flat instead of being stretched into a dramatic slope.
 */
@Composable
fun LevelHistoryChart(
    points: List<LevelPoint>,
    lineColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    val segments = ChartGeometry.splitOnGaps(points, ChartGeometry.GAP_THRESHOLD_MS)
    if (segments.isEmpty()) return

    val minTime = points.minOf { it.timestampMs }
    val maxTime = points.maxOf { it.timestampMs }
    // A single-point series (or every point sharing one timestamp) would make span 0;
    // coercing to 1 keeps x() a division by a positive number instead of by zero. Every
    // point still lands inside [0, width] because (timestampMs - minTime) is itself 0
    // in that same case.
    val span = (maxTime - minTime).coerceAtLeast(1L)

    Canvas(modifier = modifier.fillMaxWidth().height(140.dp)) {
        val width = size.width
        val height = size.height

        fun x(timestampMs: Long) = ((timestampMs - minTime).toFloat() / span) * width
        fun y(levelPct: Int) = height - (levelPct.coerceIn(0, 100) / 100f) * height

        segments.forEach { segment ->
            if (segment.size == 1) {
                val only = segment.first()
                drawCircle(
                    color = lineColor,
                    radius = 3f,
                    center = Offset(x(only.timestampMs), y(only.levelPct)),
                )
                return@forEach
            }

            val line = Path().apply {
                moveTo(x(segment.first().timestampMs), y(segment.first().levelPct))
                segment.drop(1).forEach { lineTo(x(it.timestampMs), y(it.levelPct)) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(x(segment.last().timestampMs), height)
                lineTo(x(segment.first().timestampMs), height)
                close()
            }

            drawPath(area, color = fillColor)
            drawPath(line, color = lineColor, style = Stroke(width = 2.5f))
        }
    }
}
