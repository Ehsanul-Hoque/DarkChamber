package dev.ehsanfahad.darkchamber.feature.camera.api

/**
 * Where a recording's file comes from: a fresh scratch name in the vault's private `tmp/` per
 * recording, nothing created until the recorder writes. The app implements it over `core:storage`.
 */
fun interface ClipFiles {
    fun newClip(): ClipFile
}
