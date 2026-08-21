package com.material.xray.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.material.xray.R
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.model.DnsPreset
import com.material.xray.model.dnsPresetFor
import com.material.xray.model.isEncryptedDnsValue
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.components.SettingsSwitchRow

/**
 * The DNS settings subpage, reached from the Core section of the settings list.
 *
 * DNS gets a page of its own because the two resolver lists only make sense next to an explanation
 * of which names each one answers, and that does not fit under a text field. It reuses
 * [SettingsViewModel] rather than owning one, since it lives inside the settings destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(
    settings: SettingsSnapshot,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_dns_title),
                scrollBehavior = scrollBehavior,
                showLogo = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_dns_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.settings_dns_intro),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "primary") {
                DnsResolverSection(
                    title = stringResource(R.string.settings_dns_primary_title),
                    description = stringResource(R.string.settings_dns_primary_description),
                    servers = settings.dnsServers,
                    // An empty primary list makes Xray ask the network's own resolver.
                    emptyLabel = stringResource(R.string.dns_preset_system_label),
                    emptyDescription = stringResource(R.string.dns_preset_system_description),
                    onServersChange = viewModel::setDnsServers,
                )
            }

            item(key = "direct") {
                DnsResolverSection(
                    title = stringResource(R.string.settings_dns_direct_title),
                    description = stringResource(R.string.settings_dns_direct_description),
                    servers = settings.domesticDnsServers,
                    // An empty direct list adds no resolver of its own, so these names fall through
                    // to the proxied list above. That is not the network's resolver, so it cannot
                    // borrow the label the primary list uses for the same empty value.
                    emptyLabel = stringResource(R.string.dns_preset_direct_none_label),
                    emptyDescription = stringResource(R.string.dns_preset_direct_none_description),
                    onServersChange = viewModel::setDomesticDnsServers,
                )
            }
        }
    }
}

/**
 * One resolver list: which provider answers it, whether the queries are encrypted, and the raw
 * addresses when the provider is [DnsPreset.Custom].
 *
 * The stored value is the only source of truth for the provider, so picking one from the menu just
 * writes that provider's addresses. Two things the value cannot express are held locally: that the
 * user asked for [DnsPreset.Custom] before typing anything, and whether encryption is wanted while
 * sitting on a preset that has no addresses to encrypt. Both reset whenever [servers] changes
 * underneath.
 *
 * [emptyLabel] and [emptyDescription] name [DnsPreset.System], because an empty list means something
 * different for each list and the preset cannot say which caller it is describing.
 */
@Composable
private fun DnsResolverSection(
    title: String,
    description: String,
    servers: String,
    emptyLabel: String,
    emptyDescription: String,
    onServersChange: (String) -> Unit,
) {
    val storedPreset = remember(servers) { dnsPresetFor(servers) }
    var customPicked by rememberSaveable(servers) { mutableStateOf(storedPreset == DnsPreset.Custom) }
    var encryptionWanted by rememberSaveable(servers) {
        // Seeded from the stored value when there is one to read. An empty or hand-typed list says
        // nothing about encryption, so the next provider picked from the menu gets it.
        mutableStateOf(isEncryptedDnsValue(servers) || !storedPreset.supportsEncryption)
    }
    var editingServers by rememberSaveable(servers) { mutableStateOf(servers) }
    val preset = if (customPicked) DnsPreset.Custom else storedPreset

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ReadOnlyDropdownField(
            label = stringResource(R.string.settings_dns_provider_label),
            selectedText = preset.label(emptyLabel),
            supportingText = preset.description(emptyDescription),
            options = DnsPreset.entries.map { option ->
                DropdownOption(
                    value = option,
                    label = option.label(emptyLabel),
                    description = option.description(emptyDescription),
                )
            },
            onSelected = { picked ->
                customPicked = picked == DnsPreset.Custom
                if (picked != DnsPreset.Custom) {
                    onServersChange(picked.servers(encryptionWanted && picked.supportsEncryption))
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (preset.supportsEncryption) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_dns_encrypted_title),
                description = if (encryptionWanted && !preset.encryptsIpv6) {
                    stringResource(
                        R.string.settings_dns_encrypted_ipv4_only,
                        stringResource(preset.labelResource),
                    )
                } else {
                    null
                },
                checked = encryptionWanted,
                onCheckedChange = { wanted ->
                    encryptionWanted = wanted
                    onServersChange(preset.servers(wanted))
                },
            )
        }

        if (preset == DnsPreset.Custom) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = editingServers,
                    onValueChange = { editingServers = it },
                    label = { Text(stringResource(R.string.settings_dns_custom_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.settings_dns_custom_supporting_text)) },
                )
                if (editingServers.trim() != servers) {
                    Button(onClick = { onServersChange(editingServers) }) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsPreset.label(emptyLabel: String): String = if (this == DnsPreset.System) emptyLabel else stringResource(labelResource)

@Composable
private fun DnsPreset.description(emptyDescription: String): String = if (this == DnsPreset.System) emptyDescription else stringResource(descriptionResource)
