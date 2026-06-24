package com.glassbar.ssh.ui.screen.ssh

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassbar.ssh.ui.component.CircularChart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import com.glassbar.ssh.ui.component.BlueButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LightText = Color(0xFF1A1A1A)
private val LightTextSecondary = Color(0xFF888888)
// Background now uses MiuixTheme.colorScheme.surface for consistency with bottom bar
private val LightCardBg = Color(0xFFF5F5F5)

@Composable
fun HomeScreen(
    bottomPadding: Dp = 0.dp,
    onConnect: (SshConnectionInfo) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connections by remember { mutableStateOf(SshConnectionStore.getAll(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<SshConnectionInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<SshConnectionInfo?>(null) }
    var serverStats by remember { mutableStateOf<Map<String, ServerStats>>(emptyMap()) }

    // Auto-refresh server stats every 10 seconds
    LaunchedEffect(connections) {
        while (true) {
            connections.filter { it.password.isNotBlank() }.forEach { conn ->
                val s = StatsFetcher.fetch(conn.host, conn.port, conn.username, conn.password)
                serverStats = serverStats + (conn.id to s)
            }
            delay(3_000)
        }
    }

    val refreshList: () -> Unit = {
        connections = SshConnectionStore.getAll(context)
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
                text = "SSH 连接",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = LightText,
            )
            BlueButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.height(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "添加",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("添加", color = Color.White, fontSize = 13.sp)
            }
        }

        if (connections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = Color(0xFFCCCCCC),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("暂无 SSH 连接", color = LightTextSecondary, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("点击添加创建连接", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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
                                .background(LightCardBg)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = conn.name.ifBlank { "${conn.username}@${conn.host}" },
                                    color = LightText,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${conn.username}@${conn.host}:${conn.port}",
                                    color = LightTextSecondary,
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
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(
                                    modifier = Modifier.clickable {
                                        scope.launch {
                                            val s = StatsFetcher.fetch(conn.host, conn.port, conn.username, conn.password)
                                            serverStats = serverStats + (conn.id to s)
                                        }
                                    },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularChart(
                                        value = if (hasError) 0f else cpuVal,
                                        color = if (hasError) Color(0xFFFF5252) else Color(0xFF4CAF50),
                                        label = if (hasError) "错误" else "CPU",
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    CircularChart(
                                        value = if (hasError) 0f else memVal,
                                        color = if (hasError) Color(0xFFFF5252) else Color(0xFF2196F3),
                                        label = if (hasError) "" else "内存",
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "编辑",
                                tint = Color(0xFF1976D2),
                                modifier = Modifier
                                    .size(22.dp)
                                    .clickable {
                                        editingConnection = conn
                                        showAddDialog = true
                                    },
                            )
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "删除",
                                tint = Color(0xFFBBBBBB),
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        deleteTarget = conn
                                    },
                            )
                            Spacer(Modifier.width(8.dp))
                            BlueButton(
                                onClick = { onConnect(conn) },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("连接", color = Color.White, fontSize = 12.sp)
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
            onDismiss = {
                showAddDialog = false
                editingConnection = null
            },
            onSave = { info ->
                if (editingConnection != null) {
                    SshConnectionStore.update(context, info)
                } else {
                    SshConnectionStore.add(context, info)
                }
                showAddDialog = false
                editingConnection = null
                refreshList()
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
                    "确认删除？",
                    color = LightText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    conn.name.ifBlank { "${conn.username}@${conn.host}" },
                    color = LightTextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    BlueButton(
                        onClick = { deleteTarget = null },
                        modifier = Modifier.height(40.dp).weight(1f),
                    ) {
                        Text("取消", color = Color.White, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    BlueButton(
                        onClick = {
                            SshConnectionStore.delete(context, conn.id)
                            deleteTarget = null
                            refreshList()
                        },
                        modifier = Modifier.height(40.dp).weight(1f),
                    ) {
                        Text("确认", color = Color.White, fontSize = 13.sp)
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
    onDismiss: () -> Unit,
    onSave: (SshConnectionInfo) -> Unit,
) {
    val isEdit = existing != null
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                .padding(20.dp),
        ) {
            Text(
                text = if (isEdit) "编辑连接" else "新建连接",
                color = LightText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            TextField(
                value = name, onValueChange = { name = it },
                label = "名称（选填）", useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = host, onValueChange = { host = it },
                label = "主机", useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = "端口", useLabelAsPlaceholder = true,
                    modifier = Modifier.weight(0.4f),
                )
                Spacer(Modifier.width(10.dp))
                TextField(
                    value = username, onValueChange = { username = it },
                    label = "用户名", useLabelAsPlaceholder = true,
                    modifier = Modifier.weight(0.6f),
                )
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password, onValueChange = { password = it },
                label = "密码", useLabelAsPlaceholder = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                BlueButton(onClick = onDismiss, modifier = Modifier.height(40.dp), enabled = true) {
                    Text("取消", color = Color.White, fontSize = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                BlueButton(
                    onClick = {
                        val conn = SshConnectionInfo(
                            id = existing?.id ?: kotlin.random.Random.nextLong().toString(36),
                            name = name.trim(), host = host.trim(),
                            port = port.toIntOrNull() ?: 22, username = username.trim(),
                            password = password,
                        )
                        onSave(conn)
                    },
                    modifier = Modifier.height(40.dp),
                    enabled = host.isNotBlank() && username.isNotBlank(),
                ) {
                    Text("保存", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }


}
