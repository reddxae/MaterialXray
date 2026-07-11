package com.material.xray.model

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionRouting(
    val rules: List<RoutingRule>,
    val domainStrategy: String = DEFAULT_DOMAIN_STRATEGY,
    val domainMatcher: String? = null,
) {
    fun normalized(): SubscriptionRouting = copy(
        rules = rules.mapIndexedNotNull { index, rule ->
            rule.outboundTag.trim().takeIf { it.isNotEmpty() }?.let { outboundTag ->
                rule.copy(
                    id = rule.id.trim().ifEmpty { "subscription-rule-${index + 1}" },
                    name = rule.name.trim().ifEmpty { "Rule ${index + 1}" },
                    outboundTag = outboundTag,
                    domains = rule.domains.cleanEntries(),
                    ips = rule.ips.cleanEntries(),
                    port = rule.port?.trim()?.ifEmpty { null },
                    protocols = rule.protocols.cleanEntries(),
                )
            }
        }.distinctBy { it.id },
        domainStrategy = normalizeDomainStrategy(domainStrategy),
        domainMatcher = normalizeDomainMatcher(domainMatcher),
    )

    companion object {
        const val DEFAULT_DOMAIN_STRATEGY = "IPOnDemand"

        fun normalizeDomainStrategy(value: String?): String = when (value?.trim()?.lowercase()) {
            "asis" -> "AsIs"
            "ipifnonmatch" -> "IPIfNonMatch"
            "ipondemand" -> "IPOnDemand"
            else -> DEFAULT_DOMAIN_STRATEGY
        }

        fun normalizeDomainMatcher(value: String?): String? = when (value?.trim()?.lowercase()) {
            "hybrid" -> "hybrid"
            "linear" -> "linear"
            "mph" -> "mph"
            else -> null
        }

        private fun List<String>.cleanEntries(): List<String> = map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
