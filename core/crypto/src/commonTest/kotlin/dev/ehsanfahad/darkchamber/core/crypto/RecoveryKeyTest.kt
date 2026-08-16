package dev.ehsanfahad.darkchamber.core.crypto

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.model.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryKeyTest {
    private val engine = CryptographyKotlinEngine()

    @Test
    fun generate_then_parse_round_trips_the_raw_key() {
        val (raw, display) = RecoveryKey.generate(engine)
        assertEquals(RECOVERY_KEY_SIZE, raw.size)
        assertContentEquals(raw, RecoveryKey.parse(display).getOrNull())
    }

    @Test
    fun display_never_emits_ambiguous_characters() {
        repeat(50) {
            val (_, display) = RecoveryKey.generate(engine)
            val body = display.uppercase().filter { it.isLetterOrDigit() }
            for (c in body) assertTrue(c !in "ILOU", "recovery display must not contain '$c'")
        }
    }

    @Test
    fun parse_tolerates_lowercase_spaces_and_hyphens() {
        val (raw, display) = RecoveryKey.generate(engine)
        val messy = display.lowercase().replace("-", "  ").let { " $it " }
        assertContentEquals(raw, RecoveryKey.parse(messy).getOrNull())
    }

    @Test
    fun parse_normalizes_crockford_aliases_i_l_o() {
        // I/i/l/L → 1, O/o → 0 per Crockford; substituting them must decode to the same key.
        val (raw, display) = RecoveryKey.generate(engine)
        val aliased = display.replace('1', 'I').replace('0', 'O')
        assertContentEquals(raw, RecoveryKey.parse(aliased).getOrNull())
    }

    @Test
    fun a_single_mistyped_character_is_rejected_by_the_checksum() {
        val (_, display) = RecoveryKey.generate(engine)
        // Mistype the trailing checksum char to a different valid symbol — a deterministic single-char
        // error the checksum must reject (a body flip is caught the same way, ~31/32 of the time).
        val last = display.last()
        val wrong = if (last == '0') '1' else '0'
        val tampered = display.dropLast(1) + wrong
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), RecoveryKey.parse(tampered))
    }

    @Test
    fun parse_rejects_an_excluded_letter_in_the_body() {
        val (_, display) = RecoveryKey.generate(engine)
        // 'U' is not a body symbol (Crockford excludes it) → structurally malformed.
        val bad = "U" + display.drop(1)
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), RecoveryKey.parse(bad))
    }

    @Test
    fun parse_rejects_a_wrong_length_string() {
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), RecoveryKey.parse("012345"))
        assertEquals(Outcome.Err(VaultError.Crypto.MalformedContainer), RecoveryKey.parse(""))
    }
}
