package dev.ehsanfahad.darkchamber.core.db

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreDbTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:db", CORE_DB_MODULE)
    }
}
