package com.material.xray.data.repository

import com.material.xray.data.db.dao.SubscriptionDao
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.SubscriptionRouting
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SubscriptionRoutingRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val serverRepository: ServerRepository,
) {
    suspend fun apply(routing: SubscriptionRouting): Boolean = replaceActiveRouting(routing)

    suspend fun applyForSubscription(subscriptionId: Long): Boolean {
        val subscription = subscriptionDao.getById(subscriptionId) ?: return false
        val routing = subscription.toSubscriptionRouting()
        if (subscription.providerRouting != null && routing == null) return false
        return replaceActiveRouting(routing)
    }

    suspend fun applyForSelectedServerIfProviderControlled(): Boolean {
        if (settingsRepository.routingPolicyControl.first() != RoutingPolicyControl.SubscriptionProvider) return false
        val server = serverRepository.getById(settingsRepository.lastServerId.first()) ?: return false
        return applyForSubscription(server.subscriptionId)
    }

    private suspend fun replaceActiveRouting(routing: SubscriptionRouting?): Boolean {
        val target = routing?.normalized() ?: SubscriptionRouting(emptyList())
        val current = SubscriptionRouting(
            rules = settingsRepository.routingRules.first(),
            domainStrategy = settingsRepository.routingDomainStrategy.first(),
            domainMatcher = settingsRepository.routingDomainMatcher.first(),
            fallbackOutboundTag = settingsRepository.routingFallbackOutbound.first()?.tag,
        ).normalized()
        if (current == target) return false
        settingsRepository.setSubscriptionRouting(target)
        return true
    }
}
