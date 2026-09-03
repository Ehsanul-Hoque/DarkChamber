package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver

/**
 * A raw, in-memory [SqlDriver] with **no schema created** (user_version 0) — the injected-driver
 * seam under test. Host uses `JdbcSqliteDriver(IN_MEMORY)`; iOS-sim uses a native in-memory
 * driver with a no-op create. `CatalogStore.create` owns schema creation, so tests exercise the
 * real create/migrate path. No Context, no file, no fakes.
 */
expect fun testSqlDriver(): SqlDriver
