package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Sealing an album's name, and what happens when the blob will not open.
 *
 * The round-trip goes through a **real session and a real engine**, not a fake: the point of sealing
 * album names with the existing `sealMeta` / `openMeta` pair is that nothing new is invented, and a
 * test that stubbed the crypto would assert the invention rather than the reuse.
 */
class AlbumMetaTest {
    private val engine = CryptographyKotlinEngine()
    private val kdf = DefaultKeyDerivation(engine)
    private val cheapKdf = PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1)

    private fun newSession(): VaultSession =
        VaultSession.createVault(engine, kdf, "correct horse battery".encodeToByteArray(), cheapKdf).session

    @Test
    fun a_sealed_name_opens_back_to_itself() {
        val session = newSession()
        // Non-ASCII on purpose: the name is a user string, and CBOR framing it is the whole job here.
        val meta = AlbumMeta("Trip to Köln 2026 🏔")

        val opened = session.openAlbumMeta(session.sealAlbumMeta(meta))

        assertEquals(Outcome.Ok(meta), opened)
    }

    @Test
    fun the_sealed_bytes_do_not_contain_the_name() {
        val session = newSession()
        val sealed = session.sealAlbumMeta(AlbumMeta("Receipts"))

        assertFalse(
            sealed.decodeToString().contains("Receipts"),
            "an album name is a secret; the column it lives in must not spell it out",
        )
    }

    /**
     * A flipped byte is a tampering signal, and it must arrive as one. Never retried, never
     * half-rendered: the caller shows a damaged album rather than guessing at a name.
     */
    @Test
    fun a_tampered_blob_is_an_integrity_failure_not_a_throw() {
        val session = newSession()
        val sealed = session.sealAlbumMeta(AlbumMeta("Receipts"))
        sealed[sealed.lastIndex] = (sealed[sealed.lastIndex] + 1).toByte()

        val opened = session.openAlbumMeta(sealed)

        assertIs<Outcome.Err>(opened, "the failure is returned, not thrown")
        assertEquals(VaultError.Crypto.IntegrityFailure, opened.error, "surfaced as possible tampering")
    }

    @Test
    fun a_blob_too_short_to_be_a_container_is_malformed_rather_than_tampered() {
        val session = newSession()

        val opened = session.openAlbumMeta(ByteArray(4))

        assertIs<Outcome.Err>(opened)
        assertEquals(
            VaultError.Crypto.MalformedContainer,
            opened.error,
            "structurally impossible is not the same claim as tampered with",
        )
    }

    /**
     * The failure between the two: the blob is authentic, so nothing was tampered with, but the
     * plaintext inside it is not an `AlbumMeta`. It must not escape as an exception through a flow.
     */
    @Test
    fun bytes_that_decrypt_but_do_not_decode_are_malformed() {
        val session = newSession()
        val sealed = session.sealMeta(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

        val opened = session.openAlbumMeta(sealed)

        assertIs<Outcome.Err>(opened)
        assertEquals(VaultError.Crypto.MalformedContainer, opened.error)
    }

    /**
     * Forward compatibility, which is the reason this is a struct at all: a build that predates a
     * later field still reads albums written by one that has it.
     */
    @Test
    fun an_unknown_field_in_the_sealed_record_is_ignored_rather_than_failing_the_album() {
        val session = newSession()
        // What a future version would write: the same record plus a field this build knows nothing
        // about. ObjectMeta stands in for "AlbumMeta with an extra key", since it is a wider record
        // framed by the same CBOR configuration.
        val fromTheFuture = session.sealMeta(encodeMeta(ObjectMeta(name = "Trip", mime = "x/album")))

        val opened = session.openAlbumMeta(fromTheFuture)

        assertEquals(Outcome.Ok(AlbumMeta("Trip")), opened, "the known field still reads")
    }

    /**
     * The limitation stated in `openAlbumMeta`'s KDoc, pinned so it is a decision rather than a
     * surprise: `enc_name` and `enc_meta` are sealed under the same key with the same AAD, so an
     * item's metadata moved into an album's column opens as an album named after that file.
     *
     * The reverse **fails closed**, which is the half that matters — an album name cannot be passed
     * off as an item's metadata, because `ObjectMeta` requires a `mime` the album blob never had.
     *
     * If a later change gives metadata kinds separate AADs, the first assertion here is what tells
     * whoever did it that they have fixed this. Changing it to expect a failure is the correct
     * response to that; silently deleting it is not.
     */
    @Test
    fun the_two_metadata_kinds_are_not_domain_separated_and_only_one_direction_fails_closed() {
        val session = newSession()

        val itemBlob = session.sealMeta(encodeMeta(ObjectMeta(name = "payslip.pdf", mime = "application/pdf")))
        assertEquals(
            Outcome.Ok(AlbumMeta("payslip.pdf")),
            session.openAlbumMeta(itemBlob),
            "currently permitted: an item's sealed metadata reads as an album name",
        )

        val albumBlob = session.sealAlbumMeta(AlbumMeta("Receipts"))
        val asItem = metaCborDecodeFailure { decodeMeta(session.openMeta(albumBlob).unwrapBytes()) }
        assertTrue(asItem, "an album name cannot masquerade as item metadata — mime is required")
    }

    @Test
    fun toString_does_not_print_the_name() {
        val printed = AlbumMeta("Trip to Köln 2026").toString()

        assertFalse(printed.contains("Köln"), "the name is decrypted content: $printed")
        assertFalse(printed.contains("Trip"), "including any part of it: $printed")
        assertFalse(printed.contains("17"), "and not its length either, which is a fact about it")
    }

    private fun Outcome<ByteArray>.unwrapBytes(): ByteArray = (this as Outcome.Ok).value

    private inline fun metaCborDecodeFailure(block: () -> Unit): Boolean =
        try {
            block()
            false
        } catch (e: Exception) {
            true
        }
}
