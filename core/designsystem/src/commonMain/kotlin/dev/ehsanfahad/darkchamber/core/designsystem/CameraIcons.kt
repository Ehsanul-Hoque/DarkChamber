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
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/*
 * The camera's marks: the lens that opens it, and its close, flash and lens-switch controls.
 *
 * Material Symbols exports, **path data copied in exactly as supplied** — pruning or "tidying" a
 * command would change the drawing in a way no test that only renders it would notice, which is why
 * each is pinned by its exact command and subpath count. Kotlin rather than Android vector drawables
 * for the reason every mark here is: a drawable is unreachable from shared code, and iOS would draw
 * nothing. Each vector fills black and is handed out only through a composable that takes a theme
 * role, because black on this app's surfaces is invisible.
 */

/** One 24-unit icon with a single black-filled path, the shape every supplied mark has. */
private fun markVector(name: String, pathData: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
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
            pathBuilder = pathData,
        )
    }.build()

/** Material Symbols "camera": a lens iris, so the way into the camera reads as the aperture family. */
internal val cameraVector: ImageVector by lazy {
    markVector("Camera") {
        moveTo(11.4f, 9f)
        horizontalLineToRelative(8f)
        quadTo(18.73f, 7.27f, 17.34f, 6.04f)
        reflectiveQuadTo(14.15f, 4.3f)
        lineTo(11.4f, 9f)
        close()
        moveTo(9.1f, 11f)
        lineToRelative(4f, -6.9f)
        quadTo(12.83f, 4.05f, 12.55f, 4.02f)
        reflectiveQuadTo(12f, 4f)
        quadTo(10.35f, 4f, 8.93f, 4.63f)
        reflectiveQuadTo(6.4f, 6.3f)
        lineTo(9.1f, 11f)
        close()
        moveTo(4.25f, 14f)
        horizontalLineTo(9.7f)
        lineTo(5.7f, 7.1f)
        quadTo(4.9f, 8.13f, 4.45f, 9.36f)
        reflectiveQuadTo(4f, 12f)
        quadToRelative(0f, 0.52f, 0.06f, 1.01f)
        reflectiveQuadTo(4.25f, 14f)
        close()
        moveToRelative(5.6f, 5.7f)
        lineTo(12.55f, 15f)
        horizontalLineTo(4.6f)
        quadToRelative(0.67f, 1.73f, 2.06f, 2.96f)
        reflectiveQuadTo(9.85f, 19.7f)
        close()
        moveTo(12f, 20f)
        quadToRelative(1.65f, 0f, 3.08f, -0.63f)
        reflectiveQuadTo(17.6f, 17.7f)
        lineTo(14.9f, 13f)
        lineToRelative(-4f, 6.9f)
        quadToRelative(0.28f, 0.05f, 0.54f, 0.08f)
        reflectiveQuadTo(12f, 20f)
        close()
        moveToRelative(6.3f, -3.1f)
        quadToRelative(0.8f, -1.02f, 1.25f, -2.26f)
        reflectiveQuadTo(20f, 12f)
        quadToRelative(0f, -0.53f, -0.06f, -1.01f)
        reflectiveQuadTo(19.75f, 10f)
        horizontalLineTo(14.3f)
        lineToRelative(4f, 6.9f)
        close()
        moveTo(12f, 12f)
        close()
        moveToRelative(0f, 10f)
        quadTo(9.95f, 22f, 8.13f, 21.21f)
        quadTo(6.3f, 20.43f, 4.94f, 19.06f)
        quadTo(3.58f, 17.7f, 2.79f, 15.88f)
        reflectiveQuadTo(2f, 12f)
        quadTo(2f, 9.92f, 2.79f, 8.11f)
        reflectiveQuadTo(4.94f, 4.94f)
        reflectiveQuadTo(8.13f, 2.79f)
        reflectiveQuadTo(12f, 2f)
        quadToRelative(2.08f, 0f, 3.89f, 0.79f)
        reflectiveQuadToRelative(3.17f, 2.15f)
        reflectiveQuadToRelative(2.15f, 3.17f)
        reflectiveQuadTo(22f, 12f)
        quadToRelative(0f, 2.05f, -0.79f, 3.88f)
        reflectiveQuadToRelative(-2.15f, 3.19f)
        reflectiveQuadToRelative(-3.17f, 2.15f)
        reflectiveQuadTo(12f, 22f)
        close()
    }
}

