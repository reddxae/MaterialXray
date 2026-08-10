package com.material.xray.ui.home

import android.content.Context
import android.content.res.Resources
import com.material.xray.R
import com.material.xray.core.locale.appLocaleChanges
import com.material.xray.core.locale.forAppLanguage
import com.material.xray.core.locale.localizedString
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.ServerRepository
import com.material.xray.data.repository.SettingsRepository
import com.material.xray.data.repository.SubscriptionRepository
import com.material.xray.di.ApplicationScope
import com.material.xray.model.ServerConfig
import com.material.xray.model.endpointSummary
import com.material.xray.model.proxyOutboundCount
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * Everything the Home screen needs to render its first frame, produced as one atomic snapshot so
 * the subscription list, the server rows, and the selected server title all appear together.
 */
data class HomeData(
    val subscriptions: List<SubscriptionEntity>,
    val servers: List<ServerEntity>,
    val serverItems: List<ServerListItem>,
    val selectedServerId: Long,
    val selectedServer: ServerConfig?,
)

/**
 * Process-wide holder for the Home screen data.
 *
 * The snapshot is shared eagerly in the application scope, which serves two purposes:
 * - Warm-up: constructing this holder (it is injected by [com.material.xray.MaterialXrayApp])
 *   opens the database and the settings store during application startup, before the first
 *   composition subscribes, so a cold start usually has the data ready by the first frame.
 * - Readiness: [data] is `null` until the first snapshot is built, which lets
 *   [com.material.xray.MainActivity] keep the splash screen visible until the Home screen can
 *   render fully populated, and lets the UI distinguish "not loaded yet" from "no subscriptions".
 */
@Singleton
class HomeDataState @Inject constructor(
    @param:ApplicationContext private val context: Context,
    subscriptionRepository: SubscriptionRepository,
    serverRepository: ServerRepository,
    settingsRepository: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Only touched from the combine transform below, which runs sequentially for the single
    // stateIn collector, so no synchronization is needed. Rebuilt on every emission so entries
    // for servers that no longer exist do not accumulate for the lifetime of the process.
    private var endpointSummaryCache = mapOf<String, String>()

    val data: StateFlow<HomeData?> = combine(
        subscriptionRepository.observeAll(),
        serverRepository.observeAll(),
        settingsRepository.lastServerId,
        appLocaleChanges.onStart { emit(Unit) },
    ) { subscriptions, servers, selectedServerId, _ ->
        HomeData(
            subscriptions = subscriptions,
            servers = servers,
            serverItems = buildServerItems(servers),
            selectedServerId = selectedServerId,
            selectedServer = servers.find { it.id == selectedServerId }?.let { entity ->
                runCatching { serverRepository.parseConfig(entity) }.getOrNull()
            },
        )
    }
        // Building the list items decodes every server config JSON, which grows with the number
        // of servers; keep that work off the main dispatcher.
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun buildServerItems(servers: List<ServerEntity>): List<ServerListItem> {
        val resources = context.forAppLanguage().resources
        val localeKey = resources.configuration.locales.toLanguageTags()
        val previousCache = endpointSummaryCache
        val cache = HashMap<String, String>()
        val items = servers.map { entity ->
            val key = "$localeKey\u0000${entity.configJson}"
            val summary = previousCache[key] ?: cache[key] ?: computeEndpointSummary(resources, entity)
            cache[key] = summary
            ServerListItem(entity = entity, endpointSummary = summary, latency = null)
        }
        endpointSummaryCache = cache
        return items
    }

    private fun computeEndpointSummary(resources: Resources, entity: ServerEntity): String = runCatching {
        val config = json.decodeFromString<ServerConfig>(entity.configJson)
        val outboundCount = config.proxyOutboundCount()
        if (outboundCount == null) {
            config.endpointSummary()
        } else {
            resources.getQuantityString(
                R.plurals.home_server_multiconnect_summary,
                outboundCount,
                outboundCount,
            )
        }
    }.getOrElse {
        val unknown = context.localizedString(R.string.home_server_endpoint_unknown)
        "${entity.protocol.lowercase(Locale.ROOT)} • $unknown • $unknown"
    }
}
