package dev.ehsanfahad.darkchamber.core.crypto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * The single CBOR configuration for every DarkChamber on-disk structure (the `.dchm` header, the
 * KeyStore blob, wrap AAD). One instance so the serialization settings can never drift apart between
 * writer and reader — a divergence would silently change committed bytes and break old vaults. Default
 * settings; if this ever needs options, they change here for all callers at once.
 *
 * **Why the experimental CBOR API is acceptable here**, which every use of it opts into explicitly: the
 * library version is pinned in the catalog, and what this format writes is pinned too, independently
 * of the library — by the committed `.dchm` and KeyStore test vectors (`docs/format-test-vectors/`),
 * which compare exact bytes. An upgrade that changed the encoding would fail those before it could
 * reach a vault.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val darkChamberCbor: Cbor = Cbor { }
