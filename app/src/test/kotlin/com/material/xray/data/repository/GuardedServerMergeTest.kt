package com.material.xray.data.repository

import com.material.xray.data.db.entity.ServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedServerMergeTest {
    @Test
    fun `keeps the fetched set untouched when nothing is guarded`() {
        val existing = listOf(server(id = 1, name = "A"), server(id = 2, name = "B"))
        val fetched = listOf(server(id = 0, name = "A", configJson = "new-a"), server(id = 0, name = "C"))

        assertEquals(fetched, mergeGuardedServersInto(existing, fetched))
    }

    @Test
    fun `a guarded server keeps its id and edited config in the fetched slot`() {
        val guarded = server(id = 7, name = "Tokyo", configJson = "edited", guarded = true, edited = true)
        val existing = listOf(guarded, server(id = 8, name = "Osaka"))
        val fetched = listOf(
            server(id = 0, name = "Osaka", configJson = "fresh-osaka"),
            server(id = 0, name = "Tokyo", configJson = "fresh-tokyo"),
        )

        val merged = mergeGuardedServersInto(existing, fetched)

        assertEquals(2, merged.size)
        // The provider's ordering wins: Osaka first, then the guarded Tokyo in its slot.
        assertEquals("fresh-osaka", merged[0].configJson)
        assertEquals(0L, merged[0].id)
        assertEquals(7L, merged[1].id)
        assertEquals("edited", merged[1].configJson)
        assertTrue(merged[1].guarded)
        assertTrue(merged[1].edited)
    }

    @Test
    fun `sort order follows the merged position`() {
        val existing = listOf(server(id = 3, name = "B", guarded = true))
        val fetched = listOf(server(id = 0, name = "A", sortOrder = 0), server(id = 0, name = "B", sortOrder = 1))

        val merged = mergeGuardedServersInto(existing, fetched)

        assertEquals(listOf(0, 1), merged.map { it.sortOrder })
    }

    @Test
    fun `a guarded server the provider dropped is appended rather than lost`() {
        val guarded = server(id = 9, name = "Gone", configJson = "edited", guarded = true)
        val fetched = listOf(server(id = 0, name = "A"), server(id = 0, name = "B"))

        val merged = mergeGuardedServersInto(listOf(guarded), fetched)

        assertEquals(3, merged.size)
        assertEquals(9L, merged.last().id)
        assertEquals(2, merged.last().sortOrder)
    }

    @Test
    fun `two guarded servers sharing a name both survive`() {
        val first = server(id = 1, name = "Dup", configJson = "one", guarded = true)
        val second = server(id = 2, name = "Dup", configJson = "two", guarded = true)
        val fetched = listOf(server(id = 0, name = "Dup", configJson = "fresh"))

        val merged = mergeGuardedServersInto(listOf(first, second), fetched)

        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun `a blank name does not claim the first blank-named slot`() {
        val guarded = server(id = 4, name = "  ", configJson = "edited", guarded = true)
        val fetched = listOf(server(id = 0, name = "", configJson = "fresh"))

        val merged = mergeGuardedServersInto(listOf(guarded), fetched)

        // The blank-named guarded row is appended rather than swapped in, matching how the id
        // replacement map refuses to identify servers by a blank name.
        assertEquals(2, merged.size)
        assertEquals("fresh", merged[0].configJson)
        assertEquals(4L, merged[1].id)
    }

    private fun server(
        id: Long,
        name: String,
        configJson: String = "config-$name",
        guarded: Boolean = false,
        edited: Boolean = false,
        sortOrder: Int = 0,
    ) = ServerEntity(
        id = id,
        subscriptionId = 1,
        name = name,
        protocol = "VLESS",
        address = "example.com",
        port = 443,
        configJson = configJson,
        sortOrder = sortOrder,
        edited = edited,
        guarded = guarded,
    )
}
