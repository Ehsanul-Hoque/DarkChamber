package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration

/**
 * iOS production seam. The app supplies the resolved catalog path (under the
 * backup-excluded Documents vault root), e.g. `<Documents>/vault/db/catalog.db`.
 *
 * sqliter's driver takes a bare database *name* and a directory (`basePath`) separately — a `name`
 * containing a path separator is rejected. So the full path is split here into its directory and
 * filename, and the directory is passed through `basePath` (proven on the iOS simulator).
 *
 * [CatalogPragmas] run in sqliter's `onCreateConnection`, which fires for **every** connection it
 * opens — the writer and each reader — so none is ever left without them.
 */
actual class DriverFactory(
    private val databasePath: String,
) {
    actual fun create(): SqlDriver {
        val separator = databasePath.lastIndexOf('/')
        val directory = if (separator >= 0) databasePath.substring(0, separator) else null
        val name = if (separator >= 0) databasePath.substring(separator + 1) else databasePath
        return NativeSqliteDriver(
            schema = DarkChamberDatabase.Schema,
            name = name,
            onConfiguration = { config -> withCatalogPragmas(config, directory) },
        )
    }
}

/** Places the catalog in [directory] and runs [CatalogPragmas] on every connection sqliter opens. */
private fun withCatalogPragmas(config: DatabaseConfiguration, directory: String?): DatabaseConfiguration {
    val placed = if (directory == null) {
        config
    } else {
        config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
    }
    val previous = placed.lifecycleConfig.onCreateConnection
    return placed.copy(
        lifecycleConfig = placed.lifecycleConfig.copy(
            onCreateConnection = { connection ->
                previous(connection)
                CatalogPragmas.onEveryConnection.forEach { pragma -> connection.rawExecSql(pragma.sql) }
            },
        ),
    )
}
