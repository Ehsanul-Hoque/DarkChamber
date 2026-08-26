package dev.ehsanfahad.darkchamber.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

actual fun testSqlDriver(): SqlDriver =
    NativeSqliteDriver(
        DatabaseConfiguration(
            name = null,
            version = 0,
            inMemory = true,
            create = { },
        ),
    )
