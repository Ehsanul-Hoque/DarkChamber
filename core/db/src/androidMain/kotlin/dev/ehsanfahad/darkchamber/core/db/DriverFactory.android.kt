package dev.ehsanfahad.darkchamber.core.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android production seam. The app supplies the `Context` and the resolved catalog
 * filename/path (under the backup-excluded vault root, via `core:storage`).
 */
actual class DriverFactory(
    private val context: Context,
    private val databaseName: String,
) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(DarkChamberDatabase.Schema, context, databaseName)
}
