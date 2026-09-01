package dev.ehsanfahad.darkchamber.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreVaultTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:vault", CORE_VAULT_MODULE)
    }
}
