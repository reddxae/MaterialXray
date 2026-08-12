package com.material.xray.ui.routing

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.RoutingRule
import com.material.xray.model.RoutingRuleCatalog
import com.material.xray.model.RoutingRuleOperator
import com.material.xray.model.XrayOutbound
import com.material.xray.ui.apps.AppBypassContent
import com.material.xray.ui.apps.AppRoutingMenuActions
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrollFadeEdges
import com.material.xray.ui.components.SegmentedTabRow
import com.material.xray.ui.text.catchAllEffectResource
import com.material.xray.ui.text.descriptionResource
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class RoutingTab(@StringRes val titleResource: Int) {
    Rules(R.string.routing_tab_rules),
    Apps(R.string.routing_tab_apps),
}

@Serializable
private data class EditableRoutingRule(
    val rule: RoutingRule,
    val isNew: Boolean,
)

/** Persists an in-progress rule edit across configuration changes and process death. */
private val editableRoutingRuleSaver: Saver<EditableRoutingRule?, String> = jsonSaver()

/** Persists the rule pending catch-all confirmation across configuration changes. */
private val routingRuleSaver: Saver<RoutingRule?, String> = jsonSaver()

private inline fun <reified T : Any> jsonSaver(): Saver<T?, String> = Saver(
    save = { value -> value?.let { Json.encodeToString(it) } },
    restore = { saved -> runCatching { Json.decodeFromString<T>(saved) }.getOrNull() },
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

private val protocolOptions = listOf("http", "tls", "quic", "bittorrent")
private val defaultRoutingRulesById = RoutingRuleCatalog.defaults().associateBy(RoutingRule::id)

private data class MatchModeOption(
    val value: RoutingRuleOperator,
    @param:StringRes val labelResource: Int,
    @param:StringRes val descriptionResource: Int,
)

private val matchModeOptions = listOf(
    MatchModeOption(
        value = RoutingRuleOperator.AND,
        labelResource = R.string.routing_match_all_label,
        descriptionResource = R.string.routing_match_all_description,
    ),
    MatchModeOption(
        value = RoutingRuleOperator.OR,
        labelResource = R.string.routing_match_any_label,
        descriptionResource = R.string.routing_match_any_description,
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
    var editingRule by rememberSaveable(stateSaver = editableRoutingRuleSaver) {
        mutableStateOf<EditableRoutingRule?>(null)
    }
    var pendingManualAction by remember { mutableStateOf<RoutingRuleAction?>(null) }
    var confirmResetToDefault by remember { mutableStateOf(false) }
    var rulesMenuExpanded by remember { mutableStateOf(false) }
    val selectionMode by remember { derivedStateOf { selectedRuleIds.isNotEmpty() } }
    val selectedTab = pagerState.currentPage
    val newRuleName = stringResource(R.string.routing_new_rule_name)

    fun applyRuleAction(action: RoutingRuleAction) {
        when (action) {
            RoutingRuleAction.Add -> {
                editingRule = EditableRoutingRule(
                    rule = RoutingRule(
                        id = "custom-${System.currentTimeMillis()}",
                        name = newRuleName,
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
                                pluralStringResource(
                                    R.plurals.routing_rules_selected,
                                    selectedRuleIds.size,
                                    selectedRuleIds.size,
                                )
                            else -> stringResource(R.string.routing_title)
                        },
                    )
                },
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    when (pagerState.currentPage) {
                        RoutingTab.Rules.ordinal -> {
                            if (selectionMode) {
                                IconButton(onClick = { selectedRuleIds = emptySet() }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.routing_clear_selection),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        requestRuleAction(RoutingRuleAction.Delete(selectedRuleIds))
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.routing_delete_selected_rules),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { requestRuleAction(RoutingRuleAction.Add) },
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.routing_add_rule),
                                    )
                                }
                                Box {
                                    IconButton(onClick = { rulesMenuExpanded = true }) {
                                        Icon(
                                            Icons.Default.MoreVert,
                                            contentDescription = stringResource(R.string.routing_rules_menu),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = rulesMenuExpanded,
                                        onDismissRequest = { rulesMenuExpanded = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.routing_enable_all)) },
                                            enabled = rules.any { !it.enabled },
                                            onClick = {
                                                rulesMenuExpanded = false
                                                requestRuleAction(RoutingRuleAction.EnableAll)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.routing_disable_all)) },
                                            enabled = rules.any { it.enabled },
                                            onClick = {
                                                rulesMenuExpanded = false
                                                requestRuleAction(RoutingRuleAction.DisableAll)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.routing_reset_to_default)) },
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
            SegmentedTabRow(
                labels = RoutingTab.entries.map { stringResource(it.titleResource) },
                selectedIndex = selectedTab,
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
                RoutingTab.Apps -> AppBypassContent(active = selectedTab == RoutingTab.Apps.ordinal)
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
            title = { Text(stringResource(R.string.routing_reset_to_default_title)) },
            text = { Text(stringResource(R.string.routing_reset_to_default_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetRulesToDefaults()
                        confirmResetToDefault = false
                    },
                ) {
                    Text(stringResource(R.string.routing_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetToDefault = false }) {
                    Text(stringResource(R.string.routing_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoutingRulesTab(
    rules: List<RoutingRule>,
    providerManaged: Boolean,
    providerName: String?,
    selectionMode: Boolean,
    selectedRuleIds: Set<String>,
    onRuleToggled: (RoutingRule, Boolean) -> Unit,
    onRuleClick: (RoutingRule) -> Unit,
    onRuleLongClick: (RoutingRule) -> Unit,
) {
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
                                text = providerName?.let {
                                    stringResource(R.string.routing_rules_managed_by_provider, it)
                                } ?: stringResource(R.string.routing_rules_managed_by_selected_subscription),
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
                val contentText = routingRuleContentText(rule)

                Surface(
                    color = containerColor,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .combinedClickable(
                            onClick = { onRuleClick(rule) },
                            onLongClick = { onRuleLongClick(rule) },
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = selectionMode,
                            enter = expandHorizontally(
                                animationSpec = tween(durationMillis = 180),
                                expandFrom = Alignment.Start,
                            ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                            exit = shrinkHorizontally(
                                animationSpec = tween(durationMillis = 150),
                                shrinkTowards = Alignment.Start,
                            ) + fadeOut(animationSpec = tween(durationMillis = 90)),
                        ) {
                            Box(modifier = Modifier.padding(end = 12.dp)) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = routingRuleDisplayName(rule),
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
        ScrollFadeEdges()
    }
}

@Composable
private fun AutomaticRuleRoutingDialog(
    providerName: String?,
    onDismiss: () -> Unit,
    onSwitchToManual: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.routing_rules_automatic_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                providerName?.let {
                    stringResource(R.string.routing_rules_automatic_provider_description, it)
                } ?: stringResource(R.string.routing_rules_automatic_selected_subscription_description),
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
                    Text(stringResource(R.string.routing_switch_to_manual_mode))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.routing_leave_as_is))
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
    var name by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.name))
    }
    var domains by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.domains.joinToString(", ")))
    }
    var ips by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.ips.joinToString(", ")))
    }
    var port by rememberSaveable(rule.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(rule.port.orEmpty()))
    }
    var selectedOutbound by rememberSaveable(rule.id) { mutableStateOf(rule.outboundTag) }
    var selectedOperator by rememberSaveable(rule.id) { mutableStateOf(rule.operator) }
    var selectedProtocols by rememberSaveable(rule.id) { mutableStateOf(rule.protocols.toSet()) }
    var pendingCatchAllRule by rememberSaveable(rule.id, stateSaver = routingRuleSaver) {
        mutableStateOf<RoutingRule?>(null)
    }
    val outboundOption = remember(selectedOutbound) { XrayOutbound.fromTag(selectedOutbound) }
    val matchModeOption = remember(selectedOperator) { matchModeOptions.first { it.value == selectedOperator } }
    val outboundDescription = stringResource(outboundOption.descriptionResource)
    val matchModeLabel = stringResource(matchModeOption.labelResource)
    val matchModeDescription = stringResource(matchModeOption.descriptionResource)
    val outboundOptions = XrayOutbound.entries.map { option ->
        DropdownOption(
            value = option.tag,
            label = option.tag,
            description = stringResource(option.descriptionResource),
        )
    }
    val localizedMatchModeOptions = matchModeOptions.map { option ->
        DropdownOption(
            value = option.value.name,
            label = stringResource(option.labelResource),
            description = stringResource(option.descriptionResource),
        )
    }
    val scrollState = rememberScrollState()

    fun editedRule(): RoutingRule = rule.copy(
        name = name.text.trim().ifEmpty { rule.name },
        outboundTag = selectedOutbound,
        domains = splitCsv(domains.text),
        ips = splitCsv(ips.text),
        port = port.text.trim().ifEmpty { null },
        protocols = protocolOptions.filter { it in selectedProtocols },
        operator = selectedOperator,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val edited = editedRule()
                    if (edited.matchesAllTraffic()) {
                        pendingCatchAllRule = edited
                    } else {
                        onSave(edited)
                    }
                },
            ) {
                Text(stringResource(R.string.routing_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.routing_cancel))
            }
        },
        title = { Text(stringResource(R.string.routing_edit_rule_title)) },
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
                        label = { Text(stringResource(R.string.routing_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ReadOnlyDropdownField(
                        label = stringResource(R.string.routing_outbound_tag_label),
                        selectedText = outboundOption.tag,
                        supportingText = outboundDescription,
                        options = outboundOptions,
                        onSelected = { selectedOutbound = it },
                    )

                    ReadOnlyDropdownField(
                        label = stringResource(R.string.routing_match_mode_label),
                        selectedText = matchModeLabel,
                        supportingText = matchModeDescription,
                        options = localizedMatchModeOptions,
                        onSelected = { selectedOperator = RoutingRuleOperator.valueOf(it) },
                    )

                    OutlinedTextField(
                        value = domains,
                        onValueChange = { domains = it },
                        label = { Text(stringResource(R.string.routing_domains_label)) },
                        supportingText = { Text(stringResource(R.string.routing_domains_supporting_text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ips,
                        onValueChange = { ips = it },
                        label = { Text(stringResource(R.string.routing_ips_label)) },
                        supportingText = { Text(stringResource(R.string.routing_ips_supporting_text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text(stringResource(R.string.routing_port_label)) },
                        supportingText = { Text(stringResource(R.string.routing_port_supporting_text)) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(stringResource(R.string.routing_protocols_label), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.routing_protocols_empty_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    protocolOptions.forEach { protocol ->
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
                                        text = protocol.uppercase(Locale.ROOT),
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

    pendingCatchAllRule?.let { candidate ->
        CatchAllRuleWarningDialog(
            outbound = XrayOutbound.fromTag(candidate.outboundTag),
            onKeepEditing = { pendingCatchAllRule = null },
            onSaveAnyway = {
                pendingCatchAllRule = null
                onSave(candidate)
            },
        )
    }
}

@Composable
private fun CatchAllRuleWarningDialog(
    outbound: XrayOutbound,
    onKeepEditing: () -> Unit,
    onSaveAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.routing_catch_all_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.routing_catch_all_warning_description))
                Text(
                    text = stringResource(outbound.catchAllEffectResource),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveAnyway) {
                Text(stringResource(R.string.routing_catch_all_save_anyway))
            }
        },
        dismissButton = {
            Button(onClick = onKeepEditing) {
                Text(stringResource(R.string.routing_catch_all_keep_editing))
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
private fun routingRuleContentText(rule: RoutingRule): String {
    val domains = rule.domains.map(String::trim).filter(String::isNotEmpty)
    val ips = rule.ips.map(String::trim).filter(String::isNotEmpty)
    val protocols = rule.protocols.map(String::trim).filter(String::isNotEmpty)
    val domainText = domains.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_domains, it.size, it.joinToString(", "))
    }
    val ipText = ips.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_ips, it.size, it.joinToString(", "))
    }
    val portText = rule.port?.takeIf(String::isNotBlank)?.let {
        stringResource(R.string.routing_rule_port, it)
    }
    val protocolText = protocols.takeIf(List<String>::isNotEmpty)?.let {
        pluralStringResource(R.plurals.routing_rule_protocols, it.size, it.joinToString(", "))
    }
    return listOfNotNull(domainText, ipText, portText, protocolText)
        .joinToString("\n")
        .ifBlank { stringResource(R.string.routing_no_match_content) }
}

@Composable
private fun routingRuleDisplayName(rule: RoutingRule): String {
    if (rule.name != defaultRoutingRulesById[rule.id]?.name) return rule.name
    return when (rule.id) {
        "ru-direct" -> stringResource(R.string.routing_default_rule_ru_name)
        "block-ads" -> stringResource(R.string.routing_default_rule_block_ads_name)
        else -> rule.name
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun splitCsv(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
