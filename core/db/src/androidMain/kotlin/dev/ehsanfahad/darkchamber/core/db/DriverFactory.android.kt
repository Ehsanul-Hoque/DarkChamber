package dev.ehsanfahad.darkchamber.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Android production seam. The app supplies the `Context` and the resolved catalog
 * filename/path (under the backup-excluded vault root, via `core:storage`).
 *
 * [CatalogPragmas] run in `onConfigure`, which the framework calls as it configures the connection
 * it opens — before the schema is created or upgraded, and again whenever it reopens one. Each is
 * run through `query`, because a PRAGMA that answers with a row is refused by `execSQL`.
 */
actual class DriverFactory(
    private val context: Context,
    private val databaseName: String,
) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = DarkChamberDatabase.Schema,
            context = context,
            name = databaseName,
            callback = object : AndroidSqliteDriver.Callback(DarkChamberDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    CatalogPragmas.onEveryConnection.forEach { pragma -> db.query(pragma.sql).close() }
                }
            },
        )
}
