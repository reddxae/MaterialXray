package com.material.xray.data.repository

import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.model.SubscriptionMetadata
import com.material.xray.model.SubscriptionUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionMetadataMapperTest {
    @Test
    fun toSubscriptionMetadataNormalizesBlankEntityValues() {
        val entity = SubscriptionEntity(
            name = "Sub",
            url = "https://example.com/sub",
            contentDisposition = " ",
            contentType = " application/json ",
            profileTitle = " Provider ",
            profileUpdateIntervalHours = 0,
            subscriptionUploadBytes = -1,
            subscriptionDownloadBytes = 1024,
            subscriptionTotalBytes = null,
            subscriptionExpireAt = 0,
            announce = " hello ",
        )

        assertEquals(
            SubscriptionMetadata(
                contentType = "application/json",
                profileTitle = "Provider",
                subscriptionUserInfo = SubscriptionUserInfo(download = 1024),
                announce = "hello",
            ),
            entity.toSubscriptionMetadata(),
        )
    }

    @Test
    fun withSubscriptionMetadataClearsMissingMetadataFields() {
        val entity = SubscriptionEntity(
            name = "Old",
            url = "https://old.example",
            contentType = "text/plain",
            subscriptionDownloadBytes = 100,
            announce = "old",
        )

        val updated = entity.withSubscriptionMetadata(
            metadata = SubscriptionMetadata(
                profileTitle = " Provider ",
                subscriptionUserInfo = SubscriptionUserInfo(total = 200),
            ),
            resolvedName = "New",
            resolvedUrl = "https://new.example",
            lastUpdated = 123,
        )

        assertEquals("New", updated.name)
        assertEquals("https://new.example", updated.url)
        assertEquals(123, updated.lastUpdated)
        assertNull(updated.contentType)
        assertEquals("Provider", updated.profileTitle)
        assertNull(updated.subscriptionDownloadBytes)
        assertEquals(200L, updated.subscriptionTotalBytes)
        assertNull(updated.announce)
    }
}