/** Material Symbols "close". */
internal val closeVector: ImageVector by lazy {
    markVector("Close") {
        moveTo(6.4f, 19f)
        lineTo(5f, 17.6f)
        lineTo(10.6f, 12f)
        lineTo(5f, 6.4f)
        lineTo(6.4f, 5f)
        lineTo(12f, 10.6f)
        lineTo(17.6f, 5f)
        lineTo(19f, 6.4f)
        lineTo(13.4f, 12f)
        lineTo(19f, 17.6f)
        lineTo(17.6f, 19f)
        lineTo(12f, 13.4f)
        lineTo(6.4f, 19f)
        close()
    }
}

/** Material Symbols "flash_off". */
internal val flashOffVector: ImageVector by lazy {
    markVector("FlashOff") {
        moveTo(7f, 2f)
        horizontalLineTo(17f)
        lineTo(15f, 9f)
        horizontalLineToRelative(4f)
        lineToRelative(-2.93f, 4.22f)
        lineTo(14.65f, 11.8f)
        lineTo(15.2f, 11f)
        horizontalLineTo(13.85f)
        lineTo(12.68f, 9.82f)
        lineTo(14.35f, 4f)
        horizontalLineTo(9f)
        verticalLineTo(6.15f)
        lineToRelative(-2f, -2f)
        verticalLineTo(2f)
        close()
        moveToRelative(3f, 20f)
        verticalLineTo(14f)
        horizontalLineTo(7f)
        verticalLineTo(9.85f)
        lineTo(1.38f, 4.22f)
        lineTo(2.8f, 2.8f)
        lineTo(21.2f, 21.2f)
        lineToRelative(-1.43f, 1.43f)
        lineTo(13.75f, 16.6f)
        lineTo(10f, 22f)
        close()
        moveTo(11.83f, 8.98f)
        close()
    }
}

/** Material Symbols "flash_auto". */
internal val flashAutoVector: ImageVector by lazy {
    markVector("FlashAuto") {
        moveTo(7f, 15.6f)
        lineTo(10.2f, 11f)
        horizontalLineTo(7.35f)
        lineToRelative(2f, -7f)
        horizontalLineTo(4f)
        verticalLineToRelative(8f)
        horizontalLineTo(7f)
        verticalLineToRelative(3.6f)
        close()
        moveTo(5f, 22f)
        verticalLineTo(14f)
        horizontalLineTo(2f)
        verticalLineTo(2f)
        horizontalLineTo(12f)
        lineTo(10f, 9f)
        horizontalLineToRelative(4f)
        lineTo(5f, 22f)
        close()
        moveTo(7f, 12f)
        horizontalLineTo(4f)
        horizontalLineTo(7f)
        close()
        moveToRelative(7.63f, -1f)
        lineTo(18f, 2f)
        horizontalLineToRelative(1.6f)
        lineToRelative(3.43f, 9f)
        horizontalLineTo(21.48f)
        lineTo(20.68f, 8.7f)
        horizontalLineToRelative(-3.7f)
        lineTo(16.18f, 11f)
        horizontalLineTo(14.63f)
        close()
        moveToRelative(2.8f, -3.6f)
        horizontalLineToRelative(2.75f)
        lineTo(18.85f, 3.65f)
        horizontalLineTo(18.8f)
        lineTo(17.43f, 7.4f)
        close()
    }
}

