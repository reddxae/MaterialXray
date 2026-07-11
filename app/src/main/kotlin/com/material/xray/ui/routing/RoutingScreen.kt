package com.material.xray.ui.routing

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.XrayOutbound
import com.material.xray.ui.apps.AppBypassContent
import com.material.xray.ui.apps.AppRoutingMenuActions
import kotlinx.coroutines.launch

private enum class RoutingTab(val title: String) {
    Rules("Rules"),
    Apps("Apps"),
}

private data class EditableRoutingRule(
    val rule: RoutingRule,
    val isNew: Boolean,
)

private sealed interface RoutingRuleAction {
    data object Add : RoutingRuleAction
    data object EnableAll : RoutingRuleAction
    data object DisableAll : RoutingRuleAction
    data object ResetToDefault : RoutingRuleAction
    data class Edit(val rule: RoutingRule) : RoutingRuleAction
    data class Toggle(val rule: RoutingRule, val enabled: Boolean) : RoutingRuleAction
    data class Delete(val ruleIds: Set<String>) : RoutingRuleAction
}

private val protocolOptions = listOf(
    "http" to "HTTP traffic",
    "tls" to "TLS traffic",
    "quic" to "QUIC traffic",
    "bittorrent" to "BitTorrent traffic",
)

private data class MatchModeOption(
    val value: RoutingRuleOperator,
    val label: String,
    val description: String,
)

