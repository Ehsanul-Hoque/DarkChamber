package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * The player's marks: play, pause, replay, screen rotation, brightness, volume and muted.
 *
 * Material Symbols exports, **path data copied in exactly as supplied** and pinned by exact command
 * and subpath counts — the camera's marks explain why (CameraIcons.kt). Each fills black and is handed
 * out only through a composable that takes a theme role. Skipping and holding for speed are shown as
 * text, so there is no mark for either.
 */

/** Material Symbols "play_arrow". */
internal val playVector: ImageVector by lazy {
    markVector("Play") {
        moveTo(8f, 19f)
        verticalLineTo(5f)
        lineToRelative(11f, 7f)
        lineTo(8f, 19f)
        close()
        moveToRelative(2f, -7f)
        close()
        moveToRelative(0f, 3.35f)
        lineTo(15.25f, 12f)
        lineTo(10f, 8.65f)
        verticalLineToRelative(6.7f)
        close()
    }
}

/** Material Symbols "pause". */
internal val pauseVector: ImageVector by lazy {
    markVector("Pause") {
        moveTo(13f, 19f)
        verticalLineTo(5f)
        horizontalLineToRelative(6f)
        verticalLineTo(19f)
        horizontalLineTo(13f)
        close()
        moveTo(5f, 19f)
        verticalLineTo(5f)
        horizontalLineToRelative(6f)
        verticalLineTo(19f)
        horizontalLineTo(5f)
        close()
        moveTo(15f, 17f)
        horizontalLineToRelative(2f)
        verticalLineTo(7f)
        horizontalLineTo(15f)
        verticalLineTo(17f)
        close()
        moveTo(7f, 17f)
        horizontalLineTo(9f)
        verticalLineTo(7f)
        horizontalLineTo(7f)
        verticalLineTo(17f)
        close()
        moveTo(7f, 7f)
        verticalLineTo(17f)
        verticalLineTo(7f)
        close()
        moveToRelative(8f, 0f)
        verticalLineTo(17f)
        verticalLineTo(7f)
        close()
    }
}

/** Material Symbols "replay": play again from the start, once a clip has ended. */
internal val replayVector: ImageVector by lazy {
    markVector("Replay") {
        moveTo(8.49f, 21.29f)
        quadTo(6.85f, 20.58f, 5.64f, 19.36f)
        reflectiveQuadTo(3.71f, 16.51f)
        reflectiveQuadTo(3f, 13f)
        horizontalLineTo(5f)
        quadToRelative(0f, 2.92f, 2.04f, 4.96f)
        reflectiveQuadTo(12f, 20f)
        reflectiveQuadToRelative(4.96f, -2.04f)
        quadTo(19f, 15.93f, 19f, 13f)
        quadTo(19f, 10.07f, 16.96f, 8.04f)
        reflectiveQuadTo(12f, 6f)
        horizontalLineTo(11.85f)
        lineTo(13.4f, 7.55f)
        lineTo(12f, 9f)
        lineTo(8f, 5f)
        lineTo(12f, 1f)
        lineToRelative(1.4f, 1.45f)
        lineTo(11.85f, 4f)
        horizontalLineTo(12f)
        quadToRelative(1.88f, 0f, 3.51f, 0.71f)
        quadToRelative(1.64f, 0.71f, 2.85f, 1.93f)
        reflectiveQuadToRelative(1.93f, 2.85f)
        reflectiveQuadTo(21f, 13f)
        reflectiveQuadToRelative(-0.71f, 3.51f)
        reflectiveQuadToRelative(-1.93f, 2.85f)
        reflectiveQuadToRelative(-2.85f, 1.93f)
        reflectiveQuadTo(12f, 22f)
        reflectiveQuadTo(8.49f, 21.29f)
        close()
    }
}

