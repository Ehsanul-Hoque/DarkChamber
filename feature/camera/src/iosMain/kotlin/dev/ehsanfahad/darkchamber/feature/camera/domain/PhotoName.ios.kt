package dev.ehsanfahad.darkchamber.feature.camera.domain

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.dateWithTimeIntervalSince1970

// en_US_POSIX is Apple's documented locale for a fixed format: it ignores the user's calendar,
// numbering system and 12/24-hour preference, which would otherwise rewrite the pattern.
internal actual fun captureTimestamp(epochMillis: Long): String {
    val formatter = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        dateFormat = "yyyy-MM-dd HH.mm.ss"
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0))
}
