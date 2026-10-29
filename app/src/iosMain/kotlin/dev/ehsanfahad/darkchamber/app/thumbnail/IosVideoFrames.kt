package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVAssetResourceLoader
import platform.AVFoundation.AVAssetResourceLoaderDelegateProtocol
import platform.AVFoundation.AVAssetResourceLoadingRequest
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time

/**
 * One frame of a video, at most [maxEdgePx] on its longest edge and upright as the clip is shown, or
 * `null` — no video track, a format AVFoundation cannot read, or any failure on the way. The caller
 * releases the image.
 *
 * `AVAssetImageGenerator` reads an asset by URL, so the asset's URL is one of a scheme nothing else
 * answers, and a resource-loader delegate serves its byte ranges from [reader], which decrypts only
 * the chunks each range touches. AVFoundation asks for the length before anything else; the reader
 * knows it from the moment it opened, so a clip whose index is at the end, as every phone writes it,
 * costs a read at the end rather than a read through the file. Nothing is written anywhere: the bytes
 * go from the reader's memory to AVFoundation's.
 *
 * The frame is the first key frame at or after [PREVIEW_FRAME_US], as on Android; a clip with no
 * frame that late gives its first. Waited on with a timeout, so a reader AVFoundation never finishes
 * with cannot hang an import.
 *
 * [reader] belongs to the caller, which closes it after this returns.
 *
 * **Unverified on a device**; exercised on the simulator, whose AVFoundation reads files the same way.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun videoFrame(mime: String, maxEdgePx: Int, reader: RangeReader): CGImageRef? {
    val queue = dispatch_queue_create("dev.ehsanfahad.darkchamber.preview", null)
    val quickTime = mime.equals(QUICKTIME_MIME, ignoreCase = true)
    val loader = ReaderLoader(
        reader = reader,
        contentType = if (quickTime) QUICKTIME_TYPE else MPEG4_TYPE,
        queue = queue,
    )
    // The extension in the URL's *path*: AVFoundation's format probe reads it, and declines an asset
    // without one ("This media format is not supported") whatever content type is declared. In
    // `scheme://clip.mp4` the name is the host, which is how that was found.
    val url = NSURL.URLWithString("$PREVIEW_SCHEME:///clip.${if (quickTime) "mov" else "mp4"}") ?: return null
    val asset = AVURLAsset(uRL = url, options = null)
    asset.resourceLoader().setDelegate(loader, queue)
    val generator = AVAssetImageGenerator(asset = asset).apply {
        appliesPreferredTrackTransform = true
        maximumSize = CGSizeMake(maxEdgePx.toDouble(), maxEdgePx.toDouble())
        requestedTimeToleranceBefore = kCMTimeZero.readValue()
        requestedTimeToleranceAfter = kCMTimePositiveInfinity.readValue()
    }
    return try {
        generator.frameAt(PREVIEW_FRAME_US) ?: generator.frameAt(0)
    } finally {
        generator.cancelAllCGImageGeneration()
        // On the loader's own queue, so no request it is serving runs into a closed reader.
        dispatch_sync(queue) { loader.close() }
    }
}

// One frame, waited for; retained so it outlives the handler that hands it over.
@OptIn(ExperimentalForeignApi::class)
private fun AVAssetImageGenerator.frameAt(atUs: Long): CGImageRef? {
    val done = dispatch_semaphore_create(0)
    var frame: CGImageRef? = null
    generateCGImageAsynchronouslyForTime(CMTimeMake(atUs, 1_000_000)) { image, _, error ->
        if (error == null && image != null) frame = CGImageRetain(image)
        dispatch_semaphore_signal(done)
    }
    val timedOut = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, FRAME_TIMEOUT_NS)) != 0L
    if (timedOut) {
        cancelAllCGImageGeneration()
        // The handler can still arrive after the cancel; what it retains then is released here once
        // it has. Nothing waits on it beyond this.
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, FRAME_TIMEOUT_NS))
        frame?.let(::CGImageRelease)
        return null
    }
    return frame
}

/**
 * AVFoundation's requests for the asset's bytes, answered from a [RangeReader] on the loader's
 * serial queue — the one AVFoundation calls this delegate on, so the reader is only ever touched
 * there. Never a URL or a path of the file; the scheme exists only for this.
 *
 * A request is accepted and answered after returning, as Apple's resource-loader pattern has it,
 * rather than inside the call that offers it.
 */
@OptIn(ExperimentalForeignApi::class)
private class ReaderLoader(
    private val reader: RangeReader,
    private val contentType: String,
    private val queue: dispatch_queue_t,
) : NSObject(), AVAssetResourceLoaderDelegateProtocol {
    private var closed = false
    private val chunk = ByteArray(CHUNK_BYTES)

    @ObjCSignatureOverride
    override fun resourceLoader(
        resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource: AVAssetResourceLoadingRequest,
    ): Boolean {
        if (closed) return false
        dispatch_async(queue) { serve(shouldWaitForLoadingOfRequestedResource) }
        return true
    }

    // Runs on a dispatch queue, where an exception would end the process instead of reaching the
    // import's catch around the encoder — so nothing is let out of here. A read that fails fails the
    // request (see [answer]), and the asset then has no frame to give.
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
                val n = when (val read = reader.readAt(at, chunk, 0, minOf(CHUNK_BYTES.toLong(), end - at).toInt())) {
                    // A chunk that fails its tag, a lock: the request fails, never answered short.
                    is Outcome.Err -> {
                        request.finishLoadingWithError(null)
                        return
                    }
                    is Outcome.Ok -> read.value
                }
                if (n <= 0) break
                data.respondWithData(chunk.toNSData(n))
                at += n
            }
        }
        request.finishLoading()
    }

    // The reader is the caller's to close; this only stops serving and clears its own buffer.
    fun close() {
        closed = true
        chunk.fill(0)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(count: Int): NSData =
    usePinned { NSData.create(bytes = it.addressOf(0), length = count.toULong()) }

private const val PREVIEW_SCHEME = "darkchamber-preview"
private const val MPEG4_TYPE = "public.mpeg-4"
private const val QUICKTIME_TYPE = "com.apple.quicktime-movie"
private const val QUICKTIME_MIME = "video/quicktime"
private const val CHUNK_BYTES = 256 * 1024

// Long enough for a clip whose index is at the end of a large file to be read through.
private const val FRAME_TIMEOUT_NS = 60L * 1_000_000_000
