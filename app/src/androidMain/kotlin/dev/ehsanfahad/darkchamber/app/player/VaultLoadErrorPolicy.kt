package dev.ehsanfahad.darkchamber.app.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.ehsanfahad.darkchamber.core.model.VaultError

/**
 * Media3's retry policy, with one exception: **a read the vault refused on cryptographic grounds is
 * final.** Media3 treats any `IOException` as worth retrying, with a growing delay, and gives up only
 * after several; for a chunk that failed its integrity check that is the same chunk decrypted again to
 * the same answer, and the page that says so held back for seconds. An integrity failure is never
 * retried. Any other failure keeps Media3's own policy.
 */
@UnstableApi
internal object VaultLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isVaultVerdict(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
}

/** Whether [error], or anything behind it, is the vault refusing a read on cryptographic grounds. */
internal fun isVaultVerdict(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { it is VaultReadException && it.error is VaultError.Crypto }
