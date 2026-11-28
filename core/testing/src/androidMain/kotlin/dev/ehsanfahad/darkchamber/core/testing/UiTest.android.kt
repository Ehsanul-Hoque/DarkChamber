package dev.ehsanfahad.darkchamber.core.testing

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric's runner, inherited by every UI test; a module's other host tests stay plain JUnit. */
@RunWith(RobolectricTestRunner::class)
actual abstract class UiTest actual constructor()
