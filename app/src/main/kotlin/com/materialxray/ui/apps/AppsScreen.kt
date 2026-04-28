package com.materialxray.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("App Routing") },
            windowInsets = WindowInsets(0.dp),
            actions = {
                IconButton(onClick = { viewModel.routeAllDirect() }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Route all apps direct")
                }
                IconButton(onClick = { viewModel.resetAllToDefault() }) {
                    Icon(Icons.Default.Deselect, contentDescription = "Reset all apps to default")
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
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = option.title,
                                    fontWeight = if (option.key == app.routeKey) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = option.key == app.routeKey,
                                    onClick = null,
                                )
                            },
                            modifier = Modifier.clickable { onSelected(option) },
                        )
                    }
                }
            }
        },
    )
}
