package dev.ehsanfahad.darkchamber.feature.camera.presentation

import dev.ehsanfahad.darkchamber.feature.camera.api.FlashMode
import dev.ehsanfahad.darkchamber.feature.camera.domain.DestinationTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The camera's words. Each line is written from the failure path — what is still true when the
 * thing it describes has gone wrong — and the claims it must never make are asserted absent, so a
 * later rewrite cannot quietly bring one in.
 */
class CameraFormatTest {

    private val unreadable = "Can't be verified" // what the host hands in; the camera never spells it

    @Test
    fun the_title_names_the_destination() {
        assertEquals("All items", CameraFormat.title(DestinationTitle.AllItems, unreadable))
        assertEquals("Unfiled", CameraFormat.title(DestinationTitle.Unfiled, unreadable))
        assertEquals("Trip to Sylhet", CameraFormat.title(DestinationTitle.Album("Trip to Sylhet"), unreadable))
    }

    /** The damaged-name sentence is the one the rest of the app uses, handed in — never a second copy. */
    @Test
    fun an_album_whose_name_will_not_open_reads_as_the_hosts_label() {
        assertEquals("anything the host says", CameraFormat.title(DestinationTitle.UnreadableAlbum, "anything the host says"))
    }

    /** Nothing is known to name: no title rather than a guess. */
    @Test
    fun a_gone_or_unknown_destination_has_no_title() {
        assertNull(CameraFormat.title(DestinationTitle.AlbumGone, unreadable))
        assertNull(CameraFormat.title(DestinationTitle.Unknown, unreadable))
    }

    /** The same words an album tile uses under its name, so one number reads one way everywhere. */
    @Test
    fun the_count_reads_like_an_album_tile_and_is_absent_when_unknown() {
        assertEquals("Empty", CameraFormat.count(0L))
        assertEquals("1 item", CameraFormat.count(1L))
        assertEquals("2 items", CameraFormat.count(2L))
        assertEquals("1834 items", CameraFormat.count(1834L))
        assertNull(CameraFormat.count(null), "an unknown count shows nothing, never zero")
    }

    /**
     * An album deleted under the camera unfiles what was in it — so photos already saved are still
     * in the vault. It says that, and claims nothing about a photo whose save was in flight.
     */
    @Test
    fun the_album_gone_message_says_where_saved_photos_are() {
        val text = CameraFormat.message(CameraMessage.AlbumGone)
        assertTrue("Unfiled" in text, text)
        assertTrue("already saved" in text, "only saved photos are promised: $text")
    }

    @Test
    fun a_failure_names_no_cause() {
        assertEquals("Couldn't save that photo.", CameraFormat.message(CameraMessage.SaveFailed))
        assertEquals("Couldn't take that photo.", CameraFormat.message(CameraMessage.CaptureFailed))
    }

    @Test
    fun each_explainer_says_what_is_true_and_offers_only_what_works() {
        val denied = CameraFormat.explainer(CameraExplainer.Denied)!!
        assertEquals("Allow", denied.action)

        val off = CameraFormat.explainer(CameraExplainer.PermanentlyDenied)!!
        assertEquals("Open settings", off.action, "asking again would be a button that does nothing")
        assertTrue("locks" in off.text, "leaving for Settings locks the vault, and it says so: ${off.text}")

        val inUse = CameraFormat.explainer(CameraExplainer.InUse)!!
        assertEquals("Camera is in use by another app.", inUse.text)
        assertNull(inUse.action)

        val unavailable = CameraFormat.explainer(CameraExplainer.Unavailable)!!
        assertEquals("Camera isn't available right now.", unavailable.text, "no cause the platform did not give")
        assertNull(unavailable.action)

        assertNull(CameraFormat.explainer(CameraExplainer.None))
    }

    @Test
    fun the_flash_control_says_its_state_out_loud() {
        assertEquals("Flash: off", CameraFormat.flashDescription(FlashMode.Off))
        assertEquals("Flash: auto", CameraFormat.flashDescription(FlashMode.Auto))
        assertEquals("Flash: on", CameraFormat.flashDescription(FlashMode.On))
    }

    /**
     * Every line this screen can show or speak, checked for the claims it may not make:
     * - "silent" — iOS forces a shutter sound in some regions, and nothing here can stop it;
     * - "never leave" — photos will be exportable;
     * - "wiped" / "erased" / "cleared" about memory — only this code's own arrays are zeroed;
     * - "storage" / "full" / "space" — no error says the disk is full, so nothing may name it;
     * - "tampered" / "corrupt" — a failed tag is all that is known about a damaged name.
     */
    @Test
    fun no_line_makes_a_claim_the_code_cannot_keep() {
        val forbidden = listOf("silent", "never leave", "wiped", "erased", "cleared", "storage", "full", "space", "tamper", "corrupt")
        for (line in everyLine()) {
            for (word in forbidden) {
                assertFalse(word in line.lowercase(), "\"$line\" says \"$word\"")
            }
        }
    }

    private fun everyLine(): List<String> = buildList {
        for (title in listOf(DestinationTitle.AllItems, DestinationTitle.Unfiled, DestinationTitle.UnreadableAlbum)) {
            add(CameraFormat.title(title, unreadable)!!)
        }
        for (n in listOf(0L, 1L, 5L)) add(CameraFormat.count(n)!!)
        CameraMessage.entries.forEach { add(CameraFormat.message(it)) }
        CameraExplainer.entries.mapNotNull { CameraFormat.explainer(it) }.forEach { add(it.text); it.action?.let(::add) }
        FlashMode.entries.forEach { add(CameraFormat.flashDescription(it)) }
        add(CameraFormat.shutterDescription())
        add(CameraFormat.flipDescription())
        add(CameraFormat.closeDescription())
        add(CameraFormat.lastShotDescription(hasShot = true))
        add(CameraFormat.lastShotDescription(hasShot = false))
    }
}
