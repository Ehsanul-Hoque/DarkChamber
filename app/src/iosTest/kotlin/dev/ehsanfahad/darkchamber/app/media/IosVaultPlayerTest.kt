package dev.ehsanfahad.darkchamber.app.media

import dev.ehsanfahad.darkchamber.app.media.IosPlayerMapping.ErrorLink
import dev.ehsanfahad.darkchamber.app.media.IosPlayerMapping.FoundTrack
import dev.ehsanfahad.darkchamber.app.testSqlDriver
import dev.ehsanfahad.darkchamber.app.thumbnail.VideoFixtures
import dev.ehsanfahad.darkchamber.core.crypto.CryptographyKotlinEngine
import dev.ehsanfahad.darkchamber.core.crypto.DchmCodec
import dev.ehsanfahad.darkchamber.core.crypto.DefaultKeyDerivation
import dev.ehsanfahad.darkchamber.core.crypto.PassphraseKdfProfile
import dev.ehsanfahad.darkchamber.core.crypto.VaultSession
import dev.ehsanfahad.darkchamber.core.db.CatalogStore
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import dev.ehsanfahad.darkchamber.core.storage.VaultStorage
import dev.ehsanfahad.darkchamber.core.vault.RangeReader
import dev.ehsanfahad.darkchamber.core.vault.VaultObjects
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerFailure
import dev.ehsanfahad.darkchamber.feature.vault.api.PlayerTracks
import dev.ehsanfahad.darkchamber.feature.vault.api.TrackSupport
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFormatUnsupported
import platform.AVFoundation.AVErrorUnknown
import platform.AVFoundation.AVFoundationErrorDomain
import platform.Foundation.NSDate
import platform.Foundation.NSOSStatusErrorDomain
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS player on the simulator's AVFoundation: the pure decisions first, then real playback of the
 * preview's fixture clips, stored encrypted in a throwaway vault and read through its range reader.
 */
class IosVaultPlayerTest {
    // ---- the asset's type ----------------------------------------------------------------------

    @Test
    fun each_stored_type_is_named_to_avfoundation_by_type_and_path_extension() {
        assertEquals(AssetType("public.mpeg-4", "mp4"), assetTypeFor("video/mp4"))
        assertEquals(AssetType("com.apple.quicktime-movie", "mov"), assetTypeFor("video/quicktime"))
        assertEquals(AssetType("com.apple.m4v-video", "m4v"), assetTypeFor("video/x-m4v"))
        assertEquals(AssetType("com.apple.m4a-audio", "m4a"), assetTypeFor("audio/mp4"))
        assertEquals(AssetType("com.apple.m4a-audio", "m4a"), assetTypeFor("audio/x-m4a"))
        assertEquals(AssetType("public.mp3", "mp3"), assetTypeFor("audio/mpeg"))
        assertEquals(AssetType("public.aac-audio", "aac"), assetTypeFor("audio/aac"))
        assertEquals(AssetType("com.microsoft.waveform-audio", "wav"), assetTypeFor("audio/wav"))
        assertEquals(AssetType("com.microsoft.waveform-audio", "wav"), assetTypeFor("audio/x-wav"))
        assertEquals(AssetType("public.aiff-audio", "aiff"), assetTypeFor("audio/aiff"))
        assertEquals(AssetType("org.xiph.flac", "flac"), assetTypeFor("audio/flac"))
        assertEquals(AssetType("org.xiph.ogg-audio", "ogg"), assetTypeFor("audio/ogg"))
    }

    @Test
    fun a_type_is_read_without_its_case_or_parameters() {
        assertEquals(AssetType("com.apple.quicktime-movie", "mov"), assetTypeFor("VIDEO/QuickTime"))
        assertEquals(AssetType("public.mp3", "mp3"), assetTypeFor("audio/mpeg; rate=44100"))
    }

    @Test
    fun an_unlisted_type_is_read_as_what_phones_record_for_its_family() {
        assertEquals(AssetType("public.mpeg-4", "mp4"), assetTypeFor("video/webm"))
        assertEquals(AssetType("com.apple.m4a-audio", "m4a"), assetTypeFor("audio/x-unknown"))
    }

    // ---- where a failure came from -------------------------------------------------------------

    private val avf = AVFoundationErrorDomain

    @Test
    fun a_read_the_vault_refused_wins_whatever_avfoundation_made_of_it() {
        val refused = VaultError.Crypto.IntegrityFailure
        assertEquals(
            PlayerFailure.Reader(refused),
            IosPlayerMapping.failureOf(listOf(ErrorLink(avf, AVErrorFileFormatNotRecognized)), refused),
        )
        assertEquals(PlayerFailure.Reader(refused), IosPlayerMapping.failureOf(emptyList(), refused))
    }

