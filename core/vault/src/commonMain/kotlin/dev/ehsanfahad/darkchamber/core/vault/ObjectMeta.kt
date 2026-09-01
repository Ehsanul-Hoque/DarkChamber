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
@OptIn(ExperimentalSerializationApi::class)
private val metaCbor: Cbor = Cbor { }

@OptIn(ExperimentalSerializationApi::class)
internal fun encodeMeta(meta: ObjectMeta): ByteArray = metaCbor.encodeToByteArray(meta)

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeMeta(bytes: ByteArray): ObjectMeta = metaCbor.decodeFromByteArray(bytes)
