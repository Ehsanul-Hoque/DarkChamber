package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError

/** What [VaultSession.createVault] hands back: the live session, its KeyStore, and the recovery key. */
class CreatedVault(
    val session: VaultSession,
    val keyStore: KeyStoreBlob,
    /** The recovery-key display string — shown to the user **once**; never stored by us. */
    val recoveryKey: String,
)

/**
 * The in-memory holder of an unlocked vault's MasterKey (ARCHITECTURE §3.2 / §8). It is the only
 * place a raw MK lives; it wraps/unwraps per-object DEKs on demand and zeroizes the MK on [close].
 *
 * This cycle owns key material only — the lock **lifecycle** (auto-lock timers, `tmp/` purge, the
 * Decompose Locked/Unlocked gate) belongs to a later app/session cycle. The one guarantee here is
 * that [close] best-effort-zeroizes the MK (GC-copy limits acknowledged in the threat model), and
 * that raw DEKs handed to [useDek] are zeroized after the caller is done with them.
 *
 * Construct via [createVault] or [unlock] — never directly.
 */
class VaultSession private constructor(
    private val engine: CryptoEngine,
    private val kdf: KeyDerivation,
    private val mk: ByteArray,
) {
    private var closed = false

    /**
     * A fresh per-object DEK, returned **only in wrapped form** (`nonce ‖ GCM(MK, DEK)`), suitable for
     * the catalog's `dek_wrapped` column. The raw DEK never leaves this call — access it later through
     * [useDek]. Destroying the wrapped bytes is crypto-shredding the object.
     */
    fun generateDek(): ByteArray {
        check(!closed) { "vault session is closed" }
        val dek = engine.randomBytes(DchmFormat.DEK_SIZE)
        val nonce = engine.randomBytes(WRAP_NONCE_SIZE)
        return try {
            nonce + engine.aeadSeal(mk, nonce, DEK_WRAP_AAD, dek)
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Scoped DEK access: unwraps [wrappedDek], runs [block] with the raw DEK (hand it straight to the
     * `.dchm` codec), then zeroizes the DEK — on the normal path **and** if [block] throws. Prefer this
     * over [unwrapDek]; the session keeps ownership of the zeroize guarantee.
     */
    fun <R> useDek(wrappedDek: ByteArray, block: (ByteArray) -> R): Outcome<R> {
        check(!closed) { "vault session is closed" }
        return when (val opened = unwrapDek(wrappedDek)) {
            is Outcome.Err -> opened
            is Outcome.Ok -> {
                val dek = opened.value
                try {
                    Outcome.Ok(block(dek))
                } finally {
                    dek.fill(0)
                }
            }
        }
    }

    /**
     * Unwraps a `dek_wrapped` blob to the raw DEK. The **caller must zeroize** the result — prefer
     * [useDek], which does it for you. A tampered/foreign blob → `IntegrityFailure`; a structurally
     * short blob → `MalformedContainer`.
     */
    fun unwrapDek(wrappedDek: ByteArray): Outcome<ByteArray> {
        check(!closed) { "vault session is closed" }
        if (wrappedDek.size <= WRAP_NONCE_SIZE) return Outcome.Err(VaultError.Crypto.MalformedContainer)
        val nonce = wrappedDek.copyOfRange(0, WRAP_NONCE_SIZE)
        val ciphertext = wrappedDek.copyOfRange(WRAP_NONCE_SIZE, wrappedDek.size)
        return engine.aeadOpen(mk, nonce, DEK_WRAP_AAD, ciphertext)
    }

    /**
     * Re-wraps MK under a new passphrase, returning an updated KeyStore. **No object data is
     * re-encrypted** — only the PASSPHRASE slot changes. [oldPassphrase] must currently unlock the
     * slot (wrong → `IntegrityFailure`); a KeyStore without a PASSPHRASE slot → `MalformedContainer`.
     */
    fun changePassphrase(
        keyStore: KeyStoreBlob,
        oldPassphrase: ByteArray,
        newPassphrase: ByteArray,
        pbkdf2Iterations: Int = PBKDF2_DEFAULT_ITERS,
    ): Outcome<KeyStoreBlob> {
        check(!closed) { "vault session is closed" }
        val slot = keyStore.wraps.firstOrNull { it.slot == Slot.PASSPHRASE }
            ?: return Outcome.Err(VaultError.Crypto.MalformedContainer)
        val mkFromOld = when (val opened = MasterKeyWrap.unwrap(engine, kdf, slot, oldPassphrase)) {
            is Outcome.Err -> return opened
            is Outcome.Ok -> opened.value
        }
        return try {
            val params = KdfParams.Pbkdf2(engine.randomBytes(KDF_SALT_SIZE), pbkdf2Iterations)
            val newWrap = MasterKeyWrap.wrap(engine, kdf, mkFromOld, newPassphrase, Slot.PASSPHRASE, params, engine.randomBytes(WRAP_NONCE_SIZE))
            Outcome.Ok(KeyStoreBlob(keyStore.formatVersion, keyStore.wraps.map { if (it.slot == Slot.PASSPHRASE) newWrap else it }))
        } finally {
            mkFromOld.fill(0)
        }
    }

    /**
     * Mints a fresh recovery key, wraps MK under it, and returns the updated KeyStore plus the new
     * recovery display string (shown once). Replaces any existing RECOVERY slot.
     */
    fun addRecoverySlot(keyStore: KeyStoreBlob): Pair<KeyStoreBlob, String> {
        check(!closed) { "vault session is closed" }
        val (recoveryRaw, display) = RecoveryKey.generate(engine)
        return try {
            val params = KdfParams.Hkdf(engine.randomBytes(KDF_SALT_SIZE), RECOVERY_HKDF_INFO.encodeToByteArray())
            val wrap = MasterKeyWrap.wrap(engine, kdf, mk, recoveryRaw, Slot.RECOVERY, params, engine.randomBytes(WRAP_NONCE_SIZE))
            val kept = keyStore.wraps.filter { it.slot != Slot.RECOVERY }
            KeyStoreBlob(keyStore.formatVersion, kept + wrap) to display
        } finally {
            recoveryRaw.fill(0)
        }
    }

    /** Zeroizes the MK. Idempotent; after this, every operation throws. */
    fun close() {
        if (!closed) {
            mk.fill(0)
            closed = true
        }
    }

    companion object {
        // Domain-separation label for DEK wraps; distinct from any content or MK-wrap AAD.
        private val DEK_WRAP_AAD = "darkchamber:dek-wrap:v1".encodeToByteArray()

        /**
         * Creates a brand-new vault: a random MK wrapped by [passphrase] and a fresh recovery key.
         * The [passphrase] array is **zeroized** before returning (the session keeps only the MK).
         */
        fun createVault(
            engine: CryptoEngine,
            kdf: KeyDerivation,
            passphrase: ByteArray,
            pbkdf2Iterations: Int = PBKDF2_DEFAULT_ITERS,
        ): CreatedVault {
            val mk = engine.randomBytes(MK_SIZE)
            val (recoveryRaw, recoveryDisplay) = RecoveryKey.generate(engine)
            try {
                val pwParams = KdfParams.Pbkdf2(engine.randomBytes(KDF_SALT_SIZE), pbkdf2Iterations)
                val pwWrap = MasterKeyWrap.wrap(engine, kdf, mk, passphrase, Slot.PASSPHRASE, pwParams, engine.randomBytes(WRAP_NONCE_SIZE))
                val recParams = KdfParams.Hkdf(engine.randomBytes(KDF_SALT_SIZE), RECOVERY_HKDF_INFO.encodeToByteArray())
                val recWrap = MasterKeyWrap.wrap(engine, kdf, mk, recoveryRaw, Slot.RECOVERY, recParams, engine.randomBytes(WRAP_NONCE_SIZE))
                val blob = KeyStoreBlob(KEYSTORE_VERSION, listOf(pwWrap, recWrap))
                return CreatedVault(VaultSession(engine, kdf, mk), blob, recoveryDisplay)
            } finally {
                passphrase.fill(0)
                recoveryRaw.fill(0)
            }
        }

        /**
         * Unlocks an existing vault by unwrapping MK from the [slot] wrap with [secret]. A wrong secret
         * or tamper → `IntegrityFailure`; no such slot → `MalformedContainer`.
         */
        fun unlock(
            engine: CryptoEngine,
            kdf: KeyDerivation,
            keyStore: KeyStoreBlob,
            secret: ByteArray,
            slot: Slot,
        ): Outcome<VaultSession> {
            val entry = keyStore.wraps.firstOrNull { it.slot == slot }
                ?: return Outcome.Err(VaultError.Crypto.MalformedContainer)
            return when (val opened = MasterKeyWrap.unwrap(engine, kdf, entry, secret)) {
                is Outcome.Err -> opened
                is Outcome.Ok -> Outcome.Ok(VaultSession(engine, kdf, opened.value))
            }
        }
    }
}
