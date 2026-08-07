package dev.ehsanfahad.darkchamber.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreDesignsystemTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:designsystem", CORE_DESIGNSYSTEM_MODULE)
    }
}