/** Material Symbols "screen_rotation_alt": turns the player between portrait and landscape. */
internal val rotationVector: ImageVector by lazy {
    markVector("Rotation") {
        moveTo(12.18f, 21.4f)
        lineTo(3.75f, 13f)
        horizontalLineTo(6.6f)
        lineToRelative(7f, 7f)
        lineToRelative(5f, -5f)
        horizontalLineTo(16f)
        verticalLineTo(13f)
        horizontalLineToRelative(6f)
        verticalLineToRelative(6f)
        horizontalLineTo(20f)
        verticalLineTo(16.4f)
        lineToRelative(-5f, 5f)
        quadToRelative(-0.57f, 0.58f, -1.41f, 0.58f)
        reflectiveQuadTo(12.18f, 21.4f)
        close()
        moveTo(2f, 11f)
        verticalLineTo(5f)
        horizontalLineTo(4f)
        verticalLineTo(7.6f)
        lineToRelative(5f, -5f)
        quadTo(9.58f, 2.02f, 10.41f, 2.02f)
        reflectiveQuadTo(11.83f, 2.6f)
        lineTo(20.25f, 11f)
        horizontalLineTo(17.4f)
        lineToRelative(-7f, -7f)
        lineToRelative(-5f, 5f)
        horizontalLineTo(8f)
        verticalLineToRelative(2f)
        horizontalLineTo(2f)
        close()
    }
}

/** Material Symbols "brightness_medium": shown while a slide changes the screen's brightness. */
internal val brightnessVector: ImageVector by lazy {
    markVector("Brightness") {
        moveTo(12f, 23.3f)
        lineTo(8.65f, 20f)
        horizontalLineTo(4f)
        verticalLineTo(15.35f)
        lineTo(0.7f, 12f)
        lineTo(4f, 8.65f)
        verticalLineTo(4f)
        horizontalLineTo(8.65f)
        lineTo(12f, 0.7f)
        lineTo(15.35f, 4f)
        horizontalLineTo(20f)
        verticalLineTo(8.65f)
        lineTo(23.3f, 12f)
        lineTo(20f, 15.35f)
        verticalLineTo(20f)
        horizontalLineTo(15.35f)
        lineTo(12f, 23.3f)
        close()
        moveTo(12f, 17f)
        quadToRelative(2.08f, 0f, 3.54f, -1.46f)
        reflectiveQuadTo(17f, 12f)
        quadTo(17f, 9.92f, 15.54f, 8.46f)
        reflectiveQuadTo(12f, 7f)
        verticalLineTo(17f)
        close()
        moveToRelative(0f, 3.5f)
        lineTo(14.5f, 18f)
        horizontalLineTo(18f)
        verticalLineTo(14.5f)
        lineTo(20.5f, 12f)
        lineTo(18f, 9.5f)
        verticalLineTo(6f)
        horizontalLineTo(14.5f)
        lineTo(12f, 3.5f)
        lineTo(9.5f, 6f)
        horizontalLineTo(6f)
        verticalLineTo(9.5f)
        lineTo(3.5f, 12f)
        lineTo(6f, 14.5f)
        verticalLineTo(18f)
        horizontalLineTo(9.5f)
        lineTo(12f, 20.5f)
        close()
        moveTo(12f, 12f)
        close()
    }
}

/** Material Symbols "volume_up": shown while a slide changes the volume, above zero. */
internal val volumeVector: ImageVector by lazy {
    markVector("Volume") {
        moveTo(14f, 20.73f)
        verticalLineTo(18.68f)
        quadToRelative(2.25f, -0.65f, 3.63f, -2.5f)
        reflectiveQuadTo(19f, 11.98f)
        reflectiveQuadTo(17.63f, 7.77f)
        quadTo(16.25f, 5.93f, 14f, 5.27f)
        verticalLineTo(3.22f)
        quadToRelative(3.1f, 0.7f, 5.05f, 3.14f)
        reflectiveQuadTo(21f, 11.98f)
        reflectiveQuadToRelative(-1.95f, 5.61f)
        reflectiveQuadTo(14f, 20.73f)
        close()
        moveTo(3f, 15f)
        verticalLineTo(9f)
        horizontalLineTo(7f)
        lineTo(12f, 4f)
        verticalLineTo(20f)
        lineTo(7f, 15f)
        horizontalLineTo(3f)
        close()
        moveToRelative(11f, 1f)
        verticalLineTo(7.95f)
        quadTo(15.18f, 8.5f, 15.84f, 9.6f)
        reflectiveQuadTo(16.5f, 12f)
        quadToRelative(0f, 1.27f, -0.66f, 2.36f)
        reflectiveQuadTo(14f, 16f)
        close()
        moveTo(10f, 8.85f)
        lineTo(7.85f, 11f)
        horizontalLineTo(5f)
        verticalLineToRelative(2f)
        horizontalLineTo(7.85f)
        lineTo(10f, 15.15f)
        verticalLineTo(8.85f)
        close()
        moveTo(7.5f, 12f)
        close()
    }
}

