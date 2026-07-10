package com.material.xray.ui.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.widget.Toast
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.model.ConnectionState
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionAppRouting
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.model.endpointSummary
import com.material.xray.service.ConnectionEvent
import com.material.xray.ui.components.ScrolledTopAppBar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState = collectHomeUiState(viewModel)
    val connectionUiState = buildConnectionUiState(uiState.connectionState, uiState.selectedServer)

    var showAddDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var keepQrScannerDialog by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var removeSubscriptionRequest by remember { mutableStateOf<Pair<SubscriptionEntity, Int>?>(null) }
    var showRootFallbackDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Unable to fetch link", Toast.LENGTH_SHORT).show()
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
    val pasteFromClipboard = {
        val link = context.clipboardText()
        if (link == null) {
            Toast.makeText(context, "Unable to fetch link", Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addLink(link)
        }
    }
    val openQrScanner = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showQrScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is HomeUiEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(showQrScanner) {
        if (showQrScanner) {
            keepQrScannerDialog = true
        } else {
            delay(QR_SCANNER_TRANSITION_MS.toLong())
            keepQrScannerDialog = false
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
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ConnectionPanel(
                    connectionState = uiState.connectionState,
                    selectedServerName = connectionUiState.displayServerName,
                    selectedServerDetail = connectionUiState.selectedServerDetail,
                    buttonColor = connectionUiState.buttonColor,
                    isConnected = connectionUiState.isConnected,
                    isRestartRequired = connectionUiState.isRestartRequired,
                    isInterfaceBusy = connectionUiState.isInterfaceBusy,
                    isTransitioning = connectionUiState.isTransitioning,
                    canStart = uiState.selectedServer != null,
                    onClick = {
                        if (connectionUiState.isConnected) {
                            viewModel.disconnect()
                        } else if (!connectionUiState.isTransitioning) {
                            if (uiState.useRootService) viewModel.connect() else startRootlessConnection()
                        }
                    },
                    onViewConfig = { viewModel.showRunningConfig() },
                )
            }

            if (uiState.isRefreshing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            val errorState = uiState.connectionState as? ConnectionState.Error
            if (errorState != null) {
                item {
                    ErrorCard(message = errorState.message)
                }
            }

            if (uiState.subscriptions.isEmpty()) {
                item {
                    EmptySubscriptionsCard(
                        onPasteFromClipboard = pasteFromClipboard,
                        onScanQrCode = openQrScanner,
                        onAddManually = { showAddDialog = true },
                    )
                }
            } else {
                items(
                    items = uiState.subscriptions,
                    key = { it.id },
                    contentType = { "subscription" },
                ) { subscription ->
                    val servers = uiState.serversBySubscription[subscription.id].orEmpty()
                    SubscriptionCard(
                        subscription = subscription,
                        servers = servers,
                        selectedServerId = uiState.selectedServerId,
                        defaultPingMethod = uiState.defaultPingMethod,
                        canApplyRouting = uiState.routingPolicyControl == RoutingPolicyControl.User &&
                            subscription.toSubscriptionAppRouting() != null,
                        onDelete = {
                            if (servers.isEmpty()) {
                                viewModel.deleteSubscription(subscription)
                            } else {
                                removeSubscriptionRequest = subscription to servers.size
                            }
                        },
                        onEdit = { editingSubscription = subscription },
                        onRefresh = { viewModel.refreshSubscription(subscription) },
                        onTestAll = { viewModel.testSubscriptionLatencies(subscription) },
                        onDefaultPingMethodSelected = { viewModel.setDefaultPingMethod(it) },
                        onApplyRouting = { viewModel.requestApplySubscriptionRouting(subscription) },
                        onDescriptionHiddenChange = { hidden ->
                            viewModel.setSubscriptionDescriptionHidden(subscription.id, hidden)
                        },
                        onServerSelected = { viewModel.selectServer(it) },
                        onTestLatency = { viewModel.testLatency(it) },
                    )
                }
                item(contentType = "addSubscription") {
                    AddSubscriptionActionButton(
                        modifier = Modifier.fillMaxWidth(),
                        onPasteFromClipboard = pasteFromClipboard,
                        onScanQrCode = openQrScanner,
                        onAddManually = { showAddDialog = true },
                    )
                }
            }
        }
    }

    AddSubscriptionDialogHost(
        visible = showAddDialog,
        onDismiss = { showAddDialog = false },
        onConfirm = { name, url, preferJson, userAgentMode, customUserAgent, customHeaders ->
            viewModel.addSubscription(name, url, preferJson, userAgentMode, customUserAgent, customHeaders)
            showAddDialog = false
        },
    )
    QrScannerDialogHost(
        keepDialog = keepQrScannerDialog,
        visible = showQrScanner,
        onVisibleChange = { showQrScanner = it },
        onLinkScanned = { link ->
            val trimmed = link.trim()
            if (trimmed.isEmpty()) {
                Toast.makeText(context, "Unable to fetch link", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addLink(trimmed)
            }
        },
    )
    ApplySubscriptionRoutingDialogHost(
        visible = uiState.pendingSubscriptionRouting != null,
        onDismiss = viewModel::dismissPendingSubscriptionRouting,
        onConfirm = viewModel::applyPendingSubscriptionRouting,
    )
    RootFallbackDialogHost(
        visible = showRootFallbackDialog,
        onDismiss = { showRootFallbackDialog = false },
        onConfirm = {
            showRootFallbackDialog = false
            startRootlessConnection()
        },
    )
    RemoveSubscriptionDialogHost(
        request = removeSubscriptionRequest,
        onDismiss = { removeSubscriptionRequest = null },
        onConfirm = { subscription ->
            viewModel.deleteSubscription(subscription)
            removeSubscriptionRequest = null
        },
    )
    EditSubscriptionDialogHost(
        subscription = editingSubscription,
        onDismiss = { editingSubscription = null },
        onConfirm = { subscription, name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            viewModel.updateSubscription(
                subscription,
                name,
                url,
                preferJson,
                autoUpdateIntervalHours,
                userAgentMode,
                customUserAgent,
                customHeaders,
            )
            editingSubscription = null
        },
    )
    RawConfigDialogHost(
        config = uiState.runningConfig,
        onDismiss = viewModel::dismissRunningConfig,
        onCopy = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Xray config", uiState.runningConfig.orEmpty()))
        },
    )
}

