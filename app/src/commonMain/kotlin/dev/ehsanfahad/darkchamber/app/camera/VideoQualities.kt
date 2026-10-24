package dev.ehsanfahad.darkchamber.app.camera

import dev.ehsanfahad.darkchamber.feature.camera.api.VideoQuality

/**
 * The qualities a lens is offered at, from whether the platform says it records 1080p ([fullHd],
 * offered as HD) and 2160p ([ultraHd], offered as UHD). Anything else it lists is not offered.
 *
 * Two facts rather than the platform's own types, so both controllers share the rule and a test can
 * reach it: CameraX's `Quality` cannot even be loaded off a device.
 */
internal fun videoQualitiesFor(fullHd: Boolean, ultraHd: Boolean): Set<VideoQuality> =
    buildSet {
        if (fullHd) add(VideoQuality.HD)
        if (ultraHd) add(VideoQuality.UHD)
    }
