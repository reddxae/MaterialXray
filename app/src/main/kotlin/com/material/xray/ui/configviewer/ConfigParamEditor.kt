package com.material.xray.ui.configviewer

import androidx.annotation.StringRes
import com.material.xray.R
import com.material.xray.model.Protocol
import com.material.xray.model.SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD
import com.material.xray.model.SERVER_EXTRA_WIREGUARD_PRESHARED_KEY
import com.material.xray.model.ServerConfig

/** Which part of a [ServerConfig] an editable field writes back to. */
sealed interface EditKey {
    data object Name : EditKey

    data object Protocol : EditKey

    data object Address : EditKey

    data object Port : EditKey

    data object Password : EditKey

    data object TransportType : EditKey

    data object TransportPath : EditKey

    data object TransportHost : EditKey

    data object TransportServiceName : EditKey

    data object TransportMode : EditKey

    data object SecurityType : EditKey

    data object SecuritySni : EditKey

    data object SecurityFingerprint : EditKey

    data object SecurityAlpn : EditKey

    data object SecurityPublicKey : EditKey

    data object SecurityShortId : EditKey

    data class Extra(val key: String) : EditKey
}

data class EditField(
    val key: EditKey,
    val label: ParamLabel,
    val value: String,
    val isSecret: Boolean = false,
)

data class EditSection(
    @param:StringRes val titleRes: Int,
    val fields: List<EditField>,
)

/**
 * Every editable field, including the blank ones the read-only view drops. Without the blanks
 * there would be no way to fill in a value the config does not carry yet.
 */
fun ServerConfig.toEditSections(): List<EditSection> = listOfNotNull(
    EditSection(
        R.string.config_viewer_section_connection,
        listOf(
            EditField(EditKey.Name, ParamLabel.Resource(R.string.config_viewer_field_name), name),
            EditField(EditKey.Protocol, ParamLabel.Resource(R.string.config_viewer_field_protocol), protocol.name),
            EditField(EditKey.Address, ParamLabel.Resource(R.string.config_viewer_field_address), address),
            EditField(EditKey.Port, ParamLabel.Resource(R.string.config_viewer_field_port), port.takeIf { it > 0 }?.toString().orEmpty()),
            EditField(EditKey.Password, ParamLabel.Resource(protocol.credentialLabelRes()), password, isSecret = true),
        ),
    ),
    EditSection(
        R.string.config_viewer_section_transport,
        listOf(
            EditField(EditKey.TransportType, ParamLabel.Resource(R.string.config_viewer_field_transport_type), transport.type),
            EditField(EditKey.TransportPath, ParamLabel.Resource(R.string.config_viewer_field_transport_path), transport.path),
            EditField(EditKey.TransportHost, ParamLabel.Resource(R.string.config_viewer_field_transport_host), transport.host),
            EditField(
                EditKey.TransportServiceName,
                ParamLabel.Resource(R.string.config_viewer_field_transport_service_name),
                transport.serviceName,
            ),
            EditField(EditKey.TransportMode, ParamLabel.Resource(R.string.config_viewer_field_transport_mode), transport.mode),
        ),
    ),
    EditSection(
        R.string.config_viewer_section_security,
        listOf(
            EditField(EditKey.SecurityType, ParamLabel.Resource(R.string.config_viewer_field_security_type), security.type),
            EditField(EditKey.SecuritySni, ParamLabel.Resource(R.string.config_viewer_field_security_sni), security.sni),
            EditField(
                EditKey.SecurityFingerprint,
                ParamLabel.Resource(R.string.config_viewer_field_security_fingerprint),
                security.fingerprint,
            ),
            EditField(
                EditKey.SecurityAlpn,
                ParamLabel.Resource(R.string.config_viewer_field_security_alpn),
                security.alpn.joinToString(", "),
            ),
            EditField(
                EditKey.SecurityPublicKey,
                ParamLabel.Resource(R.string.config_viewer_field_security_public_key),
                security.publicKey,
            ),
            EditField(EditKey.SecurityShortId, ParamLabel.Resource(R.string.config_viewer_field_security_short_id), security.shortId),
        ),
    ),
    // Only the keys already present. A config that needs a brand new extra key is a raw JSON
    // config, and that has the JSON editor.
    extra.takeIf { it.isNotEmpty() }?.let { entries ->
        EditSection(
            R.string.config_viewer_section_extra,
            entries.map { (key, value) ->
                EditField(EditKey.Extra(key), ParamLabel.Key(key), value, isSecret = key in SECRET_EXTRA_KEYS)
            },
        )
    },
)

/** Why an edited field set could not be turned back into a [ServerConfig]. */
enum class EditValidationError(@param:StringRes val messageRes: Int) {
    INVALID_PORT(R.string.config_viewer_error_invalid_port),
    MISSING_ADDRESS(R.string.config_viewer_error_missing_address),
    MISSING_NAME(R.string.config_viewer_error_missing_name),
}

sealed interface EditOutcome {
    data class Valid(val config: ServerConfig) : EditOutcome

    data class Invalid(val error: EditValidationError) : EditOutcome
}

/**
 * Rebuilds a [ServerConfig] from edited fields. Blank values are dropped, matching what the
 * read-only view shows, and [original] supplies everything the editor does not expose.
 */
fun List<EditSection>.toServerConfig(original: ServerConfig): EditOutcome {
    val values = flatMap { it.fields }.associate { it.key to it.value }
    fun value(key: EditKey): String = values[key]?.trim().orEmpty()

    val name = value(EditKey.Name)
    if (name.isEmpty()) return EditOutcome.Invalid(EditValidationError.MISSING_NAME)

    val address = value(EditKey.Address)
    if (address.isEmpty()) return EditOutcome.Invalid(EditValidationError.MISSING_ADDRESS)

    val rawPort = value(EditKey.Port)
    val port = rawPort.toIntOrNull()
    if (port == null || port !in 1..MAX_PORT) return EditOutcome.Invalid(EditValidationError.INVALID_PORT)

    val protocol = Protocol.entries.find { it.name == value(EditKey.Protocol) } ?: original.protocol

    return EditOutcome.Valid(
        original.copy(
            protocol = protocol,
            name = name,
            address = address,
            port = port,
            password = value(EditKey.Password),
            transport = ServerConfig.Transport(
                type = value(EditKey.TransportType).ifEmpty { "tcp" },
                path = value(EditKey.TransportPath),
                host = value(EditKey.TransportHost),
                serviceName = value(EditKey.TransportServiceName),
                mode = value(EditKey.TransportMode),
            ),
            security = ServerConfig.Security(
                type = value(EditKey.SecurityType).ifEmpty { "none" },
                sni = value(EditKey.SecuritySni),
                fingerprint = value(EditKey.SecurityFingerprint),
                alpn = value(EditKey.SecurityAlpn)
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                publicKey = value(EditKey.SecurityPublicKey),
                shortId = value(EditKey.SecurityShortId),
            ),
            extra = values
                .mapNotNull { (key, value) -> (key as? EditKey.Extra)?.let { it.key to value.trim() } }
                .filter { (_, value) -> value.isNotEmpty() }
                .toMap(),
        ),
    )
}

@StringRes
internal fun Protocol.credentialLabelRes(): Int = when (this) {
    Protocol.VLESS, Protocol.VMESS -> R.string.config_viewer_field_id
    else -> R.string.config_viewer_field_password
}

internal val SECRET_EXTRA_KEYS = setOf(SERVER_EXTRA_HYSTERIA_OBFS_PASSWORD, SERVER_EXTRA_WIREGUARD_PRESHARED_KEY)

private const val MAX_PORT = 65535
