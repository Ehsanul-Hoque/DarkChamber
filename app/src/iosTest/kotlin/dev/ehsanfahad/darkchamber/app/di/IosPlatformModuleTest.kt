package dev.ehsanfahad.darkchamber.app.di

import dev.ehsanfahad.darkchamber.app.thumbnail.IosThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import coil3.PlatformContext
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The real iOS platform module binds a thumbnail encoder — the counterpart to the Android check,
 * and for the same reason: the session resolves the encoder optionally so that a missing binding
 * cannot stop a vault opening, which leaves this test as the only thing that would notice one
 * going missing.
 */
class IosPlatformModuleTest {
    @Test
    fun the_platform_module_binds_the_ios_thumbnail_encoder() {
        val app = koinApplication { modules(platformModule()) }
        try {
            assertIs<IosThumbnailEncoder>(app.koin.get<ThumbnailEncoder>())
        } finally {
            app.close()
        }
    }

    @Test
    fun the_platform_module_binds_a_context_for_the_image_loader() {
        // The session builds its image loader only if this resolves, and falls back to no loader —
        // no thumbnails at all — if it does not. That fallback exists for host tests, which cannot
        // produce a platform context; this is what keeps it from quietly becoming production's
        // behaviour too.
        val app = koinApplication { modules(platformModule()) }
        try {
            assertNotNull(app.koin.get<PlatformContext>())
        } finally {
            app.close()
        }
    }
}
