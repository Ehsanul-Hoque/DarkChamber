package dev.ehsanfahad.darkchamber.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreLoggingTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:logging", CORE_LOGGING_MODULE)
    }
}
