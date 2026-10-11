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