@Composable
private fun AddSubscriptionDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    if (!visible) return

    AddSubscriptionDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun QrScannerDialogHost(
    keepDialog: Boolean,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onLinkScanned: (String) -> Unit,
) {
    if (!keepDialog) return

    Dialog(
        onDismissRequest = { onVisibleChange(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
            exit = fadeOut(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
        ) {
            QrScannerOverlay(
                onQrCodeScanned = { link ->
                    onVisibleChange(false)
                    onLinkScanned(link)
                },
                onClose = { onVisibleChange(false) },
            )
        }
    }
}

@Composable
private fun ApplySubscriptionRoutingDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    ApplySubscriptionRoutingDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun RootFallbackDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text("Unable to access root on device, falling back to rootless mode") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun RemoveSubscriptionDialogHost(
    request: Pair<SubscriptionEntity, Int>?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity) -> Unit,
) {
    val (subscription, serverCount) = request ?: return

    RemoveSubscriptionDialog(
        serverCount = serverCount,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(subscription) },
    )
}

@Composable
private fun EditSubscriptionDialogHost(
    subscription: SubscriptionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity, String, String, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    subscription ?: return

    EditSubscriptionDialog(
        subscription = subscription,
        onDismiss = onDismiss,
        onConfirm = { name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            onConfirm(subscription, name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders)
        },
    )
}

@Composable
private fun RawConfigDialogHost(
    config: String?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    if (config == null) return

    RawConfigDialog(
        config = config,
        onDismiss = onDismiss,
        onCopy = onCopy,
    )
}

