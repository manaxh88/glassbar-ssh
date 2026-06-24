package com.glassbar.ssh.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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

@Composable
fun CircularChart(
    value: Float,
    color: Color,
    label: String,
    size: Dp = 44.dp,
    strokeWidth: Dp = 4.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = strokeWidth.toPx()
                drawArc(
                    color = Color(0xFFE8E8E8),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.toPx() - stroke, size.toPx() - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (value > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (value / 100f) * 360f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.toPx() - stroke, size.toPx() - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                text = "${value.toInt()}%",
                fontSize = (size.value / 4.2).sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333),
            )
        }
        Text(
            text = label,
            fontSize = 9.sp,
            color = Color(0xFF999999),
        )
    }
}
