package com.material.xray.ui.configviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.ui.components.ScrolledTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigViewerScreen(
    request: ConfigViewerRequest,
    onBack: () -> Unit,
    viewModel: ConfigViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(request) { viewModel.load(request) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val title = request.title()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val copiedMessage = stringResource(R.string.config_viewer_copied)
    val clipboardLabel = stringResource(R.string.config_viewer_clipboard_label)
    val copyable = uiState.copyableText()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // The viewer is drawn over the whole app, past the bottom navigation bar, so nothing else
        // is left to keep the content clear of the system navigation bar.
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            ScrolledTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                showLogo = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.config_viewer_back),
                        )
                    }
                },
                actions = {
                    if (copyable != null) {
                        IconButton(
                            onClick = {
                                context.copyToClipboard(clipboardLabel, copyable)
                                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.config_viewer_copy),
                            )
                        }
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
            when (val state = uiState) {
                // Reading a local row or file is quick enough that a spinner would only ever be a
                // flash of noise between the fade-in and the content.
                ConfigViewerUiState.Loading -> Unit
                is ConfigViewerUiState.Message -> Text(
                    text = stringResource(state.textRes),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ConfigViewerUiState.JsonDocument -> JsonDocumentContent(state)
                is ConfigViewerUiState.Params -> ParamsList(state)
            }
        }
    }
}

@Composable
private fun ConfigViewerRequest.title(): String = when (this) {
    is ConfigViewerRequest.Server -> name
    ConfigViewerRequest.Running -> stringResource(R.string.home_active_xray_config_title)
}

private fun ConfigViewerUiState.copyableText(): String? = when (this) {
    is ConfigViewerUiState.JsonDocument -> prettyJson
    is ConfigViewerUiState.Params -> rawLink.takeIf { it.isNotBlank() }
    else -> null
}

@Composable
private fun JsonDocumentContent(state: ConfigViewerUiState.JsonDocument) {
    val colors = rememberJsonSyntaxColors()
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerLow

    // One Text per line inside a lazy list. The generated config runs to a couple of thousand
    // lines, and laying that out as a single paragraph costs enough on the first frame to eat the
    // fade-in animation and to make every selection drag stutter. The trade is that a selection
    // only reaches the lines currently composed; the copy action covers the whole document.
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (state.showDisclaimer) {
                item(contentType = "banner") {
                    NotFinalConfigBanner()
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
            item(contentType = "codeEdge") {
                CodeBlockEdge(codeBackground, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            }
            items(state.lines, contentType = { "line" }) { tokens ->
                JsonLine(tokens, colors, codeBackground)
            }
            item(contentType = "codeEdge") {
                CodeBlockEdge(codeBackground, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            }
        }
    }
}

@Composable
private fun JsonLine(tokens: List<JsonToken>, colors: JsonSyntaxColors, background: Color) {
    val line = remember(tokens, colors) {
        buildAnnotatedString {
            tokens.forEach { token ->
                // Indentation is most of the token count. Leaving it on the base colour keeps
                // pointless spans out of the text layout.
                val style = colors.spanOf(token.kind)
                if (style == null) append(token.text) else withStyle(style) { append(token.text) }
            }
        }
    }

    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 12.dp),
        color = colors.plain,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

@Composable
private fun CodeBlockEdge(color: Color, shape: RoundedCornerShape) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .background(color, shape),
    )
}

@Composable
private fun NotFinalConfigBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Column {
                Text(
                    text = stringResource(R.string.config_viewer_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.config_viewer_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ParamsList(state: ConfigViewerUiState.Params) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.sections, contentType = { "section" }) { section ->
            ParamSectionCard(section)
        }
        if (state.rawLink.isNotBlank()) {
            item(contentType = "rawLink") {
                RawLinkCard(state.rawLink)
            }
        }
    }
}

@Composable
private fun ParamSectionCard(section: ParamSection) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            section.rows.forEach { row -> ParamRowItem(row) }
        }
    }
}

@Composable
private fun ParamRowItem(row: ParamRow) {
    var revealed by rememberSaveable(row.label, row.value) { mutableStateOf(false) }
    val showValue = !row.isSecret || revealed

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label.resolve(),
            modifier = Modifier.width(112.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = if (showValue) row.value else MASKED_VALUE,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (row.isSecret) {
            IconButton(onClick = { revealed = !revealed }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (revealed) R.string.config_viewer_hide_secret else R.string.config_viewer_reveal_secret,
                    ),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ParamLabel.resolve(): String = when (this) {
    is ParamLabel.Resource -> stringResource(id)
    is ParamLabel.Key -> text
}

@Composable
private fun RawLinkCard(rawLink: String) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.config_viewer_section_raw_link),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SelectionContainer {
                Text(
                    text = rawLink,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Fixed palettes rather than theme colours. The app uses Android dynamic colour on API 31+, and a
 * monochrome wallpaper palette would collapse keys, strings and numbers into a single hue.
 */
@Composable
private fun rememberJsonSyntaxColors(): JsonSyntaxColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) { if (dark) DarkJsonSyntaxColors else LightJsonSyntaxColors }
}

private data class JsonSyntaxColors(
    val key: Color,
    val stringValue: Color,
    val number: Color,
    val literal: Color,
    val punctuation: Color,
    val plain: Color,
) {
    private val spans = mapOf(
        JsonTokenKind.Key to SpanStyle(color = key),
        JsonTokenKind.StringValue to SpanStyle(color = stringValue),
        JsonTokenKind.Number to SpanStyle(color = number),
        JsonTokenKind.Literal to SpanStyle(color = literal),
        JsonTokenKind.Punctuation to SpanStyle(color = punctuation),
    )

    /** Null for tokens already covered by the base text colour. */
    fun spanOf(kind: JsonTokenKind): SpanStyle? = spans[kind]
}

private val LightJsonSyntaxColors = JsonSyntaxColors(
    key = Color(0xFF0B57D0),
    stringValue = Color(0xFF1B7F3B),
    number = Color(0xFFA6412A),
    literal = Color(0xFF7A3E9D),
    punctuation = Color(0xFF6B6B6B),
    plain = Color(0xFF1F1F1F),
)

private val DarkJsonSyntaxColors = JsonSyntaxColors(
    key = Color(0xFF8AB4F8),
    stringValue = Color(0xFF7EC699),
    number = Color(0xFFE8A87C),
    literal = Color(0xFFC792EA),
    punctuation = Color(0xFF9AA0A6),
    plain = Color(0xFFE3E3E3),
)

private fun Context.copyToClipboard(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private const val MASKED_VALUE = "••••••••"
