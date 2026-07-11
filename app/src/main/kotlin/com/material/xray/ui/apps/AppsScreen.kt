package com.material.xray.ui.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.model.RoutingPolicyControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBypassContent(viewModel: AppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val routeOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val appSpecificServerNoteShown by viewModel.appSpecificServerNoteShown.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val iconSize = 40.dp
    val iconPixelSize = remember(density) { with(density) { iconSize.roundToPx() } }
    val visibleRouteOptions by remember(routeOptions) {
        derivedStateOf {
            if (routeOptions.count { it.kind == AppRouteKind.SERVER } == 1) {
                routeOptions.filterNot { it.kind == AppRouteKind.SERVER }
            } else {
                routeOptions
            }
        }
    }
    var editingApp by remember { mutableStateOf<AppItem?>(null) }
    var pendingManualEdit by remember { mutableStateOf<AppItem?>(null) }
    var pendingSpecificServerRoute by remember { mutableStateOf<AppRouteSelection?>(null) }
    val pullToRefreshState = rememberPullToRefreshState()
    val showInitialLoading = isLoadingApps && apps.isEmpty()

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingManualEdit?.let { app ->
                pendingManualEdit = null
                editingApp = app
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isLoadingApps && !showInitialLoading,
        onRefresh = { if (!isLoadingApps) viewModel.refreshApps() },
        modifier = Modifier.fillMaxSize(),
        state = pullToRefreshState,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showInitialLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
                SubscriptionRoutingBanner(automaticRoutingProviderName)
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 8.dp),
            )

            val fadeColor = MaterialTheme.colorScheme.surface
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = apps,
                        key = { it.appKey },
                        contentType = { "app" },
                    ) { app ->
                        ListItem(
                            headlineContent = { Text(app.name) },
                            supportingContent = {
                                Text(
                                    text = if (app.workProfile) {
                                        "${app.packageName} • ${app.profileLabel}"
                                    } else {
                                        app.packageName
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                val iconBitmap = remember(app.appKey, app.icon, iconPixelSize) {
                                    app.icon?.toBitmap(iconPixelSize, iconPixelSize)?.asImageBitmap()
                                }
                                iconBitmap?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(iconSize),
                                    )
                                }
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.width(176.dp),
                                ) {
                                    Text(
                                        text = app.routeTitle,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
                                    pendingManualEdit = app
                                } else {
                                    editingApp = app
                                }
                            },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(fadeColor, fadeColor.copy(alpha = 0f)),
                            ),
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(fadeColor.copy(alpha = 0f), fadeColor),
                            ),
                        ),
                )
            }
        }
    }

    if (pendingManualEdit != null) {
        AutomaticRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingManualEdit = null },
            onSwitchToManual = {
                viewModel.switchToManualRouting()
            },
        )
    }

    editingApp?.let { app ->
        AppRoutePickerDialog(
            app = app,
            routeOptions = visibleRouteOptions,
            singleServerRouteHidden = visibleRouteOptions.size != routeOptions.size,
            onDismiss = { editingApp = null },
            onSelected = { option ->
                editingApp = null
                if (option.kind == AppRouteKind.SERVER && !appSpecificServerNoteShown) {
                    pendingSpecificServerRoute = AppRouteSelection(app, option)
                } else {
                    viewModel.setAppRoute(app, option)
                }
            },
        )
    }

    pendingSpecificServerRoute?.let { selection ->
        SpecificServerRouteNoteDialog(
            onDismiss = { pendingSpecificServerRoute = null },
            onConfirm = {
                viewModel.setAppSpecificServerNoteShown()
                viewModel.setAppRoute(selection.app, selection.option)
                pendingSpecificServerRoute = null
            },
        )
    }
}

@Composable
private fun SubscriptionRoutingBanner(providerName: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "App routing is managed by $providerName",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AppRoutingMenuActions(viewModel: AppsViewModel = hiltViewModel()) {
    val showSystemApps by viewModel.showSystemApps.collectAsStateWithLifecycle()
    val showWorkProfileApps by viewModel.showWorkProfileApps.collectAsStateWithLifecycle()
    val hasWorkProfileApps by viewModel.hasWorkProfileApps.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    var pendingBulkAction by remember { mutableStateOf<BulkAppRouteAction?>(null) }
    var pendingAutomaticBulkAction by remember { mutableStateOf<BulkAppRouteAction?>(null) }
    var appRoutingMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingAutomaticBulkAction?.let { action ->
                pendingAutomaticBulkAction = null
                pendingBulkAction = action
            }
        }
    }

    fun requestBulkAction(action: BulkAppRouteAction) {
        if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
            pendingAutomaticBulkAction = action
        } else {
            pendingBulkAction = action
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { viewModel.refreshApps() },
            enabled = !isLoadingApps,
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh apps")
        }
        Box {
            IconButton(onClick = { appRoutingMenuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "App routing menu")
            }
            DropdownMenu(
                expanded = appRoutingMenuExpanded,
                onDismissRequest = { appRoutingMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Reset to defaults") },
                    onClick = {
                        appRoutingMenuExpanded = false
                        requestBulkAction(BulkAppRouteAction.ResetToDefaults)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Bypass all apps") },
                    onClick = {
                        appRoutingMenuExpanded = false
                        requestBulkAction(BulkAppRouteAction.BypassAllApps)
                    },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Show system apps") },
                    trailingIcon = {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = null,
                        )
                    },
                    onClick = {
                        viewModel.setShowSystemApps(!showSystemApps)
                    },
                )
                if (hasWorkProfileApps) {
                    DropdownMenuItem(
                        text = { Text("Show work profile apps") },
                        trailingIcon = {
                            Checkbox(
                                checked = showWorkProfileApps,
                                onCheckedChange = null,
                            )
                        },
                        onClick = {
                            viewModel.setShowWorkProfileApps(!showWorkProfileApps)
                        },
                    )
                }
            }
        }
    }

    if (pendingAutomaticBulkAction != null) {
        AutomaticRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingAutomaticBulkAction = null },
            onSwitchToManual = {
                viewModel.switchToManualRouting()
            },
        )
    }

    pendingBulkAction?.let { action ->
        BulkAppRouteConfirmationDialog(
            action = action,
            onDismiss = { pendingBulkAction = null },
            onConfirm = {
                when (action) {
                    BulkAppRouteAction.ResetToDefaults -> viewModel.resetAllToDefault()
                    BulkAppRouteAction.BypassAllApps -> viewModel.bypassAllApps()
                }
                pendingBulkAction = null
            },
        )
    }
}

