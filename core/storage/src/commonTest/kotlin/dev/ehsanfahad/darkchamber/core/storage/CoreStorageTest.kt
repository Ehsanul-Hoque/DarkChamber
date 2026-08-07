package dev.ehsanfahad.darkchamber.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreStorageTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:storage", CORE_STORAGE_MODULE)
    }
}
