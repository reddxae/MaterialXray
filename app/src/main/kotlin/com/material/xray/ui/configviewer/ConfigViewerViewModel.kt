package com.material.xray.ui.configviewer

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.material.xray.R
import com.material.xray.core.xray.ActiveConfigOverrideStore
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.parser.SubscriptionFetcher
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.service.ConnectionRuntimeManager
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.XrayService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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

/** How a server edit should survive future subscription updates. */
enum class ServerSaveMode {
    /** Saved as is, replaced by the next subscription update. */
    KEEP,

    /** Saved, and the subscription stops updating itself. */
    DISABLE_AUTO_UPDATE,

    /** Saved, and updates to this one server are ignored from now on. */
    GUARD,
}

/** The live editor buffer, owned by the composition and handed over only on save. */
sealed interface EditDraft {
    data class Json(val text: String) : EditDraft

    data class Params(val sections: List<EditSection>) : EditDraft
}

sealed interface ConfigViewerUiState {
    data object Loading : ConfigViewerUiState

    /** Nothing to show, and why. */
    data class Message(@param:StringRes val textRes: Int) : ConfigViewerUiState

    /**
     * A whole JSON document. [lines] is the already-tokenized form of [prettyJson]; a document that
     * could not be re-parsed is still shown, just without highlighting. [showDisclaimer] is set for
     * a subscription's source config, which Material Xray rewrites before running it.
     * [overrideActive] marks a running config the user has replaced by hand.
     */
    data class JsonDocument(
        val prettyJson: String,
        val lines: List<List<JsonToken>>,
        val showDisclaimer: Boolean,
        val overrideActive: Boolean = false,
    ) : ConfigViewerUiState

    /**
     * The same document, open for editing. [initialText] seeds the editor once; the live buffer is
     * Compose state, so typing never round-trips through this flow. Highlighting is dropped while
     * editing: re-tokenizing a few thousand lines per keystroke costs more than the colour is worth.
     */
    data class JsonEditor(
        val initialText: String,
        @param:StringRes val errorRes: Int? = null,
    ) : ConfigViewerUiState

    data class Params(
        val sections: List<ParamSection>,
        val rawLink: String,
        val edited: Boolean = false,
    ) : ConfigViewerUiState

    /** [initialSections] seeds the fields once; the live values are Compose state. */
    data class ParamsEditor(
        val initialSections: List<EditSection>,
        @param:StringRes val errorRes: Int? = null,
    ) : ConfigViewerUiState
}

