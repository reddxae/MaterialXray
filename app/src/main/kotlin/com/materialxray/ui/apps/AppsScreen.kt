package com.materialxray.ui.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBypassContent(viewModel: AppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val routeOptions by viewModel.routeOptions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val iconSize = 40.dp
    val iconPixelSize = remember(density) { with(density) { iconSize.roundToPx() } }
    var editingApp by remember { mutableStateOf<AppItem?>(null) }
    var pendingBulkAction by remember { mutableStateOf<BulkAppRouteAction?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("App Routing") },
            windowInsets = WindowInsets(0.dp),
            actions = {
                IconButton(onClick = { pendingBulkAction = BulkAppRouteAction.ClearProxiedApps }) {
                    Icon(Icons.Default.Deselect, contentDescription = "Route all apps direct")
                }
                IconButton(onClick = { pendingBulkAction = BulkAppRouteAction.ProxyAllApps }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Reset all apps to default")
                }
            },
        )

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            label = { Text("Search apps") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(
                items = apps,
                key = { it.packageName },
                contentType = { "app" },
            ) { app ->
                ListItem(
                    headlineContent = { Text(app.name) },
                    supportingContent = {
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        val iconBitmap = remember(app.packageName, app.icon, iconPixelSize) {
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
                            modifier = Modifier.widthIn(max = 176.dp),
                        ) {
                            Text(
                                text = app.routeTitle,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    },
                    modifier = Modifier.clickable { editingApp = app },
                )
            }
        }
    }

    editingApp?.let { app ->
        AppRoutePickerDialog(
            app = app,
            routeOptions = routeOptions,
            onDismiss = { editingApp = null },
            onSelected = { option ->
                viewModel.setAppRoute(app, option)
                editingApp = null
            },
        )
    }

    pendingBulkAction?.let { action ->
        BulkAppRouteConfirmationDialog(
            action = action,
            onDismiss = { pendingBulkAction = null },
            onConfirm = {
                when (action) {
                    BulkAppRouteAction.ClearProxiedApps -> viewModel.routeAllDirect()
                    BulkAppRouteAction.ProxyAllApps -> viewModel.resetAllToDefault()
                }
                pendingBulkAction = null
            },
        )
    }
}

private enum class BulkAppRouteAction {
    ClearProxiedApps,
    ProxyAllApps,
}

@Composable
private fun BulkAppRouteConfirmationDialog(
    action: BulkAppRouteAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        BulkAppRouteAction.ClearProxiedApps -> "Clear proxied apps list?"
        BulkAppRouteAction.ProxyAllApps -> "Proxy all apps?"
    }
    val description = when (action) {
        BulkAppRouteAction.ClearProxiedApps -> buildBulkActionDescription("Not proxied")
        BulkAppRouteAction.ProxyAllApps -> buildBulkActionDescription("default selected config")
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

private fun buildBulkActionDescription(value: String) = buildAnnotatedString {
    append("Manual settings for all apps will be overridden to ")
    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    append("\"")
    append(value)
    append("\"")
    pop()
    append(".")
}

@Composable
private fun AppRoutePickerDialog(
    app: AppItem,
    routeOptions: List<AppRouteOption>,
    onDismiss: () -> Unit,
    onSelected: (AppRouteOption) -> Unit,
) {
    var query by remember(app.packageName) { mutableStateOf("") }
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
                        items = filteredOptions,
                        key = { it.key },
                        contentType = { "routeOption" },
                    ) { option ->
                        RouteOptionRow(
                            option = option,
                            selected = option.key == app.routeKey,
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
