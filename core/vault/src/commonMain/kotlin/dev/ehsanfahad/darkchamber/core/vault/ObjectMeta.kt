package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * The list-view metadata for a vault object — the plaintext that gets sealed under MK into the
 * catalog `enc_meta` column (5c middle depth). Deliberately minimal (name + mime) for Phase 0; the
 * richer metadata model (dimensions, duration, origin) is Phase-1 feature work.
 */
@Serializable
data class ObjectMeta(val name: String, val mime: String)

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
