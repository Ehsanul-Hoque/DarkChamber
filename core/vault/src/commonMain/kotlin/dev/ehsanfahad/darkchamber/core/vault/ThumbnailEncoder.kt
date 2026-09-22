package dev.ehsanfahad.darkchamber.core.vault

import kotlinx.io.RawSource

/**
 * The longest edge, in pixels, of a generated thumbnail.
 *
 * The gallery is a three-column contact sheet of square-cropped tiles (DESIGN §6), so a tile is
 * roughly a third of the screen's width: ~360 px on a 1080 px phone and ~480 px on a 1440 px one.
 * 512 covers both without a visibly soft tile, and costs about 1% of the payload it previews — on a
 * vault of photos, thumbnails are rounding error against the originals. Smaller would be cheaper
 * and visibly worse on the densest screens; larger buys nothing a 3-column grid can show.
 */
internal const val THUMBNAIL_MAX_EDGE_PX: Int = 512

/**
 * The decode-and-downscale seam: plaintext bytes in, a small encoded preview out.
 *
 * A plain interface rather than `expect`/`actual`, for the same reason as `SecureKeyStore` and the
 * pickers — the platform implementations need platform state (an Android `Context` for the content
 * resolver's hints, an iOS image-source options dictionary), so `:app` constructs the right one and
 * injects it. Host and simulator tests then drive every path through [FakeThumbnailEncoder],
 * including the failure paths a real decoder only reaches with a deliberately corrupt file.
 *
 * Nothing here touches ciphertext. The encoder is handed **plaintext** and returns **plaintext**;
 * sealing the result into its own `.dchm` container is the caller's job, and the encoder never
 * learns a key (hard rule #5).
 */
interface ThumbnailEncoder {
    /**
     * Decode the image behind [openSource], downscale it so its longest edge is at most
     * [maxEdgePx], and return it as a **self-contained encoded image** (JPEG or PNG — the choice is
     * the implementation's; the caller stores the bytes opaque and hands them to an image loader
     * that sniffs the format). Aspect ratio is preserved and a source smaller than [maxEdgePx] is
     * never upscaled.
     *
     * [openSource] yields a fresh source; the encoder owns it and must close it. It is a lambda
     * rather than a `RawSource` because an implementation may need more than one pass — read the
     * header to pick a subsampling factor, then read again to decode at that factor, which is how
     * both platforms keep a 50 MP photo from ever materialising as a full bitmap.
     *
     * **`null` is an ordinary outcome, not an error.** A [mime] this implementation does not handle,
     * a file whose bytes are not the image the MIME claims, a corrupt or truncated payload, or a
     * platform decoder that simply declines — every one of them returns `null`, and the caller must
     * treat every one of them identically as "this item has no thumbnail". There is no error type
     * here to distinguish them, deliberately: nothing downstream would act differently, and "no
     * thumbnail" is a permanent, ordinary condition with its own rendering, not a transient failure
     * to report or retry.
     *
     * An implementation *may* still throw — a platform decoder can run a file out of memory — so a
     * caller must contain that too, and treat it the same way. A thumbnail is a cache; losing one
     * must never cost the user their file.
     *
     * [mime] is a parameter **now** so that video and PDF thumbnails can be added implementation-side
     * later without reshaping this interface or its call site. Today only image MIMEs produce bytes;
     * every other type returns `null` and the tile keeps its type badge. The cheaper signature —
     * bytes in, bytes out — is precisely the one that would have to be broken to add a kind that
     * needs to know what it is looking at before it starts reading.
     */
    fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray?
}

/**
 * The encoder used when none is injected: no thumbnails, for every type. It never opens the source,
 * so an import costs exactly what it did before thumbnails existed.
 *
 * This is the honest default rather than a convenience. `:app` binds the platform encoders; every
 * other caller — a test harness, a future headless tool — gets a vault whose items simply render
 * their type badge, which is a state the UI has to handle anyway (an unsupported type, a file the
 * decoder declines).
 */
internal object NoThumbnails : ThumbnailEncoder {
    override fun encode(mime: String, maxEdgePx: Int, openSource: () -> RawSource): ByteArray? = null
}