private enum class BulkAppRouteAction {
    ResetToDefaults,
    BypassAllApps,
}

private data class AppRouteSelection(
    val app: AppItem,
    val option: AppRouteOption,
)

@Composable
private fun AutomaticRoutingDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "App routing is automatic",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                buildAnnotatedString {
                    append("Your current app routing rules are provided by ")
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(providerName)
                    pop()
                    append(" and are updated automatically. Editing them will disable automatic updates.")
                },
            )
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSwitchToManual,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Switch to manual mode")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Leave as is")
                }
            }
        },
    )
}

@Composable
private fun SpecificServerRouteNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Specific server routing") },
        text = {
            Text(
                "Custom routing rules are not applied when an app is routed to a specific server. " +
                    "Use Default outbound or Default selected server to keep Routing rules active.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK")
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
private fun BulkAppRouteConfirmationDialog(
    action: BulkAppRouteAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        BulkAppRouteAction.ResetToDefaults -> "Reset to defaults?"
        BulkAppRouteAction.BypassAllApps -> "Bypass all apps?"
    }
    val description = when (action) {
        BulkAppRouteAction.ResetToDefaults -> buildBulkActionDescription(
            prefix = "All apps routing settings will be ",
            emphasized = "reset to the default selected config.",
        )
        BulkAppRouteAction.BypassAllApps -> buildBulkActionDescription(
            prefix = "All apps routing settings will be ",
            emphasized = "set to \"Not proxied\".",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun buildBulkActionDescription(
    prefix: String,
    emphasized: String,
) = buildAnnotatedString {
    append(prefix)
    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    append(emphasized)
    pop()
}

@Composable
private fun AppRoutePickerDialog(
    app: AppItem,
    routeOptions: List<AppRouteOption>,
    singleServerRouteHidden: Boolean,
    onDismiss: () -> Unit,
    onSelected: (AppRouteOption) -> Unit,
) {
    var query by remember(app.appKey) { mutableStateOf("") }
    val filteredOptions by remember(routeOptions, query) {
        derivedStateOf {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                routeOptions
            } else {
                routeOptions.filter { option ->
                    option.title.contains(trimmed, ignoreCase = true) ||
                        option.description.contains(trimmed, ignoreCase = true)
                }
            }
        }
    }
    val presetOptions by remember(filteredOptions) {
        derivedStateOf { filteredOptions.filter { it.kind != AppRouteKind.SERVER } }
    }
    val serverOptions by remember(filteredOptions) {
        derivedStateOf { filteredOptions.filter { it.kind == AppRouteKind.SERVER } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Route ${app.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (routeOptions.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search configurations") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    items(
                        items = presetOptions,
                        key = { it.key },
                        contentType = { "routeOption" },
                    ) { option ->
                        RouteOptionRow(
                            option = option,
                            selected = option.key == app.routeKey ||
                                (singleServerRouteHidden && app.routeKind == AppRouteKind.SERVER && option.kind == AppRouteKind.DEFAULT),
                            onSelected = { onSelected(option) },
                        )
                    }
                    if (presetOptions.isNotEmpty() && serverOptions.isNotEmpty()) {
                        item(contentType = "routeOptionDivider") {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                    items(
                        items = serverOptions,
                        key = { it.key },
                        contentType = { "routeOption" },
                    ) { option ->
                        RouteOptionRow(
                            option = option,
                            selected = option.key == app.routeKey ||
                                (singleServerRouteHidden && app.routeKind == AppRouteKind.SERVER && option.kind == AppRouteKind.DEFAULT),
                            onSelected = { onSelected(option) },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun RouteOptionRow(
    option: AppRouteOption,
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
        RouteOptionIndicator(selected = selected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = option.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RouteOptionIndicator(selected: Boolean) {
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
