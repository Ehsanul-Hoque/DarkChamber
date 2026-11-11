package dev.ehsanfahad.darkchamber.core.db

/**
 * What every connection to the catalog runs as it opens — applied by each platform's
 * [DriverFactory] at the point its driver opens a connection, so a reopened or second connection
 * gets it too. **It has to be per connection, not once per driver:** a driver may open connections
 * the code never sees (iOS's native driver keeps a reader beside the writer, and SQLDelight's JDBC
 * driver opens and closes one per statement), and a pragma set once lands on whichever happened to
 * be open.
 *
 * **`secure_delete`.** Deleting an item row is what crypto-shreds it: the row holds the item's
 * wrapped key. Without this, SQLite only marks the row's space free, and the key's bytes stay in the
 * database file until something happens to reuse that space. With it, SQLite overwrites deleted
 * content with zeros as it frees it. Its default depends on how the platform's SQLite was compiled,
 * so it is never assumed.
 */
internal object CatalogPragmas {
    class Pragma(val name: String, val value: String) {
        val sql: String get() = "PRAGMA $name = $value"
    }

    val onEveryConnection: List<Pragma> = listOf(Pragma("secure_delete", "ON"))
}
