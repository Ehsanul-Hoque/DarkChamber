package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The list-view metadata for a vault object — the plaintext that gets sealed under MK into the
 * catalog `enc_meta` column (5c middle depth). Deliberately minimal; the richer metadata model
 * (dimensions, duration, origin) waits for the cycle that first reads it.
 *
 * [thumbnailKey] is the DEK of this object's thumbnail container, **wrapped under MK**, absent when
 * the object has no thumbnail — an unsupported type, a file the decoder declined, or an item
 * imported before thumbnails existed. **Absent is an ordinary, permanent state**, not a pending one.
 *
 * Wrapped rather than raw, even though this whole blob is sealed under MK: the listing decodes
 * `enc_meta` for every row it displays, so a raw key would put one plaintext DEK per item into the
 * gallery's state, reachable in memory well after a lock zeroized the master key. Wrapped, it is
 * inert without an open session (hard rule #5) and costs one unwrap when a tile is actually drawn.
 *
 * ### Why the key lives here and not in an `items` column
 *
 * `enc_meta` is already sealed under MK, so a key stored inside it is protected exactly as well as
 * `dek_wrapped` in the row next to it — the blob is the security boundary, not the column. Against
 * that, a new column would be a **schema migration**, and "old vaults always open" is
 * release-blocking while `migrationTest` does not yet exist; whereas `enc_meta` is versionless CBOR
 * read with `ignoreUnknownKeys`, so adding a field is a non-event on vaults already in the field
 * (see the compat tests). Deleting the catalog row still destroys both keys together, so
 * crypto-shredding is unaffected.
 *
 * The thumbnail gets its **own** DEK rather than reusing the object's: `docs/FORMAT.md` §2 requires
 * a fresh random DEK per object precisely so no `nonce_prefix` is ever reused across containers,
 * and a thumbnail is a separate container. That is a normative requirement, not a preference.
 */
@Serializable
data class ObjectMeta(
    val name: String,
    val mime: String,
    val thumbnailKey: ByteArray? = null,
) {
    // Hand-written rather than generated, because the generated pair would compare [thumbnailKey] by
    // identity. `VaultItemSummary` compares its whole `meta`, and the gallery re-decodes rows on
    // every catalog change — so two decodes of an unchanged row would look like a change, redrawing
    // tiles and replaying the arrival animation on items that never moved.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ObjectMeta) return false
        return name == other.name &&
            mime == other.mime &&
            thumbnailKey.contentEqualsOrBothNull(other.thumbnailKey)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + (thumbnailKey?.contentHashCode() ?: 0)
        return result
    }

    /**
     * MIME and thumbnail *presence* only. The name is decrypted content (hard rule #4) and
     * [thumbnailKey] is key material (hard rule #5) — neither may reach a log, and the generated
     * `toString` would print the name verbatim.
     */
    override fun toString(): String =
        "ObjectMeta(mime=$mime, thumbnailKey=${if (thumbnailKey == null) "absent" else "present"})"
}

private fun ByteArray?.contentEqualsOrBothNull(other: ByteArray?): Boolean =
    if (this == null || other == null) this == null && other == null else contentEquals(other)

// One CBOR instance for enc_meta framing — the same serialization idiom as the .dchm header and the
// KeyStore. enc_meta bytes are non-normative (no committed format vector): they never leave this
// module's own seal/open, so the config need not match the container's byte-for-byte.
//
// `internal` rather than private only so the forward-compatibility test can decode the same
// configuration into a wider shape; everything else goes through encodeMeta/decodeMeta.
//
// `ignoreUnknownKeys` is what makes the metadata model extensible on vaults that already exist: a
// later cycle adds a field, and a build that predates it still reads those rows instead of failing
// them as unverifiable. It is safe here and only here — enc_meta is a display record, not an
// authenticity claim. The .dchm header stays strict, because an unexpected key there is tampering.
@OptIn(ExperimentalSerializationApi::class)
internal val metaCbor: Cbor = Cbor { ignoreUnknownKeys = true }

@OptIn(ExperimentalSerializationApi::class)
internal fun encodeMeta(meta: ObjectMeta): ByteArray = metaCbor.encodeToByteArray(meta)

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeMeta(bytes: ByteArray): ObjectMeta = metaCbor.decodeFromByteArray(bytes)
