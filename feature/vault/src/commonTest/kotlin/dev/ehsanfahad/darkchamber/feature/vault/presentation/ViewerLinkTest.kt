package dev.ehsanfahad.darkchamber.feature.vault.presentation

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.feature.vault.FakeVaultItems
import dev.ehsanfahad.darkchamber.feature.vault.api.GalleryItem
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpened
import dev.ehsanfahad.darkchamber.feature.vault.api.LinkOpener
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerNotice
import dev.ehsanfahad.darkchamber.feature.vault.api.ViewerPage
import dev.ehsanfahad.darkchamber.feature.vault.domain.GalleryControls
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveAlbum
import dev.ehsanfahad.darkchamber.feature.vault.domain.ObserveGallery
import dev.ehsanfahad.darkchamber.feature.vault.readableMeta
import dev.ehsanfahad.darkchamber.feature.vault.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Opening a web link from the viewer, once the reader has confirmed it: only an address the link rules
 * call a web address reaches the platform, handed over as written; the vault locks only once something
 * has taken it, and when nothing did, nothing locks and the notice says so.
 */
class ViewerLinkTest {
    private val lifecycle = LifecycleRegistry().apply { resume() }

    /** Every address handed to the platform, and the answer each one waits for. */
    private val handed = mutableListOf<String>()
    private val answers = mutableListOf<CompletableDeferred<LinkOpened>>()

    private val opener =
        object : LinkOpener {
            override suspend fun open(address: String): LinkOpened {
                handed += address
                val answer = CompletableDeferred<LinkOpened>().also { answers += it }
                return answer.await()
            }
        }

    private var locks = 0

    private val items =
        FakeVaultItems(
            onOpen = { _: ByteArray, sink: RawSink ->
                val bytes = "see [it](https://example.com)\n".encodeToByteArray()
                sink.write(Buffer().apply { write(bytes) }, bytes.size.toLong())
                Outcome.Ok(Unit)
            },
        ).apply {
            summaries.value =
                listOf(summary(byteArrayOf(1), readableMeta("a.md", "text/markdown"), size = 30, createdAt = 1))
        }

    private fun TestScope.viewer(
        links: LinkOpener? = opener,
        lockVault: (() -> Unit)? = { locks++ },
        main: CoroutineContext = UnconfinedTestDispatcher(testScheduler),
    ) = ViewerComponent(
        componentContext = DefaultComponentContext(lifecycle),
        observeGallery = ObserveGallery(items),
        observeAlbum = ObserveAlbum(items),
        load = { _: GalleryItem, _: Int -> LoadedPage.Page(ViewerPage.CantShow) },
        shred = { Outcome.Ok(Unit) },
        filter = ItemFilter.All,
        controls = GalleryControls(),
        startId = byteArrayOf(1),
        onBack = {},
        openRange = items::openRange,
        playerFactory = null,
        mainContext = main,
        ioContext = UnconfinedTestDispatcher(testScheduler),
        releaseContext = UnconfinedTestDispatcher(testScheduler),
        openObject = items::open,
        links = links,
        lockVault = lockVault,
    ).also { it.viewportMeasured(1_000, 2_000) }

    private fun ViewerComponent<*>.notice() = state.value.notice

    @Test
    fun a_web_address_is_handed_over_as_written_and_the_vault_locks_once_it_is_taken() =
        runTest {
            val viewer = viewer()

            viewer.openLink("https://Example.com/a%20b?q=1#top")

            assertEquals(listOf("https://Example.com/a%20b?q=1#top"), handed, "as written, nothing tidied")
            assertEquals(0, locks, "nothing locks before the platform answers")

            answers.single().complete(LinkOpened.Opened)

            assertEquals(1, locks, "the vault locks once a browser has the address")
            assertNull(viewer.notice())
        }

    @Test
    fun the_scheme_is_matched_in_any_case_as_the_link_rules_match_it() =
        runTest {
            val viewer = viewer()

            viewer.openLink("HTTP://example.com")

            assertEquals(listOf("HTTP://example.com"), handed)
        }

    @Test
    fun when_nothing_takes_it_nothing_locks_and_the_notice_says_so() =
        runTest {
            val viewer = viewer()

            viewer.openLink("https://example.com")
            answers.single().complete(LinkOpened.NotOpened)

            assertEquals(0, locks)
            assertEquals(ViewerNotice.LinkNotOpened, viewer.notice())
        }

    /** Defence in depth: whatever the screen hands in, only a web address leaves. */
    @Test
    fun an_address_the_link_rules_do_not_call_web_never_reaches_the_platform() =
        runTest {
            val viewer = viewer()
            val zeroWidth = Char(0x200B)
            val refused =
                listOf(
                    "javascript:alert(1)",
                    "file:///etc/hosts",
                    "mailto:a@example.com",
                    "intent://example.com#Intent;scheme=https;end",
                    "#top",
                    "example.com",
                    "https://",
                    " https://example.com",
                    "https://exa${zeroWidth}mple.com",
                    "",
                )

            refused.forEach { viewer.openLink(it) }

            assertEquals(emptyList(), handed)
            assertEquals(0, locks)
            assertNull(viewer.notice(), "an inert link does nothing at all")
        }

    @Test
    fun a_second_open_while_the_first_waits_is_ignored() =
        runTest {
            val viewer = viewer()

            viewer.openLink("https://example.com/one")
            viewer.openLink("https://example.com/two")

            assertEquals(listOf("https://example.com/one"), handed, "one address at a time")
        }

    @Test
    fun once_answered_another_link_can_be_opened() =
        runTest {
            val viewer = viewer()

            viewer.openLink("https://example.com/one")
            answers.single().complete(LinkOpened.NotOpened)
            viewer.dismissNotice()
            viewer.openLink("https://example.com/two")

            assertEquals(listOf("https://example.com/one", "https://example.com/two"), handed)
        }

    /** No opener on this platform: nothing to hand it to, so the notice; and nothing locks. */
    @Test
    fun without_an_opener_nothing_locks_and_the_notice_says_so() =
        runTest {
            val viewer = viewer(links = null)

            viewer.openLink("https://example.com")

            assertEquals(0, locks)
            assertEquals(ViewerNotice.LinkNotOpened, viewer.notice())
        }

    /** An address that left without the lock would leave the vault open beside the browser. */
    @Test
    fun without_the_lock_no_address_is_handed_over() =
        runTest {
            val viewer = viewer(lockVault = null)

            viewer.openLink("https://example.com")

            assertEquals(emptyList(), handed)
            assertEquals(ViewerNotice.LinkNotOpened, viewer.notice())
        }

    /**
     * The viewer closed (back) while the platform answers: the address still left, so the vault still
     * locks — the wait belongs to the session, not to the screen.
     */
    @Test
    fun a_viewer_closed_while_waiting_still_locks_once_the_address_is_taken() =
        runTest {
            val viewer = viewer()

            viewer.openLink("https://example.com")
            lifecycle.destroy()
            answers.single().complete(LinkOpened.Opened)

            assertEquals(1, locks)
        }

    /** The session ended (a lock from elsewhere) while the platform answers: nothing is left to lock. */
    @Test
    fun a_session_ended_while_waiting_locks_nothing_again() =
        runTest {
            val session = Job()
            val viewer = viewer(main = UnconfinedTestDispatcher(testScheduler) + session)

            viewer.openLink("https://example.com")
            session.cancel()
            answers.single().complete(LinkOpened.Opened)

            assertEquals(0, locks)
            assertEquals(1, handed.size)
        }
}
