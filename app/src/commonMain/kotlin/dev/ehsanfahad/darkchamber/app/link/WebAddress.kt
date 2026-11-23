package dev.ehsanfahad.darkchamber.app.link

/**
 * Whether [address] may be handed to the browser: an `http` or `https` address, in any case, with
 * something after the scheme, and no space or control character the platform's parser would read
 * past. Both openers check it themselves before reaching the platform, whatever the viewer already
 * checked — the one place an address leaves the app does not trust its caller.
 */
internal fun isWebAddress(address: String): Boolean {
    val scheme = WEB_SCHEMES.firstOrNull { address.startsWith(it, ignoreCase = true) } ?: return false
    return address.length > scheme.length && address.none { it <= ' ' || it == DELETE }
}

private val WEB_SCHEMES = listOf("http://", "https://")

private val DELETE = Char(0x7F)