@HiltViewModel
class ConfigViewerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val serverRepo: ServerRepository,
    private val subscriptionRepo: SubscriptionRepository,
    private val settingsRepo: SettingsRepository,
    private val connectionRuntimeManager: ConnectionRuntimeManager,
    private val activeConfigOverrideStore: ActiveConfigOverrideStore,
    private val subscriptionFetcher: SubscriptionFetcher,
    private val stateCoordinator: ConnectionStateCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ConfigViewerUiState>(ConfigViewerUiState.Loading)
    val uiState: StateFlow<ConfigViewerUiState> = _uiState.asStateFlow()

    /** Null while the viewer is not showing a saved server, which cannot be guarded. */
    private val _guarded = MutableStateFlow<Boolean?>(null)
    val guarded: StateFlow<Boolean?> = _guarded.asStateFlow()

    /** Set once an edit has passed validation and the save mode is up to the user. */
    private val _saveModePrompt = MutableStateFlow(false)
    val saveModePrompt: StateFlow<Boolean> = _saveModePrompt.asStateFlow()

    /** String resources for one-off toasts. */
    private val _events = Channel<Int>(Channel.BUFFERED)
    val events: Flow<Int> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var request: ConfigViewerRequest? = null
    private var serverEntity: ServerEntity? = null
    private var serverConfig: ServerConfig? = null

    /** The config an accepted edit produced, held between validation and the save mode choice. */
    private var validatedConfig: ServerConfig? = null

    fun load(request: ConfigViewerRequest) {
        loadJob?.cancel()
        this.request = request
        _saveModePrompt.value = false
        validatedConfig = null
        _uiState.value = ConfigViewerUiState.Loading
        loadJob = viewModelScope.launch {
            _uiState.value = when (request) {
                is ConfigViewerRequest.Server -> loadServer(request.serverId)
                ConfigViewerRequest.Running -> loadRunning()
            }
        }
    }

    fun beginEdit() {
        viewModelScope.launch {
            _uiState.value = when (val state = _uiState.value) {
                is ConfigViewerUiState.JsonDocument -> ConfigViewerUiState.JsonEditor(state.prettyJson)
                is ConfigViewerUiState.Params -> {
                    val config = serverConfig ?: return@launch
                    ConfigViewerUiState.ParamsEditor(config.toEditSections())
                }
                else -> return@launch
            }
        }
    }

    fun cancelEdit() {
        request?.let(::load)
    }

    fun toggleGuard() {
        val entity = serverEntity ?: return
        val next = !(_guarded.value ?: false)
        viewModelScope.launch {
            serverRepo.setGuarded(entity.id, next)
            _guarded.value = next
            serverEntity = entity.copy(guarded = next)
            _events.send(if (next) R.string.config_viewer_guarded else R.string.config_viewer_unguarded)
        }
    }

    /**
     * Validates [draft]. A running config is saved straight away; a server edit stops here and
     * waits for the user to pick how it should survive future updates.
     */
    fun save(draft: EditDraft) {
        viewModelScope.launch {
            when (request) {
                ConfigViewerRequest.Running -> saveRunning(draft)
                is ConfigViewerRequest.Server -> {
                    val config = validateServerEdit(draft) ?: return@launch
                    validatedConfig = config
                    _saveModePrompt.value = true
                }
                null -> Unit
            }
        }
    }

    fun dismissSaveModePrompt() {
        _saveModePrompt.value = false
        validatedConfig = null
    }

    fun confirmSave(mode: ServerSaveMode) {
        val entity = serverEntity ?: return
        val config = validatedConfig ?: return
        _saveModePrompt.value = false
        validatedConfig = null

        viewModelScope.launch {
            // A refresh between opening the viewer and saving deletes and re-inserts the row under
            // a new id, and the targeted updates below would then match nothing. Say so instead of
            // reporting a save that did not happen.
            if (serverRepo.getById(entity.id) == null) {
                _events.send(R.string.config_viewer_save_failed_gone)
                request?.let(::load)
                return@launch
            }

            serverRepo.saveEditedConfig(entity.id, config)
            when (mode) {
                ServerSaveMode.KEEP -> Unit
                ServerSaveMode.DISABLE_AUTO_UPDATE -> subscriptionRepo.setAutoUpdateInterval(entity.subscriptionId, 0)
                ServerSaveMode.GUARD -> {
                    serverRepo.setGuarded(entity.id, true)
                    _guarded.value = true
                }
            }
            _events.send(R.string.config_viewer_saved)
            discardOverrideAndReloadIfServerIsLive(entity.id)
            request?.let(::load)
        }
    }

    private suspend fun saveRunning(draft: EditDraft) {
        val state = _uiState.value as? ConfigViewerUiState.JsonEditor ?: return
        val text = (draft as? EditDraft.Json)?.text ?: return
        if (!text.isValidJson()) {
            _uiState.value = state.copy(errorRes = R.string.config_viewer_error_invalid_json)
            return
        }
        if (!activeConfigOverrideStore.save(text)) {
            _events.send(R.string.config_viewer_save_failed)
            return
        }

        if (stateCoordinator.state.value is ConnectionState.Connected) XrayService.reload(context)
        _events.send(R.string.config_viewer_saved)
        _uiState.value = withContext(Dispatchers.Default) {
            text.toJsonDocument(showDisclaimer = false, overrideActive = true)
        }
    }

    /** Null when [draft] does not hold a usable config; the error is already published. */
    private suspend fun validateServerEdit(draft: EditDraft): ServerConfig? = when (draft) {
        is EditDraft.Json -> {
            val state = _uiState.value as? ConfigViewerUiState.JsonEditor
            val parsed = withContext(Dispatchers.Default) { subscriptionFetcher.parseJsonConfig(draft.text) }
            if (parsed == null && state != null) {
                _uiState.value = state.copy(errorRes = R.string.config_viewer_error_invalid_json)
            }
            parsed
        }
        is EditDraft.Params -> {
            val state = _uiState.value as? ConfigViewerUiState.ParamsEditor
            when (val outcome = serverConfig?.let(draft.sections::toServerConfig)) {
                is EditOutcome.Valid -> outcome.config
                is EditOutcome.Invalid -> {
                    if (state != null) _uiState.value = state.copy(errorRes = outcome.error.messageRes)
                    null
                }
                null -> null
            }
        }
    }

    /**
     * An override replaces generation entirely, so restarting the core with one in place would
     * ignore the edit that was just saved and look like the save did nothing. The newer, more
     * specific edit wins; the user is told the whole-config edit went with it.
     */
    private suspend fun discardOverrideAndReloadIfServerIsLive(serverId: Long) {
        if (settingsRepo.lastServerId.first() != serverId) return
        if (activeConfigOverrideStore.exists()) {
            activeConfigOverrideStore.clear()
            _events.send(R.string.config_viewer_override_discarded)
        }
        if (stateCoordinator.state.value !is ConnectionState.Connected) return
        XrayService.reload(context)
    }

    private suspend fun loadServer(serverId: Long): ConfigViewerUiState {
        val entity = serverRepo.getById(serverId) ?: return SERVER_MISSING
        val config = runCatching { serverRepo.parseConfig(entity) }.getOrNull() ?: return SERVER_MISSING
        serverEntity = entity
        serverConfig = config
        _guarded.value = entity.guarded

        // Re-parsing and tokenizing the whole document is more work than belongs on the main
        // thread once a subscription ships a multi-outbound config.
        return withContext(Dispatchers.Default) {
            val rawJson = config.rawConfigJson
            if (rawJson.isBlank()) {
                ConfigViewerUiState.Params(
                    sections = config.toParamSections(),
                    rawLink = config.rawUri,
                    edited = entity.edited,
                )
            } else {
                rawJson.toJsonDocument(showDisclaimer = true)
            }
        }
    }

    private suspend fun loadRunning(): ConfigViewerUiState {
        serverEntity = null
        serverConfig = null
        _guarded.value = null
        val raw = connectionRuntimeManager.readActiveConfig()
            ?: return ConfigViewerUiState.Message(R.string.home_no_active_xray_config)
        val overrideActive = activeConfigOverrideStore.exists()
        // This is the config the core was handed, so there is nothing left to warn about.
        return withContext(Dispatchers.Default) {
            raw.toJsonDocument(showDisclaimer = false, overrideActive = overrideActive)
        }
    }
}

private val SERVER_MISSING = ConfigViewerUiState.Message(R.string.config_viewer_not_found)

private fun String.isValidJson(): Boolean = runCatching { configJson.parseToJsonElement(this) }.isSuccess

private fun String.toJsonDocument(
    showDisclaimer: Boolean,
    overrideActive: Boolean = false,
): ConfigViewerUiState.JsonDocument {
    val pretty = prettyPrintedOrNull()
    return ConfigViewerUiState.JsonDocument(
        prettyJson = pretty ?: this,
        lines = pretty?.let(::tokenizeJsonLines) ?: asPlainLines(),
        showDisclaimer = showDisclaimer,
        overrideActive = overrideActive,
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

private val configJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