    @Test
    fun a_format_or_decoder_this_device_lacks_is_the_decoders() {
        for (code in listOf(AVErrorFileFormatNotRecognized, AVErrorDecoderNotFound, AVErrorFormatUnsupported)) {
            assertEquals(
                PlayerFailure.Decoder,
                IosPlayerMapping.failureOf(listOf(ErrorLink(avf, code)), null),
                "code $code",
            )
        }
    }

    /**
     * A decoder busy for now — another app holding it, or this one not in front — is not a device that
     * cannot play the file, which is what the decoder's page says. It names no cause.
     */
    @Test
    fun a_decoder_that_is_busy_for_now_is_not_a_file_this_device_cannot_play() {
        assertEquals(
            PlayerFailure.Other,
            IosPlayerMapping.failureOf(listOf(ErrorLink(avf, AVErrorDecoderTemporarilyUnavailable)), null),
        )
    }

    @Test
    fun a_decoder_code_wrapped_under_another_error_still_counts() {
        val chain = listOf(ErrorLink(avf, AVErrorUnknown), ErrorLink(avf, AVErrorDecoderNotFound))

        assertEquals(PlayerFailure.Decoder, IosPlayerMapping.failureOf(chain, null))
    }

    @Test
    fun the_same_number_in_another_domain_is_not_avfoundations() {
        assertEquals(
            PlayerFailure.Other,
            IosPlayerMapping.failureOf(listOf(ErrorLink(NSOSStatusErrorDomain, AVErrorFileFormatNotRecognized)), null),
        )
    }

    @Test
    fun anything_else_names_no_cause() {
        assertEquals(
            PlayerFailure.Other,
            IosPlayerMapping.failureOf(listOf(ErrorLink(avf, AVErrorFailedToParse)), null),
        )
        assertEquals(PlayerFailure.Other, IosPlayerMapping.failureOf(listOf(ErrorLink(avf, AVErrorUnknown)), null))
        assertEquals(PlayerFailure.Other, IosPlayerMapping.failureOf(emptyList(), null))
    }

    // ---- the tracks ----------------------------------------------------------------------------

    @Test
    fun the_tracks_add_up_to_a_picture_and_a_sound_as_on_android() {
        assertEquals(
            PlayerTracks(TrackSupport.Unplayable, TrackSupport.Playable),
            IosPlayerMapping.tracksOf(
                listOf(
                    FoundTrack(video = true, audio = false, playable = false),
                    FoundTrack(video = false, audio = true, playable = true),
                ),
            ),
        )
        assertEquals(PlayerTracks(), IosPlayerMapping.tracksOf(emptyList()))
        assertEquals(
            TrackSupport.Playable,
            IosPlayerMapping.tracksOf(listOf(FoundTrack(true, false, false), FoundTrack(true, false, true))).video,
            "one playable among several",
        )
        assertEquals(
            PlayerTracks(TrackSupport.Absent, TrackSupport.Playable),
            IosPlayerMapping.tracksOf(
                listOf(FoundTrack(video = false, audio = false, playable = false), FoundTrack(false, true, true)),
            ),
            "a track that is neither (subtitles, timecode) counts for neither",
        )
    }

    @Test
    fun the_player_buffers_ten_to_fifteen_seconds_ahead() {
        assertTrue(IosPlayerMapping.FORWARD_BUFFER_SECONDS in 10.0..15.0, "${IosPlayerMapping.FORWARD_BUFFER_SECONDS}")
    }

    // ---- playing for real on the simulator -------------------------------------------------------

    private val root = Path(SystemTemporaryDirectory, "ivp-${Random.nextULong()}")
    private val engine = CryptographyKotlinEngine()
    private val session =
        VaultSession
            .createVault(
                engine,
                DefaultKeyDerivation(engine),
                "player test".encodeToByteArray(),
                PassphraseKdfProfile.Argon2id(memoryKib = 256, iterations = 1, parallelism = 1),
            ).session
    private val objects =
        run {
            SystemFileSystem.createDirectories(root)
            VaultObjects(
                session,
                VaultStorage(root),
                (CatalogStore.create(testSqlDriver()) as Outcome.Ok).value,
                DchmCodec(engine),
                clock = { 0L },
                random = { n -> engine.randomBytes(n) },
            )
        }
    private val players = mutableListOf<IosVaultPlayer>()

