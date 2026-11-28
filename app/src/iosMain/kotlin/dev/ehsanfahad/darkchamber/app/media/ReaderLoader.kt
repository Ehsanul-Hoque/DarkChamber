package dev.ehsanfahad.darkchamber.app.media

import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.Volatile

/**
 * AVFoundation's requests for an asset's bytes, answered from a [RangeReader] on the loader's serial
 * queue — the one AVFoundation calls this delegate on, so the reader is only ever touched there. Never a
 * URL or a path of the file; the scheme exists only for this. Shared by the video preview and the
 * player.
 *
 * A request is accepted and answered after returning, as Apple's resource-loader pattern has it, rather
 * than inside the call that offers it. A read the vault refuses fails the request — never answered
 * short — and the first such error is kept in [failure], so a player that stops can say whether the
 * vault refused a read or its decoders declined the format.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ReaderLoader(
    private val reader: RangeReader,
    private val contentType: String,
    private val queue: dispatch_queue_t,
) : NSObject(),
    AVAssetResourceLoaderDelegateProtocol {
    private var closed = false
    private val chunk = ByteArray(CHUNK_BYTES)

    /** The first error a read came back with. Written on the loader's queue; read by the player. */
    @Volatile
    var failure: VaultError? = null
        private set

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        if (closed) return false
        dispatch_async(queue) { serve(shouldWaitForLoadingOfRequestedResource) }
        return true
    }

    // Runs on a dispatch queue, where an exception would end the process — so nothing is let out of
    // here. A read that fails fails the request (see [answer]).
    private fun serve(request: AVAssetResourceLoadingRequest) {
        try {
            answer(request)
        } catch (_: Exception) {
            request.finishLoadingWithError(null)
        }
    }

    private fun answer(request: AVAssetResourceLoadingRequest) {
        if (closed) {
            request.finishLoadingWithError(null)
            return
        }
        val length = reader.length
        request.contentInformationRequest?.let { info ->
            info.contentType = contentType
            info.contentLength = length
            info.byteRangeAccessSupported = true
            // Read on demand like a local file, rather than streamed in order.
            info.setEntireLengthAvailableOnDemand(true)
        }
        request.dataRequest?.let { data ->
            var at = data.requestedOffset
            val end = if (data.requestsAllDataToEndOfResource) length else minOf(length, at + data.requestedLength)
            while (at < end && !request.cancelled) {
                val n =
                    when (val read = reader.readAt(at, chunk, 0, minOf(CHUNK_BYTES.toLong(), end - at).toInt())) {
                        // A chunk that fails its tag, a lock: the request fails, never answered short.
                        is Outcome.Err -> {
                            if (failure == null) failure = read.error
                            request.finishLoadingWithError(null)
                            return
                        }

                        is Outcome.Ok -> {
                            read.value
                        }
                    }
                if (n <= 0) break
                data.respondWithData(chunk.toNSData(n))
                at += n
            }
        }
        request.finishLoading()
    }

    /**
     * Stops serving and clears its own buffer. Call it on the loader's queue, so no request it is
     * serving runs into it. The reader is not closed here: its owner closes it after this.
     */
    fun close() {
        closed = true
        chunk.fill(0)
    }
}

private const val CHUNK_BYTES = 256 * 1024

/** What AVFoundation is told an asset is: its type identifier, and the extension its URL's path carries. */
internal data class AssetType(
    val uti: String,
    val extension: String,
)

/**
 * The asset type for a stored MIME type. AVFoundation decides how to read an asset from its URL's path
 * extension as much as from the declared type, so both have to agree. A type it cannot play still gets
 * its own name, so the refusal comes from AVFoundation as "format not recognised", not from a guess here.
 * Video defaults to MPEG-4 and audio to MPEG-4 audio, which is what phones record.
 */
internal fun assetTypeFor(mime: String): AssetType {
    val type = mime.substringBefore(';').trim().lowercase()
    return when (type) {
        "video/quicktime" -> {
            AssetType("com.apple.quicktime-movie", "mov")
        }

        "video/x-m4v" -> {
            AssetType("com.apple.m4v-video", "m4v")
        }

        "audio/mpeg", "audio/mp3" -> {
            AssetType("public.mp3", "mp3")
        }

        "audio/aac", "audio/x-aac" -> {
            AssetType("public.aac-audio", "aac")
        }

        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> {
            AssetType("com.microsoft.waveform-audio", "wav")
        }

        "audio/aiff", "audio/x-aiff" -> {
            AssetType("public.aiff-audio", "aiff")
        }

        "audio/flac", "audio/x-flac" -> {
            AssetType("org.xiph.flac", "flac")
        }

        "audio/ogg", "audio/opus" -> {
            AssetType("org.xiph.ogg-audio", "ogg")
        }

        else -> {
            if (type.startsWith(
                    "audio/",
                )
            ) {
                AssetType("com.apple.m4a-audio", "m4a")
            } else {
                AssetType("public.mpeg-4", "mp4")
            }
        }
    }
}

/** The asset's URL: a scheme nothing else answers, and the extension in the path (see [assetTypeFor]). */
internal fun assetUrl(
    scheme: String,
    type: AssetType,
): NSURL? = NSURL.URLWithString("$scheme:///item.${type.extension}")

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(count: Int): NSData =
    usePinned { NSData.create(bytes = it.addressOf(0), length = count.toULong()) }