private val matchModeOptions = listOf(
    MatchModeOption(
        value = RoutingRuleOperator.AND,
        label = "All conditions (AND)",
        description = "All filled fields stay in one Xray rule. Traffic must satisfy every filled field.",
    ),
    MatchModeOption(
        value = RoutingRuleOperator.OR,
        label = "Any condition group (OR)",
        description = "Each filled field group becomes its own Xray rule. Traffic can match any one of them.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingScreen(viewModel: RoutingViewModel = hiltViewModel()) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val automaticRoutingProviderName by viewModel.automaticRoutingProviderName.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { RoutingTab.entries.size })
    val coroutineScope = rememberCoroutineScope()
    var previousTab by remember { mutableIntStateOf(pagerState.currentPage) }
    var selectedRuleIds by remember { mutableStateOf(emptySet<String>()) }
    var editingRule by remember { mutableStateOf<EditableRoutingRule?>(null) }
    var pendingManualAction by remember { mutableStateOf<RoutingRuleAction?>(null) }
    var confirmResetToDefault by remember { mutableStateOf(false) }
    var rulesMenuExpanded by remember { mutableStateOf(false) }
    val selectionMode by remember { derivedStateOf { selectedRuleIds.isNotEmpty() } }
    val selectedTab = pagerState.currentPage

    fun applyRuleAction(action: RoutingRuleAction) {
        when (action) {
            RoutingRuleAction.Add -> {
                editingRule = EditableRoutingRule(
                    rule = RoutingRule(
                        id = "custom-${System.currentTimeMillis()}",
                        name = "New Rule",
                        outboundTag = "proxy",
                    ),
                    isNew = true,
                )
            }
            RoutingRuleAction.EnableAll -> viewModel.setAllRulesEnabled(true)
            RoutingRuleAction.DisableAll -> viewModel.setAllRulesEnabled(false)
            RoutingRuleAction.ResetToDefault -> confirmResetToDefault = true
            is RoutingRuleAction.Edit -> editingRule = EditableRoutingRule(rule = action.rule, isNew = false)
            is RoutingRuleAction.Toggle -> viewModel.updateRule(action.rule.copy(enabled = action.enabled))
            is RoutingRuleAction.Delete -> {
                viewModel.deleteRules(action.ruleIds)
                selectedRuleIds = emptySet()
            }
        }
    }

    fun requestRuleAction(action: RoutingRuleAction) {
        if (routingPolicyControl == RoutingPolicyControl.SubscriptionProvider) {
            pendingManualAction = action
        } else {
            applyRuleAction(action)
        }
    }

    LaunchedEffect(routingPolicyControl) {
        if (routingPolicyControl == RoutingPolicyControl.User) {
            pendingManualAction?.let(::applyRuleAction)
            pendingManualAction = null
        }
    }

    LaunchedEffect(selectedTab) {
        if (previousTab != selectedTab) {
            if (previousTab == RoutingTab.Rules.ordinal) {
                selectedRuleIds = emptySet()
            }
            viewModel.applyPendingChangesIfNeeded()
        }
        previousTab = selectedTab
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            pagerState.currentPage == RoutingTab.Rules.ordinal && selectionMode ->
                                "${selectedRuleIds.size} selected"
                            else -> "Routing"
                        },
                    )
                },
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    when (pagerState.currentPage) {
                        RoutingTab.Rules.ordinal -> {
                            if (selectionMode) {
                                IconButton(onClick = { selectedRuleIds = emptySet() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear selection")
                                }
                                IconButton(
                                    onClick = {
                                        requestRuleAction(RoutingRuleAction.Delete(selectedRuleIds))
                                    },
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete selected rules")
                                }
                            } else {
                                IconButton(
                                    onClick = { requestRuleAction(RoutingRuleAction.Add) },
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add rule")
                                }
                                Box {
                                    IconButton(onClick = { rulesMenuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Routing rules menu")
                                    }
                                    DropdownMenu(
                                        expanded = rulesMenuExpanded,
                                        onDismissRequest = { rulesMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Enable all") },
                                            enabled = rules.any { !it.enabled },
                                            onClick = {
                                                rulesMenuExpanded = false
                                                requestRuleAction(RoutingRuleAction.EnableAll)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Disable all") },
                                            enabled = rules.any { it.enabled },
                                            onClick = {
                                                rulesMenuExpanded = false
                                                requestRuleAction(RoutingRuleAction.DisableAll)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Reset to default") },
                                            onClick = {
                                                rulesMenuExpanded = false
                                                requestRuleAction(RoutingRuleAction.ResetToDefault)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        RoutingTab.Apps.ordinal -> AppRoutingMenuActions()
                    }
                },
            )
        },
        bottomBar = {
            RoutingTabSelector(
                selectedTab = selectedTab,
                onSelected = { index ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(index)
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            when (RoutingTab.entries[page]) {
                RoutingTab.Rules -> RoutingRulesTab(
                    rules = rules,
                    providerManaged = routingPolicyControl == RoutingPolicyControl.SubscriptionProvider,
                    providerName = automaticRoutingProviderName,
                    selectionMode = selectionMode,
                    selectedRuleIds = selectedRuleIds,
                    onRuleToggled = { rule, enabled -> requestRuleAction(RoutingRuleAction.Toggle(rule, enabled)) },
                    onRuleClick = { rule ->
                        if (selectionMode) {
                            selectedRuleIds = selectedRuleIds.toggle(rule.id)
                        } else {
                            requestRuleAction(RoutingRuleAction.Edit(rule))
                        }
                    },
                    onRuleLongClick = { rule ->
                        selectedRuleIds = selectedRuleIds.toggle(rule.id)
                    },
                )
                RoutingTab.Apps -> AppBypassContent()
            }
        }
    }

    editingRule?.let { editableRule ->
        EditRoutingRuleDialog(
            rule = editableRule.rule,
            onDismiss = { editingRule = null },
            onSave = { updatedRule ->
                if (editableRule.isNew) viewModel.addRule(updatedRule) else viewModel.updateRule(updatedRule)
                editingRule = null
            },
        )
    }

    if (pendingManualAction != null) {
        AutomaticRuleRoutingDialog(
            providerName = automaticRoutingProviderName,
            onDismiss = { pendingManualAction = null },
            onSwitchToManual = viewModel::switchToManualRouting,
        )
    }

    if (confirmResetToDefault) {
        AlertDialog(
            onDismissRequest = { confirmResetToDefault = false },
            title = { Text("Reset to default?") },
            text = { Text("All routing rules and matching behavior will be reset to the app defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetRulesToDefaults()
                        confirmResetToDefault = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetToDefault = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RoutingTabSelector(
    selectedTab: Int,
    onSelected: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RoutingTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selectedTab == index,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index, RoutingTab.entries.size),
            ) {
                Text(tab.title)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutingRulesTab(
    rules: List<RoutingRule>,
    providerManaged: Boolean,
    providerName: String,
    selectionMode: Boolean,
    selectedRuleIds: Set<String>,
    onRuleToggled: (RoutingRule, Boolean) -> Unit,
    onRuleClick: (RoutingRule) -> Unit,
    onRuleLongClick: (RoutingRule) -> Unit,
) {
    val fadeColor = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (providerManaged) {
                item(contentType = "providerRoutingBanner") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
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
                                text = "Routing rules are managed by $providerName",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            items(items = rules, key = { it.id }, contentType = { "routingRule" }) { rule ->
                val selected = rule.id in selectedRuleIds
                val containerColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    label = "routingRuleContainerColor",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    label = "routingRuleBorderColor",
                )
                val contentText = remember(rule) { rule.contentText().ifBlank { "No match content" } }

                Surface(
                    color = containerColor,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onRuleClick(rule) },
                            onLongClick = { onRuleLongClick(rule) },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = rule.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Switch(
                            checked = rule.enabled,
                            enabled = !selectionMode,
                            onCheckedChange = { enabled -> onRuleToggled(rule, enabled) },
                        )
                    }
                }
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

@Composable
private fun AutomaticRuleRoutingDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Routing rules are automatic",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                buildAnnotatedString {
                    append("Your current routing rules are provided by ")
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
private fun EditRoutingRuleDialog(
    rule: RoutingRule,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
) {
    var name by remember(rule.id) { mutableStateOf(TextFieldValue(rule.name)) }
    var domains by remember(rule.id) { mutableStateOf(TextFieldValue(rule.domains.joinToString(", "))) }
    var ips by remember(rule.id) { mutableStateOf(TextFieldValue(rule.ips.joinToString(", "))) }
    var port by remember(rule.id) { mutableStateOf(TextFieldValue(rule.port.orEmpty())) }
    var outboundExpanded by remember { mutableStateOf(false) }
    var operatorExpanded by remember { mutableStateOf(false) }
    var selectedOutbound by remember(rule.id) { mutableStateOf(rule.outboundTag) }
    var selectedOperator by remember(rule.id) { mutableStateOf(rule.operator) }
    var selectedProtocols by remember(rule.id) { mutableStateOf(rule.protocols.toSet()) }
    val outboundOption = remember(selectedOutbound) { XrayOutbound.fromTag(selectedOutbound) }
    val matchModeOption = remember(selectedOperator) { matchModeOptions.first { it.value == selectedOperator } }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        rule.copy(
                            name = name.text.trim().ifEmpty { rule.name },
                            outboundTag = selectedOutbound,
                            domains = splitCsv(domains.text),
                            ips = splitCsv(ips.text),
                            port = port.text.trim().ifEmpty { null },
                            protocols = protocolOptions.map { it.first }.filter { it in selectedProtocols },
                            operator = selectedOperator,
                        ),
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Edit Rule") },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(maxHeight = 520.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    DropdownSelector(
                        label = "Outbound Tag",
                        value = outboundOption.label,
                        description = outboundOption.description,
                        expanded = outboundExpanded,
                        onExpandedChange = { outboundExpanded = it },
                        options = XrayOutbound.entries.map { Triple(it.tag, it.label, it.description) },
                        onSelected = { selectedOutbound = it },
                    )

                    DropdownSelector(
                        label = "Match Mode",
                        value = matchModeOption.label,
                        description = matchModeOption.description,
                        expanded = operatorExpanded,
                        onExpandedChange = { operatorExpanded = it },
                        options = matchModeOptions.map { Triple(it.value.name, it.label, it.description) },
                        onSelected = { selectedOperator = RoutingRuleOperator.valueOf(it) },
                    )

                    OutlinedTextField(
                        value = domains,
                        onValueChange = { domains = it },
                        label = { Text("Domains") },
                        supportingText = { Text("Comma-separated. Example: domain:ru, geosite:category-ads-all") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ips,
                        onValueChange = { ips = it },
                        label = { Text("IPs") },
                        supportingText = { Text("Comma-separated. Example: geoip:ru, 1.2.3.0/24") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        supportingText = { Text("Single port or range, e.g. 443 or 0-65535") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text("Protocols", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "If nothing is selected, all traffic is matched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    protocolOptions.forEach { (protocol, _) ->
                        val checked = protocol in selectedProtocols
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = if (checked) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                1.dp,
                                if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = checked,
                                        onValueChange = { enabled ->
                                            selectedProtocols = if (enabled) {
                                                selectedProtocols + protocol
                                            } else {
                                                selectedProtocols - protocol
                                            }
                                        },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = protocol.uppercase(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
                DialogScrollbar(
                    scrollValue = scrollState.value,
                    maxScrollValue = scrollState.maxValue,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(4.dp),
                )
            }
        },
    )
}

@Composable
private fun DialogScrollbar(
    scrollValue: Int,
    maxScrollValue: Int,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)

    Canvas(modifier = modifier) {
        val radius = size.width / 2f
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(radius, radius),
        )
        if (maxScrollValue <= 0 || size.height <= 0f) return@Canvas

        val contentHeight = size.height + maxScrollValue
        val thumbHeight = (size.height * size.height / contentHeight).coerceAtLeast(32.dp.toPx())
        val thumbOffset = (scrollValue / maxScrollValue.toFloat()) * (size.height - thumbHeight)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, thumbOffset),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

@Composable
private fun DropdownSelector(
    label: String,
    value: String,
    description: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<Triple<String, String, String>>,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                label = { Text(label) },
                supportingText = {
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Open $label")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { onExpandedChange(true) },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                options.forEach { (optionValue, optionLabel, optionDescription) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(optionLabel)
                                Text(
                                    optionDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelected(optionValue)
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun splitCsv(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
