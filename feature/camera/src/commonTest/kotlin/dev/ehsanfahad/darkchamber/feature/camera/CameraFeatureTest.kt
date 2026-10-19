package dev.ehsanfahad.darkchamber.feature.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/** Smoke test: module compiles and its common test target runs on every platform. */
class CameraFeatureTest {
    @Test
    fun moduleMarkerResolves() {
        assertEquals("feature:camera", CAMERA_FEATURE_MODULE)
    }
}
