package dev.ehsanfahad.darkchamber.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreCryptoTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:crypto", CORE_CRYPTO_MODULE)
    }
}
