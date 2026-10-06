package dev.ehsanfahad.darkchamber.core.vault

import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.flatMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * An album's secret: its name, sealed under MK into the catalog's `enc_name` column.
 *
 * **A struct rather than the bare string**, which [ObjectMeta] learned the hard way — it gained
 * `thumbnailKey` after it was first written and needed the forward-compatibility guard below to do
 * it without breaking vaults already in the field. A raw sealed string has nowhere to grow: adding
 * anything beside it would be a format change on data that already exists.
 *
 * Sealed and opened with [VaultSession.sealMeta] / [VaultSession.openMeta] — the same pair
 * `enc_meta` uses, no new construction and no cipher primitive in sight (hard rule #2). Everything
 * about an album except its name is a plaintext index column, so this is the whole of what an album
 * keeps secret.
 */
@Serializable
data class AlbumMeta(val name: String) {
    /**
     * Carries nothing. The name is decrypted vault content (hard rule #4), it is the only field, and
     * the generated `toString` would print it verbatim into any log line that touched an album —
     * including the length, which is itself a fact about the name.
     */
    override fun toString(): String = "AlbumMeta(name=<redacted>)"
}

/**
 * Seal [meta] for the `enc_name` column. Requires an open session; a closed one throws, as every
 * other session-guarded operation does.
 */
internal fun VaultSession.sealAlbumMeta(meta: AlbumMeta): ByteArray = sealMeta(encodeAlbumMeta(meta))

/**
 * Open an `enc_name` blob.
 *
 * Mirrors `VaultObjects.readMeta`: a tampered or foreign blob surfaces as
 * [VaultError.Crypto.IntegrityFailure] from the session, a structurally short one as
 * [VaultError.Crypto.MalformedContainer], and bytes that decrypt but will not decode as
 * `MalformedContainer` too. **Never retried and never half-rendered** — an integrity failure here is
 * a tampering signal, and the caller's job is to show the album as damaged rather than to guess at a
 * name (docs/engineering/PATTERNS.md §5).
 *
 * ### One thing this cannot distinguish
 *
 * An `enc_name` and an `enc_meta` blob are sealed under the same key with the same AAD, so this
 * cannot tell which column a blob came from. Because the CBOR here ignores unknown keys, an item's
 * `enc_meta` moved into `albums.enc_name` opens as an album named after that file. The reverse fails
 * closed — [ObjectMeta] requires `mime`, which an album blob does not carry — so the confusion is
 * one-directional, and both directions need write access to the catalog to arrange.
 *
 * Accepted rather than fixed here: separating the two domains means a new AAD for metadata kinds,
 * which belongs to `core:crypto` and to the format doc, not to a feature inventing one at the
 * call site (hard rule #2). The effect is a wrong label on a screen, not a key or a plaintext
 * reaching anywhere it should not. Pinned by a test so that whoever adds the separation is told.
 */
internal fun VaultSession.openAlbumMeta(encName: ByteArray): Outcome<AlbumMeta> =
    openMeta(encName).flatMap { bytes ->
        try {
            Outcome.Ok(decodeAlbumMeta(bytes))
        } catch (e: Exception) {
            Outcome.Err(VaultError.Crypto.MalformedContainer)
        }
    }

// Deliberately the same `metaCbor` instance `enc_meta` is framed with, rather than a second
// configuration: its `ignoreUnknownKeys` is what lets a later field be added to an album without
// failing albums that predate it, and a separate instance would be one more place for that setting
// to drift out of agreement.
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeAlbumMeta(meta: AlbumMeta): ByteArray = metaCbor.encodeToByteArray(meta)

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeAlbumMeta(bytes: ByteArray): AlbumMeta = metaCbor.decodeFromByteArray(bytes)
