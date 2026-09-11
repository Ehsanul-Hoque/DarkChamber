package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class VaultItemKindTest {

    /**
     * These codes are **persisted data** (the `items.kind` column). Renumbering one silently
     * reinterprets every existing row, so the literals are asserted here rather than derived from
     * the enum — a test that read `VaultItemKind.FILE.code` would happily follow a renumbering.
     */
    @Test
    fun `codes are frozen`() {
        assertEquals(0, VaultItemKind.UNKNOWN.code)
        assertEquals(1, VaultItemKind.FILE.code)
        assertEquals(2, VaultItemKind.NOTE.code)
    }

    @Test
    fun `every kind round-trips through its code`() {
        VaultItemKind.entries.forEach { kind ->
            assertSame(kind, VaultItemKind.fromCode(kind.code), "round-trip failed for $kind")
        }
    }

    @Test
    fun `codes are unique`() {
        val codes = VaultItemKind.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size, "duplicate code in $codes")
    }

    /**
     * Zero is deliberately not a real kind: a defaulted or garbage row reads back as [UNKNOWN]
     * rather than masquerading as a file.
     */
    @Test
    fun `zero decodes to UNKNOWN`() {
        assertSame(VaultItemKind.UNKNOWN, VaultItemKind.fromCode(0))
    }

    /**
     * A row written by a newer version of the app must not crash an older one — the catalog is
     * read fail-closed, and an unreadable kind is a display concern, not a fatal one.
     */
    @Test
    fun `unrecognised codes decode to UNKNOWN instead of throwing`() {
        listOf(3, 99, -1, Int.MAX_VALUE, Int.MIN_VALUE).forEach { code ->
            assertSame(VaultItemKind.UNKNOWN, VaultItemKind.fromCode(code), "code $code")
        }
    }
}
