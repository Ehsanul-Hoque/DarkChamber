package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The viewer's marks: back, details and delete.
 *
 * Material Symbols exports, **path data copied in exactly as supplied** and pinned by exact command
 * and subpath counts — the camera's marks explain why (CameraIcons.kt). Each fills black and is handed
 * out only through a composable that takes a theme role.
 */

/** Material Symbols "arrow_back_ios": the way back out of the viewer. */
internal val backVector: ImageVector by lazy {
    markVector("Back") {
        moveTo(10f, 22f)
        lineTo(0f, 12f)
        lineTo(10f, 2f)
        lineToRelative(1.78f, 1.77f)
        lineTo(3.55f, 12f)
        lineToRelative(8.23f, 8.23f)
        lineTo(10f, 22f)
        close()
    }
}

/** Material Symbols "info": opens an item's details. */
internal val detailsVector: ImageVector by lazy {
    markVector("Details") {
        moveTo(11f, 17f)
        horizontalLineToRelative(2f)
        verticalLineTo(11f)
        horizontalLineTo(11f)
        verticalLineToRelative(6f)
        close()
        moveTo(12.71f, 8.71f)
        quadTo(13f, 8.42f, 13f, 8f)
        quadTo(13f, 7.57f, 12.71f, 7.29f)
        reflectiveQuadTo(12f, 7f)
        reflectiveQuadTo(11.29f, 7.29f)
        reflectiveQuadTo(11f, 8f)
        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
        reflectiveQuadTo(12f, 9f)
        reflectiveQuadTo(12.71f, 8.71f)
        close()
        moveTo(12f, 22f)
        quadTo(9.93f, 22f, 8.1f, 21.21f)
        quadTo(6.28f, 20.43f, 4.93f, 19.08f)
        quadTo(3.58f, 17.73f, 2.79f, 15.9f)
        reflectiveQuadTo(2f, 12f)
        quadTo(2f, 9.92f, 2.79f, 8.1f)
        quadTo(3.58f, 6.27f, 4.93f, 4.93f)
        quadTo(6.28f, 3.57f, 8.1f, 2.79f)
        quadTo(9.93f, 2f, 12f, 2f)
        reflectiveQuadToRelative(3.9f, 0.79f)
        reflectiveQuadToRelative(3.17f, 2.14f)
        quadToRelative(1.35f, 1.35f, 2.14f, 3.17f)
        quadTo(22f, 9.92f, 22f, 12f)
        reflectiveQuadToRelative(-0.79f, 3.9f)
        reflectiveQuadToRelative(-2.14f, 3.17f)
        quadToRelative(-1.35f, 1.35f, -3.17f, 2.14f)
        reflectiveQuadTo(12f, 22f)
        close()
        moveToRelative(0f, -2f)
        quadToRelative(3.35f, 0f, 5.68f, -2.32f)
        reflectiveQuadTo(20f, 12f)
        reflectiveQuadTo(17.68f, 6.32f)
        reflectiveQuadTo(12f, 4f)
        reflectiveQuadTo(6.33f, 6.32f)
        reflectiveQuadTo(4f, 12f)
        reflectiveQuadToRelative(2.33f, 5.68f)
        reflectiveQuadTo(12f, 20f)
        close()
        moveToRelative(0f, -8f)
        close()
    }
}

/**
 * Material Symbols "delete_forever": a bin with a cross. The forever is honest here — deleting from
 * the viewer destroys the item's key, and there is no bin to restore from.
 */
internal val deleteVector: ImageVector by lazy {
    markVector("Delete") {
        moveTo(9.4f, 16.5f)
        lineTo(12f, 13.9f)
        lineToRelative(2.6f, 2.6f)
        lineTo(16f, 15.1f)
        lineTo(13.4f, 12.5f)
        lineTo(16f, 9.9f)
        lineTo(14.6f, 8.5f)
        lineTo(12f, 11.1f)
        lineTo(9.4f, 8.5f)
        lineTo(8f, 9.9f)
        lineToRelative(2.6f, 2.6f)
        lineTo(8f, 15.1f)
        lineToRelative(1.4f, 1.4f)
        close()
        moveTo(7f, 21f)
        quadTo(6.18f, 21f, 5.59f, 20.41f)
        reflectiveQuadTo(5f, 19f)
        verticalLineTo(6f)
        horizontalLineTo(4f)
        verticalLineTo(4f)
        horizontalLineTo(9f)
        verticalLineTo(3f)
        horizontalLineToRelative(6f)
        verticalLineTo(4f)
        horizontalLineToRelative(5f)
        verticalLineTo(6f)
        horizontalLineTo(19f)
        verticalLineTo(19f)
        quadToRelative(0f, 0.82f, -0.59f, 1.41f)
        reflectiveQuadTo(17f, 21f)
        horizontalLineTo(7f)
        close()
        moveTo(17f, 6f)
        horizontalLineTo(7f)
        verticalLineTo(19f)
        horizontalLineTo(17f)
        verticalLineTo(6f)
        close()
        moveTo(7f, 6f)
        verticalLineTo(19f)
        verticalLineTo(6f)
        close()
    }
}

/*
 * The composables. As the camera's: a theme role for the tint and a content description that is not
 * defaulted away. The tint defaults to the primary text role; the viewer passes `danger` for delete.
 */

/** The back mark. */
@Composable
fun BackMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = backVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The details mark. */
@Composable
fun DetailsMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = detailsVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The delete mark. */
@Composable
fun DeleteMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = deleteVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}
