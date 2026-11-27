package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError

/** Length of the recovery key, in bytes (256-bit, full entropy — see FORMAT.md §3). */
const val RECOVERY_KEY_SIZE = 32

/**
 * Encodes/decodes the 256-bit recovery key as grouped Crockford base32 with a checksum (FORMAT.md
 * §3). The **raw bytes are the source of truth**; this string is presentation only — a fresh-device
 * unlock path a user can write down. Crockford's alphabet omits the ambiguous `I L O U`, and decode
 * folds the look-alikes `I/L → 1`, `O → 0`, so an honest transcription error is tolerated while a
 * real slip is caught by the trailing checksum *before* it costs an unwrap attempt (a wrong key
 * would otherwise surface only as an `IntegrityFailure`, §3.1).
 */
object RecoveryKey {
    // Crockford base32: no I, L, O, U. Index = 5-bit value.
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP = 4
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF

    /** One base32 symbol carries 5 bits; the mask keeps exactly those. */
    private const val BITS_PER_SYMBOL = 5
    private const val SYMBOL_MASK = 0x1F

    /** The checksum's position weight (see [checkSymbol]). */
    private const val CHECK_WEIGHT = 31

    private val decodeMap: Map<Char, Int> =
        buildMap {
            ALPHABET.forEachIndexed { i, c -> put(c, i) }
            // Crockford look-alike folds.
            put('I', 1)
            put('L', 1)
            put('O', 0)
        }

    /** Body length for a 32-byte key: ceil(256 / 5) = 52 base32 chars. */
    private const val BODY_LEN = (RECOVERY_KEY_SIZE * BITS_PER_BYTE + BITS_PER_SYMBOL - 1) / BITS_PER_SYMBOL

    /** A fresh random recovery key and its display encoding. */
    fun generate(engine: CryptoEngine): Pair<ByteArray, String> {
        val raw = engine.randomBytes(RECOVERY_KEY_SIZE)
        return raw to encode(raw)
    }

    fun encode(raw: ByteArray): String {
        require(raw.size == RECOVERY_KEY_SIZE) { "recovery key must be $RECOVERY_KEY_SIZE bytes, was ${raw.size}" }
        val body = StringBuilder(BODY_LEN)
        var buffer = 0
        var bits = 0
        for (b in raw) {
            buffer = (buffer shl BITS_PER_BYTE) or (b.toInt() and BYTE_MASK)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_SYMBOL) {
                bits -= BITS_PER_SYMBOL
                body.append(ALPHABET[(buffer ushr bits) and SYMBOL_MASK])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) body.append(ALPHABET[(buffer shl (BITS_PER_SYMBOL - bits)) and SYMBOL_MASK])
        body.append(checkSymbol(raw))
        return group(body)
    }

    // Each refusal returns its typed error where it is found, so detekt's return count is suppressed.
    @Suppress("ReturnCount")
    fun parse(text: String): Outcome<ByteArray> {
        val cleaned = text.uppercase().filter { it != '-' && !it.isWhitespace() }
        if (cleaned.length != BODY_LEN + 1) return Outcome.Err(VaultError.Crypto.MalformedContainer)

        val body = cleaned.substring(0, BODY_LEN)
        val providedCheck = cleaned[BODY_LEN]

        val raw = decodeBody(body) ?: return Outcome.Err(VaultError.Crypto.MalformedContainer)
        // Fold the checksum char through the same Crockford aliases (I/L→1, O→0) before comparing, so
        // a look-alike typo in the checksum position is tolerated exactly like one in the body.
        val providedIdx = decodeMap[providedCheck] ?: return Outcome.Err(VaultError.Crypto.MalformedContainer)
        if (providedIdx !=
            decodeMap.getValue(checkSymbol(raw))
        ) {
            return Outcome.Err(VaultError.Crypto.MalformedContainer)
        }
        return Outcome.Ok(raw)
    }

    // An unknown symbol, non-zero padding and a wrong length each return null where found, so
    // detekt's return count is suppressed.
    @Suppress("ReturnCount")
    private fun decodeBody(body: String): ByteArray? {
        val out = ArrayList<Byte>(RECOVERY_KEY_SIZE)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val v = decodeMap[c] ?: return null
            buffer = (buffer shl BITS_PER_SYMBOL) or v
            bits += BITS_PER_SYMBOL
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                out.add(((buffer ushr bits) and BYTE_MASK).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        // The 4 trailing bits of a 52-char body are padding and MUST be zero.
        if (buffer != 0) return null
        return if (out.size == RECOVERY_KEY_SIZE) out.toByteArray() else null
    }

    // A position-weighted checksum reduced mod 32, so the symbol is always a canonical base32 char
    // (never the ambiguous I/L/O/U, unlike Crockford's mod-37 set). A transcription guard, not a MAC —
    // real key integrity is the GCM tag on unwrap.
    private fun checkSymbol(raw: ByteArray): Char {
        var rem = 0
        for (b in raw) rem = (rem * CHECK_WEIGHT + (b.toInt() and BYTE_MASK)) and SYMBOL_MASK
        return ALPHABET[rem]
    }

    private fun group(body: CharSequence): String {
        val sb = StringBuilder(body.length + body.length / GROUP)
        for (i in body.indices) {
            if (i > 0 && i % GROUP == 0) sb.append('-')
            sb.append(body[i])
        }
        return sb.toString()
    }
}