/** Material Symbols "flash_on". */
internal val flashOnVector: ImageVector by lazy {
    markVector("FlashOn") {
        moveTo(12f, 15.6f)
        lineTo(15.2f, 11f)
        horizontalLineTo(12.35f)
        lineToRelative(2f, -7f)
        horizontalLineTo(9f)
        verticalLineToRelative(8f)
        horizontalLineToRelative(3f)
        verticalLineToRelative(3.6f)
        close()
        moveTo(10f, 22f)
        verticalLineTo(14f)
        horizontalLineTo(7f)
        verticalLineTo(2f)
        horizontalLineTo(17f)
        lineTo(15f, 9f)
        horizontalLineToRelative(4f)
        lineTo(10f, 22f)
        close()
        moveTo(12f, 12f)
        horizontalLineTo(9f)
        horizontalLineToRelative(3f)
        close()
    }
}

/** Material Symbols "flip_camera_android": two arrows round a lens. */
internal val switchCameraVector: ImageVector by lazy {
    markVector("SwitchCamera") {
        moveTo(12f, 22f)
        quadTo(8.43f, 22f, 5.68f, 19.75f)
        reflectiveQuadTo(2.2f, 14f)
        horizontalLineTo(4.25f)
        quadToRelative(0.7f, 2.65f, 2.85f, 4.32f)
        reflectiveQuadTo(12f, 20f)
        quadToRelative(2.15f, 0f, 4f, -1.06f)
        reflectiveQuadTo(18.9f, 16f)
        horizontalLineTo(16f)
        verticalLineTo(14f)
        horizontalLineToRelative(6f)
        verticalLineToRelative(6f)
        horizontalLineTo(20f)
        verticalLineTo(18f)
        quadToRelative(-1.43f, 1.9f, -3.52f, 2.95f)
        reflectiveQuadTo(12f, 22f)
        close()
        moveTo(9.88f, 14.13f)
        quadTo(9f, 13.25f, 9f, 12f)
        reflectiveQuadTo(9.88f, 9.88f)
        reflectiveQuadTo(12f, 9f)
        reflectiveQuadToRelative(2.13f, 0.88f)
        reflectiveQuadTo(15f, 12f)
        reflectiveQuadToRelative(-0.88f, 2.13f)
        reflectiveQuadTo(12f, 15f)
        reflectiveQuadTo(9.88f, 14.13f)
        close()
        moveTo(2f, 10f)
        verticalLineTo(4f)
        horizontalLineTo(4f)
        verticalLineTo(6f)
        quadTo(5.43f, 4.1f, 7.53f, 3.05f)
        reflectiveQuadTo(12f, 2f)
        quadToRelative(3.58f, 0f, 6.32f, 2.25f)
        quadTo(21.08f, 6.5f, 21.8f, 10f)
        horizontalLineTo(19.75f)
        quadTo(19.05f, 7.35f, 16.9f, 5.68f)
        reflectiveQuadTo(12f, 4f)
        quadTo(9.85f, 4f, 8f, 5.06f)
        reflectiveQuadTo(5.1f, 8f)
        horizontalLineTo(8f)
        verticalLineToRelative(2f)
        horizontalLineTo(2f)
        close()
    }
}

/** Which flash setting a [FlashMark] shows. Its own type, so this module needs nothing from a feature. */
enum class FlashGlyph { Off, Auto, On }

/*
 * The composables. Every one takes a theme role for its tint and a content description that is not
 * defaulted away — these are drawings, so they carry words or a screen reader cannot see them
 * (docs/DESIGN.md §13). Pass null only where the control around the mark already has a spoken name.
 * The tint defaults to the primary text role: each is part of a control, and a control mark quieter
 * than its label is how an earlier caret became invisible.
 */

/** The lens mark that opens the camera. */
@Composable
fun CameraMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = cameraVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The close mark. */
@Composable
fun CloseMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = closeVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The flash setting in force, drawn as its own mark. */
@Composable
fun FlashMark(
    glyph: FlashGlyph,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = flashVector(glyph), contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** Which drawing a flash state uses — outside the composable, so a mix-up is a failing test. */
internal fun flashVector(glyph: FlashGlyph): ImageVector =
    when (glyph) {
        FlashGlyph.Off -> flashOffVector
        FlashGlyph.Auto -> flashAutoVector
        FlashGlyph.On -> flashOnVector
    }

/** The lens-switch mark. */
@Composable
fun SwitchCameraMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = switchCameraVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}
