package dev.ehsanfahad.darkchamber.feature.camera.api

/**
 * The file format a platform's recorder writes, which a clip's scratch file, MIME and name follow:
 * CameraX writes MP4, and iOS's movie file output writes a QuickTime movie. A viewer or an export
 * trusts the MIME, so it names what is in the file rather than what was hoped for.
 */
enum class VideoContainer(val mime: String, val extension: String) {
    Mp4("video/mp4", "mp4"),
    QuickTime("video/quicktime", "mov"),
}
