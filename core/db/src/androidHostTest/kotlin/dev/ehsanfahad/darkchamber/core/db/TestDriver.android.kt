package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// Raw in-memory JDBC driver: user_version 0, no tables. CatalogStore.create builds the schema.
actual fun testSqlDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
