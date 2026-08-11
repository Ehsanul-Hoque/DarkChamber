package dev.ehsanfahad.darkchamber.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class DchmHeaderTest {
    @Test
    fun header_round_trips_through_cbor_preserving_all_fields() {
        val header = DchmHeader(
            originalName = "vacation photo.jpg",
            mime = "image/jpeg",
            plaintextSize = 1_234_567L,
            createdAt = 1_699_999_999_000L,
            sha256Plaintext = ByteArray(32) { it.toByte() },
            chunkSize = DchmFormat.DEFAULT_CHUNK_SIZE,
        )
        val bytes = DchmHeaderCodec.encode(header)
        assertEquals(header, DchmHeaderCodec.decode(bytes))
    }
}
