package dev.ehsanfahad.darkchamber.core.logging

import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hard rule #4: logs carry identifiers/sizes/error-codes only — never a cause message,
 * a path, an id, or any content derived from a vault item. [redactCause] is the boundary
 * that enforces this for every [VaultError] before it reaches a log sink.
 */
class LoggingTest {
    @Test
    fun crypto_cases_redact_to_their_stable_code() {
        assertEquals("Crypto.IntegrityFailure", redactCause(VaultError.Crypto.IntegrityFailure))
        assertEquals("Crypto.UnsupportedFormatVersion", redactCause(VaultError.Crypto.UnsupportedFormatVersion))
        assertEquals("Crypto.MalformedContainer", redactCause(VaultError.Crypto.MalformedContainer))
    }

    @Test
    fun io_redacts_to_a_content_free_code_never_the_cause_message() {
        val secretPath = "/vault/secret.jpg"
        val redacted = redactCause(VaultError.Io(cause = Exception(secretPath)))

        assertEquals("Io", redacted)
        assertFalse(redacted.contains(secretPath), "redacted Io leaked the cause message")
        assertFalse(redacted.contains("secret"), "redacted Io leaked content")
    }

    @Test
    fun db_redacts_to_a_content_free_code_never_the_cause_message() {
        val secretSql = "SELECT enc_name FROM albums WHERE id = x'deadbeef'"
        val redacted = redactCause(VaultError.Db(cause = IllegalStateException(secretSql)))

        assertEquals("Db", redacted)
        assertFalse(redacted.contains("enc_name"), "redacted Db leaked SQL text")
        assertFalse(redacted.contains("deadbeef"), "redacted Db leaked an id/blob")
    }

    @Test
    fun unexpected_redacts_to_a_content_free_code_never_the_cause_message() {
        val secret = "/vault/note-title.txt while decrypting"
        val redacted = redactCause(VaultError.Unexpected(cause = RuntimeException(secret)))

        assertEquals("Unexpected", redacted)
        assertFalse(redacted.contains("vault"), "redacted Unexpected leaked the cause message")
    }

    @Test
    fun no_redacted_code_contains_a_digit_or_slash_that_could_be_content() {
        // A code is a fixed type token — never an id, size, path, or blob.
        val codes = listOf(
            redactCause(VaultError.Crypto.IntegrityFailure),
            redactCause(VaultError.Io(cause = RuntimeException("42 /tmp/x"))),
            redactCause(VaultError.Db(cause = RuntimeException("row 7"))),
        )
        codes.forEach { code ->
            assertTrue(code.none { it.isDigit() || it == '/' }, "code '$code' may carry content")
        }
    }
}
