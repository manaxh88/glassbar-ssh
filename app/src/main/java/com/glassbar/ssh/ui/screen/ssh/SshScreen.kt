package com.glassbar.ssh.ui.screen.ssh

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.glassbar.ssh.R
import com.glassbar.ssh.ui.LocalMainPagerState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Icon
import com.glassbar.ssh.ui.component.BlueButton
import com.glassbar.ssh.ui.theme.isInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SshScreen(
    bottomPadding: Dp = 0.dp,
    initialConnection: SshConnectionInfo? = null,
    onConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var privateKeyUri by remember { mutableStateOf("") }
    var privateKeyPassphrase by remember { mutableStateOf("") }
    var showKeyPassphrase by remember { mutableStateOf(false) }
    var lastConfig by remember { mutableStateOf<SshConfig?>(null) }
    var localErrorMessage by remember { mutableStateOf<String?>(null) }
    var dismissedHostFingerprint by remember { mutableStateOf<String?>(null) }
    var terminalFontScale by rememberSaveable { mutableFloatStateOf(1f) }
    var terminalSelection by remember { mutableStateOf<TerminalSelection?>(null) }

    val textColor = MiuixTheme.colorScheme.onSurface
    val secondaryTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val terminalTheme = if (isInDarkTheme()) TerminalTheme.Dark else TerminalTheme.Light
    val invalidPortMessage = stringResource(R.string.connection_port_invalid)
    val privateKeyReadFailedMessage = stringResource(R.string.private_key_read_failed)
    val privateKeyPermissionFailedMessage = stringResource(R.string.private_key_permission_failed)
    val connectionSaveFailedMessage = stringResource(R.string.connection_storage_write_failed)
    val hostKeySaveFailedMessage = stringResource(R.string.host_key_save_failed)
    val terminalController = rememberTerminalController()
    val privateKeyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (persistPrivateKeyPermission(context, uri)) {
            localErrorMessage = null
            privateKeyUri = uri.toString()
        } else {
            localErrorMessage = privateKeyPermissionFailedMessage
        }
    }

    val sshViewModel = viewModel<SshViewModel>()
    val terminalBuffer = sshViewModel.terminalBuffer
    val sshSession = sshViewModel.session
    val connectionState by sshSession.state.collectAsStateWithLifecycle()
    val errorMessage by sshSession.errorMessage.collectAsStateWithLifecycle()
    val hostKeyStatus by sshSession.hostKeyStatus.collectAsStateWithLifecycle()

    val isConnected = connectionState == SshConnectionState.CONNECTED
    val isConnecting = connectionState == SshConnectionState.CONNECTING
    val displayedError = localErrorMessage ?: errorMessage

    fun replaceLastConfig(config: SshConfig?) {
        lastConfig?.clearOwnedSecrets()
        lastConfig = config
    }

    DisposableEffect(Unit) {
        onDispose {
            lastConfig?.clearOwnedSecrets()
        }
    }

    // Populate a selected profile and auto-connect only when it contains usable credentials.
    LaunchedEffect(initialConnection?.id) {
        val connection = initialConnection ?: return@LaunchedEffect
        host = connection.host
        port = connection.port.toString()
        username = connection.username
        password = connection.password
        savePassword = connection.savePassword
        privateKeyUri = connection.privateKeyUri
        sshViewModel.disconnect()

        if (connection.password.isNotBlank() || connection.privateKeyUri.isNotBlank()) {
            runCatching {
                SshConfig(
                    host = connection.host.trim(),
                    port = connection.port,
                    username = connection.username.trim(),
                    password = connection.password,
                    privateKey = readPrivateKey(context, connection.privateKeyUri),
                    privateKeyName = "glassbar-${connection.id}",
                )
            }.onSuccess { config ->
                localErrorMessage = null
                replaceLastConfig(config)
                dismissedHostFingerprint = null
                sshViewModel.connect(config)
            }.onFailure {
                localErrorMessage = privateKeyReadFailedMessage
            }
        }
        onConsumed()
    }

    val doConnect: () -> Unit = {
        focusManager.clearFocus()
        scope.launch {
            val targetPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
            if (targetPort == null) {
                localErrorMessage = invalidPortMessage
                return@launch
            }
            runCatching {
                SshConfig(
                    host = host.trim(),
                    port = targetPort,
                    username = username.trim(),
                    password = password,
                    privateKey = readPrivateKey(context, privateKeyUri),
                    privateKeyPassphrase = privateKeyPassphrase
                        .takeIf(String::isNotEmpty)
                        ?.toByteArray(),
                )
            }.onSuccess { config ->
                localErrorMessage = null
                replaceLastConfig(config)
                dismissedHostFingerprint = null
                sshViewModel.connect(config)
            }.onFailure {
                localErrorMessage = privateKeyReadFailedMessage
            }
        }
    }

    val mainPagerState = LocalMainPagerState.current
    LaunchedEffect(connectionState, hostKeyStatus) {
        mainPagerState.isScrollLocked = isConnected
        if (isConnected) {
            replaceLastConfig(null)
            password = ""
            showPassword = false
            privateKeyPassphrase = ""
            showKeyPassphrase = false
        } else if (
            connectionState == SshConnectionState.ERROR &&
            hostKeyStatus !is SshHostKeyStatus.Unknown &&
            hostKeyStatus !is SshHostKeyStatus.Changed
        ) {
            replaceLastConfig(null)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding)
                .imePadding()
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
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.ssh_connection_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (initialConnection != null) {
                    Text(
                        text = "${initialConnection.username}@${initialConnection.host}:${initialConnection.port}",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                TextField(
                    value = host, onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.connection_host), useLabelAsPlaceholder = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(0.35f)) {
                        TextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                            label = stringResource(R.string.connection_port), useLabelAsPlaceholder = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number, imeAction = ImeAction.Next,
                            ),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(0.65f)) {
                        TextField(
                            value = username, onValueChange = { username = it },
                            label = stringResource(R.string.connection_username), useLabelAsPlaceholder = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                TextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.connection_password_optional), useLabelAsPlaceholder = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    ),
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = stringResource(
                                if (showPassword) R.string.hide_password else R.string.show_password,
                            ),
                            modifier = Modifier.clickable { showPassword = !showPassword },
                        )
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { savePassword = !savePassword },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = savePassword,
                        onCheckedChange = { savePassword = it },
                    )
                    Text(
                        text = stringResource(R.string.save_password_securely),
                        color = secondaryTextColor,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BlueButton(
                        onClick = {
                            privateKeyPicker.launch(
                                arrayOf("application/octet-stream", "application/x-pem-file", "text/plain"),
                            )
                        },
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(
                                if (privateKeyUri.isBlank()) {
                                    R.string.select_private_key
                                } else {
                                    R.string.change_private_key
                                },
                            ),
                            color = Color.White,
                        )
                    }
                    if (privateKeyUri.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = privateKeyDisplayName(context, privateKeyUri),
                            color = secondaryTextColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.remove_private_key),
                            tint = MiuixTheme.colorScheme.error,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable {
                                    privateKeyUri = ""
                                    privateKeyPassphrase = ""
                                },
                        )
                    }
                }
                if (privateKeyUri.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = privateKeyPassphrase,
                        onValueChange = { privateKeyPassphrase = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.private_key_passphrase_optional),
                        useLabelAsPlaceholder = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (showKeyPassphrase) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = if (showKeyPassphrase) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(
                                    if (showKeyPassphrase) {
                                        R.string.hide_private_key_passphrase
                                    } else {
                                        R.string.show_private_key_passphrase
                                    },
                                ),
                                modifier = Modifier.clickable {
                                    showKeyPassphrase = !showKeyPassphrase
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.height(20.dp))

                BlueButton(
                    onClick = doConnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = host.isNotBlank() && username.isNotBlank() && !isConnecting,
                ) {
                    Text(
                        text = stringResource(
                            if (isConnecting) R.string.action_connecting else R.string.action_connect,
                        ),
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
                            password = if (savePassword) password else "",
                            savePassword = savePassword,
                            privateKeyUri = privateKeyUri,
                        )
                        runCatching { SshConnectionStore.add(context, conn) }
                            .onSuccess { doConnect() }
                            .onFailure { localErrorMessage = connectionSaveFailedMessage }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = host.isNotBlank() && username.isNotBlank() && !isConnecting,
                ) {
                    Text(
                        text = stringResource(R.string.action_save_and_connect),
                        color = Color.White, fontWeight = FontWeight.Bold,
                    )
                }

                AnimatedVisibility(visible = displayedError != null) {
                    Text(
                        text = displayedError ?: "",
                        color = MiuixTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
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
                            .background(MiuixTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${username}@${host}:${port}",
                        color = textColor, fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_selected_text),
                        tint = if (terminalSelection != null) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            secondaryTextColor
                        },
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(enabled = terminalSelection != null) {
                                terminalController.copySelection()
                            },
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Rounded.ContentPaste,
                        contentDescription = stringResource(R.string.paste),
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { terminalController.pasteFromClipboard() },
                    )
                    Spacer(Modifier.width(12.dp))
                    BlueButton(
                        onClick = { sshViewModel.disconnect() },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            stringResource(R.string.action_disconnect),
                            fontSize = 12.sp,
                            color = Color.White,
                        )
                    }
                }

                TerminalView(
                    buffer = terminalBuffer,
                    onKeyEvent = { key -> sshSession.send(key) },
                    onResize = { cols, rows -> sshSession.resize(cols, rows) },
                    controller = terminalController,
                    theme = terminalTheme,
                    fontScale = terminalFontScale,
                    onFontScaleChange = { terminalFontScale = it },
                    onSelectionChange = { terminalSelection = it },
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
            }
        }
        }

        val presentedHostKey = when (val status = hostKeyStatus) {
            is SshHostKeyStatus.Unknown -> status.presented
            is SshHostKeyStatus.Changed -> status.presented
            else -> null
        }
        if (presentedHostKey != null &&
            presentedHostKey.fingerprintSha256 != dismissedHostFingerprint
        ) {
            HostKeyTrustDialog(
                status = hostKeyStatus,
                onDismiss = {
                    dismissedHostFingerprint = presentedHostKey.fingerprintSha256
                    replaceLastConfig(null)
                },
                onTrust = {
                    if (sshSession.trustPendingHostKey(presentedHostKey.fingerprintSha256)) {
                        dismissedHostFingerprint = null
                        lastConfig?.let(sshViewModel::connect)
                    } else {
                        localErrorMessage = hostKeySaveFailedMessage
                        dismissedHostFingerprint = presentedHostKey.fingerprintSha256
                        replaceLastConfig(null)
                    }
                },
            )
        }
    }
}

@Composable
private fun HostKeyTrustDialog(
    status: SshHostKeyStatus,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val presented = when (status) {
        is SshHostKeyStatus.Unknown -> status.presented
        is SshHostKeyStatus.Changed -> status.presented
        else -> return
    }
    val changed = status is SshHostKeyStatus.Changed

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable(onClick = {})
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(
                    if (changed) R.string.host_key_changed_title else R.string.host_key_confirm_title,
                ),
                color = if (changed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.host_key_endpoint,
                    presented.host,
                    presented.port,
                    presented.algorithm,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = presented.fingerprintSha256,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth(),
            )
            if (status is SshHostKeyStatus.Changed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.host_key_previous_fingerprint,
                        status.trusted.fingerprintSha256,
                    ),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.host_key_verify_message),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))
            BlueButton(
                onClick = onTrust,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(
                    stringResource(
                        if (changed) {
                            R.string.host_key_update_and_reconnect
                        } else {
                            R.string.host_key_trust_and_reconnect
                        },
                    ),
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(10.dp))
            BlueButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(42.dp),
            ) {
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
        }
    }
}
