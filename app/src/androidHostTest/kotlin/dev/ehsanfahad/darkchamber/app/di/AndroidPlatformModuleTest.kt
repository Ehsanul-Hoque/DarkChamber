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
