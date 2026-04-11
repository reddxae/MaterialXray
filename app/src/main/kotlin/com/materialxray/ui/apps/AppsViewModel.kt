package com.materialxray.ui.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.materialxray.data.db.dao.AppBypassDao
import com.materialxray.data.db.entity.AppBypassEntity
import com.materialxray.service.RoutingChangeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppItem(
    val packageName: String,
    val name: String,
    val uid: Int,
    val icon: Drawable?,
    val isExcluded: Boolean,
)

@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appBypassDao: AppBypassDao,
    private val routingChangeManager: RoutingChangeManager,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val bypassedApps = appBypassDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _installedApps = MutableStateFlow<List<AppItem>>(emptyList())

    val apps: StateFlow<List<AppItem>> = combine(_installedApps, bypassedApps, _searchQuery) { installed, bypassed, query ->
        val bypassSet = bypassed.filter { it.excluded }.map { it.packageName }.toSet()
        installed
            .map { it.copy(isExcluded = it.packageName in bypassSet) }
            .filter {
                query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { info ->
                        AppItem(
                            packageName = info.packageName,
                            name = info.loadLabel(pm).toString(),
                            uid = info.uid,
                            icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                            isExcluded = false,
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
            _installedApps.value = apps
        }
    }

    fun toggleExclude(app: AppItem) {
        viewModelScope.launch {
            if (app.isExcluded) appBypassDao.delete(app.packageName)
            else appBypassDao.upsert(AppBypassEntity(app.packageName, app.uid, excluded = true))
            routingChangeManager.markPendingChanges()
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun excludeAll() {
        viewModelScope.launch {
            _installedApps.value.forEach { appBypassDao.upsert(AppBypassEntity(it.packageName, it.uid, excluded = true)) }
            routingChangeManager.markPendingChanges()
        }
    }

    fun includeAll() {
        viewModelScope.launch {
            appBypassDao.deleteAll()
            routingChangeManager.markPendingChanges()
        }
    }
}