    @AfterTest
    fun cleanup() {
        players.forEach { it.release() }
        spin(0.3)
        session.close()

        fun delete(p: Path) {
            if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach(::delete)
            SystemFileSystem.delete(p, mustExist = false)
        }
        delete(root)
    }

    /** Counts its closes, and can fail every read from [failFrom] on, as a damaged chunk would. */
    private class Watched(
        private val inner: RangeReader,
        private val failFrom: Long = Long.MAX_VALUE,
    ) : RangeReader by inner {
        var closes = 0

        override fun readAt(
            position: Long,
            into: ByteArray,
            offset: Int,
            size: Int,
        ): Outcome<Int> {
            if (position >= failFrom) return Outcome.Err(VaultError.Crypto.IntegrityFailure)
            val clipped = if (position + size > failFrom) (failFrom - position).toInt() else size
            return inner.readAt(position, into, offset, clipped)
        }

        override fun close() {
            closes++
            inner.close()
        }
    }

    private fun stored(
        bytes: ByteArray,
        failFrom: Long = Long.MAX_VALUE,
    ): Watched {
        val id =
            objects.importObject(
                kind = 0,
                name = "clip",
                mime = "application/octet-stream",
                chunkSize = 64 * 1024,
                openSource = { Buffer().apply { write(bytes) } },
            )
        return Watched((objects.openRange((id as Outcome.Ok).value) as Outcome.Ok).value, failFrom)
    }

    private fun play(
        reader: RangeReader,
        mime: String = "video/mp4",
    ) = IosVaultPlayer(reader, mime).also {
        players +=
            it
    }

    private fun spin(seconds: Double) {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(seconds))
    }

    /** Runs the main run loop until [condition] holds or [seconds] pass; says which. */
    private fun waitFor(
        seconds: Double = 15.0,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(seconds)
        while (!condition()) {
            if (deadline.timeIntervalSinceNow < 0) return false
            spin(0.05)
        }
        return true
    }

    @Test
    fun a_clip_read_through_the_vault_opens_with_its_picture_playable_and_its_length_known() {
        val player = play(stored(VideoFixtures.colour))

        assertTrue(waitFor { player.state.value.tracks.video != TrackSupport.Absent }, "the tracks were read")
        assertEquals(TrackSupport.Playable, player.state.value.tracks.video)
        assertTrue(waitFor { player.state.value.durationMs != null })
        assertNull(player.state.value.failure)
    }

    @Test
    fun playing_moves_the_position_on() {
        val player = play(stored(VideoFixtures.colour))
        player.play()

        assertTrue(waitFor { player.state.value.positionMs > 200 }, "position ${player.state.value.positionMs}")
    }

    @Test
    fun a_read_the_vault_refuses_stops_playback_as_the_vaults_verdict() {
        val player = play(stored(VideoFixtures.colour, failFrom = 4_096))
        player.play()

        assertTrue(waitFor { player.state.value.failure != null }, "it stopped")
        assertEquals(PlayerFailure.Reader(VaultError.Crypto.IntegrityFailure), player.state.value.failure)
    }

    /**
     * Which code AVFoundation gives for bytes it cannot read is not checkable here: in this test process
     * it refuses any asset with sound — even a plain file — with the same unknown error, so it cannot be
     * told from a format refusal (the mapping itself is pinned above; the real code is for an iPhone).
     * What does hold: it stops, and the stop is never blamed on the vault, whose reads all succeeded.
     */
    @Test
    fun bytes_that_are_no_media_at_all_stop_without_blaming_the_vault() {
        val player = play(stored(Random(7).nextBytes(200_000)))
        player.play()

        assertTrue(waitFor { player.state.value.failure != null }, "it stopped")
        assertTrue(player.state.value.failure !is PlayerFailure.Reader)
    }

    @Test
    fun letting_the_player_go_closes_its_reader_once_and_it_reports_nothing_more() {
        val reader = stored(VideoFixtures.colour)
        val player = play(reader)
        player.play()
        assertTrue(waitFor { player.state.value.positionMs > 0 })

        player.release()
        player.release()
        spin(0.5)
        val after = player.state.value
        spin(0.6)

        assertEquals(1, reader.closes)
        assertEquals(after, player.state.value, "no more reports")
    }

    @Test
    fun the_volume_slide_sets_the_players_own_volume() {
        val player = play(stored(VideoFixtures.colour))

        player.setVolume(0.4f)
        spin(0.1)

        assertEquals(0.4f, player.volume(), "and a slide after starts from it")
    }
}
