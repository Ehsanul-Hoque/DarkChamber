package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The album mark: a stack of pictures, drawn wherever something is shown to be in an album.
 *
 * **Kotlin, not an Android vector drawable.** A drawable cannot be referenced from `commonMain` at
 * all, so an XML copy would be an Android-only asset in a module whose whole purpose is to be shared
 * — and iOS would silently draw nothing.
 *
 * It says "this is in an album" and no more. No padlock and no shield: an album organises and does
 * not protect, and a mark implying otherwise is the easiest way to break the honesty rule by accident
 * (hard rule 6, docs/DESIGN.md).
 *
 * Drawn through [AlbumMark] rather than handed out raw, which is what keeps the colour a **theme
 * role** decided at the call site. The path below fills black, as every icon of this shape does, and
 * black on this app's surfaces is invisible — so nothing may draw this vector without a tint, and the
 * composable is the only thing that draws it.
 */
internal val albumMarkVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "AlbumMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        @Suppress("CheckReturnValue")
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(9f, 14f)
            horizontalLineTo(19f)
            lineTo(15.55f, 9.5f)
            lineToRelative(-2.3f, 3f)
            lineToRelative(-1.55f, -2f)
            lineTo(9f, 14f)
            close()
            moveTo(8f, 18f)
            quadTo(7.18f, 18f, 6.59f, 17.41f)
            reflectiveQuadTo(6f, 16f)
            verticalLineTo(4f)
            quadTo(6f, 3.17f, 6.59f, 2.59f)
            reflectiveQuadTo(8f, 2f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 4f)
            verticalLineTo(16f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 18f)
            horizontalLineTo(8f)
            close()
            moveTo(8f, 16f)
            horizontalLineTo(20f)
            verticalLineTo(4f)
            horizontalLineTo(8f)
            verticalLineTo(16f)
            close()
            moveTo(4f, 22f)
            quadTo(3.18f, 22f, 2.59f, 21.41f)
            reflectiveQuadTo(2f, 20f)
            verticalLineTo(6f)
            horizontalLineTo(4f)
            verticalLineTo(20f)
            horizontalLineTo(18f)
            verticalLineToRelative(2f)
            horizontalLineTo(4f)
            close()
            // Kept exactly as supplied, degenerate though it is — a zero-width line fills nothing.
            // This is a move, not a redraw: pruning path data would make the icon's appearance
            // something this commit changed rather than something it carried across.
            moveTo(8f, 4f)
            verticalLineTo(16f)
            verticalLineTo(4f)
            close()
        }
    }.build()
}

/**
 * The album mark, tinted by a theme role.
 *
 * [tint] defaults to the secondary text role, because every place this is drawn so far pairs it with
 * a word in that role and a mark louder than its own label reads as a warning.
 *
 * [contentDescription] is **not** defaulted away: this is a drawing, so it carries words or it is
 * invisible to a screen reader (docs/DESIGN.md §13). Pass null only where the text beside it already
 * says the same thing — a caption reading "Album" does not need the mark announced twice.
 */
@Composable
fun AlbumMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textSecondary,
) {
    Icon(
        imageVector = albumMarkVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

/**
 * The downward chevron on a control that opens something beneath it.
 *
 * Kotlin rather than an Android drawable, for the same reason the album mark is: a drawable is
 * unreachable from shared code, so the iOS build would draw nothing at all.
 *
 * **It exists because the thing it replaces was a character.** The order control used to append a
 * literal `▾` to its label and render it at the label's own 12 sp, so it was as small as the type
 * around it and carried whatever weight the system font gave it — there was nothing to resize,
 * re-weight or tint, and on a device it was barely visible. A drawing has a size of its own.
 */
internal val disclosureCaretVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "DisclosureCaret",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        @Suppress("CheckReturnValue")
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.NonZero,
        ) {
            // Exactly as supplied, from the same icon set the album mark comes from (Material
            // Symbols rounded — docs/DESIGN.md §9 asks for one set and never a mix). A filled
            // outline rather than a stroked line, which is why shrinking it below 24 dp costs less
            // here than it would for a stroke-drawn icon: the chevron's own thickness scales with it
            // instead of a 2 dp stroke thinning to a hairline.
            moveTo(12f, 15.4f)
            lineTo(6f, 9.4f)
            lineTo(7.4f, 8f)
            lineTo(12f, 12.6f)
            lineTo(16.6f, 8f)
            lineTo(18f, 9.4f)
            lineToRelative(-6f, 6f)
            close()
        }
    }.build()
}

/**
 * The disclosure chevron, tinted by a theme role.
 *
 * [tint] defaults to the **primary** text role rather than the secondary one the album mark uses.
 * They are different kinds of mark: the album mark annotates something, so it sits beside its label
 * in the same quiet role, while this one is part of a control and reads as the thing you press. A
 * caret quieter than its own label is how the glyph version came to be invisible.
 *
 * [contentDescription] is not defaulted away — same rule as the album mark. Pass null where the
 * control already has a spoken name, which is every use so far: a caret announced as "caret" tells a
 * screen reader nothing the control's own description has not already said.
 */
@Composable
fun DisclosureCaret(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(
        imageVector = disclosureCaretVector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}
