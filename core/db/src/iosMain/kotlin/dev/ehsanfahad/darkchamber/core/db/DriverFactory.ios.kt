package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * iOS production seam. The app (cycle 5) supplies the resolved catalog path (under the
 * backup-excluded Documents vault root).
 */
actual class DriverFactory(
    private val databasePath: String,
) {
    actual fun create(): SqlDriver =
        NativeSqliteDriver(DarkChamberDatabase.Schema, databasePath)
}
