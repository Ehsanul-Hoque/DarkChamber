package dev.ehsanfahad.darkchamber.feature.camera.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// SimpleDateFormat rather than java.time, which needs API 26 without desugaring; a fresh instance
// per call because it is not thread-safe, and a capture is named once.
internal actual fun captureTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(epochMillis))
