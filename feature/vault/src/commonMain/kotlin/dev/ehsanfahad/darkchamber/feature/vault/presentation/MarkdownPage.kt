package dev.ehsanfahad.darkchamber.feature.vault.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ehsanfahad.darkchamber.core.designsystem.DarkChamberTheme
import dev.ehsanfahad.darkchamber.core.designsystem.ExcludeFromPlatformStructures
import kotlinx.coroutines.launch

/**
 * A Markdown file drawn as its blocks ([MarkdownRows]): headings, paragraphs, lists with their marks,
 * quotes with a bar, code and tables each in their own sideways scroll, rules, and HTML as written.
 * A link that may do something is a tap: a web address opens the confirmation ([LinkDialog]), a
 * `#section` brings its heading to the top; anything else is plain words.
 */
@Composable
internal fun MarkdownPageView(
    document: MarkdownDocument,
    onTap: () -> Unit,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    barsShown: Boolean,
    onOpenLink: (String) -> Unit,
) {
    val colors = DarkChamberTheme.colors
    val typography = DarkChamberTheme.typography
    val rows = remember(document) { MarkdownRows.rows(document) }
    val sections = remember(document) { LinkRules.sections(document) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The address being asked about. Kept in memory only, never saved state: an address is vault content,
    // and saved state may be written out by the platform.
    var asking by remember { mutableStateOf<LinkTarget.Web?>(null) }
    // One sideways scroll per code block or table, shared by its pieces so they move together.
    val sideways = remember(rows) { HashMap<Int, ScrollState>() }

    val runStyles = remember(colors, typography) {
        MdRunStyles(
            code = SpanStyle(fontFamily = typography.mono.fontFamily, background = colors.surface),
            link = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline),
            image = SpanStyle(fontStyle = FontStyle.Italic, color = colors.textSecondary),
        )
    }
    val onLink: (String) -> Unit = { address ->
        when (val target = LinkRules.target(address, sections)) {
            is LinkTarget.Web -> asking = target
            is LinkTarget.Section -> MarkdownRows.firstRowOf(rows, target.blockIndex)?.let { scope.launch { list.animateScrollToItem(it) } }
            LinkTarget.Inert -> Unit
        }
    }

    RowsColumn(
        list = list,
        rowCount = rows.size,
        note = null,
        bubble = { null },
        onTap = onTap,
        topPaddingPx = topPaddingPx,
        bottomPaddingPx = bottomPaddingPx,
        barsShown = barsShown,
    ) { index ->
        val row = rows[index]
        val scroll = when (row) {
            is MdRow.Code -> sideways.getOrPut(row.group) { ScrollState(0) }
            is MdRow.TableRows -> sideways.getOrPut(row.group) { ScrollState(0) }
            else -> null
        }
        MdRowView(row = row, runStyles = runStyles, sections = sections, onLink = onLink, scroll = scroll)
    }

    asking?.let { web ->
        LinkDialog(
            address = web.address,
            host = web.host,
            onOpen = {
                asking = null
                onOpenLink(web.address)
            },
            onCancel = { asking = null },
        )
    }
}

