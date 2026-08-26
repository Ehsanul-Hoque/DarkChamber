package dev.ehsanfahad.darkchamber.app

import app.cash.sqldelight.db.SqlDriver

/**
 * A raw, in-memory [SqlDriver] with no schema (user_version 0) — `CatalogStore.create` owns
 * schema creation. Host uses `JdbcSqliteDriver(IN_MEMORY)`; iOS-sim a native in-memory driver.
 * Mirrors `core:db`'s test seam; used by the bootstrap tests' `CatalogProvider`.
 */
expect fun testSqlDriver(): SqlDriver
