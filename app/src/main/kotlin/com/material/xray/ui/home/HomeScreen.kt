package com.material.xray.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.ConnectionState
import com.material.xray.ui.components.ScrolledTopAppBar
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
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
    val isRestartRequired = connectionState is ConnectionState.RestartRequired
    val isInterfaceBusy = connectionState is ConnectionState.InterfaceBusy
    val isTransitioning = connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.ApplyingRoutingChanges ||
            connectionState is ConnectionState.UpdatingRoutingData ||
            connectionState is ConnectionState.Disconnecting

    val buttonColor = when {
        isConnected || isRestartRequired || isInterfaceBusy -> MaterialTheme.colorScheme.error
        isTransitioning -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var autoUpdateSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val selectedServerName = remember(selectedServer) { selectedServer?.name ?: "No server selected" }
    val selectedServerDetail = remember(selectedServer) {
        selectedServer?.let {
            "${it.protocol.displayName.uppercase()} | ${it.transport.type.uppercase()} | ${it.security.type.uppercase()}"
        } ?: "Select a server below"
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val displayServerName = remember(connectionState, selectedServerName) {
        (connectionState as? ConnectionState.Connected)?.serverName ?: selectedServerName
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshTunnelInterfaceState()
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshTunnelInterfaceState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                    selectedServerName = displayServerName,
                    selectedServerDetail = selectedServerDetail,
                    buttonColor = buttonColor,
                    isConnected = isConnected,
                    isRestartRequired = isRestartRequired,
                    isInterfaceBusy = isInterfaceBusy,
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
                        onAutoUpdateIntervalClick = { autoUpdateSubscription = subscription },
                        onDescriptionHiddenChange = { hidden ->
                            viewModel.setSubscriptionDescriptionHidden(subscription.id, hidden)
                        },
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
            onConfirm = { name, url ->
                viewModel.updateSubscription(subscription, name, url)
                editingSubscription = null
            },
        )
    }

    autoUpdateSubscription?.let { subscription ->
        AutoUpdateIntervalDialog(
            subscription = subscription,
            onDismiss = { autoUpdateSubscription = null },
            onSelected = { intervalHours ->
                viewModel.setSubscriptionAutoUpdateInterval(subscription.id, intervalHours)
                autoUpdateSubscription = null
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
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
    isTransitioning: Boolean,
    canStart: Boolean,
    onClick: () -> Unit,
    onViewConfig: () -> Unit,
) {
    val buttonEnabled = (canStart || isConnected || isRestartRequired || isInterfaceBusy) && !isTransitioning
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
                is ConnectionState.RestartRequired -> "Restart required"
                is ConnectionState.InterfaceBusy -> "Interface busy"
                is ConnectionState.Disconnecting -> "Disconnecting..."
                is ConnectionState.Error -> "Error"
                ConnectionState.Disconnected -> "Disconnected"
            },
            style = MaterialTheme.typography.titleLarge,
            color = when {
                isConnected -> MaterialTheme.colorScheme.primary
                isRestartRequired || isInterfaceBusy -> MaterialTheme.colorScheme.error
                connectionState is ConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isInterfaceBusy -> "The selected interface is currently in use by another client.\nClick the \"Restart\" button to shut it down and connect to the selected server."
                isRestartRequired -> "The client has been relaunched; to regain control, click the Restart button."
                else -> selectedServerName
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (isRestartRequired || isInterfaceBusy) 4 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!isRestartRequired && !isInterfaceBusy) {
            Text(
                text = selectedServerDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

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
                        text = when {
                            isConnected -> "Stop"
                            isRestartRequired || isInterfaceBusy -> "Restart"
                            else -> "Start"
                        },
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
    onAutoUpdateIntervalClick: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
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
                onAutoUpdateIntervalClick = onAutoUpdateIntervalClick,
                onDescriptionHiddenChange = onDescriptionHiddenChange,
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
private fun SubscriptionMetadataSection(
    subscription: SubscriptionEntity,
) {
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
    val expiry = remember(subscription.subscriptionExpireAt) {
        subscription.subscriptionExpireAt?.let(::formatSubscriptionExpiryUiState)
    }
    val updateIntervalText = remember(subscription.autoUpdateIntervalHours) {
        formatAutoUpdateInterval(subscription.autoUpdateIntervalHours)
    }

    val hasMetadata = announcement.isNotEmpty() ||
            traffic != null ||
            expiry != null ||
            updateIntervalText.isNotBlank()

    if (!hasMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180))
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = announcement.isNotEmpty() && !subscription.descriptionHidden,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
            Text(
                text = announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (traffic != null || expiry != null || updateIntervalText.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    traffic?.let { trafficState ->
                        if (trafficState.quotaText == null) {
                            SubscriptionTrafficText(
                                text = trafficState.summaryText(expiry),
                            )
                        } else {
                            SubscriptionTrafficProgress(state = trafficState)
                        }
                    }

                    val detailText = traffic?.detailText(expiry)
                    if (!detailText.isNullOrBlank()) {
                        SubscriptionTrafficText(
                            text = detailText,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                        )
                    } else if (traffic == null && expiry != null) {
                        SubscriptionTrafficText(
                            text = expiry.standaloneText,
                        )
                    }

                    Text(
                        text = updateIntervalText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionTrafficText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = remember(text) { text.withMetadataEmphasis() },
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
    )
}

@Composable
private fun SubscriptionTrafficProgress(
    state: SubscriptionTrafficUiState,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "0 GB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = state.quotaText.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    onAutoUpdateIntervalClick: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val webPageUrl = subscription.profileWebPageUrl?.trim().orEmpty()
    val supportUrl = subscription.supportUrl?.trim().orEmpty()
    val hasDescription = subscription.announce?.trim()?.isNotEmpty() == true

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
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
                if (hasDescription) {
                    DropdownMenuItem(
                        text = {
                            Text(if (subscription.descriptionHidden) "Show description" else "Hide description")
                        },
                        onClick = {
                            showMenu = false
                            onDescriptionHiddenChange(!subscription.descriptionHidden)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Auto update") },
                    onClick = {
                        showMenu = false
                        onAutoUpdateIntervalClick()
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
    val latencyText = latencyMs?.let {
        when {
            it == LATENCY_TESTING -> "Testing..."
            it < 0 -> "Failed"
            else -> "${it}ms"
        }
    }
    val latencyColor = when {
        latencyMs == null -> MaterialTheme.colorScheme.onSurfaceVariant
        latencyMs == LATENCY_TESTING -> MaterialTheme.colorScheme.onSurfaceVariant
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
    val quotaText: String? = null,
    val progress: Float = 0f,
    val downloadText: String? = null,
)

private data class SubscriptionExpiryUiState(
    val inlineText: String,
    val standaloneText: String,
)

private fun SubscriptionTrafficUiState.summaryText(expiry: SubscriptionExpiryUiState?): String =
    if (quotaText == null && expiry != null) {
        "$summary, ${expiry.inlineText}"
    } else {
        summary
    }

private fun SubscriptionTrafficUiState.detailText(expiry: SubscriptionExpiryUiState?): String? {
    val downloaded = downloadText
    return when {
        quotaText == null -> null
        downloaded != null && expiry != null -> "$downloaded, ${expiry.inlineText}"
        downloaded != null -> downloaded
        expiry != null -> expiry.inlineText
        else -> null
    }
}

private fun buildSubscriptionTrafficUiState(subscription: SubscriptionEntity): SubscriptionTrafficUiState? {
    val download = subscription.subscriptionDownloadBytes
    val total = subscription.subscriptionTotalBytes

    if (subscription.subscriptionUploadBytes == null && download == null && total == null) return null

    val downloaded = download?.coerceAtLeast(0) ?: 0L
    val downloadText = "$DOWNLOAD_TRAFFIC_PREFIX ${formatGigabyteCount(downloaded)}"

    return when {
        total == null || total <= 0 -> SubscriptionTrafficUiState(
            summary = if (download == null) {
                INFINITE_TRAFFIC_TEXT
            } else {
                "$INFINITE_TRAFFIC_TEXT, $downloadText"
            },
            downloadText = download?.let { downloadText },
        )

        else -> {
            SubscriptionTrafficUiState(
                summary = "${formatGigabyteCount(downloaded)} of ${formatGigabyteCount(total)}",
                quotaText = formatGigabyteCount(total),
                progress = (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat(),
                downloadText = downloadText,
            )
        }
    }
}

private fun formatSubscriptionExpiryUiState(epochSeconds: Long): SubscriptionExpiryUiState? {
    if (epochSeconds <= 0) return null

    val now = Instant.now()
    val expiresAt = normalizeSubscriptionExpireInstant(epochSeconds) ?: return null
    if (expiresAt.isAfter(now.plus(LONG_TERM_SUBSCRIPTION_DURATION))) return null
    if (!expiresAt.isAfter(now)) {
        return SubscriptionExpiryUiState(
            inlineText = "expired",
            standaloneText = "Expired",
        )
    }

    val formattedDate = SUBSCRIPTION_EXPIRY_DATE_FORMATTER.format(
        expiresAt.atZone(ZoneId.systemDefault()).toLocalDate(),
    )
    return SubscriptionExpiryUiState(
        inlineText = "expires on $formattedDate",
        standaloneText = "Expires on $formattedDate",
    )
}

private fun normalizeSubscriptionExpireInstant(value: Long): Instant? {
    val normalizedValue = value.coerceAtLeast(0)
    return runCatching {
        when {
            normalizedValue in SUBSCRIPTION_EXPIRY_BASIC_DATE_RANGE -> {
                parseBasicDateExpireInstant(normalizedValue) ?: Instant.ofEpochSecond(normalizedValue)
            }

            normalizedValue in SUBSCRIPTION_EXPIRY_YEAR_RANGE -> {
                LocalDate.of(normalizedValue.toInt(), 12, 31)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            }

            normalizedValue >= EPOCH_MILLIS_THRESHOLD -> Instant.ofEpochMilli(normalizedValue)

            else -> Instant.ofEpochSecond(normalizedValue)
        }
    }.getOrNull()
}

private fun parseBasicDateExpireInstant(value: Long): Instant? =
    try {
        LocalDate.parse(value.toString(), DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

private fun formatGigabyteCount(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble() / BYTES_PER_GB
    val formatted = if (value == 0.0 || value >= 10.0 && value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$formatted GB"
}

private fun String.withMetadataEmphasis() = buildAnnotatedString {
    var startIndex = 0
    while (true) {
        val nextToken = findNextEmphasizedToken(startIndex)
        if (nextToken == null) {
            append(substring(startIndex))
            return@buildAnnotatedString
        }

        append(substring(startIndex, nextToken.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(nextToken.value)
        }
        startIndex = nextToken.range.last + 1
    }
}

private data class EmphasizedToken(
    val range: IntRange,
    val value: String,
)

private fun String.findNextEmphasizedToken(startIndex: Int): EmphasizedToken? {
    val arrowIndex = indexOf(DOWNLOAD_TRAFFIC_PREFIX, startIndex)
    val expiredIndex = indexOf(EXPIRED_STATUS_TEXT, startIndex, ignoreCase = true)

    return listOfNotNull(
        arrowIndex.takeIf { it >= 0 }?.let {
            EmphasizedToken(it until it + DOWNLOAD_TRAFFIC_PREFIX.length, DOWNLOAD_TRAFFIC_PREFIX)
        },
        expiredIndex.takeIf { it >= 0 }?.let {
            val value = substring(it, it + EXPIRED_STATUS_TEXT.length)
            EmphasizedToken(it until it + value.length, value)
        },
    ).minByOrNull { it.range.first }
}

private const val INFINITE_TRAFFIC_TEXT = "∞ traffic"
private const val DOWNLOAD_TRAFFIC_PREFIX = "↓"
private const val EXPIRED_STATUS_TEXT = "expired"
private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
private val SUBSCRIPTION_EXPIRY_YEAR_RANGE = 2000L..9999L
private val SUBSCRIPTION_EXPIRY_BASIC_DATE_RANGE = 20_000_000L..99_991_231L
private val LONG_TERM_SUBSCRIPTION_DURATION: Duration = Duration.ofDays(365)
private val SUBSCRIPTION_EXPIRY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.US)

private data class AutoUpdateIntervalOption(
    val label: String,
    val intervalHours: Int,
)

private val autoUpdateIntervalOptions = listOf(
    AutoUpdateIntervalOption("1 hour", 1),
    AutoUpdateIntervalOption("3 hours", 3),
    AutoUpdateIntervalOption("6 hours", 6),
    AutoUpdateIntervalOption("1 day", 24),
    AutoUpdateIntervalOption("3 days", 72),
    AutoUpdateIntervalOption("Manual only", 0),
)

private fun formatAutoUpdateInterval(intervalHours: Int): String =
    when (intervalHours) {
        0 -> "Manual update only"
        1 -> "Auto update every hour"
        24 -> "Auto update every day"
        72 -> "Auto update every 3 days"
        else -> "Auto update every $intervalHours hours"
    }

@Composable
private fun AutoUpdateIntervalDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto update") },
        text = {
            Column {
                autoUpdateIntervalOptions.forEach { option ->
                    val selected = option.intervalHours == subscription.autoUpdateIntervalHours
                    AutoUpdateIntervalRow(
                        option = option,
                        selected = selected,
                        onSelected = { onSelected(option.intervalHours) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AutoUpdateIntervalRow(
    option: AutoUpdateIntervalOption,
    selected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onSelected,
                role = Role.RadioButton,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoUpdateIntervalIndicator(selected = selected)
        Text(
            text = option.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AutoUpdateIntervalIndicator(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.size(20.dp)) {
        val strokeWidth = 2.dp.toPx()
        drawCircle(
            color = if (selected) primary else outline,
            radius = size.minDimension / 2 - strokeWidth / 2,
            style = Stroke(width = strokeWidth),
        )
        if (selected) {
            drawCircle(
                color = primary,
                radius = size.minDimension * 0.28f,
            )
        }
    }
}

@Composable
private fun EditSubscriptionDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    var url by remember(subscription.id) { mutableStateOf(subscription.url) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Subscription") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Leave empty to get name from subscription provider") },
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
                enabled = url.isNotBlank() &&
                    (name.trim() != subscription.name || url.trim() != subscription.url),
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
                    supportingText = { Text("Leave empty to get name from subscription provider") },
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
                enabled = url.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