/** One row: its quote bars and list indent, then what it holds. */
@Composable
private fun MdRowView(row: MdRow, runStyles: MdRunStyles, sections: Map<String, Int>, onLink: (String) -> Unit, scroll: ScrollState?) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val quotes = row.quotes.coerceAtMost(MarkdownRows.MAX_DRAWN_DEPTH)
    val indent = row.indent.coerceAtMost(MarkdownRows.MAX_DRAWN_DEPTH)
    val density = LocalDensity.current
    val barColor = colors.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (row.gapAbove) spacing.spaceSm else 0.dp)
            // The quote bars, one per quote, drawn down the row's left edge.
            .drawBehind {
                val step = with(density) { QUOTE_STEP.toPx() }
                val bar = with(density) { QUOTE_BAR.toPx() }
                repeat(quotes) { i -> drawRect(barColor, Offset(i * step, 0f), Size(bar, size.height)) }
            }
            .padding(start = QUOTE_STEP * quotes + INDENT_STEP * (indent - 1).coerceAtLeast(0)),
    ) {
        if (indent > 0) {
            val marker = (row as? MdRow.Words)?.marker
            val spoken = (row as? MdRow.Words)?.markerSpoken
            Box(modifier = Modifier.width(INDENT_STEP)) {
                if (marker != null) {
                    Text(
                        text = marker,
                        style = DarkChamberTheme.typography.bodyLarge,
                        color = colors.textSecondary,
                        modifier = Modifier.clearAndSetSemantics { if (spoken != null) contentDescription = spoken },
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            when (row) {
                is MdRow.Words -> WordsRow(row, runStyles, sections, onLink)
                is MdRow.Code -> CodeRow(row, scroll!!)
                is MdRow.TableRows -> TableRow(row, runStyles, sections, onLink, scroll!!)
                is MdRow.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = spacing.spaceXs).height(1.dp).background(colors.outline))
                is MdRow.Raw -> Text(text = row.text, style = DarkChamberTheme.typography.mono, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun WordsRow(row: MdRow.Words, runStyles: MdRunStyles, sections: Map<String, Int>, onLink: (String) -> Unit) {
    if (row.spans.isEmpty()) return
    val typography = DarkChamberTheme.typography
    val style = when (row.size) {
        MdTextSize.Title1 -> typography.titleLarge
        MdTextSize.Title2 -> typography.title
        MdTextSize.Title3 -> typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
        MdTextSize.Body -> typography.bodyLarge
    }
    val text = remember(row, runStyles) { MarkdownText.annotated(row.spans, runStyles, sections, onLink) }
    Text(
        text = text,
        style = style,
        color = DarkChamberTheme.colors.textPrimary,
        modifier = Modifier.fillMaxWidth().then(if (row.size != MdTextSize.Body) Modifier.semantics { heading() } else Modifier),
    )
}

/** A piece of a code block: monospace, unwrapped, as wide as the block's longest line, on the code ground. */
@Composable
private fun CodeRow(row: MdRow.Code, scroll: ScrollState) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val mono = DarkChamberTheme.typography.mono
    val charPx = rememberCharWidth(mono)
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .keepsItsDrag(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = spacing.spaceSm)
            .padding(top = if (row.first) spacing.spaceSm else 0.dp, bottom = if (row.last) spacing.spaceSm else 0.dp),
    ) {
        Text(
            text = row.text,
            style = mono,
            color = colors.textPrimary,
            softWrap = false,
            modifier = Modifier.widthIn(min = with(density) { (row.widthChars * charPx).toDp() }),
        )
    }
}

/** A piece of a table: its rows of cells, each column one width across the whole table. */
@Composable
private fun TableRow(row: MdRow.TableRows, runStyles: MdRunStyles, sections: Map<String, Int>, onLink: (String) -> Unit, scroll: ScrollState) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val body = DarkChamberTheme.typography.bodyLarge
    val charPx = rememberCharWidth(body, sample = LETTERS)
    val density = LocalDensity.current
    // A reading face's letters differ in width, so a column gets one character's room to spare.
    val widths = row.widths.map { with(density) { ((it + 1) * charPx).toDp() } + spacing.spaceSm * 2 }
    Column(modifier = Modifier.fillMaxWidth().keepsItsDrag(scroll).horizontalScroll(scroll)) {
        val all = listOfNotNull(row.header?.let { it to true }) + row.rows.map { it to false }
        for ((cells, isHeader) in all) {
            // One height for the row: the tallest cell's, which every cell fills, so the borders line up.
            Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.height(IntrinsicSize.Min)) {
                widths.forEachIndexed { c, width ->
                    val spans = cells.getOrNull(c).orEmpty()
                    val text = remember(spans, runStyles) { MarkdownText.annotated(spans, runStyles, sections, onLink) }
                    Box(
                        modifier = Modifier
                            .width(width)
                            .fillMaxHeight()
                            .border(1.dp, colors.outline)
                            .padding(horizontal = spacing.spaceSm, vertical = spacing.spaceXs),
                    ) {
                        Text(
                            text = text,
                            style = if (isHeader) body.copy(fontWeight = FontWeight.Bold) else body,
                            color = colors.textPrimary,
                            textAlign = when (row.alignments.getOrNull(c)) {
                                CellAlign.Center -> TextAlign.Center
                                CellAlign.End -> TextAlign.End
                                else -> TextAlign.Start
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A sideways scroll that keeps the whole of a drag started on it: past its end the rest of the drag,
 * and of a fling, stops there rather than going on to turn the viewer's page — a new drag does that.
 * A block narrow enough not to scroll keeps nothing, so the page still turns over it.
 */
private fun Modifier.keepsItsDrag(scroll: ScrollState): Modifier = nestedScroll(
    object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (scroll.maxValue > 0) Offset(available.x, 0f) else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (scroll.maxValue > 0) Velocity(available.x, 0f) else Velocity.Zero
    },
)

/**
 * The width of one character in [style], in pixels: the average over [sample] — a monospace face's
 * exactly, a reading face's on average, since its letters differ.
 */
@Composable
private fun rememberCharWidth(style: TextStyle, sample: String = "0"): Float {
    val measurer = rememberTextMeasurer()
    return remember(style, measurer, sample) { measurer.measure(sample, style).size.width.toFloat() / sample.length }
}

/** Letters and digits, for a reading face's average width. */
private const val LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/**
 * The confirmation before a web link leaves the vault: the full address exactly as the file gives it,
 * wrapping, the site it reaches on its own line, what opening it means, and Cancel before Open. Its own
 * window, so its own exclusion from the platform's structures.
 */
@Composable
private fun LinkDialog(address: String, host: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    val colors = DarkChamberTheme.colors
    val spacing = DarkChamberTheme.spacing
    val typography = DarkChamberTheme.typography
    Dialog(onDismissRequest = onCancel) {
        ExcludeFromPlatformStructures()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(color = colors.surface, shape = DarkChamberTheme.shapes.large)
                .padding(spacing.spaceLg),
        ) {
            Text(text = ViewerFormat.linkTitle(), style = typography.title, color = colors.textPrimary)
            Text(
                text = address,
                style = typography.mono,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.spaceSm),
            )
            Text(
                text = ViewerFormat.linkSite(host),
                style = typography.body,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = spacing.spaceXs),
            )
            Text(
                text = ViewerFormat.linkLeaves(),
                style = typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceSm),
            )
            Text(
                text = ViewerFormat.linkLocks(),
                style = typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.spaceXs),
            )
            Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = spacing.spaceMd)) {
                TextButton(onClick = onCancel) {
                    Text(text = ViewerFormat.cancel(), style = typography.label, color = colors.textPrimary)
                }
                TextButton(onClick = onOpen) {
                    Text(text = ViewerFormat.linkOpen(), style = typography.label, color = colors.accent)
                }
            }
        }
    }
}

/** The room a quote bar takes, and the bar itself. */
private val QUOTE_STEP: Dp = 12.dp
private val QUOTE_BAR: Dp = 3.dp

/** The room each list level takes; its last slot holds the item's mark. */
private val INDENT_STEP: Dp = 32.dp