/** Material Symbols "volume_off": shown while a slide has taken the volume to zero. */
internal val mutedVector: ImageVector by lazy {
    markVector("Muted") {
        moveTo(19.8f, 22.6f)
        lineTo(16.78f, 19.58f)
        quadToRelative(-0.63f, 0.4f, -1.33f, 0.69f)
        reflectiveQuadTo(14f, 20.73f)
        verticalLineTo(18.68f)
        quadToRelative(0.35f, -0.13f, 0.69f, -0.25f)
        quadToRelative(0.34f, -0.13f, 0.64f, -0.3f)
        lineTo(12f, 14.8f)
        verticalLineTo(20f)
        lineTo(7f, 15f)
        horizontalLineTo(3f)
        verticalLineTo(9f)
        horizontalLineTo(6.2f)
        lineTo(1.4f, 4.2f)
        lineTo(2.8f, 2.8f)
        lineTo(21.2f, 21.2f)
        lineToRelative(-1.4f, 1.4f)
        close()
        moveTo(19.6f, 16.8f)
        lineTo(18.15f, 15.35f)
        quadToRelative(0.42f, -0.78f, 0.64f, -1.63f)
        reflectiveQuadTo(19f, 11.98f)
        quadTo(19f, 9.63f, 17.63f, 7.77f)
        quadTo(16.25f, 5.93f, 14f, 5.27f)
        verticalLineTo(3.22f)
        quadToRelative(3.1f, 0.7f, 5.05f, 3.14f)
        reflectiveQuadTo(21f, 11.98f)
        quadToRelative(0f, 1.32f, -0.36f, 2.55f)
        reflectiveQuadTo(19.6f, 16.8f)
        close()
        moveTo(16.25f, 13.45f)
        lineTo(14f, 11.2f)
        verticalLineTo(7.95f)
        quadTo(15.18f, 8.5f, 15.84f, 9.6f)
        reflectiveQuadTo(16.5f, 12f)
        quadToRelative(0f, 0.38f, -0.06f, 0.74f)
        reflectiveQuadToRelative(-0.19f, 0.71f)
        close()
        moveTo(12f, 9.2f)
        lineTo(9.4f, 6.6f)
        lineTo(12f, 4f)
        verticalLineTo(9.2f)
        close()
        moveToRelative(-2f, 5.95f)
        verticalLineTo(12.8f)
        lineTo(8.2f, 11f)
        horizontalLineTo(5f)
        verticalLineToRelative(2f)
        horizontalLineTo(7.85f)
        lineTo(10f, 15.15f)
        close()
        moveTo(9.1f, 11.9f)
        close()
    }
}

/*
 * The composables. As the viewer's: a theme role for the tint and a content description that is not
 * defaulted away. The tint defaults to the primary text role.
 */

/** The play mark. */
@Composable
fun PlayMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = playVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The pause mark. */
@Composable
fun PauseMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = pauseVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The replay mark. */
@Composable
fun ReplayMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = replayVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The screen-rotation mark. */
@Composable
fun RotationMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = rotationVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The brightness mark. */
@Composable
fun BrightnessMark(
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(imageVector = brightnessVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** The volume mark: [muted] draws the crossed-out speaker shown at zero. */
@Composable
fun VolumeMark(
    muted: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = DarkChamberTheme.colors.textPrimary,
) {
    Icon(
        imageVector = volumeMarkVector(muted),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier,
    )
}

/** Which drawing [VolumeMark] shows. */
internal fun volumeMarkVector(muted: Boolean): ImageVector = if (muted) mutedVector else volumeVector
