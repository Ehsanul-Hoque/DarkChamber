package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver

/**
 * An in-memory [SqlDriver] with schema v1 already created — the injected-driver seam (D2) under
 * test. Host uses `JdbcSqliteDriver(IN_MEMORY)`; iOS-sim uses the native in-memory driver. No
 * Context, no file, no fakes: SQLDelight runs for real on both platforms.
 */
expect fun testSqlDriver(): SqlDriver
