package com.glassbar.ssh.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CircularChart(
    value: Float,
    color: Color,
    label: String,
    size: Dp = 44.dp,
    strokeWidth: Dp = 4.dp,
) {
    val normalizedValue = value.takeIf { it.isFinite() }?.coerceIn(0f, 100f) ?: 0f
    val trackColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val valueTextColor = MiuixTheme.colorScheme.onSurface
    val labelTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Column(
        modifier = Modifier.width(size),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = strokeWidth.toPx()
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.toPx() - stroke, size.toPx() - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (normalizedValue > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (normalizedValue / 100f) * 360f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.toPx() - stroke, size.toPx() - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = "${normalizedValue.toInt()}%",
                fontSize = (size.value / 4.2).sp,
                fontWeight = FontWeight.Bold,
                color = valueTextColor,
            )
        }
        Text(
            text = label,
            fontSize = 9.sp,
            color = labelTextColor,
        )
    }
}
