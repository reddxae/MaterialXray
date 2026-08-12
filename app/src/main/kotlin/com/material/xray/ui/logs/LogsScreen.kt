package com.material.xray.ui.logs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.service.LogEntry
import com.material.xray.service.LogSource
import com.material.xray.ui.components.ScrollFadeEdges
import com.material.xray.ui.components.SegmentedTabRow
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class LogFilter(@param:StringRes val labelRes: Int) {
    ALL(R.string.logs_filter_all),
    APP(R.string.logs_filter_app),
    XRAY(R.string.logs_filter_xray),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val allEntries by viewModel.entries.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { LogFilter.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    val saveLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { destination ->
        if (destination != null) {
            coroutineScope.launch {
                isExporting = true
                try {
                    viewModel.saveLogs(destination)
                    Toast.makeText(context, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                } catch (_: IOException) {
                    Toast.makeText(context, R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    isExporting = false
                }
            }
        }
    }
    val selectedFilter by remember {
        derivedStateOf { LogFilter.entries[pagerState.targetPage] }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onVisible()
                Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.navigation_logs)) },
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    Box {
                        IconButton(
                            enabled = !isExporting,
                            onClick = { showExportMenu = true },
                        ) {
                            Icon(Icons.Default.Save, contentDescription = stringResource(R.string.logs_export))
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_save_to_file)) },
                                leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    try {
                                        saveLogsLauncher.launch(LOG_EXPORT_FILE_NAME)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(
                                            context,
                                            R.string.logs_save_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.logs_share)) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    showExportMenu = false
                                    coroutineScope.launch {
                                        isExporting = true
                                        try {
                                            shareLogFile(context, viewModel.createShareFile())
                                        } catch (_: IOException) {
                                            Toast.makeText(
                                                context,
                                                R.string.logs_share_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } catch (_: IllegalArgumentException) {
                                            Toast.makeText(
                                                context,
                                                R.string.logs_share_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } catch (_: ActivityNotFoundException) {
                                            Toast.makeText(
                                                context,
                                                R.string.logs_share_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        } finally {
                                            isExporting = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                    IconButton(onClick = {
                        viewModel.copyAll()
                        Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy_all))
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear))
                    }
                },
            )
        },
        bottomBar = {
            SegmentedTabRow(
                labels = LogFilter.entries.map { stringResource(it.labelRes) },
                selectedIndex = LogFilter.entries.indexOf(selectedFilter),
                onSelected = { index ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageFilter = LogFilter.entries[page]
                val entries = remember(allEntries, pageFilter) {
                    allEntries.filterBy(pageFilter)
                }
                LogEntriesList(
                    entries = entries,
                    onCopy = { entry ->
                        viewModel.copyEntry(entry)
                        Toast.makeText(context, R.string.log_entry_copied, Toast.LENGTH_SHORT).show()
                    },
                )
            }
            ScrollFadeEdges()
        }
    }
}

private fun shareLogFile(context: Context, uri: Uri) {
    val label = context.getString(
        R.string.clipboard_label_logs,
        context.getString(R.string.app_name),
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, label)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(label, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.logs_share)))
}

@Composable
private fun LogEntriesList(
    entries: List<LogEntry>,
    onCopy: (LogEntry) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(
            items = entries,
            key = { it.id },
            contentType = { it.source },
        ) { entry ->
            LogEntryRow(entry = entry, onCopy = { onCopy(entry) })
        }
    }
}

private fun List<LogEntry>.filterBy(filter: LogFilter): List<LogEntry> = when (filter) {
    LogFilter.ALL -> this
    LogFilter.APP -> filter { it.source == LogSource.APP }
    LogFilter.XRAY -> filter { it.source == LogSource.XRAY }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogEntryRow(entry: LogEntry, onCopy: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    val time = remember(entry.timestamp) { timeFormat.format(Date(entry.timestamp)) }
    val isError = entry.message.contains("error", ignoreCase = true) ||
        entry.message.contains("fail", ignoreCase = true)
    val messageColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Text(
        text = "$time [${entry.source.name}] ${entry.message}",
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = messageColor,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
