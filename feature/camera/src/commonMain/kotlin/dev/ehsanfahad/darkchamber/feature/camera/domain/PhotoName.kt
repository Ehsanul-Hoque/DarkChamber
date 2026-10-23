package dev.ehsanfahad.darkchamber.feature.camera.domain

/**
 * A captured photo's name: `Photo yyyy-MM-dd HH.mm.ss.jpg` in the device's local time.
 *
 * ASCII digits and a fixed pattern whatever the device's locale, so names sort the same way
 * everywhere — a locale-formatted date would put Bangla or Arabic-Indic digits into some vaults and
 * not others, and the gallery's name order is a plain case fold. Dots rather than colons in the time,
 * because a name may one day leave the vault as a file name, and a colon is not allowed in one on
 * every system.
 */
internal fun photoName(epochMillis: Long): String = "Photo ${captureTimestamp(epochMillis)}.jpg"

/** A recorded clip's name, in the same pattern: `Video yyyy-MM-dd HH.mm.ss.mp4`. */
internal fun videoName(epochMillis: Long): String = "Video ${captureTimestamp(epochMillis)}.mp4"

/**
 * `yyyy-MM-dd HH.mm.ss` in the device's local time, with ASCII digits.
 *
 * `expect`/`actual` rather than a seam: it reads the device's time zone, an ambient setting with no
 * state behind it, and the platforms' own formatters already handle every zone rule.
 */
internal expect fun captureTimestamp(epochMillis: Long): String
