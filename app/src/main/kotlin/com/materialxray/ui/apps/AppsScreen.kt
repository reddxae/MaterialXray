package com.materialxray.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBypassContent(viewModel: AppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("App Bypass") },
            windowInsets = WindowInsets(0.dp),
            actions = {
                IconButton(onClick = { viewModel.excludeAll() }) {
                    Icon(Icons.Default.SelectAll, contentDescription = "Exclude all")
                }
                IconButton(onClick = { viewModel.includeAll() }) {
                    Icon(Icons.Default.Deselect, contentDescription = "Include all")
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
                    supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = {
                        val iconBitmap = remember(app.packageName, app.icon) {
                            app.icon?.toBitmap(40, 40)?.asImageBitmap()
                        }
                        iconBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    },
                    trailingContent = {
                        Switch(checked = app.isExcluded, onCheckedChange = { viewModel.toggleExclude(app) })
                    },
                )
            }
        }
    }
}
