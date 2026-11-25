package dev.ehsanfahad.darkchamber.konsist

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.codeOf
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.inModule
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.productionFiles
import dev.ehsanfahad.darkchamber.konsist.ProjectSources.productionImports
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The **security-invariant** architecture rules (CLAUDE.md hard rules #1–#4, docs/THREAT_MODEL.md).
 * These make the invariants self-policing: violating "all crypto goes through `CryptoEngine`" or
 * "no network in v1" fails the build instead of relying on a reviewer noticing an import.
 *
 * Layer/naming rules are deliberately absent — they land with the rest of the quality tooling.
 */
class SecurityArchitectureTest {

    @Test
    fun `cipher primitives are imported only inside core-crypto`() {
        assertNoForbiddenImports(
            rule = "cipher primitives (hard rule #2)",
            forbidden = ImportPolicy::isCipherPrimitive,
            allowedIn = { it.inModule("core/crypto") },
            remedy = "Go through CryptoEngine / DchmCodec / VaultSession instead.",
        )
    }

    @Test
    fun `no module imports a networking API`() {
        assertNoForbiddenImports(
            rule = "networking APIs (hard rule #3 / ADR-004)",
            forbidden = ImportPolicy::isNetwork,
            remedy = "v1 ships without the INTERNET permission and has no network code at all.",
        )
    }

    @Test
    fun `no module imports an analytics or crash-reporting SDK`() {
        assertNoForbiddenImports(
            rule = "analytics / crash SDKs (hard rule #4)",
            forbidden = ImportPolicy::isTelemetrySdk,
            remedy = "No trackers, ever. Diagnostics are local, opt-in and user-exported.",
        )
    }

    @Test
    fun `shared-storage APIs are touched only inside core-storage`() {
        assertNoForbiddenImports(
            rule = "shared-storage APIs (hard rule #1)",
            forbidden = ImportPolicy::isSharedStorage,
            allowedIn = { it.inModule("core/storage") },
            remedy = "Plaintext never touches shared storage; core:storage owns the filesystem.",
        )
    }

    @Test
    fun `the logger is imported only inside core-logging`() {
        assertNoForbiddenImports(
            rule = "logger (hard rule #4)",
            forbidden = ImportPolicy::isLogger,
            allowedIn = { it.inModule("core/logging") },
            remedy = "Log via core:logging, which redacts VaultError to a content-free code.",
        )
    }

    @Test
    fun `every image loader is built without a disk cache`() {
        // An image loader in this app is only ever handed decrypted vault content, and a disk cache
        // stores what it fetched — so one left on writes plaintext previews into the app's cache
        // directory, where they survive a lock, the scratch purge and the process. Hard rule #1, and
        // invisible: every tile renders correctly and no test that draws a grid would notice.
        //
        // Coil attaches one by default (measured — a bare builder hands back a RealDiskCache), so
        // this is a rule about what a future loader must remember, not about the one that exists.
        // It is a source rule rather than a unit test because a test can only cover the loader it
        // knows about, and the hazard is precisely the loader nobody told the tests about.
        val violations = productionFiles
            .filter { BUILDS_AN_IMAGE_LOADER.containsMatchIn(codeOf(it)) }
            .filterNot { file -> DISK_CACHE_OFF.any { file.text.contains(it) } }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "An ImageLoader is built without disabling its disk cache (hard rule #1). " +
                        "Call vaultImageLoader, or disable it with diskCache(null) and " +
                        "diskCachePolicy(CachePolicy.DISABLED).",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `every file that opens a window of its own keeps it out of the platform's structures`() {
        // A dialog is its own window with its own view root, so the exclusion set on the host's
        // window does not reach it. Measured on a device: with the activity's window excluded, the
        // album-name field inside a dialog still appeared in an autofill fill request sent to
        // another process — one autofillable id, which went to none once the dialog excluded itself.
        //
        // A menu and a bottom sheet are the same thing in a different shape — both are built on a
        // popup, which is another window — so they are held to the same rule even while the two
        // menus that exist show no vault content. The rule is per window rather than per wording:
        // the menu that lists albums by name is the one that would otherwise be written without the
        // exclusion, and by then nothing would be asking the question.
        //
        // The text in these windows is vault content: an album's name, a search the user typed
        // (hard rules #1 and #5). So the rule is "a file that opens a window also excludes it",
        // which is what stops the next one from being the one that forgets.
        //
        // A source rule rather than a unit test for the usual reason: nothing a host test can reach
        // observes a platform window, and the hazard is the call site nobody wrote a test for.
        val violations = productionFiles
            .filter { OPENS_A_WINDOW.containsMatchIn(codeOf(it)) }
            // The **call**, not the name: the first version of this looked for the bare identifier
            // and was satisfied by the import line, so deleting the call from a dialog while
            // leaving the import — the exact shape of an accidental removal — passed.
            //
            // And **one call per window**: a file opening a sheet and a dialog with one exclusion
            // between them passed while asking only whether the file mentioned it — measured by
            // removing the sheet's call from a file that kept the dialog's.
            .filter { file ->
                val code = codeOf(file)
                Regex(Regex.escape("$EXCLUDES_FROM_STRUCTURES()")).findAll(code).count() <
                    OPENS_A_WINDOW.findAll(code).count()
            }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "A window is opened without excluding it from the platform's autofill " +
                        "and content-capture structures (hard rules #1/#5). Call " +
                        "$EXCLUDES_FROM_STRUCTURES() inside each window's content — one per window.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `every activity keeps its own window out of the platform's structures`() {
        // The companion to the rule above, for the one window no composable can reach: an activity's
        // window exists before any composition does, so it sets the two properties itself. One
        // activity exists today and it does this — the rule is for the second one, which a later
        // phase brings (a share target, a widget's configuration screen) and which would otherwise
        // ship a window whose every text field is published to another process, silently.
        //
        // Checked by the two property names rather than by the helper wrapping them, because a new
        // activity is free to write its own helper. What must not exist is an activity that sets
        // neither.
        val violations = productionFiles
            .filter { DECLARES_AN_ACTIVITY.containsMatchIn(codeOf(it)) }
            .filterNot { file -> EXCLUDES_A_WINDOW.all { codeOf(file).contains(it) } }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "An activity does not exclude its window from the platform's autofill and " +
                        "content-capture structures (hard rules #1/#5). Set " +
                        "${EXCLUDES_A_WINDOW.joinToString(" and ")} on the window's decor view, " +
                        "behind the version guards the existing host uses.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `no Android source declares a PlatformContext in the graph`() {
        // On Android `coil3.PlatformContext` is a typealias for `android.content.Context`, so a
        // `single<PlatformContext> { androidContext() }` is a definition keyed on Context whose body
        // asks Koin for a Context — itself. It also overrides the application context registered at
        // startup, so the *first* thing in the graph to want a context recurses until the stack ends.
        // A fresh install crashed on launch before the gate drew anything, and the graph offers no
        // clean error to read: just a StackOverflowError inside Koin's own resolver.
        //
        // The right answer is no binding at all — the application context already is a
        // PlatformContext. A source rule because it cannot be unit-tested: producing the failure
        // needs a real Context, and the failure is a stack overflow rather than a return value.
        val violations = productionFiles
            .filter { it.projectPath.contains("/src/androidMain/") }
            .filter { DECLARES_A_PLATFORM_CONTEXT.containsMatchIn(codeOf(it)) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "An Android source declares PlatformContext in the Koin graph. On Android that " +
                        "type is android.content.Context, so the definition resolves to itself and " +
                        "the graph recurses. Remove it: androidContext(app) already provides one.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `the not-saved record holds counts, never content`() {
        // The record of what did not make it into the vault is kept above the session gate so that
        // a lock cannot destroy the news of what it abandoned — which makes it the one place in the
        // app that deliberately outlives a lock. It may therefore hold counts, kinds and reasons
        // and nothing else: an id, a name or a path in it would survive the teardown that exists to
        // remove exactly those. A source rule because common tests have no reflection to inspect a
        // type's fields with, and because it must also cover whatever gets added to it later.
        //
        // Every file declaring a `NotSaved…` type is read whole, so a field whose type is inferred
        // rather than written cannot slip past a declaration-based check.
        val files = productionFiles.filter { DECLARES_A_NOT_SAVED_TYPE.containsMatchIn(codeOf(it)) }
        assertTrue(files.size >= 2, "the rule found no not-saved types to check — has it moved?")

        val violations = files.filter { HOLDS_CONTENT.containsMatchIn(codeOf(it)) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "A not-saved type mentions a type that can carry content. The record outlives a " +
                        "lock, so it may hold counts, kinds and reasons only — never an id, a name or " +
                        "a path. Put the wording in a formatter file instead.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `screen state in a feature holds no picture, no player and no text`() {
        // The session-gate rule: screen state holds ids and descriptions, never decrypted content.
        // A decoded picture is the content itself, a player holds decoded video and sound, and a text
        // document holds a decrypted file's words; state is what gets copied, compared, kept across
        // recompositions and handed around — so a viewer keeps its pictures, its one player and its
        // one text in separate holders it drops on destroy. Read file-wide, like the not-saved rule,
        // so a field whose type is inferred cannot slip past; a state type therefore lives in a file
        // that mentions none of those types at all. A `String` cannot be named here — every state has
        // them — which is why decoded text always travels as one of the viewer's own text types.
        val files = productionFiles.filter {
            it.projectPath.contains("/feature/") && DECLARES_A_STATE_TYPE.containsMatchIn(codeOf(it))
        }
        assertTrue(files.size >= 5, "the rule found too few feature state types to check — has it moved?")

        val violations = files.filter { HOLDS_DECRYPTED_CONTENT.containsMatchIn(codeOf(it)) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "A file declaring a feature's state type mentions a picture, player, surface or text " +
                        "document type. State holds ids and descriptions only; keep decoded pictures, " +
                        "players and texts in their own holders, in another file.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    @Test
    fun `nothing opens a web address except the confirmed link opener`() {
        // A web address leaves the vault only after the reader has seen the whole address in the
        // confirmation, and the vault locks once a browser has it. Compose has its own ways to open
        // one with neither: a `LinkAnnotation.Url` span opens on tap, and `LocalUriHandler` /
        // `openUri` open whatever they are given — forbidden everywhere. Each platform has its own
        // too — a `VIEW` intent, `openURL`, a custom tab, a Safari view — allowed only in the files
        // that are the way out on purpose: the two link openers, and the iOS camera's link to the
        // app's Settings page. Matched on source text, so an import, a qualified call and a member
        // call are all seen.
        val violations = productionFiles.filter { file ->
            val code = codeOf(file)
            OPENS_AN_ADDRESS_BY_ITSELF.containsMatchIn(code) ||
                (PLATFORM_OPENS_AN_ADDRESS.containsMatchIn(code) && OPENS_ADDRESSES_ON_PURPOSE.none { file.projectPath.endsWith(it) })
        }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine(
                    "A source opens an address outside the confirmed link opener — Compose's own " +
                        "(LinkAnnotation.Url, LocalUriHandler, UriHandler, openUri) anywhere, or the " +
                        "platform's (ACTION_VIEW, openURL, CustomTabsIntent, SFSafariViewController) outside " +
                        "the link openers. Draw links with LinkAnnotation.Clickable and hand the address to " +
                        "the viewer's confirmation, which opens it through LinkOpener.",
                )
                violations.forEach { appendLine("  ${it.projectPath}") }
            },
        )
    }

    private fun assertNoForbiddenImports(
        rule: String,
        forbidden: (String) -> Boolean,
        allowedIn: (KoFileDeclaration) -> Boolean = { false },
        remedy: String,
    ) {
        val violations = productionImports
            .filterNot { (file, _) -> allowedIn(file) }
            .filter { (_, import) -> forbidden(import) }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Forbidden $rule found in production sources. $remedy")
                violations.forEach { (file, import) -> appendLine("  ${file.projectPath}: $import") }
            },
        )
    }

    private companion object {
        /**
         * Both ways Coil hands back a loader: the builder, and the one-argument factory
         * `ImageLoader(context)` — which is `ImageLoader.Builder(context).build()`, defaults and
         * all, so it reintroduces the disk cache in a single line that mentions no builder.
         *
         * The leading boundary is what keeps `vaultImageLoader(…)` — whose name ends in the same
         * characters — from matching every call site of the one loader that is already safe.
         */
        val BUILDS_AN_IMAGE_LOADER = Regex("""(?<![A-Za-z0-9_])ImageLoader(\.Builder)?\(""")

        // Either is sufficient on its own; the loader that exists does both, and a file doing one
        // of them has clearly made the decision rather than inherited it.
        val DISK_CACHE_OFF = listOf("diskCache(null)", "CachePolicy.DISABLED")

        /**
         * Any Koin declaration of `PlatformContext` — `single`, `factory` or `scoped`, named or not,
         * and written either bare or fully qualified as `coil3.PlatformContext`. A *named* one would
         * not recurse, but it is still a second `Context` definition beside the real one and the
         * reason to want one has not come up.
         *
         * The optional package prefix is here because the first version of this regex missed
         * `single<coil3.PlatformContext>` — checked by planting both spellings, which is the only
         * way a text rule is ever actually verified.
         */
        val DECLARES_A_PLATFORM_CONTEXT =
            Regex("""(single|factory|scoped)\s*(\([^)]*\))?\s*<\s*([A-Za-z0-9_]+\.)*PlatformContext\s*>""")

        /**
         * A composable opening a window of its own — a dialog, and the three popup-backed surfaces
         * beside it. A menu and a bottom sheet are built on `Popup`, which the platform adds to the
         * window manager as its own window with its own view root, exactly as a dialog is: the
         * exclusion set on the host reaches none of them.
         *
         * Each spelling carries a leading boundary so that `AlbumNameDialog(` and other composables
         * whose names merely end in one of these words are not read as opening a window of their
         * own. They are not: they call one of these, in the file this rule then checks.
         */
        val OPENS_A_WINDOW =
            Regex(
                """(?<![A-Za-z0-9_])""" +
                    """(Dialog|AlertDialog|BasicAlertDialog|Popup|DropdownMenu|ModalBottomSheet)""" +
                    """\s*\(""",
            )

        /**
         * An Android activity being declared — its window is the one every composition sits in, and
         * it exists before any composition does, so it cannot use the composable above.
         */
        val DECLARES_AN_ACTIVITY =
            Regex("""class\s+[A-Za-z0-9_]+\s*(\([^)]*\))?\s*:\s*[^{]*Activity\b""")

        /**
         * The two properties the exclusion sets. Checked by name rather than by the helper that
         * wraps them, because a second activity is free to write its own helper — what must not
         * happen is a window with neither property set.
         */
        val EXCLUDES_A_WINDOW = listOf(
            "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS",
            "IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS",
        )

        /** A class, interface or enum whose name begins `NotSaved`. */
        val DECLARES_A_NOT_SAVED_TYPE =
            Regex("""(class|interface)\s+NotSaved[A-Za-z0-9_]*""")

        /**
         * Types that can carry an id, a name, a path or bytes — named, or arrived at by inference
         * from a string literal or a byte-array builder, which mention no type at all. The files
         * these are matched against hold no text of their own (wording lives in a formatter), so a
         * quote mark there is already a finding.
         */
        val HOLDS_CONTENT =
            Regex(
                """(?<![A-Za-z0-9_])(ByteArray|String|CharSequence|Path|RawSource|UByteArray|byteArrayOf)""" +
                    """(?![A-Za-z0-9_])|"""",
            )

        /** A class whose name ends `State` — a screen's state, or a stage of one. */
        val DECLARES_A_STATE_TYPE = Regex("""class\s+[A-Za-z0-9_]*State(?![A-Za-z0-9_])""")

        /**
         * Every type that carries decrypted vault content, on either platform or in Compose: decoded
         * pictures; the players and surfaces that hold decoded video and sound; the PDF buffer, document
         * and renderers that hold a whole decrypted PDF; the viewer's text types, the rows a text page is
         * drawn as, a link's target (an address from the file) and Compose's styled text, all holding a
         * decrypted file's words — and what makes those.
         */
        val HOLDS_DECRYPTED_CONTENT =
            Regex(
                """(?<![A-Za-z0-9_])(ImageBitmap|Bitmap|Painter|UIImage|CGImage|""" +
                    """VaultPlayer|Player|ExoPlayer|MediaPlayer|AVPlayer|AVPlayerItem|AVPlayerLayer|""" +
                    """Surface|SurfaceView|TextureView|""" +
                    """VaultPdfRenderer|VaultPdfBuffer|VaultPdfDocument|PdfOpened|PdfRenderer|""" +
                    """PDFDocument|PDFPage|""" +
                    """TextDocument|DecodedText|FormattedText|MarkdownDocument|MdBlock|MdSpan|MdListItem|""" +
                    """TextRows|MdRow|LinkTarget|AnnotatedString|""" +
                    // What makes them, so a value whose type is inferred (`val x = TextDecoding.decode(…)`)
                    // is caught by the call that made it.
                    """TextDecoding|MarkdownModel|JsonIndent|TextReading|MarkdownRows|LinkRules)(?![A-Za-z0-9_])""",
            )

        /**
         * Compose's own ways to open a web address: a URL link span, the URI handler (as the
         * composition local, the type, or its one call), and the span imported on its own.
         */
        val OPENS_AN_ADDRESS_BY_ITSELF =
            Regex("""LinkAnnotation\s*\.\s*Url(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(LocalUriHandler|UriHandler|openUri)(?![A-Za-z0-9_])""")

        /**
         * The platforms' ways to open an address: Android's `VIEW` intent and custom tabs, iOS's
         * `openURL` and Safari view.
         */
        val PLATFORM_OPENS_AN_ADDRESS =
            Regex("""(?<![A-Za-z0-9_])(ACTION_VIEW|openURL|CustomTabsIntent|SFSafariViewController)(?![A-Za-z0-9_])""")

        /**
         * The files that open an address on purpose: the two link openers, which take only an address
         * the reader confirmed, and the iOS camera, which opens the app's own page in Settings when the
         * camera was refused.
         */
        val OPENS_ADDRESSES_ON_PURPOSE = listOf(
            "/app/link/AndroidLinkOpener.kt",
            "/app/link/IosLinkOpener.kt",
            "/app/camera/IosCameraController.kt",
        )

        /** The helper that excludes the window it is composed in. */
        const val EXCLUDES_FROM_STRUCTURES = "ExcludeFromPlatformStructures"
    }
}

/**
 * Guards the guard. Every rule above is an *absence* assertion, so a scope that resolves to zero
 * files — a moved directory, a Konsist upgrade changing path handling, a broken filter — would make
 * every one of them pass while checking nothing. These assertions fail loudly in that case.
 */
class ScanCoverageTest {

    @Test
    fun `the production scan reaches every module that holds Kotlin sources`() {
        val modules = listOf(
            "app", "androidApp",
            "core/model", "core/crypto", "core/storage", "core/db",
            "core/vault", "core/designsystem", "core/logging",
            // Every new feature module belongs here: the rules are absence assertions, so a module
            // the scan never reaches is a module the security invariants do not cover.
            "feature/vault", "feature/camera",
        )
        val missing = modules.filter { module -> productionFiles.none { it.inModule(module) } }

        assertTrue(missing.isEmpty(), "Konsist scanned no production file in: $missing")
    }

    @Test
    fun `the production scan sees a meaningful number of imports`() {
        assertTrue(
            productionImports.size > 100,
            "Only ${productionImports.size} production imports scanned — the scope looks broken.",
        )
    }

    @Test
    fun `test sources are excluded from the production scan`() {
        val testFiles = productionFiles.filter { file ->
            file.projectPath.substringAfter("/src/").substringBefore('/').contains("Test")
        }

        assertTrue(testFiles.isEmpty(), "Test sources leaked into the production scan: $testFiles")
    }
}
