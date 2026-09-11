package dev.ehsanfahad.darkchamber.core.model

/**
 * What a vault item *is* — the named form of the catalog's `items.kind` column
 * (docs/ARCHITECTURE.md §5), which discriminates a stored file from a note.
 *
 * [code] is **persisted data**: it is written into every catalog row, so once a released version
 * has created a vault the mapping can only be *added to*, never renumbered — an old vault must
 * always open (docs/FORMAT.md versioning policy). New kinds take the next free code.
 *
 * [UNKNOWN] is not a kind anything is stored as. It exists so a defaulted, corrupted, or
 * newer-than-us row decodes to something explicit instead of masquerading as a file: zero is
 * deliberately not a real kind, and [fromCode] never throws.
 */
enum class VaultItemKind(val code: Int) {
    /** Not a real kind — the decode fallback for an unrecognised or defaulted `items.kind`. */
    UNKNOWN(0),

    /** An imported or captured file: photo, video, audio, PDF, or arbitrary document. */
    FILE(1),

    /** An encrypted note whose body is stored as a vault object like any other (Phase 2). */
    NOTE(2),
    ;

    companion object {
        /**
         * The kind persisted as [code], or [UNKNOWN] if this build does not recognise it.
         *
         * Never throws: a row written by a newer version of the app must not crash an older one.
         * An unrecognised kind is a display concern, not a fatal one — the item still lists, and
         * its content still refuses to open under the format-version path.
         */
        fun fromCode(code: Int): VaultItemKind = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
