package dev.ehsanfahad.darkchamber.core.testing

/**
 * The base of every Compose UI test, so one test written in commonTest runs on both platforms: on the
 * Android host it runs under Robolectric, which gives the JVM an Android framework to compose on; on the
 * iOS simulator it needs nothing. A module opts in with the `darkchamber.compose.uitest` plugin.
 */
expect abstract class UiTest()
