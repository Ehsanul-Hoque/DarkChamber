package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver

actual fun testSqlDriver(): SqlDriver = inMemoryDriver(DarkChamberDatabase.Schema)
