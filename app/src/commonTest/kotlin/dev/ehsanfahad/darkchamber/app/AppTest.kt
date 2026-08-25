package dev.ehsanfahad.darkchamber.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: `:app` compiles and its common test target runs on every platform. */
class AppTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("app", APP_MODULE)
    }
}