@Composable
private fun collectHomeUiState(viewModel: HomeViewModel): HomeUiState {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val useRootService by viewModel.useRootService.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val serversBySubscription by viewModel.serversBySubscription.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val runningConfig by viewModel.runningConfig.collectAsStateWithLifecycle()
    val defaultPingMethod by viewModel.defaultPingMethod.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val pendingSubscriptionRouting by viewModel.pendingSubscriptionRouting.collectAsStateWithLifecycle()

    return HomeUiState(
        connectionState = connectionState,
        selectedServer = selectedServer,
        selectedServerId = selectedServerId,
        useRootService = useRootService,
        subscriptions = subscriptions,
        serversBySubscription = serversBySubscription,
        isRefreshing = isRefreshing,
        runningConfig = runningConfig,
        defaultPingMethod = defaultPingMethod,
        routingPolicyControl = routingPolicyControl,
        pendingSubscriptionRouting = pendingSubscriptionRouting,
    )
}

@Composable
private fun buildConnectionUiState(
    connectionState: ConnectionState,
    selectedServer: ServerConfig?,
): ConnectionUiState {
    val isConnected = connectionState is ConnectionState.Connected
    val isRestartRequired = connectionState is ConnectionState.RestartRequired
    val isInterfaceBusy = connectionState is ConnectionState.InterfaceBusy
    val isTransitioning = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.ApplyingRoutingChanges ||
        connectionState is ConnectionState.UpdatingRoutingData ||
        connectionState is ConnectionState.Disconnecting
    val selectedServerName = selectedServer?.name ?: "No server selected"

    return ConnectionUiState(
        isConnected = isConnected,
        isRestartRequired = isRestartRequired,
        isInterfaceBusy = isInterfaceBusy,
        isTransitioning = isTransitioning,
        buttonColor = when {
            isConnected || isRestartRequired || isInterfaceBusy -> MaterialTheme.colorScheme.error
            isTransitioning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        displayServerName = (connectionState as? ConnectionState.Connected)?.serverName ?: selectedServerName,
        selectedServerDetail = selectedServer?.endpointSummary() ?: "Select a server below",
    )
}

private data class HomeUiState(
    val connectionState: ConnectionState,
    val selectedServer: ServerConfig?,
    val selectedServerId: Long,
    val useRootService: Boolean,
    val subscriptions: List<SubscriptionEntity>,
    val serversBySubscription: Map<Long, List<ServerListItem>>,
    val isRefreshing: Boolean,
    val runningConfig: String?,
    val defaultPingMethod: PingMethod,
    val routingPolicyControl: RoutingPolicyControl,
    val pendingSubscriptionRouting: SubscriptionAppRouting?,
)

private data class ConnectionUiState(
    val isConnected: Boolean,
    val isRestartRequired: Boolean,
    val isInterfaceBusy: Boolean,
    val isTransitioning: Boolean,
    val buttonColor: Color,
    val displayServerName: String,
    val selectedServerDetail: String,
)

@Composable
private fun ConnectionPanel(
    connectionState: ConnectionState,
    selectedServerName: String,
    selectedServerDetail: String,
    buttonColor: Color,
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

        Spacer(modifier = Modifier.height(24.dp))

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

        Spacer(modifier = Modifier.height(10.dp))
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
private fun EmptySubscriptionsCard(
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
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
            AddSubscriptionActionButton(
                onPasteFromClipboard = onPasteFromClipboard,
                onScanQrCode = onScanQrCode,
                onAddManually = onAddManually,
            )
        }
    }
}

