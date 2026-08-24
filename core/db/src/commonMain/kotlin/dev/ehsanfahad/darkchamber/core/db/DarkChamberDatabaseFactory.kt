package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.SqlDriver

/**
 * Builds the generated [DarkChamberDatabase] with its column adapters wired.
 *
 * Centralized so the schema's `INTEGER AS kotlin.Int` columns (`kind`, `kek_variant`) get their
 * `IntColumnAdapter` in exactly one place — `CatalogStore` and the tests both go through here.
 */
internal fun darkChamberDatabase(driver: SqlDriver): DarkChamberDatabase =
    DarkChamberDatabase(
        driver = driver,
        albumsAdapter = Albums.Adapter(kek_variantAdapter = IntColumnAdapter),
        itemsAdapter = Items.Adapter(kindAdapter = IntColumnAdapter),
    )
