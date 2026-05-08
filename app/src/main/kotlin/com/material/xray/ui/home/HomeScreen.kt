package com.material.xray.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.material.xray.model.endpointSummary
import com.material.xray.service.ConnectionEvent
import com.material.xray.ui.components.ScrolledTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val useRootService by viewModel.useRootService.collectAsStateWithLifecycle()
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
    var showRootFallbackDialog by remember { mutableStateOf(false) }
    val selectedServerName = remember(selectedServer) { selectedServer?.name ?: "No server selected" }
    val selectedServerDetail = remember(selectedServer) {
        selectedServer?.endpointSummary() ?: "Select a server below"
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val displayServerName = remember(connectionState, selectedServerName) {
        (connectionState as? ConnectionState.Connected)?.serverName ?: selectedServerName
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }
    val startRootlessConnection = {
        val vpnPermissionIntent = VpnService.prepare(context)
        if (vpnPermissionIntent != null) {
            vpnPermissionLauncher.launch(vpnPermissionIntent)
        } else {
            viewModel.connect()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshTunnelInterfaceState()
    }

    LaunchedEffect(viewModel) {
        viewModel.connectionEvents.collect { event ->
            when (event) {
                ConnectionEvent.RootUnavailableFallback -> showRootFallbackDialog = true
            }
        }
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
        contentWindowInsets = WindowInsets(0.dp),
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
                        else if (!isTransitioning) {
                            if (useRootService) viewModel.connect() else startRootlessConnection()
                        }
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

    if (showRootFallbackDialog) {
        AlertDialog(
            onDismissRequest = { showRootFallbackDialog = false },
            text = { Text("Unable to access root on device, falling back to rootless mode") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRootFallbackDialog = false
                        startRootlessConnection()
                    },
                ) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRootFallbackDialog = false }) {
                    Text("Cancel")
                }
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
    val metadata = remember(
        subscription.announce,
        subscription.subscriptionUploadBytes,
        subscription.subscriptionDownloadBytes,
        subscription.subscriptionTotalBytes,
        subscription.subscriptionExpireAt,
        subscription.autoUpdateIntervalHours,
    ) {
        buildSubscriptionMetadataUiState(subscription)
    }

    if (!metadata.hasMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180))
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = metadata.announcement.isNotEmpty() && !subscription.descriptionHidden,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
            Text(
                text = metadata.announcement,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (metadata.traffic != null || metadata.expiry != null || metadata.updateIntervalText.isNotBlank()) {
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
                    metadata.traffic?.let { trafficState ->
                        if (trafficState.quotaText == null) {
                            SubscriptionTrafficText(
                                text = trafficState.summaryText(metadata.expiry),
                            )
                        } else {
                            SubscriptionTrafficProgress(state = trafficState)
                        }
                    }

                    val detailText = metadata.traffic?.detailText(metadata.expiry)
                    if (!detailText.isNullOrBlank()) {
                        SubscriptionTrafficText(
                            text = detailText,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            textAlign = TextAlign.Center,
                        )
                    } else if (metadata.traffic == null && metadata.expiry != null) {
                        SubscriptionTrafficText(
                            text = metadata.expiry.standaloneText,
                        )
                    }

                    Text(
                        text = metadata.updateIntervalText,
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

private fun String.withMetadataEmphasis() = buildAnnotatedString {
    metadataTextSegments(this@withMetadataEmphasis).forEach { segment ->
        if (segment.emphasized) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(segment.value)
            }
        } else {
            append(segment.value)
        }
    }
}

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
            Button(
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
