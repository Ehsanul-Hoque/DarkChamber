package dev.ehsanfahad.darkchamber.app.di

import dev.ehsanfahad.darkchamber.app.thumbnail.AndroidThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import org.koin.dsl.koinApplication
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
 */
class AndroidPlatformModuleTest {
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
