package com.glassbar.ssh.ui.screen.dicecup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.min
import kotlin.random.Random

@Composable
fun DiceCupScreen(bottomPadding: Dp = 0.dp) {
    var rolling by remember { mutableStateOf(false) }
    var rollId by remember { mutableIntStateOf(0) }
    var value by remember { mutableIntStateOf(5) }

    LaunchedEffect(rollId) {
        if (rollId == 0) return@LaunchedEffect
        repeat(8) {
            value = Random.nextInt(1, 7)
            delay(70)
        }
        rolling = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            DiceGlBox(
                value = value,
                rolling = rolling,
                modifier = Modifier.size(220.dp),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 18.dp)
                .size(86.dp)
                .clickable(enabled = !rolling) {
                    rolling = true
                    rollId++
                },
        ) {
            ShakeButton(enabled = !rolling, modifier = Modifier.fillMaxSize())
            Text(
                text = "\u6447",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DiceGlBox(
    value: Int,
    rolling: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            DiceGlView(context).apply {
                setDice(value, rolling)
            }
        },
        update = { view ->
            view.setDice(value, rolling)
        },
    )
}

@Composable
private fun ShakeButton(enabled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = min(size.width, size.height) / 2f
        val c = center
        drawCircle(Color.Black.copy(alpha = 0.38f), radius = r * 0.98f, center = c + Offset(0f, r * 0.08f))
        drawCircle(Color(0xFFD8D9DD), radius = r * 0.90f, center = c)
        drawCircle(Color(0xFF6F747D), radius = r * 0.76f, center = c)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    if (enabled) Color(0xFFFF5A62) else Color(0xFF9D474C),
                    if (enabled) Color(0xFFE01924) else Color(0xFF713A3E),
                    Color(0xFF8B101A),
                ),
                center = c - Offset(r * 0.25f, r * 0.30f),
                radius = r * 0.95f,
            ),
            radius = r * 0.65f,
            center = c,
        )
        drawCircle(Color.White.copy(alpha = 0.28f), radius = r * 0.42f, center = c - Offset(r * 0.16f, r * 0.22f))
    }
}
