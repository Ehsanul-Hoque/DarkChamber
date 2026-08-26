package dev.ehsanfahad.darkchamber.app

import dev.ehsanfahad.darkchamber.core.model.Outcome
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cycle 5a acceptance (D3) on the iOS simulator — the iOS-runtime-specific glue no common test can
 * reach: [IosVaultRootResolver] resolves the real `<Documents>/vault` root and sets
 * `isExcludedFromBackup = true` (the deferred cycle-3 runtime call), and the full Swift-facing
 * entry [runVaultSelfCheck] cold-opens + round-trips through the real Foundation + native SQLite
 * stack. Runs via `./gradlew :app:iosSimulatorArm64Test`.
 */
class IosVaultRootResolverTest {
    @Test
    fun resolve_marks_the_documents_vault_root_excluded_from_backup() {
        val outcome = IosVaultRootResolver().resolve()

        assertTrue(outcome is Outcome.Ok, "resolve failed: $outcome")
        assertTrue(
            isExcludedFromBackup(outcome.value.toString()),
            "vault root is not isExcludedFromBackup — the cycle-3 runtime call did not take",
        )
    }

    @Test
    fun runVaultSelfCheck_cold_opens_and_round_trips_on_the_simulator() {
        val outcome = makeVaultBootstrap().selfCheck()
        assertTrue(outcome is Outcome.Ok, "iOS cold-open + round-trip failed: $outcome")
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun isExcludedFromBackup(path: String): Boolean = memScoped {
        val url = NSURL.fileURLWithPath(path)
        val holder = alloc<ObjCObjectVar<Any?>>()
        val ok = url.getResourceValue(holder.ptr, NSURLIsExcludedFromBackupKey, null)
        if (!ok) false else (holder.value as? NSNumber)?.boolValue ?: false
    }
}