@Composable
private fun AddSubscriptionActionButton(
    modifier: Modifier = Modifier,
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Add new server or subscription")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Paste from clipboard") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_paste_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onPasteFromClipboard()
                },
            )
            DropdownMenuItem(
                text = { Text("Scan QR code") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_scanner_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onScanQrCode()
                },
            )
            DropdownMenuItem(
                text = { Text("Add manually") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onAddManually()
                },
            )
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    servers: List<ServerListItem>,
    selectedServerId: Long,
    defaultPingMethod: PingMethod,
    canApplyRouting: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onDefaultPingMethodSelected: (PingMethod) -> Unit,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
    onServerSelected: (Long) -> Unit,
    onTestLatency: (ServerEntity) -> Unit,
) {
    val metadata = remember(
        subscription.announce,
        subscription.subscriptionUploadBytes,
        subscription.subscriptionDownloadBytes,
        subscription.subscriptionTotalBytes,
        subscription.subscriptionExpireAt,
    ) {
        buildSubscriptionMetadataUiState(subscription)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubscriptionHeader(
                subscription = subscription,
                metadata = metadata,
                defaultPingMethod = defaultPingMethod,
                onRefresh = onRefresh,
                onTestAll = onTestAll,
                onDefaultPingMethodSelected = onDefaultPingMethodSelected,
                onDelete = onDelete,
                onEdit = onEdit,
                canApplyRouting = canApplyRouting,
                onApplyRouting = onApplyRouting,
                onDescriptionHiddenChange = onDescriptionHiddenChange,
            )
            if (metadata.hasVisibleSubscriptionSection()) {
                Spacer(modifier = Modifier.height(SubscriptionBlockGap))
            }
            SubscriptionMetadataSection(
                subscription = subscription,
                metadata = metadata,
            )

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
                            contentPadding = ServerRowDefaults.contentPadding,
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
    metadata: SubscriptionMetadataUiState,
) {
    val limitedTraffic = metadata.traffic?.takeUnless { it.quotaText == null }

    val hasVisibleMetadata = metadata.announcement.isNotEmpty() ||
        limitedTraffic != null ||
        metadata.expiry != null
    if (!hasVisibleMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180))
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(SubscriptionBlockGap),
    ) {
        AnimatedVisibility(
            visible = metadata.announcement.isNotEmpty() && !subscription.descriptionHidden,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
            Column {
                SubscriptionDescriptionText(description = metadata.announcement)
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        if (limitedTraffic != null || metadata.expiry != null) {
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
                    limitedTraffic?.let { trafficState ->
                        SubscriptionTrafficProgress(state = trafficState)
                    }

                    val detailText = limitedTraffic?.detailText(metadata.expiry)
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
                }
            }
        }
    }
}

private fun SubscriptionMetadataUiState.hasVisibleSubscriptionSection(): Boolean {
    val limitedTraffic = traffic?.takeUnless { it.quotaText == null }
    return announcement.isNotEmpty() ||
        limitedTraffic != null ||
        expiry != null
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionEntity,
    metadata: SubscriptionMetadataUiState,
    defaultPingMethod: PingMethod,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onDefaultPingMethodSelected: (PingMethod) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    canApplyRouting: Boolean,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showPingMethodDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val supportUrl = subscription.supportUrl?.trim().orEmpty()
    val hasDescription = subscription.announce?.trim()?.isNotEmpty() == true
    val consumedTrafficText = metadata.traffic
        ?.takeIf { it.quotaText == null }
        ?.downloadText

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    role = Role.Button,
                    onClick = {
                        if (hasDescription) {
                            onDescriptionHiddenChange(!subscription.descriptionHidden)
                        }
                    },
                    onLongClick = onEdit,
                ),
        ) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!consumedTrafficText.isNullOrBlank()) {
                Text(
                    text = remember(consumedTrafficText) { consumedTrafficText.withMetadataEmphasis() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh ${subscription.name}")
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onTestAll,
                    onLongClick = { showPingMethodDialog = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = "Test ${subscription.name} with ${defaultPingMethod.value}",
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Subscription menu")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.edit_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                if (supportUrl.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Support") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.support_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            uriHandler.openUri(supportUrl)
                        },
                    )
                }
                if (hasDescription) {
                    DropdownMenuItem(
                        text = {
                            Text(if (subscription.descriptionHidden) "Show description" else "Hide description")
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(
                                    if (subscription.descriptionHidden) {
                                        R.drawable.visibility_24px
                                    } else {
                                        R.drawable.visibility_off_24px
                                    },
                                ),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDescriptionHiddenChange(!subscription.descriptionHidden)
                        },
                    )
                }
                if (canApplyRouting) {
                    DropdownMenuItem(
                        text = { Text("Apply routing") },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.cloud_download_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onApplyRouting()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.delete_forever_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }

    if (showPingMethodDialog) {
        PingMethodDialog(
            selectedMethod = defaultPingMethod,
            onDismiss = { showPingMethodDialog = false },
            onSelected = { method ->
                onDefaultPingMethodSelected(method)
                showPingMethodDialog = false
            },
        )
    }
}

