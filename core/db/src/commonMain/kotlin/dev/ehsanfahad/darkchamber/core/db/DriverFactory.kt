package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.SqlDriver

/**
 * The production [SqlDriver] seam — the one place the platform SQLite driver is built.
 *
 * Creation is **the app module's job**: the app supplies the resolved `<root>/db/catalog.db`
 * path (and on Android a `Context`) to the platform actual's constructor, having created the `db/`
 * directory via `core:storage`. Unit tests never use this — they inject an in-memory driver into
 * `CatalogStore.create` directly. The module only compile-gates the `expect`/`actual` pair.
 *
 * `create()` returns a driver whose schema is already applied by the platform wrapper;
 * `CatalogStore.create` then opens over it (its version-aware create is a no-op in that case).
 */
expect class DriverFactory {
    fun create(): SqlDriver
}
