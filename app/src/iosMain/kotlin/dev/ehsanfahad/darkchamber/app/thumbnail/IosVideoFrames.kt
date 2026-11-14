package dev.ehsanfahad.darkchamber.app.thumbnail

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import dev.ehsanfahad.darkchamber.app.media.ReaderLoader
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.resourceLoader
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
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

private const val PREVIEW_SCHEME = "darkchamber-preview"
private const val MPEG4_TYPE = "public.mpeg-4"
private const val QUICKTIME_TYPE = "com.apple.quicktime-movie"
private const val QUICKTIME_MIME = "video/quicktime"

// Long enough for a clip whose index is at the end of a large file to be read through.
private const val FRAME_TIMEOUT_NS = 60L * 1_000_000_000
