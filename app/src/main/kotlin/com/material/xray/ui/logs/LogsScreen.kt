package com.material.xray.ui.logs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.service.LogEntry
import com.material.xray.service.LogSource
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private enum class LogFilter(val label: String) { ALL("All"), APP("App"), XRAY("Xray") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: LogsViewModel = hiltViewModel()) {
    val allEntries by viewModel.entries.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { LogFilter.entries.size })
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val filter = LogFilter.entries[pagerState.currentPage]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    IconButton(onClick = {
                        viewModel.copyAll()
                        Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
        bottomBar = {
            LogFilterSelector(
                selectedFilter = filter,
                onSelected = { index ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
            )
        },
    ) { padding ->
        val fadeColor = MaterialTheme.colorScheme.surface
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
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                )
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

@Composable
private fun LogFilterSelector(
    selectedFilter: LogFilter,
    onSelected: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LogFilter.entries.forEachIndexed { index, filter ->
            SegmentedButton(
                selected = selectedFilter == filter,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index, LogFilter.entries.size),
            ) {
                Text(filter.label)
            }
        }
    }
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
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
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
            .padding(vertical = 2.dp),
    )
}
