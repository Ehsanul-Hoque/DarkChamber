package dev.ehsanfahad.darkchamber.core.testing

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CoreTestingTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("core:testing", CORE_TESTING_MODULE)
    }
}
