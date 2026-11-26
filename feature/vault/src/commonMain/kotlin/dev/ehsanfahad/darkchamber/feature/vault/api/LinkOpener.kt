package dev.ehsanfahad.darkchamber.feature.vault.api

/**
 * The platform seam that hands a web address to another app — the browser — after the reader has
 * confirmed it. The app module implements it per platform: a browsable `VIEW` intent on Android,
 * `UIApplication.openURL` on iOS.
 *
 * The contract every implementation keeps:
 *
 * - **Web addresses only.** The viewer calls it only with an `http` or `https` address the link rules
 *   accepted; an implementation checks the scheme again itself and opens nothing else.
 * - **Handed over as written.** The address goes to the platform exactly as the reader saw it in the
 *   confirmation — nothing added, nothing tidied.
 * - **Nothing kept.** No address is stored, logged or remembered once it has been handed over.
 * - **Called on the main thread**, where both platforms start another app; it may suspend until the
 *   platform answers.
 * - **Declines, never throws**, across this seam.
 */
interface LinkOpener {
    /** Hand [address] to the app that opens web links, and say whether one took it. */
    suspend fun open(address: String): LinkOpened
}

/** What handing an address over gave. */
enum class LinkOpened {
    /** Another app took the address. It has left the vault. */
    Opened,

    /** Nothing took it: no app could, or the platform refused it. Nothing left the vault. */
    NotOpened,
}
