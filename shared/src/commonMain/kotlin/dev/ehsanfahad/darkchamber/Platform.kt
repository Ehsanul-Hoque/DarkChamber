package dev.ehsanfahad.darkchamber

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform