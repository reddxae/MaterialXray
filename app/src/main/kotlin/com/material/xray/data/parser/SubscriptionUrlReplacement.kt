package com.material.xray.data.parser

import com.material.xray.model.SubscriptionMetadata
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Resolves Happ's `new-url` and `new-domain` directives: a panel that has to move the subscription
 * (for example because its domain got blocked) advertises the replacement and the client swaps its
 * stored subscription URL permanently. `new-url` wins when both are present; anything that is not a
 * valid HTTPS target or that names the current URL is ignored.
 */
object SubscriptionUrlReplacement {
    fun resolve(metadata: SubscriptionMetadata?, currentUrl: String): String? {
        if (metadata == null) return null
        metadata.newUrl?.let { newUrl -> return fullReplacement(newUrl, currentUrl) }
        metadata.newDomain?.let { newDomain -> return domainReplacement(newDomain, currentUrl) }
        return null
    }

    private fun fullReplacement(newUrl: String, currentUrl: String): String? {
        val httpUrl = newUrl.toHttpUrlOrNull() ?: return null
        if (!httpUrl.isHttps) return null
        return httpUrl.toString().takeIf { !it.equals(currentUrl.trim(), ignoreCase = true) }
    }

    private fun domainReplacement(newDomain: String, currentUrl: String): String? {
        val url = currentUrl.trim().toHttpUrlOrNull() ?: return null
        val replaced = runCatching { url.newBuilder().host(newDomain.trim()).build() }.getOrNull() ?: return null
        return replaced.toString().takeIf { !it.equals(url.toString(), ignoreCase = true) }
    }
}
