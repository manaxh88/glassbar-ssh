package com.glassbar.ssh.ui.screen.ssh

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glassbar.ssh.R
import com.glassbar.ssh.ui.LocalMainPagerState
import com.glassbar.ssh.ui.component.CircularChart
import com.glassbar.ssh.ui.navigation3.LocalNavigator
import com.glassbar.ssh.ui.navigation3.Route
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import com.glassbar.ssh.ui.component.BlueButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    bottomPadding: Dp = 0.dp,
    onConnect: (SshConnectionInfo) -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val homeViewModelFactory = remember(application) {
        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }
    val homeViewModel = viewModel<HomeViewModel>(factory = homeViewModelFactory)
    val connections by homeViewModel.connections.collectAsStateWithLifecycle()
    val serverStats by homeViewModel.serverStats.collectAsStateWithLifecycle()
    val refreshingIds by homeViewModel.refreshingIds.collectAsStateWithLifecycle()
    val storageLoadError by homeViewModel.storageLoadError.collectAsStateWithLifecycle()
    val storageOperationError by homeViewModel.storageOperationError.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val pagerState = LocalMainPagerState.current.pagerState
    val navigator = LocalNavigator.current
    val textColor = MiuixTheme.colorScheme.onSurface
    val secondaryTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val cardColor = MiuixTheme.colorScheme.surface
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<SshConnectionInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<SshConnectionInfo?>(null) }
    var showResetCorruptDialog by remember { mutableStateOf(false) }

    val isHomeVisible =
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
            navigator.current() is Route.Main &&
            pagerState.settledPage == 0

    // HorizontalPager keeps off-screen pages composed. Check both pager visibility and the
    // Activity lifecycle so the terminal/about screens never trigger background SSH logins.
    LaunchedEffect(isHomeVisible) {
        homeViewModel.setActive(isHomeVisible)
    }

    DisposableEffect(homeViewModel) {
        onDispose { homeViewModel.setActive(false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding)
            .background(MiuixTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.about),
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp).clickable(onClick = onAbout),
                )
                Spacer(Modifier.width(14.dp))
                BlueButton(
                    onClick = {
                        homeViewModel.clearStorageOperationError()
                        editingConnection = null
                        showAddDialog = true
                    },
                    modifier = Modifier.height(40.dp),
                    enabled = storageLoadError == null,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.action_add),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_add), color = Color.White, fontSize = 13.sp)
                }
            }
        }

        storageLoadError?.let { loadError ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.connection_storage_corrupt_title),
                        color = MiuixTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (loadError.backupAvailable) {
                            R.string.connection_storage_corrupt_backup_saved
                        } else {
                            R.string.connection_storage_corrupt_backup_failed
                        },
                    ),
                    color = secondaryTextColor,
                    fontSize = 12.sp,
                )
                storageOperationError?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(message, color = MiuixTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    BlueButton(
                        onClick = {
                            homeViewModel.clearStorageOperationError()
                            showResetCorruptDialog = true
                        },
                        modifier = Modifier.height(38.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reset_connection_storage),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (storageLoadError == null && storageOperationError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = storageOperationError.orEmpty(),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = secondaryTextColor,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(onClick = homeViewModel::clearStorageOperationError),
                )
            }
        }

        if (storageLoadError != null) {
            Spacer(modifier = Modifier.weight(1f))
        } else if (connections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = secondaryTextColor.copy(alpha = 0.55f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.home_empty_title),
                        color = secondaryTextColor,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.home_empty_message),
                        color = secondaryTextColor,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 4.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(connections, key = { it.id }) { conn ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConnect(conn) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardColor)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conn.name.ifBlank { "${conn.username}@${conn.host}" },
                                    color = textColor,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${conn.username}@${conn.host}:${conn.port}",
                                    color = secondaryTextColor,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Server stats charts — always visible, tap to refresh
                            val stats = serverStats[conn.id]
                            val cpuVal = stats?.cpuPercent ?: 0f
                            val memVal = stats?.memPercent ?: 0f
                            val hasError = stats?.error != null
                            val isRefreshing = conn.id in refreshingIds
                            val updatedTime = stats?.let {
                                remember(it.updatedAtMillis) {
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                        .format(Date(it.updatedAtMillis))
                                }
                            }
                            Column(
                                modifier = Modifier.width(100.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Row(
                                    modifier = Modifier.clickable(
                                        enabled = isHomeVisible &&
                                            !isRefreshing &&
                                            (conn.password.isNotBlank() || conn.privateKeyUri.isNotBlank()),
                                        onClick = { homeViewModel.refreshNow(conn) },
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularChart(
                                        value = if (hasError) 0f else cpuVal,
                                        color = if (hasError) {
                                            MiuixTheme.colorScheme.error
                                        } else {
                                            MiuixTheme.colorScheme.primary
                                        },
                                        label = if (hasError) {
                                            stringResource(R.string.server_stats_error)
                                        } else {
                                            "CPU"
                                        },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    CircularChart(
                                        value = if (hasError) 0f else memVal,
                                        color = if (hasError) {
                                            MiuixTheme.colorScheme.error
                                        } else {
                                            MiuixTheme.colorScheme.primary.copy(alpha = 0.72f)
                                        },
                                        label = if (hasError) {
                                            ""
                                        } else {
                                            stringResource(R.string.server_stats_memory)
                                        },
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        isRefreshing -> stringResource(R.string.server_stats_refreshing)
                                        conn.password.isBlank() && conn.privateKeyUri.isBlank() ->
                                            stringResource(R.string.server_stats_no_saved_credentials)
                                        stats == null -> stringResource(R.string.server_stats_waiting)
                                        hasError -> stringResource(
                                            R.string.server_stats_failed,
                                            updatedTime.orEmpty(),
                                        )
                                        else -> stringResource(
                                            R.string.server_stats_updated,
                                            updatedTime.orEmpty(),
                                        )
                                    },
                                    color = if (hasError) {
                                        MiuixTheme.colorScheme.error
                                    } else {
                                        secondaryTextColor
                                    },
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.action_edit),
                                tint = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable(enabled = storageLoadError == null) {
                                        homeViewModel.clearStorageOperationError()
                                        editingConnection = conn
                                        showAddDialog = true
                                    },
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = secondaryTextColor,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable(enabled = storageLoadError == null) {
                                        homeViewModel.clearStorageOperationError()
                                        deleteTarget = conn
                                    },
                            )
                            Spacer(Modifier.width(8.dp))
                            BlueButton(
                                onClick = { onConnect(conn) },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text(
                                    stringResource(R.string.action_connect),
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

        // Add/Edit dialog
        if (showAddDialog || editingConnection != null) {
            AddEditDialog(
                existing = editingConnection,
                operationError = storageOperationError,
                onDismiss = {
                    showAddDialog = false
                    editingConnection = null
                },
                onSave = { info ->
                    val saved = if (editingConnection != null) {
                        homeViewModel.update(info)
                    } else {
                        homeViewModel.add(info)
                    }
                    if (saved) {
                        showAddDialog = false
                        editingConnection = null
                    }
                },
            )
        }

    // Delete confirmation dialog
    if (deleteTarget != null) {
        val conn = deleteTarget!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { deleteTarget = null }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.delete_connection_title),
                    color = textColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    conn.name.ifBlank { "${conn.username}@${conn.host}" },
                    color = secondaryTextColor,
                    fontSize = 14.sp,
                )
                storageOperationError?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = MiuixTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BlueButton(
                        onClick = { deleteTarget = null },
                        modifier = Modifier.height(40.dp).weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.action_cancel),
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    BlueButton(
                        onClick = {
                            if (homeViewModel.delete(conn.id)) {
                                deleteTarget = null
                            }
                        },
                        modifier = Modifier.height(40.dp).weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.action_confirm),
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

        if (showResetCorruptDialog && storageLoadError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = { showResetCorruptDialog = false },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainer)
                        .clickable(
                            interactionSource = remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                            indication = null,
                            onClick = {},
                        )
                        .padding(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reset_connection_storage_confirm_title),
                        color = textColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            if (storageLoadError?.backupAvailable == true) {
                                R.string.reset_connection_storage_confirm_message
                            } else {
                                R.string.reset_connection_storage_confirm_message_no_backup
                            },
                        ),
                        color = secondaryTextColor,
                        fontSize = 13.sp,
                    )
                    storageOperationError?.let { message ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = MiuixTheme.colorScheme.error,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        BlueButton(
                            onClick = { showResetCorruptDialog = false },
                            modifier = Modifier
                                .height(40.dp)
                                .weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.action_cancel),
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        BlueButton(
                            onClick = {
                                if (homeViewModel.resetCorruptConnections()) {
                                    showResetCorruptDialog = false
                                }
                            },
                            modifier = Modifier
                                .height(40.dp)
                                .weight(1f),
                        ) {
                            Text(
                                text = stringResource(R.string.reset_connection_storage),
                                color = Color.White,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddEditDialog(
    existing: SshConnectionInfo?,
    operationError: String?,
    onDismiss: () -> Unit,
    onSave: (SshConnectionInfo) -> Unit,
) {
    val context = LocalContext.current
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var savePassword by remember { mutableStateOf(existing?.savePassword ?: false) }
    var showPassword by remember { mutableStateOf(false) }
    var privateKeyUri by remember { mutableStateOf(existing?.privateKeyUri ?: "") }
    var privateKeyPermissionError by remember { mutableStateOf(false) }
    val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val textColor = MiuixTheme.colorScheme.onSurface
    val secondaryTextColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val privateKeyName = remember(context, privateKeyUri) {
        privateKeyDisplayName(context, privateKeyUri)
    }
    val privateKeyPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (persistPrivateKeyPermission(context, uri)) {
            privateKeyUri = uri.toString()
            privateKeyPermissionError = false
        } else {
            privateKeyPermissionError = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = if (isEdit) {
                    stringResource(R.string.edit_connection_title)
                } else {
                    stringResource(R.string.new_connection_title)
                },
                color = textColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            operationError?.let { message ->
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            TextField(
                value = name, onValueChange = { name = it },
                label = stringResource(R.string.connection_name_optional),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = host, onValueChange = { host = it },
                label = stringResource(R.string.connection_host),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = stringResource(R.string.connection_port),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.weight(0.4f),
                )
                Spacer(Modifier.width(10.dp))
                TextField(
                    value = username, onValueChange = { username = it },
                    label = stringResource(R.string.connection_username),
                    useLabelAsPlaceholder = true,
                    modifier = Modifier.weight(0.6f),
                )
            }
            if (parsedPort == null) {
                Text(
                    text = stringResource(R.string.connection_port_invalid),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.connection_password_optional),
                useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                ),
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) {
                            stringResource(R.string.hide_password)
                        } else {
                            stringResource(R.string.show_password)
                        },
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
            Spacer(Modifier.height(10.dp))
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
                    Text(
                        if (privateKeyUri.isBlank()) {
                            stringResource(R.string.select_private_key)
                        } else {
                            stringResource(R.string.change_private_key)
                        },
                        color = Color.White,
                    )
                }
                if (privateKeyUri.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = privateKeyName,
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
                                privateKeyPermissionError = false
                            },
                    )
                }
            }
            if (privateKeyPermissionError) {
                Text(
                    text = stringResource(R.string.private_key_permission_failed),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                BlueButton(onClick = onDismiss, modifier = Modifier.height(40.dp), enabled = true) {
                    Text(
                        stringResource(R.string.action_cancel),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                BlueButton(
                    onClick = {
                        parsedPort?.let { validPort ->
                            val conn = SshConnectionInfo(
                                id = existing?.id ?: kotlin.random.Random.nextLong().toString(36),
                                name = name.trim(),
                                host = host.trim(),
                                port = validPort,
                                username = username.trim(),
                                password = if (savePassword) password else "",
                                savePassword = savePassword,
                                privateKeyUri = privateKeyUri,
                            )
                            onSave(conn)
                        }
                    },
                    modifier = Modifier.height(40.dp),
                    enabled = host.isNotBlank() &&
                        username.isNotBlank() &&
                        parsedPort != null,
                ) {
                    Text(
                        stringResource(R.string.action_save),
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }


}
