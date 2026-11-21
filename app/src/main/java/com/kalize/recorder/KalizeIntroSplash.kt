package com.kalize.recorder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun KalizeIntroSplash(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 2450))
        delay(550)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Canvas(Modifier.size(224.dp)) {
                val unit = min(size.width, size.height) / 108f
                val left = (size.width - 108f * unit) / 2f
                val top = (size.height - 108f * unit) / 2f
                fun point(x: Float, y: Float) = Offset(left + x * unit, top + y * unit)

                // The four viewfinder corners are "written" in sequence.
                val strokes = listOf(
                    floatArrayOf(20f, 20f, 42f, 20f),
                    floatArrayOf(20f, 20f, 20f, 42f),
                    floatArrayOf(66f, 20f, 88f, 20f),
                    floatArrayOf(88f, 20f, 88f, 42f),
                    floatArrayOf(20f, 66f, 20f, 88f),
                    floatArrayOf(20f, 88f, 42f, 88f),
                    floatArrayOf(88f, 66f, 88f, 88f),
                    floatArrayOf(66f, 88f, 88f, 88f)
                )
                val frameProgress = (progress.value / 0.58f).coerceIn(0f, 1f)
                strokes.forEachIndexed { index, s ->
                    val p = (frameProgress * strokes.size - index).coerceIn(0f, 1f)
                    val x1 = s[0]
                    val y1 = s[1]
                    val x2 = s[2]
                    val y2 = s[3]
                    drawLine(
                        color = Color.White.copy(alpha = 0.98f),
                        start = point(x1, y1),
                        end = point(x1 + (x2 - x1) * p, y1 + (y2 - y1) * p),
                        strokeWidth = 5.5f * unit,
                        cap = StrokeCap.Round
                    )
                }

                // A vivid red recording ring traces itself after the frame.
                val ringProgress = ((progress.value - 0.48f) / 0.34f).coerceIn(0f, 1f)
                if (ringProgress > 0f) {
                    drawArc(
                        color = Color(0xFFFF242E),
                        startAngle = -90f,
                        sweepAngle = 360f * ringProgress,
                        useCenter = false,
                        topLeft = point(30f, 30f),
                        size = androidx.compose.ui.geometry.Size(48f * unit, 48f * unit),
                        style = Stroke(width = 6.5f * unit, cap = StrokeCap.Round)
                    )
                }

                // The record/play mark resolves with a soft red glow.
                val markProgress = ((progress.value - 0.79f) / 0.21f).coerceIn(0f, 1f)
                if (markProgress > 0f) {
                    drawCircle(
                        color = Color(0xFFFF1728).copy(alpha = markProgress),
                        radius = 18f * unit,
                        center = point(54f, 54f)
                    )
                    val triangle = Path().apply {
                        moveTo(point(49f, 45f).x, point(49f, 45f).y)
                        lineTo(point(65f, 54f).x, point(65f, 54f).y)
                        lineTo(point(49f, 63f).x, point(49f, 63f).y)
                        close()
                    }
                    drawPath(triangle, Color.White.copy(alpha = markProgress))
                }
            }

            Text(
                text = "KALIZE RECORDER",
                color = Color.White.copy(alpha = (progress.value * 1.35f).coerceIn(0f, 1f)),
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )
        }
    }
}
