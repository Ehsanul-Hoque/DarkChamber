package dev.ehsanfahad.darkchamber.core.vault

/**
 * What [VaultObjects.importObject] does when the album it was asked to file into is not there —
 * whether it never was, or was deleted while a long import ran.
 */
sealed interface WhenAlbumMissing {
    /**
     * The import fails with `VaultError.Storage.DestinationMissing` and stores nothing. The default:
     * quietly putting a file somewhere other than where the caller asked is the kind of helpfulness
     * that loses things, and an import has a summary in which to say what happened.
     */
    data object Fail : WhenAlbumMissing

    /**
     * The item is kept and filed unfiled instead, with no second encrypt, and [onFiledUnfiled] is
     * called at most once before the import returns so the caller can say where it went. It can be
     * called before the encrypt starts, and the import can still fail after it, so it means "filed
     * unfiled" only alongside an `Ok` result.
     *
     * For a caller whose payload is expensive and cannot be had again — a recording, minutes of
     * someone's time — where losing it would be worse than filing it somewhere it was not aimed.
     */
    class FileUnfiled(val onFiledUnfiled: () -> Unit) : WhenAlbumMissing
}
