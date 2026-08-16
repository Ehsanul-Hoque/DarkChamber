package dev.ehsanfahad.darkchamber.core.crypto

import kotlinx.serialization.cbor.Cbor

/**
 * The single CBOR configuration for every DarkChamber on-disk structure (the `.dchm` header, the
 * KeyStore blob, wrap AAD). One instance so the serialization settings can never drift apart between
 * writer and reader — a divergence would silently change committed bytes and break old vaults. Default
 * settings; if this ever needs options, they change here for all callers at once.
 */
internal val darkChamberCbor: Cbor = Cbor { }
