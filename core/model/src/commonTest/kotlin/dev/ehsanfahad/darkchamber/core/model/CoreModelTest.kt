package dev.ehsanfahad.darkchamber.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke test: proves the module compiles and its common test target actually runs on
 * every platform (JVM host + iOS). Replaced by real domain-type tests in a later cycle.
 */
class CoreModelTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:model", CORE_MODEL_MODULE)
    }
}
