package dev.ehsanfahad.darkchamber.core.vault

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

// Raw in-memory native driver: user_version 0, no-op create, no tables. CatalogStore.create builds
// the schema (mirrors the host JdbcSqliteDriver(IN_MEMORY) raw state).
actual fun testSqlDriver(): SqlDriver =
    NativeSqliteDriver(
        DatabaseConfiguration(
            name = null,
            version = 0,
            inMemory = true,
            create = { },
        ),
    )
