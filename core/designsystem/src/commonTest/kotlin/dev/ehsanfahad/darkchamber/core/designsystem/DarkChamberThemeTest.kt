package dev.ehsanfahad.darkchamber.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Token + aperture-logic tests. Everything asserted here is a pure value (color/shape/
 * motion factories, the aperture open-fraction mapping) so the suite runs on the JVM
 * host and the iOS simulator without a Compose composition.
 */
class DarkChamberThemeTest {
    @Test
    fun darkColorsCarryTheChamberTokens() {
        val c = darkChamberDarkColors()
        assertTrue(c.isDark)
        assertEquals(Color(0xFF131110), c.background)
        assertEquals(Color(0xFFE3A144), c.accent) // safelight amber
        assertEquals(Color(0xFF8FB896), c.sealed) // calm sage
        assertEquals(Color(0xFFE06A5E), c.danger)
    }

    @Test
    fun lightColorsCarryThePrintRoomTokens() {
        val c = darkChamberLightColors()
        assertTrue(!c.isDark)
        assertEquals(Color(0xFFFAF9F7), c.background)
        assertEquals(Color(0xFF7A5200), c.accent) // safelight deep
    }

    @Test
    fun darkAndLightAreDistinct() {
        assertNotEquals(darkChamberDarkColors().background, darkChamberLightColors().background)
        assertNotEquals(darkChamberDarkColors().accent, darkChamberLightColors().accent)
    }

    @Test
    fun shapesMatchTheRadiusScale() {
        val s = DarkChamberShapes()
        assertEquals(6.dp, s.radiusSm)
        assertEquals(10.dp, s.radiusMd)
        assertEquals(14.dp, s.radiusLg)
        assertEquals(20.dp, s.radiusXl)
    }

    @Test
    fun motionMatchesTheDurationTokens() {
        val m = DarkChamberMotion()
        assertEquals(120, m.motionFast)
        assertEquals(200, m.motionBase)
        assertEquals(320, m.motionSlow)
        // Lock is never animated slower than motionBase (§8).
        assertTrue(m.motionSlow >= m.motionBase)
    }

    @Test
    fun typographyMonoRolesAreDistinctFromBody() {
        val t = DarkChamberTypography()
        assertNotEquals(t.body.fontFamily, t.mono.fontFamily)
        assertEquals(t.mono.fontFamily, t.monoKey.fontFamily)
    }

    @Test
    fun apertureOpenFractionMapsState() {
        assertEquals(0f, ApertureState.Closed.openFraction())
        assertEquals(1f, ApertureState.Open.openFraction())
        assertEquals(0f, ApertureState.Failed.openFraction()) // failed stays shut (§7)
        assertTrue(ApertureState.Unlocking.openFraction() in 0f..1f)
    }

    @Test
    fun apertureDefaultsAreTheShutterGeometry() {
        assertEquals(72.dp, ApertureDefaults.Size)
        assertEquals(3.dp, ApertureDefaults.RingWidth)
    }
}
