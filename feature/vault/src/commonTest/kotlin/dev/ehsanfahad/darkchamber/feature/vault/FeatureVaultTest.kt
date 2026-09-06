package dev.ehsanfahad.darkchamber.feature.vault

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: the module compiles and its common test target runs on every platform. */
class FeatureVaultTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("feature:vault", FEATURE_VAULT_MODULE)
    }
}
