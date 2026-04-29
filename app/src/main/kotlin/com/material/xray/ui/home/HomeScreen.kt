package com.material.xray.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.ConnectionState
import com.material.xray.ui.components.ScrolledTopAppBar
import java.time.Duration
import java.time.Instant
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val serversBySubscription by viewModel.serversBySubscription.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val runningConfig by viewModel.runningConfig.collectAsStateWithLifecycle()

    val isConnected = connectionState is ConnectionState.Connected
    val isTransitioning = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.ApplyingRoutingChanges ||
            connectionState is ConnectionState.UpdatingRoutingData ||
            connectionState is ConnectionState.Disconnecting

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> MaterialTheme.colorScheme.error
            isTransitioning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor",
    )

    var showAddDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val selectedServerName = remember(selectedServer) { selectedServer?.name ?: "No server selected" }
    val selectedServerDetail = remember(selectedServer) {
        selectedServer?.let {
            "${it.protocol.displayName.uppercase()} | ${it.transport.type.uppercase()} | ${it.security.type.uppercase()}"
        } ?: "Select a server below"
    }
    val context = LocalContext.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        topBar = {
            ScrolledTopAppBar(
                title = "Material Xray",
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ConnectionPanel(
                    connectionState = connectionState,
                    selectedServerName = selectedServerName,
                    selectedServerDetail = selectedServerDetail,
                    buttonColor = buttonColor,
                    isConnected = isConnected,
                    isTransitioning = isTransitioning,
                    canStart = selectedServer != null,
                    onClick = {
                        if (isConnected) viewModel.disconnect()
                        else if (!isTransitioning) viewModel.connect()
                    },
                    onViewConfig = { viewModel.showRunningConfig() },
                )
            }

            if (isRefreshing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (connectionState is ConnectionState.Error) {
                item {
                    ErrorCard(message = (connectionState as ConnectionState.Error).message)
                }
            }

            if (subscriptions.isEmpty()) {
                item {
                    EmptySubscriptionsCard(onAddSubscription = { showAddDialog = true })
                }
            } else {
                items(
                    items = subscriptions,
                    key = { it.id },
                    contentType = { "subscription" },
                ) { subscription ->
                    SubscriptionCard(
                        subscription = subscription,
                        servers = serversBySubscription[subscription.id].orEmpty(),
                        selectedServerId = selectedServerId,
                        onDelete = { viewModel.deleteSubscription(subscription) },
                        onEdit = { editingSubscription = subscription },
                        onRefresh = { viewModel.refreshSubscription(subscription) },
                        onTestAll = { viewModel.testSubscriptionLatencies(subscription) },
                        onServerSelected = { viewModel.selectServer(it) },
                        onTestLatency = { viewModel.testLatency(it) },
                    )
                }
                item(contentType = "addSubscription") {
                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add new subscription")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                viewModel.addSubscription(name, url)
                showAddDialog = false
            },
        )
    }

    editingSubscription?.let { subscription ->
        EditSubscriptionDialog(
            subscription = subscription,
            onDismiss = { editingSubscription = null },
            onConfirm = { url ->
                viewModel.updateSubscriptionUrl(subscription, url)
                editingSubscription = null
            },
        )
    }

    if (runningConfig != null) {
        RawConfigDialog(
            config = runningConfig.orEmpty(),
            onDismiss = viewModel::dismissRunningConfig,
            onCopy = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Xray config", runningConfig.orEmpty()))
            },
        )
    }
}