@Composable
private fun PingMethodDialog(
    selectedMethod: PingMethod,
    onDismiss: () -> Unit,
    onSelected: (PingMethod) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Choose method") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PingMethod.entries.forEach { method ->
                    PingMethodRow(
                        method = method,
                        selected = method == selectedMethod,
                        onSelected = { onSelected(method) },
                    )
                }
            }
        },
    )
}

@Composable
private fun PingMethodRow(
    method: PingMethod,
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = method.label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = method.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubscriptionDescriptionText(description: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val uriHandler = LocalUriHandler.current
    var pendingUrl by remember(description) { mutableStateOf<String?>(null) }
    val annotatedDescription = remember(description, linkColor) {
        description.withUrlLinks(linkColor) { url ->
            pendingUrl = url
        }
    }

    SelectionContainer {
        Text(
            text = annotatedDescription,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }

    pendingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text("Open link?") },
            text = {
                SelectionContainer {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUrl = null
                        uriHandler.openUri(url)
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ServerListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
    contentPadding: PaddingValues = ServerRowDefaults.contentPadding,
) {
    val latency = server.latency
    val latencyMs = latency?.latencyMs
    val latencyText = latency?.let {
        when {
            it.latencyMs == LATENCY_TESTING -> "Testing..."
            it.latencyMs < 0 -> "N/A"
            else -> "${it.latencyMs}ms"
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
                .padding(contentPadding),
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

private object ServerRowDefaults {
    val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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

private fun String.withUrlLinks(
    linkColor: androidx.compose.ui.graphics.Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )

    subscriptionUrlRegex.findAll(this@withUrlLinks).forEach { match ->
        val start = match.range.first
        val end = this@withUrlLinks.trimmedUrlEnd(match)
        if (end <= start) return@forEach

        if (cursor < start) {
            append(this@withUrlLinks.substring(cursor, start))
        }

        val url = this@withUrlLinks.substring(start, end)
        val linkStart = length
        append(url)
        addLink(
            LinkAnnotation.Clickable(
                tag = url.normalizedSubscriptionUrl(),
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { link ->
                    (link as? LinkAnnotation.Clickable)?.tag?.let(onUrlClick)
                },
            ),
            start = linkStart,
            end = length,
        )
        cursor = end
    }

    if (cursor < this@withUrlLinks.length) {
        append(this@withUrlLinks.substring(cursor))
    }
}

private fun String.trimmedUrlEnd(match: MatchResult): Int {
    var end = match.range.last + 1
    while (end > match.range.first && this[end - 1] in trailingUrlPunctuation) {
        end--
    }
    return end
}

private fun String.normalizedSubscriptionUrl(): String = if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
    this
} else {
    "https://$this"
}

private val subscriptionUrlRegex = Regex(
    pattern = """(?i)(?<![@\w])(?:https?://[^\s<>"']+|(?:www\.|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,})(?:/[^\s<>"']*)?)""",
)
private val trailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
private val SubscriptionBlockGap = 6.dp
private const val QR_SCANNER_TRANSITION_MS = 180

private fun Context.clipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSubscriptionDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    var url by remember(subscription.id) { mutableStateOf(subscription.url) }
    var preferJson by remember(subscription.id) { mutableStateOf(subscription.preferJson ?: true) }
    var autoUpdateIntervalHours by remember(subscription.id) {
        mutableStateOf(subscription.autoUpdateIntervalHours)
    }
    var autoUpdateExpanded by remember(subscription.id) { mutableStateOf(false) }
    var userAgentMode by remember(subscription.id) {
        mutableStateOf(SubscriptionUserAgentMode.fromValue(subscription.userAgentMode))
    }
    var customUserAgent by remember(subscription.id) {
        mutableStateOf(subscription.customUserAgent.orEmpty())
    }
    var customHeaders by remember(subscription.id) {
        mutableStateOf(subscription.customHeaders.orEmpty())
    }
    val hasChanges = name.trim() != subscription.name ||
        url.trim() != subscription.url ||
        preferJson != (subscription.preferJson ?: true) ||
        autoUpdateIntervalHours != subscription.autoUpdateIntervalHours ||
        userAgentMode != SubscriptionUserAgentMode.fromValue(subscription.userAgentMode) ||
        customUserAgent.trim().ifBlank { null } != subscription.customUserAgent ||
        customHeaders.trim().ifBlank { null } != subscription.customHeaders
    val selectedAutoUpdateOption = autoUpdateIntervalOptions.firstOrNull {
        it.intervalHours == autoUpdateIntervalHours
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit subscription") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionFetchTypeDropdown(
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                ExposedDropdownMenuBox(
                    expanded = autoUpdateExpanded,
                    onExpandedChange = { autoUpdateExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedAutoUpdateOption?.label ?: formatAutoUpdateInterval(autoUpdateIntervalHours),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Auto update") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = autoUpdateExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = autoUpdateExpanded,
                        onDismissRequest = { autoUpdateExpanded = false },
                    ) {
                        autoUpdateIntervalOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    autoUpdateIntervalHours = option.intervalHours
                                    autoUpdateExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionUserAgentSection(
                    selectedMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.trim(), url.trim(), preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders)
                },
                enabled = url.isNotBlank() && hasChanges,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ApplySubscriptionRoutingDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply subscription routing?") },
        text = { Text("This action will override existing routing setup.") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Yes, apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("No, thanks")
            }
        },
    )
}

@Composable
private fun RemoveSubscriptionDialog(
    serverCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove subscription?") },
        text = {
            if (serverCount > 1) {
                Text(
                    buildAnnotatedString {
                        append("All servers from this subscription ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("will be removed")
                        }
                        append(".")
                    },
                )
            } else {
                Text(
                    buildAnnotatedString {
                        append("Server from this subscription ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("will be removed")
                        }
                        append(".")
                    },
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var preferJson by remember { mutableStateOf(true) }
    var userAgentMode by remember { mutableStateOf(SubscriptionUserAgentMode.default) }
    var customUserAgent by remember { mutableStateOf("") }
    var customHeaders by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add manually") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
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
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionFetchTypeDropdown(
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionUserAgentSection(
                    selectedMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim(), preferJson, userAgentMode, customUserAgent, customHeaders) },
                enabled = url.isNotBlank(),
            ) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionFetchTypeDropdown(
    preferJson: Boolean,
    onPreferJsonChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = if (preferJson) "JSON first" else "Compatibility mode",
            onValueChange = {},
            readOnly = true,
            label = { Text("Fetch type") },
            supportingText = {
                Text(
                    if (preferJson) {
                        "Try the /json endpoint first, then fall back to the saved URL"
                    } else {
                        "Fetch directly from the saved URL"
                    },
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(true to "JSON first", false to "Compatibility mode").forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onPreferJsonChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionUserAgentSection(
    selectedMode: SubscriptionUserAgentMode,
    customUserAgent: String,
    customHeaders: String,
    onModeChange: (SubscriptionUserAgentMode) -> Unit,
    onCustomUserAgentChange: (String) -> Unit,
    onCustomHeadersChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedMode.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("User-Agent") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SubscriptionUserAgentMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mode.label)
                            Text(
                                mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
    if (selectedMode == SubscriptionUserAgentMode.CUSTOM) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customUserAgent,
            onValueChange = onCustomUserAgentChange,
            label = { Text("User-Agent") },
            placeholder = { Text("e.g. Happ/3.23.0") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customHeaders,
            onValueChange = onCustomHeadersChange,
            label = { Text("Headers") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("One per line, e.g. X-Hwid: 0123456789abcdef") },
        )
    }
}
