package dev.ehsanfahad.darkchamber.feature.camera

import app.cash.sqldelight.db.SqlDriver

/**
 * A raw, in-memory [SqlDriver] with **no schema created** — `CatalogStore.create` builds it, as it
 * does in production. (core:vault's own test seam is not visible across modules.)
 */
expect fun testSqlDriver(): SqlDriver
