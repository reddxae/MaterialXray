package com.material.xray.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.material.xray.core.app.appKey
import com.material.xray.core.launcher.LauncherIconManager
import com.material.xray.data.db.AppDatabase
import com.material.xray.data.db.dao.AppBypassDao
import com.material.xray.data.db.dao.ServerDao
import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.data.db.entity.AppRouteAssignment
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.db.entity.routeAssignment
import com.material.xray.data.db.entity.toAppBypassEntity
import com.material.xray.model.BackupData
import com.material.xray.model.ConnectionState
import com.material.xray.model.ServerConfig
import com.material.xray.service.AppUpdateScheduler
import com.material.xray.service.ConnectionStateCoordinator
import com.material.xray.service.XrayService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val subscriptionDao: SubscriptionDao,
    private val serverDao: ServerDao,
    private val appBypassDao: AppBypassDao,
    private val settingsRepository: SettingsRepository,
    private val launcherIconManager: LauncherIconManager,
    private val appUpdateScheduler: AppUpdateScheduler,
    private val connectionStateCoordinator: ConnectionStateCoordinator,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val operationMutex = Mutex()
    private val journalStore = BackupRestoreJournalStore(context.filesDir, json)

    suspend fun export(uri: Uri): BackupSummary = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
        val snapshot = createSnapshot()
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Unable to open the selected backup destination")
        output.use { stream ->
            stream.write(json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
            stream.flush()
        }
        BackupImportPlanner.create(snapshot).toSummary()
    }

    suspend fun prepareImport(uri: Uri): PreparedBackupImport {
        val backup = decodeBackup(readBackup(uri))
        return PreparedBackupImport(createImportPlan(backup))
    }

    suspend fun restore(prepared: PreparedBackupImport) = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
        disconnectActiveConnection()
        val previous = createSnapshot()
        journalStore.write(BackupRestoreJournal(previous))

        val result = runCatching {
            applyPlan(prepared.plan)
            applyExternalSettings()
            journalStore.delete()
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                rollbackAfterFailure(previous, error)
                throw error
            }
            rollbackAfterFailure(previous, error)
            throw IOException("Unable to restore the backup", error)
        }
    }

    suspend fun recoverInterruptedRestore(): Boolean = operationMutex.withLock {
        recoverInterruptedRestoreLocked()
    }

    private suspend fun recoverInterruptedRestoreLocked(): Boolean {
        val journal = journalStore.read() ?: return false
        applyPlan(BackupImportPlanner.create(journal.previous))
        applyExternalSettings()
        journalStore.delete()
        return true
    }

    private suspend fun createSnapshot(): BackupData {
        val settings = settingsRepository.getAllAsMap()
        val (subscriptions, servers, appRoutes) = database.withTransaction {
            Triple(subscriptionDao.getAll(), serverDao.getAll(), appBypassDao.getAll())
        }
        val subscriptionKeyById = subscriptions.associate { subscription ->
            subscription.id to "subscription-${subscription.id}"
        }
        val serverKeyById = servers.associate { server -> server.id to "server-${server.id}" }
        val selectedServerKey = settings[LAST_SERVER_ID_SETTING]
            ?.toLongOrNull()
            ?.let(serverKeyById::get)

        return BackupData(
            version = BackupData.CURRENT_VERSION,
            subscriptions = subscriptions.map { subscription ->
                BackupData.BackupSubscription(
                    key = subscriptionKeyById.getValue(subscription.id),
                    name = subscription.name,
                    url = subscription.url,
                    preferJson = subscription.preferJson,
                    autoUpdateIntervalHours = subscription.autoUpdateIntervalHours,
                    descriptionHidden = subscription.descriptionHidden,
                    userAgentMode = subscription.userAgentMode,
                    customUserAgent = subscription.customUserAgent,
                    customHeaders = subscription.customHeaders,
                    metadata = subscription.toSubscriptionMetadata(),
                    appRouting = subscription.toSubscriptionAppRouting(),
                    routing = subscription.toSubscriptionRouting(),
                )
            },
            servers = servers.map { server ->
                BackupData.BackupServer(
                    key = serverKeyById.getValue(server.id),
                    subscriptionKey = subscriptionKeyById.getValue(server.subscriptionId),
                    subscriptionUrl = subscriptions.first { it.id == server.subscriptionId }.url,
                    config = json.decodeFromString<ServerConfig>(server.configJson),
                )
            },
            bypassedApps = appRoutes
                .filter { it.routeAssignment().mode == com.material.xray.data.db.entity.AppRouteMode.Bypass }
                .map { appKey(it.profileId, it.packageName) },
            settings = settings,
            appRoutes = appRoutes.map { route ->
                val assignment = route.routeAssignment()
                BackupData.BackupAppRoute(
                    packageName = route.packageName,
                    profileId = route.profileId,
                    mode = assignment.mode.name,
                    serverKey = assignment.serverId?.let(serverKeyById::get),
                    manual = route.manual,
                )
            },
            selectedServerKey = selectedServerKey,
        )
    }

    private suspend fun applyPlan(plan: BackupImportPlan) {
        var selectedServerId = -1L
        database.withTransaction {
            appBypassDao.deleteAll()
            subscriptionDao.deleteAll()

            val subscriptionIdByKey = plan.subscriptions.associate { planned ->
                planned.key to subscriptionDao.insert(
                    SubscriptionEntity(
                        name = planned.value.name,
                        url = planned.value.url,
                        preferJson = planned.value.preferJson,
                        autoUpdateIntervalHours = planned.value.autoUpdateIntervalHours,
                        descriptionHidden = planned.value.descriptionHidden,
                        userAgentMode = planned.value.userAgentMode,
                        customUserAgent = planned.value.customUserAgent,
                        customHeaders = planned.value.customHeaders,
                    ).withSubscriptionMetadata(planned.value.metadata)
                        .withSubscriptionAppRouting(planned.value.appRouting)
                        .withSubscriptionRouting(planned.value.routing),
                )
            }
            val serverEntities = plan.servers.map { planned ->
                ServerEntity(
                    subscriptionId = subscriptionIdByKey.getValue(planned.subscriptionKey),
                    name = planned.config.name,
                    protocol = planned.config.protocol.name,
                    address = planned.config.address,
                    port = planned.config.port,
                    configJson = json.encodeToString(planned.config),
                    sortOrder = planned.sortOrder,
                )
            }
            val serverIds = if (serverEntities.isEmpty()) emptyList() else serverDao.insertAll(serverEntities)
            val serverIdByKey = plan.servers.map { it.key }.zip(serverIds).toMap()

            val routes = plan.appRoutes.map { planned ->
                AppRouteAssignment(
                    mode = planned.mode,
                    serverId = planned.serverKey?.let(serverIdByKey::getValue),
                ).toAppBypassEntity(
                    packageName = planned.packageName,
                    profileId = planned.profileId,
                    uid = 0,
                    manual = planned.manual,
                )
            }
            if (routes.isNotEmpty()) appBypassDao.insertAll(routes)
            selectedServerId = plan.selectedServerKey?.let(serverIdByKey::getValue) ?: -1L
        }

        settingsRepository.restoreFromMap(
            plan.source.settings + (LAST_SERVER_ID_SETTING to selectedServerId.toString()),
            sourceBackupVersion = plan.source.version,
        )
    }

    private suspend fun disconnectActiveConnection() {
        if (!connectionStateCoordinator.state.value.isRunning()) return
        XrayService.disconnect(context, force = true)
        check(
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) {
                connectionStateCoordinator.state.first { !it.isRunning() }
            } != null,
        ) { "Timed out waiting for the active connection to stop" }
    }

    private suspend fun applyExternalSettings() {
        launcherIconManager.apply(settingsRepository.launcherIcon.first())
        appUpdateScheduler.setEnabled(settingsRepository.appUpdateChecksEnabled.first())
    }

    private suspend fun rollbackAfterFailure(previous: BackupData, original: Throwable) {
        runCatching {
            withContext(NonCancellable) {
                applyPlan(BackupImportPlanner.create(previous))
                applyExternalSettings()
                journalStore.delete()
            }
        }.exceptionOrNull()?.let(original::addSuppressed)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BACKUP_BYTES) throw IOException("The selected backup is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readBackup(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open the selected backup")
        return input.use(::readLimited)
    }

    private fun decodeBackup(bytes: ByteArray): BackupData = try {
        json.decodeFromString(bytes.toString(Charsets.UTF_8))
    } catch (error: SerializationException) {
        throw IOException("The selected file is not a valid MaterialXray backup", error)
    }

    private fun createImportPlan(backup: BackupData): BackupImportPlan = try {
        BackupImportPlanner.create(backup)
    } catch (error: IllegalArgumentException) {
        throw IOException(error.message ?: "The backup is invalid", error)
    }

    private fun BackupImportPlan.toSummary() = BackupSummary(
        subscriptionCount = subscriptions.size,
        serverCount = servers.size,
        appRouteCount = appRoutes.size,
    )

    private fun ConnectionState.isRunning(): Boolean = when (this) {
        ConnectionState.Connecting,
        ConnectionState.ApplyingRoutingChanges,
        ConnectionState.UpdatingRoutingData,
        is ConnectionState.Connected,
        ConnectionState.Disconnecting,
        -> true

        ConnectionState.Disconnected,
        is ConnectionState.Error,
        is ConnectionState.InterfaceBusy,
        is ConnectionState.RestartRequired,
        -> false
    }

    private companion object {
        const val LAST_SERVER_ID_SETTING = "last_server_id"
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        const val DISCONNECT_TIMEOUT_MILLIS = 10_000L
    }
}
