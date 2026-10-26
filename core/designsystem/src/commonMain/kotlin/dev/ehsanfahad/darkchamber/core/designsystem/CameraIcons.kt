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
 * The camera's marks: the lens that opens it, its close, flash, torch and lens-switch controls, and
 * the microphone.
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

/** Material Symbols "flashlight_on": the torch, lit. */
internal val torchOnVector: ImageVector by lazy {
    markVector("TorchOn") {
        moveTo(8f, 22f)
        verticalLineTo(11f)
        lineTo(6f, 8f)
        verticalLineTo(2f)
        horizontalLineTo(18f)
        verticalLineTo(8f)
        lineToRelative(-2f, 3f)
        verticalLineTo(22f)
        horizontalLineTo(8f)
        close()
        moveToRelative(2.94f, -6.94f)
        quadTo(10.5f, 14.63f, 10.5f, 14f)
        reflectiveQuadToRelative(0.44f, -1.06f)
        reflectiveQuadTo(12f, 12.5f)
        reflectiveQuadToRelative(1.06f, 0.44f)
        reflectiveQuadTo(13.5f, 14f)
        reflectiveQuadToRelative(-0.44f, 1.06f)
        reflectiveQuadTo(12f, 15.5f)
        reflectiveQuadTo(10.94f, 15.06f)
        close()
        moveTo(8f, 5f)
        horizontalLineToRelative(8f)
        verticalLineTo(4f)
        horizontalLineTo(8f)
        verticalLineTo(5f)
        close()
        moveToRelative(8f, 2f)
        horizontalLineTo(8f)
        verticalLineTo(7.4f)
        lineToRelative(2f, 3f)
        verticalLineTo(20f)
        horizontalLineToRelative(4f)
        verticalLineTo(10.4f)
        lineToRelative(2f, -3f)
        verticalLineTo(7f)
        close()
        moveToRelative(-4f, 5f)
        close()
    }
}

/** Material Symbols "flashlight_off": the torch, out. */
internal val torchOffVector: ImageVector by lazy {
    markVector("TorchOff") {
        moveTo(19.8f, 22.6f)
        lineTo(16f, 18.8f)
        verticalLineTo(22f)
        horizontalLineTo(8f)
        verticalLineTo(10.8f)
        lineTo(1.4f, 4.2f)
        lineTo(2.8f, 2.8f)
        lineTo(21.2f, 21.2f)
        lineToRelative(-1.4f, 1.4f)
        close()
        moveTo(10f, 20f)
        horizontalLineToRelative(4f)
        verticalLineTo(16.8f)
        lineToRelative(-4f, -4f)
        verticalLineTo(20f)
        close()
        moveToRelative(6f, -6.85f)
        lineToRelative(-2f, -2f)
        verticalLineTo(10.4f)
        lineToRelative(2f, -3f)
        verticalLineTo(7f)
        horizontalLineTo(9.85f)
        lineToRelative(-2f, -2f)
        horizontalLineTo(16f)
        verticalLineTo(4f)
        horizontalLineTo(6.85f)
        lineTo(6f, 3.15f)
        verticalLineTo(2f)
        horizontalLineTo(18f)
        verticalLineTo(8f)
        lineToRelative(-2f, 3f)
        verticalLineToRelative(2.15f)
        close()
        moveTo(12f, 14.8f)
        close()
        moveTo(12.43f, 9.57f)
        close()
    }
}

