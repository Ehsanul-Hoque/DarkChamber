package dev.ehsanfahad.darkchamber.feature.camera.domain

import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Android name format, run on the host JVM with the zone and locale pinned. The iOS formatter
 * is compiled for both iOS targets and checked by its fixed POSIX locale; it has no host to run on.
 */
class PhotoNameTest {
    private lateinit var zone: TimeZone
    private lateinit var locale: Locale

    @BeforeTest
    fun pin() {
        zone = TimeZone.getDefault()
        locale = Locale.getDefault()
    }

    @AfterTest
    fun restore() {
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    @Test
    fun the_name_is_the_local_time_in_a_fixed_pattern() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka")) // UTC+6, no daylight saving
        // 2026-10-19T08:30:12.345Z
        assertEquals("Photo 2026-10-19 14.30.12.jpg", photoName(1_792_398_612_345L))
    }

    @Test
    fun a_video_is_named_in_the_same_pattern() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"))
        assertEquals("Video 2026-10-19 14.30.12.mp4", videoName(1_792_398_612_345L))
    }

    /** A device set to Bangla must not put Bangla digits in a name; the gallery sorts names as text. */
    @Test
    fun the_digits_are_ascii_whatever_the_devices_locale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.forLanguageTag("bn-BD-u-nu-beng"))
        assertEquals("Photo 1970-01-01 00.00.00.jpg", photoName(0L))
    }
}
