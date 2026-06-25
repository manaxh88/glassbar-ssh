package com.glassbar.ssh.ui.screen.ssh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.glassbar.ssh.ui.LocalMainPagerState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import com.glassbar.ssh.ui.component.BlueButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LightText = Color(0xFF1A1A1A)
private val LightTextSecondary = Color(0xFF888888)

@Composable
fun SshScreen(
    bottomPadding: Dp = 0.dp,
    initialConnection: SshConnectionInfo? = null,
    onConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host ?: "") }
    var port by remember(initialConnection) { mutableStateOf((initialConnection?.port ?: 22).toString()) }
    var username by remember(initialConnection) { mutableStateOf(initialConnection?.username ?: "") }
    var password by remember(initialConnection) { mutableStateOf(initialConnection?.password ?: "") }

    val terminalBuffer = remember { TerminalBuffer(rows = 34, cols = 60) }
    val sshSession = remember { SshSession(terminalBuffer) }
    val connectionState by sshSession.state.collectAsStateWithLifecycle()
    val errorMessage by sshSession.errorMessage.collectAsStateWithLifecycle()

    val isConnected = connectionState == SshConnectionState.CONNECTED
    val isConnecting = connectionState == SshConnectionState.CONNECTING

    // Auto-connect when navigated from Home with saved password
    LaunchedEffect(initialConnection) {
        if (initialConnection != null && initialConnection.password.isNotBlank()
            && connectionState == SshConnectionState.DISCONNECTED) {
            sshSession.connect(
                SshConfig(
                    host = initialConnection.host.trim(),
                    port = initialConnection.port,
                    username = initialConnection.username.trim(),
                    password = initialConnection.password,
                )
            )
            onConsumed()
        }
    }

    val doConnect: () -> Unit = {
        focusManager.clearFocus()
        scope.launch {
            sshSession.connect(
                SshConfig(
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 22,
                    username = username.trim(),
                    password = password,
                )
            )
        }
    }

    val mainPagerState = LocalMainPagerState.current
    LaunchedEffect(isConnected) {
        mainPagerState.isScrollLocked = isConnected
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        // Connection form (visible when not connected)
        AnimatedVisibility(
            visible = !isConnected,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "SSH 连接",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightText,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (initialConnection != null) {
                    Text(
                        text = "${initialConnection.username}@${initialConnection.host}:${initialConnection.port}",
                        fontSize = 14.sp,
                        color = Color(0xFF1976D2),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                TextField(
                    value = host, onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "主机", useLabelAsPlaceholder = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(0.35f)) {
                        TextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = "端口", useLabelAsPlaceholder = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(0.65f)) {
                        TextField(
                            value = username, onValueChange = { username = it },
                            label = "用户名", useLabelAsPlaceholder = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                TextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "密码", useLabelAsPlaceholder = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    ),
                )
                Spacer(Modifier.height(20.dp))

                BlueButton(
                    onClick = doConnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = host.isNotBlank() && username.isNotBlank() && !isConnecting,
                ) {
                    Text(
                        text = if (isConnecting) "连接中..." else "连接",
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(10.dp))

                BlueButton(
                    onClick = {
                        val conn = SshConnectionInfo(
                            name = "",
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 22,
                            username = username.trim(),
                            password = password,
                        )
                        SshConnectionStore.add(context, conn)
                        doConnect()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = host.isNotBlank() && username.isNotBlank() && !isConnecting,
                ) {
                    Text(
                        text = "保存并连接",
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                }

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = Color(0xFFFF4444), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
                Text("使用提示", color = LightTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text(
                    "  Connect to SSH servers\n  Use hardware keyboard for terminal input\n  Ctrl+C/D/Z shortcuts supported\n  Arrow keys for navigation",
                    color = Color(0xFFAAAAAA), fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }

        // Terminal (visible when connected)
        AnimatedVisibility(
            visible = isConnected,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Status bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF4CAF50))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${username}@${host}:${port}",
                        color = LightText, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    BlueButton(
                        onClick = { sshSession.disconnect() },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("断开", fontSize = 12.sp, color = Color.White)
                    }
                }

                TerminalView(
                    buffer = terminalBuffer,
                    onKeyEvent = { key -> sshSession.send(key) },
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
            }
        }
    }
}
