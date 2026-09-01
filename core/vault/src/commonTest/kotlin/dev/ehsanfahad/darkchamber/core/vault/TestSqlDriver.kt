package dev.ehsanfahad.darkchamber.core.vault

import app.cash.sqldelight.db.SqlDriver

/**
 * A raw, in-memory [SqlDriver] with **no schema created** (user_version 0) — `CatalogStore.create`
 * owns schema creation, so these tests exercise the real create path. Host uses
 * `JdbcSqliteDriver(IN_MEMORY)`; iOS-sim uses a native in-memory driver. (Mirrors core:db's own
 * test seam; that one lives in core:db's test source set and is not visible across modules.)
 */
expect fun testSqlDriver(): SqlDriver