/** Material Symbols "mic": sound on. */
internal val microphoneOnVector: ImageVector by lazy {
    markVector("MicrophoneOn") {
        moveTo(9.88f, 13.13f)
        quadTo(9f, 12.25f, 9f, 11f)
        verticalLineTo(5f)
        quadTo(9f, 3.75f, 9.88f, 2.88f)
        reflectiveQuadTo(12f, 2f)
        reflectiveQuadToRelative(2.13f, 0.88f)
        reflectiveQuadTo(15f, 5f)
        verticalLineToRelative(6f)
        quadToRelative(0f, 1.25f, -0.88f, 2.13f)
        reflectiveQuadTo(12f, 14f)
        reflectiveQuadTo(9.88f, 13.13f)
        close()
        moveTo(12f, 8f)
        close()
        moveTo(11f, 21f)
        verticalLineTo(17.93f)
        quadTo(8.4f, 17.58f, 6.7f, 15.6f)
        reflectiveQuadTo(5f, 11f)
        horizontalLineTo(7f)
        quadToRelative(0f, 2.07f, 1.46f, 3.54f)
        reflectiveQuadTo(12f, 16f)
        reflectiveQuadToRelative(3.54f, -1.46f)
        reflectiveQuadTo(17f, 11f)
        horizontalLineToRelative(2f)
        quadToRelative(0f, 2.63f, -1.7f, 4.6f)
        reflectiveQuadTo(13f, 17.93f)
        verticalLineTo(21f)
        horizontalLineTo(11f)
        close()
        moveToRelative(1.71f, -9.29f)
        quadTo(13f, 11.43f, 13f, 11f)
        verticalLineTo(5f)
        quadTo(13f, 4.57f, 12.71f, 4.29f)
        reflectiveQuadTo(12f, 4f)
        reflectiveQuadTo(11.29f, 4.29f)
        reflectiveQuadTo(11f, 5f)
        verticalLineToRelative(6f)
        quadToRelative(0f, 0.42f, 0.29f, 0.71f)
        reflectiveQuadTo(12f, 12f)
        reflectiveQuadToRelative(0.71f, -0.29f)
        close()
    }
}

/** Material Symbols "mic_off": the no-sound mark. */
internal val microphoneOffVector: ImageVector by lazy {
    markVector("MicrophoneOff") {
        moveTo(17.75f, 14.95f)
        lineTo(16.3f, 13.5f)
        quadToRelative(0.35f, -0.58f, 0.52f, -1.2f)
        reflectiveQuadTo(17f, 11f)
        horizontalLineToRelative(2f)
        quadToRelative(0f, 1.1f, -0.32f, 2.09f)
        quadToRelative(-0.32f, 0.99f, -0.93f, 1.86f)
        close()
        moveTo(12f, 9.15f)
        close()
        moveToRelative(2.8f, 2.8f)
        lineTo(13f, 10.15f)
        verticalLineTo(5f)
        quadTo(13f, 4.57f, 12.71f, 4.29f)
        reflectiveQuadTo(12f, 4f)
        reflectiveQuadTo(11.29f, 4.29f)
        reflectiveQuadTo(11f, 5f)
        verticalLineTo(8.15f)
        lineToRelative(-2f, -2f)
        verticalLineTo(5f)
        quadTo(9f, 3.75f, 9.88f, 2.88f)
        reflectiveQuadTo(12f, 2f)
        reflectiveQuadToRelative(2.13f, 0.88f)
        reflectiveQuadTo(15f, 5f)
        verticalLineToRelative(6f)
        quadToRelative(0f, 0.27f, -0.06f, 0.5f)
        reflectiveQuadTo(14.8f, 11.95f)
        close()
        moveTo(11f, 21f)
        verticalLineTo(17.93f)
        quadTo(8.4f, 17.58f, 6.7f, 15.6f)
        reflectiveQuadTo(5f, 11f)
        horizontalLineTo(7f)
        quadToRelative(0f, 2.07f, 1.44f, 3.54f)
        reflectiveQuadTo(12f, 16f)
        quadToRelative(0.85f, 0f, 1.61f, -0.26f)
        quadTo(14.38f, 15.48f, 15f, 15f)
        lineToRelative(1.43f, 1.43f)
        quadTo(15.7f, 17f, 14.84f, 17.4f)
        reflectiveQuadTo(13f, 17.93f)
        verticalLineTo(21f)
        horizontalLineTo(11f)
        close()
        moveToRelative(8.8f, 1.6f)
        lineTo(1.4f, 4.2f)
        lineTo(2.8f, 2.8f)
        lineTo(21.2f, 21.2f)
        lineToRelative(-1.4f, 1.4f)
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

/** The torch, lit or out — the light control in video. */
@Composable
fun TorchMark(
    on: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = torchVector(on), contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** Which drawing a torch state uses — outside the composable, so a mix-up is a failing test. */
internal fun torchVector(on: Boolean): ImageVector = if (on) torchOnVector else torchOffVector

/** The microphone, on or crossed out; crossed out, it is the no-sound mark beside a recording. */
@Composable
fun MicrophoneMark(
    on: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = microphoneVector(on), contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** Which drawing a microphone state uses — outside the composable, so a mix-up is a failing test. */
internal fun microphoneVector(on: Boolean): ImageVector = if (on) microphoneOnVector else microphoneOffVector
