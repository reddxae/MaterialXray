package com.material.xray.ui.configviewer

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.data.repository.ServerRepository
import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.ServerConfig
import com.material.xray.service.ConnectionRuntimeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** What the viewer was asked to show. Carries the title so the app bar never has to wait. */
sealed interface ConfigViewerRequest {
    /** The source config a saved server was parsed from. */
    data class Server(val serverId: Long, val name: String) : ConfigViewerRequest

    /** The config file the running Xray core was actually started with. */
    data object Running : ConfigViewerRequest
}

/** Label of a single parameter row: either a translated field or a raw config key. */
sealed interface ParamLabel {
    data class Resource(@param:StringRes val id: Int) : ParamLabel

    data class Key(val text: String) : ParamLabel
}

data class ParamRow(
    val label: ParamLabel,
    val value: String,
    val isSecret: Boolean = false,
)

data class ParamSection(
    @param:StringRes val titleRes: Int,
    val rows: List<ParamRow>,
)

sealed interface ConfigViewerUiState {
    data object Loading : ConfigViewerUiState

    /** Nothing to show, and why. */
    data class Message(@param:StringRes val textRes: Int) : ConfigViewerUiState

    /**
     * A whole JSON document. [lines] is the already-tokenized form of [prettyJson]; a document that
     * could not be re-parsed is still shown, just without highlighting. [showDisclaimer] is set for
     * a subscription's source config, which Material Xray rewrites before running it.
     */
    data class JsonDocument(
        val prettyJson: String,
        val lines: List<List<JsonToken>>,
        val showDisclaimer: Boolean,
    ) : ConfigViewerUiState

    data class Params(
        val sections: List<ParamSection>,
        val rawLink: String,
    ) : ConfigViewerUiState
}

@HiltViewModel
class ConfigViewerViewModel @Inject constructor(
    private val serverRepo: ServerRepository,
    private val connectionRuntimeManager: ConnectionRuntimeManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ConfigViewerUiState>(ConfigViewerUiState.Loading)
    val uiState: StateFlow<ConfigViewerUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    fun load(request: ConfigViewerRequest) {
        loadJob?.cancel()
        _uiState.value = ConfigViewerUiState.Loading
        loadJob = viewModelScope.launch {
            _uiState.value = when (request) {
                is ConfigViewerRequest.Server -> loadServer(request.serverId)
                ConfigViewerRequest.Running -> loadRunning()
            }
        }
    }

    private suspend fun loadServer(serverId: Long): ConfigViewerUiState {
        val entity = serverRepo.getById(serverId) ?: return SERVER_MISSING
        val config = runCatching { serverRepo.parseConfig(entity) }.getOrNull() ?: return SERVER_MISSING

        // Re-parsing and tokenizing the whole document is more work than belongs on the main
        // thread once a subscription ships a multi-outbound config.
        return withContext(Dispatchers.Default) {
            val rawJson = config.rawConfigJson
            if (rawJson.isBlank()) {
                ConfigViewerUiState.Params(sections = config.toParamSections(), rawLink = config.rawUri)
            } else {
                rawJson.toJsonDocument(showDisclaimer = true)
            }
        }
    }

    private suspend fun loadRunning(): ConfigViewerUiState {
        val raw = connectionRuntimeManager.readActiveConfig()
            ?: return ConfigViewerUiState.Message(R.string.home_no_active_xray_config)
        // This is the config the core was handed, so there is nothing left to warn about.
        return withContext(Dispatchers.Default) { raw.toJsonDocument(showDisclaimer = false) }
    }
}

private val SERVER_MISSING = ConfigViewerUiState.Message(R.string.config_viewer_not_found)

private fun String.toJsonDocument(showDisclaimer: Boolean): ConfigViewerUiState.JsonDocument {
    val pretty = prettyPrintedOrNull()
    return ConfigViewerUiState.JsonDocument(
        prettyJson = pretty ?: this,
        lines = pretty?.let(::tokenizeJsonLines) ?: asPlainLines(),
        showDisclaimer = showDisclaimer,
    )
}

/** Every line as a single unclassified token, for a document the tokenizer should not guess at. */
private fun String.asPlainLines(): List<List<JsonToken>> = lines().map { line ->
    if (line.isEmpty()) emptyList() else listOf(JsonToken(line, JsonTokenKind.Plain))
}

private fun String.prettyPrintedOrNull(): String? = runCatching {
    // JsonObject is backed by a LinkedHashMap, so the provider's key order survives the round trip.
    val element = configJson.parseToJsonElement(this)
    configJson.encodeToString(JsonElement.serializer(), element)
}.getOrNull()

private fun ServerConfig.toParamSections(): List<ParamSection> = listOfNotNull(
    connectionSection(),
    transportSection(),
    securitySection(),
    extraSection(),
)

private fun ServerConfig.connectionSection(): ParamSection? = section(R.string.config_viewer_section_connection) {
    row(R.string.config_viewer_field_name, name)
    row(R.string.config_viewer_field_protocol, protocol.displayName)
    row(R.string.config_viewer_field_address, address)
    row(R.string.config_viewer_field_port, port.takeIf { it > 0 }?.toString().orEmpty())
    row(protocol.credentialLabelRes(), password, isSecret = true)
}

private fun ServerConfig.transportSection(): ParamSection? = section(R.string.config_viewer_section_transport) {
    row(R.string.config_viewer_field_transport_type, transport.type)
    row(R.string.config_viewer_field_transport_path, transport.path)
    row(R.string.config_viewer_field_transport_host, transport.host)
    row(R.string.config_viewer_field_transport_service_name, transport.serviceName)
    row(R.string.config_viewer_field_transport_mode, transport.mode)
}

private fun ServerConfig.securitySection(): ParamSection? = section(R.string.config_viewer_section_security) {
    row(R.string.config_viewer_field_security_type, security.type)
    row(R.string.config_viewer_field_security_sni, security.sni)
    row(R.string.config_viewer_field_security_fingerprint, security.fingerprint)
    row(R.string.config_viewer_field_security_alpn, security.alpn.joinToString(", "))
    row(R.string.config_viewer_field_security_public_key, security.publicKey)
    row(R.string.config_viewer_field_security_short_id, security.shortId)
}

private fun ServerConfig.extraSection(): ParamSection? {
    val rows = extra
        .filterValues { it.isNotBlank() }
        .map { (key, value) ->
            ParamRow(ParamLabel.Key(key), value, isSecret = key in SECRET_EXTRA_KEYS)
        }
    return rows.takeIf { it.isNotEmpty() }?.let { ParamSection(R.string.config_viewer_section_extra, it) }
}

/**
 * Builds a section from [build], dropping rows whose value is blank and the whole section when
 * nothing is left.
 */
private fun section(@StringRes titleRes: Int, build: ParamRowBuilder.() -> Unit): ParamSection? {
    val rows = ParamRowBuilder().apply(build).rows
    return rows.takeIf { it.isNotEmpty() }?.let { ParamSection(titleRes, it) }
}

private class ParamRowBuilder {
    val rows = mutableListOf<ParamRow>()

    fun row(@StringRes labelRes: Int, value: String, isSecret: Boolean = false) {
        if (value.isBlank()) return
        rows += ParamRow(ParamLabel.Resource(labelRes), value, isSecret)
    }
}

@StringRes
private fun Protocol.credentialLabelRes(): Int = when (this) {
    Protocol.VLESS, Protocol.VMESS -> R.string.config_viewer_field_id
    else -> R.string.config_viewer_field_password
}

private val SECRET_EXTRA_KEYS = setOf(SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD)
private val configJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
