package dev.ehsanfahad.darkchamber.app.di

import dev.ehsanfahad.darkchamber.app.thumbnail.IosThumbnailEncoder
import dev.ehsanfahad.darkchamber.core.vault.ThumbnailEncoder
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertIs

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
}
