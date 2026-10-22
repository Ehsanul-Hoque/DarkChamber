package dev.ehsanfahad.darkchamber.app.di

import android.content.Context
import android.content.ContextWrapper
import dev.ehsanfahad.darkchamber.app.camera.AndroidCameraController
import dev.ehsanfahad.darkchamber.app.thumbnail.AndroidThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import dev.ehsanfahad.darkchamber.feature.camera.api.CameraController
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * The real Android platform module binds a thumbnail encoder.
 *
 * This is the only thing standing between a deleted binding and a build that quietly stops making
 * thumbnails forever: the session resolves the encoder *optionally*, because a missing cache must
 * never stop a vault opening, and the price of that choice is that nothing else would complain.
 *
 * Only the encoder is resolved — the rest of the module wants a real `Context`, and those bindings
 * are proven by the instrumented tests.
 *
 * **There is no `PlatformContext` binding on Android to assert**, and this comment used to say the
 * opposite. When there was one, resolving it here recursed into a `StackOverflowError`, which was
 * written off as a limitation of host testing; it was the graph reporting that the definition
 * resolved to itself, and a fresh install crashed on launch for exactly that reason. The type is an
 * alias for `Context`, so `androidContext(app)` already provides it and nothing else should.
 * A source rule now fails the build if one comes back; the iOS counterpart is asserted, because
 * there the type is Coil's own and can simply be asked for.
 */
class AndroidPlatformModuleTest {
    /**
     * The real camera is bound — for the encoder's reason: the session falls back to a camera that
     * says it is unavailable when none is, so a deleted binding would ship as a camera that never
     * works and nothing else would complain. Constructing it touches no framework API, so a bare
     * context is enough to resolve it here.
     */
    @Test
    fun the_platform_module_binds_the_android_camera() {
        val app = koinApplication {
            modules(module { single<Context> { ContextWrapper(null) } }, platformModule())
        }
        try {
            assertIs<AndroidCameraController>(app.koin.get<CameraController>())
        } finally {
            app.close()
        }
    }

    @Test
    fun the_platform_module_binds_the_android_thumbnail_encoder() {
        val app = koinApplication { modules(platformModule()) }
        try {
            assertIs<AndroidThumbnailEncoder>(app.koin.get<ThumbnailEncoder>())
        } finally {
            app.close()
        }
    }
}