@Composable
private fun ConnectionPanel(
    connectionState: ConnectionState,
    selectedServerName: String,
    selectedServerDetail: String,
    buttonColor: androidx.compose.ui.graphics.Color,
    isConnected: Boolean,
    isTransitioning: Boolean,
    canStart: Boolean,
    onClick: () -> Unit,
    onViewConfig: () -> Unit,
) {
    val buttonEnabled = (canStart || isConnected) && !isTransitioning
    val containerColor = if (buttonEnabled) {
        buttonColor.copy(alpha = 0.15f)
    } else {
        buttonColor.copy(alpha = 0.10f)
    }
    val contentColor = if (buttonEnabled) {
        buttonColor
    } else {
        buttonColor.copy(alpha = 0.75f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (connectionState) {
                is ConnectionState.Connected -> "Connected"
                is ConnectionState.Connecting -> "Connecting..."
                ConnectionState.ApplyingRoutingChanges -> "Applying routing changes..."
                ConnectionState.UpdatingRoutingData -> "Updating routing data..."
                is ConnectionState.Disconnecting -> "Disconnecting..."
                is ConnectionState.Error -> "Error"
                ConnectionState.Disconnected -> "Disconnected"
            },
            style = MaterialTheme.typography.titleLarge,
            color = when {
                isConnected -> MaterialTheme.colorScheme.primary
                connectionState is ConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = selectedServerName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = selectedServerDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = CircleShape,
            modifier = Modifier
                .size(124.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = buttonEnabled,
                    onClick = onClick,
                    onLongClick = {
                        if (isConnected) {
                            onViewConfig()
                        }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        strokeWidth = 4.dp,
                        color = buttonColor,
                    )
                } else {
                    Text(
                        text = if (isConnected) "Stop" else "Start",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun RawConfigDialog(
    config: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Active Xray Config") },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = config,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 420.dp)
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun EmptySubscriptionsCard(onAddSubscription: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a subscription to show servers here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onAddSubscription) {
                Text("Add subscription")
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    servers: List<ServerListItem>,
    selectedServerId: Long,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onServerSelected: (Long) -> Unit,
    onTestLatency: (ServerEntity) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubscriptionHeader(
                subscription = subscription,
                serverCount = servers.size,
                onRefresh = onRefresh,
                onTestAll = onTestAll,
                onDelete = onDelete,
                onEdit = onEdit,
            )
            SubscriptionMetadataSection(subscription = subscription)

            if (servers.isEmpty()) {
                Text(
                    "No servers in this subscription.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                servers.forEachIndexed { index, server ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        )
                    }
                    key(server.entity.id) {
                        ServerRow(
                            server = server,
                            isSelected = server.entity.id == selectedServerId,
                            onClick = { onServerSelected(server.entity.id) },
                            onTestLatency = { onTestLatency(server.entity) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionMetadataSection(subscription: SubscriptionEntity) {
    val announcement = remember(subscription.announce) {
        subscription.announce?.trim().orEmpty()
    }
    val traffic = remember(
        subscription.subscriptionUploadBytes,
        subscription.subscriptionDownloadBytes,
        subscription.subscriptionTotalBytes,
    ) {
        buildSubscriptionTrafficUiState(subscription)
    }
    val expiryText = remember(subscription.subscriptionExpireAt) {
        subscription.subscriptionExpireAt?.let(::formatSubscriptionExpiryRelative)
    }
    val updateIntervalText = remember(subscription.profileUpdateIntervalHours) {
        subscription.profileUpdateIntervalHours?.let { interval ->
            if (interval == 1) "Auto update every hour" else "Auto update every $interval hours"
        }
    }

    val hasMetadata = announcement.isNotEmpty() ||
            traffic != null ||
            !expiryText.isNullOrBlank() ||
            !updateIntervalText.isNullOrBlank()

    if (!hasMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (announcement.isNotEmpty()) {
            Text(
                text = announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        traffic?.let { trafficState ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = trafficState.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trafficState.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (!expiryText.isNullOrBlank()) {
            Text(
                text = expiryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!updateIntervalText.isNullOrBlank()) {
            Text(
                text = updateIntervalText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionEntity,
    serverCount: Int,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val webPageUrl = subscription.profileWebPageUrl?.trim().orEmpty()
    val supportUrl = subscription.supportUrl?.trim().orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$serverCount servers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh ${subscription.name}")
        }
        IconButton(onClick = onTestAll) {
            Icon(Icons.Default.Speed, contentDescription = "Test ${subscription.name}")
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Subscription menu")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (webPageUrl.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Web Page") },
                        onClick = {
                            showMenu = false
                            uriHandler.openUri(webPageUrl)
                        },
                    )
                }
                if (supportUrl.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Support") },
                        onClick = {
                            showMenu = false
                            uriHandler.openUri(supportUrl)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ServerListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
) {
    val latencyMs = server.latencyMs
    val latencyText = latencyMs?.let { if (it < 0) "Failed" else "${it}ms" }
    val latencyColor = when {
        latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
        latencyMs < 0 -> MaterialTheme.colorScheme.error
        latencyMs < 200 -> MaterialTheme.colorScheme.primary
        latencyMs < 500 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onTestLatency),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSelectionDot(isSelected = isSelected)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.entity.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.endpointSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (latencyText != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = latencyText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = latencyColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSelectionDot(isSelected: Boolean) {
    Surface(
        modifier = Modifier.size(18.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 2.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private data class SubscriptionTrafficUiState(
    val summary: String,
    val progress: Float? = null,
)

private fun buildSubscriptionTrafficUiState(subscription: SubscriptionEntity): SubscriptionTrafficUiState? {
    val upload = subscription.subscriptionUploadBytes
    val download = subscription.subscriptionDownloadBytes
    val total = subscription.subscriptionTotalBytes

    if (upload == null && download == null && total == null) return null

    val used = listOfNotNull(upload, download).sum()
    val details = buildList {
        upload?.let { add("↑ ${formatByteCount(it)}") }
        download?.let { add("↓ ${formatByteCount(it)}") }
    }.joinToString("  ")

    return when {
        total == null -> {
            val summary = if (details.isNotEmpty()) {
                "Unlimited traffic • $details"
            } else {
                "Unlimited traffic"
            }
            SubscriptionTrafficUiState(summary = summary)
        }

        total > 0 -> {
            val remaining = (total - used).coerceAtLeast(0)
            val summary = buildString {
                append("${formatByteCount(used)} used of ${formatByteCount(total)}")
                append(" • ${formatByteCount(remaining)} left")
                if (details.isNotEmpty()) append(" • $details")
            }
            SubscriptionTrafficUiState(
                summary = summary,
                progress = (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
            )
        }

        else -> {
            val summary = if (details.isNotEmpty()) {
                "No traffic quota • $details"
            } else {
                "No traffic quota"
            }
            SubscriptionTrafficUiState(summary = summary)
        }
    }
}

private fun formatSubscriptionExpiryRelative(epochSeconds: Long): String {
    val now = Instant.now()
    val expiresAt = Instant.ofEpochSecond(epochSeconds)
    if (!expiresAt.isAfter(now)) return "Expired"

    val remaining = Duration.between(now, expiresAt)
    val days = remaining.toDays()
    if (days >= 1) {
        return if (days == 1L) "Expires in 1 day" else "Expires in $days days"
    }

    val hours = remaining.toHours().coerceAtLeast(1)
    return if (hours == 1L) "Expires in 1 hour" else "Expires in $hours hours"
}

private fun formatByteCount(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    val formatted = if (unitIndex == 0) {
        bytes.toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }

    return "$formatted ${units[unitIndex]}"
}

@Composable
private fun EditSubscriptionDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember(subscription.id) { mutableStateOf(subscription.url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Subscription") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank() && url.trim() != subscription.url,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddSubscriptionDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Subscription") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
