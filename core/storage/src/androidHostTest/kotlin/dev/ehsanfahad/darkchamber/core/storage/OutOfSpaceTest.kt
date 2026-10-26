package dev.ehsanfahad.darkchamber.core.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * On Android a full disk arrives as an `IOException` whose cause is the platform's
 * `android.system.ErrnoException` carrying `ENOSPC` — libcore's own typed report, which the
 * classifier reads by type and number rather than by message. That class cannot be constructed on
 * the host, so the recognising branch is checked on a device; here, only that nothing else is
 * mistaken for it — not even a message that *says* the disk is full, which is not the platform's
 * report and must not be treated as one.
 */
class OutOfSpaceTest {
    @Test
    fun no_failure_without_the_platform_errno_is_called_a_full_disk() {
        for (failure in listOf(
            IOException("write failed: ENOSPC (No space left on device)"),
            IOException("No space left on device"),
            IllegalStateException("encrypt failed", IOException("disk")),
            IOException(),
        )) {
            assertFalse(isOutOfSpace(failure), failure.message)
        }
    }
}
