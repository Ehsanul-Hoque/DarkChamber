package dev.ehsanfahad.darkchamber.feature.camera.api

/** Which way the camera faces. */
enum class Lens { Back, Front }

/** The flash setting a capture is taken with. */
enum class FlashMode { Off, Auto, On }

/**
 * Whether the app may use the camera.
 *
 * [PermanentlyDenied] is its own state, not a flavour of [Denied], because the two need different
 * answers: a denied permission can be asked for again, while a permanently denied one can only be
 * changed in the system's settings — offering "Allow" there would be a button that does nothing.
 * On iOS a refusal is always permanent (the system never asks twice), so that platform never
 * reports [Denied].
 */
enum class CameraPermission { NotAsked, Granted, Denied, PermanentlyDenied }

/**
 * Whether the app may record sound, with the same four answers as [CameraPermission] and for the
 * same reasons. Its own type so that one permission can never be passed where the other is meant.
 */
enum class MicrophonePermission { NotAsked, Granted, Denied, PermanentlyDenied }

/** What the shutter takes. Also chooses the capture's shape: 4:3 for a photo, 16:9 for a video. */
enum class CaptureMode { Photo, Video }

/** A video's resolution: [HD] is 1080p, [UHD] is 2160p. */
enum class VideoQuality { HD, UHD }

/**
 * Whether a camera can be opened right now, separately from whether we are allowed to.
 *
 * [InUseByAnotherApp] is reported **only** when the platform says exactly that. Every other reason —
 * a hardware error, a disconnected camera, a device with none, a simulator — is [Unavailable], so
 * no screen can name a cause the platform did not give.
 */
enum class CameraAvailability { Available, InUseByAnotherApp, Unavailable }

/**
 * What the open camera can do, so controls the hardware does not support are not offered.
 *
 * A front lens commonly has no flash, and some devices have only one lens; a control that is drawn
 * but does nothing is a small lie about the device.
 *
 * Everything but [lenses] describes the lens that is open now, and changes when it does. [hasFlash]
 * covers the torch as well, since both are the same light. [videoQualities] is empty where the lens
 * records no video, and a lens that cannot do 4K lists only [VideoQuality.HD].
 */
class CameraCapabilities(
    val lenses: Set<Lens>,
    val hasFlash: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    val videoQualities: Set<VideoQuality> = emptySet(),
) {
    override fun equals(other: Any?): Boolean =
        other is CameraCapabilities &&
            lenses == other.lenses &&
            hasFlash == other.hasFlash &&
            minZoom == other.minZoom &&
            maxZoom == other.maxZoom &&
            videoQualities == other.videoQualities

    override fun hashCode(): Int {
        var result = lenses.hashCode()
        result = 31 * result + hasFlash.hashCode()
        result = 31 * result + minZoom.hashCode()
        result = 31 * result + maxZoom.hashCode()
        result = 31 * result + videoQualities.hashCode()
        return result
    }

    override fun toString(): String =
        "CameraCapabilities(lenses=$lenses, hasFlash=$hasFlash, zoom=$minZoom..$maxZoom, video=$videoQualities)"

    companion object {
        /** What is known before a camera has opened: nothing beyond a fixed 1× view. */
        val None: CameraCapabilities = CameraCapabilities(emptySet(), hasFlash = false, minZoom = 1f, maxZoom = 1f)
    }
}

/**
 * One captured photo: the JPEG exactly as the platform encoded it, and how far it has to be turned
 * to display upright.
 *
 * [jpeg] is **plaintext image content held in memory** — the only form a capture ever takes before
 * it is encrypted into the vault. It is never written to a file, never logged, and never held in
 * screen state; whoever receives it owns it, and zeroes it once it has been sealed. Zeroing reaches
 * this array only: the platform's own capture buffers are copies outside our control.
 *
 * [rotationDegrees] is 0, 90, 180 or 270 clockwise, written into the JPEG's orientation tag before it
 * is saved — so 0 means the pixels are upright as they stand, and a tag saying otherwise is reset.
 * `null` means the platform has already written the orientation into the JPEG itself, and the bytes
 * are saved as they came.
 *
 * Not a data class: the generated `equals` would compare the array by identity and the generated
 * `toString` would print its contents.
 */
class CapturedPhoto(val jpeg: ByteArray, val rotationDegrees: Int?) {
    /** The size only — never the bytes (hard rule #4). */
    override fun toString(): String = "CapturedPhoto(jpeg=${jpeg.size}B, rotation=$rotationDegrees)"
}

/** How a capture ended. A failure is an ordinary outcome the screen reports, never an exception. */
sealed interface CaptureResult {
    class Captured(val photo: CapturedPhoto) : CaptureResult {
        override fun toString(): String = "Captured($photo)"
    }

    /**
     * The capture did not produce a photo. Carries no platform message: an error string from a
     * camera stack can name a device, a file or a path, and nothing downstream would act on it.
     */
    data object Failed : CaptureResult
}
