package dev.ehsanfahad.darkchamber.app

/** Wall-clock time in epoch milliseconds — the `created_at` source for imported vault objects. */
internal expect fun nowEpochMillis(): Long
