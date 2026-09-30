package dev.ehsanfahad.darkchamber.core.vault

/**
 * Which stretch of an import a progress report belongs to.
 *
 * An import does not read its payload once. The container reads it twice, a one-shot source has to
 * be copied before either pass can happen, and an image is read again to make its preview — and each
 * of those stretches divides by a different number. Reporting bytes alone therefore left the caller
 * dividing by a denominator that was only sometimes right: it could not know whether the source had
 * spilled, because that is decided inside the import.
 *
 * So the import says which stretch it is in, and the caller keeps the arithmetic:
 *
 * | phase          | what it covers                          | denominator      |
 * |----------------|-----------------------------------------|------------------|
 * | [COPYING]      | draining a one-shot source into `tmp/`  | the payload size |
 * | [ENCRYPTING]   | the container's two passes              | twice that       |
 * | [PREVIEWING]   | the preview encoder's own reads          | none             |
 *
 * **This module still owns no percentage, no total and no notion of a bar.** The phase is not UI
 * vocabulary; it is a statement about which read is happening, which is the one part of the
 * arithmetic the caller cannot work out for itself.
 *
 * Bytes are cumulative **within** a phase and start again at each boundary. Across the two passes of
 * [ENCRYPTING] the count must not restart — one file is one bar filling once — but a count carried
 * across a boundary would put [ENCRYPTING] at the payload size before it had encrypted anything.
 *
 * Not every import reports every phase: a re-openable source never reports [COPYING], and [PREVIEWING]
 * appears once per preview *attempt*, whether or not one is produced.
 */
enum class ImportPhase {
    /**
     * The payload is being copied into app-private scratch because its source opens only once.
     *
     * Reported only on the spill path. These are real bytes with a real total, which matters for a
     * provider that streams: the whole of its transfer happens here, and without this phase it would
     * be the one long stretch of an import with nothing to show.
     */
    COPYING,

    /**
     * The container is reading the payload — size and digest, then the chunk encrypt.
     *
     * Two passes, one count, running to twice the payload size. Whether that is one file or two is
     * the container's business; a caller that reported each pass separately would show the same file
     * arriving twice.
     */
    ENCRYPTING,

    /**
     * A preview is being made from the plaintext.
     *
     * Reported once, with no bytes, before the encoder is asked. The encoder may pass over the
     * payload as many times as it needs — subsample the header, read an orientation tag, decode —
     * and that count is deliberately its own business, so there is nothing here to divide by. It
     * reports the *attempt*: an import whose file gets no preview passes through this phase too,
     * because whether a preview is possible is something only the encoder knows.
     */
    PREVIEWING,
}
