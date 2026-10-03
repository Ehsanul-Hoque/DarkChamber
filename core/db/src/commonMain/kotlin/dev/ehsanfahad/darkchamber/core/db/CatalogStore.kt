package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.ehsanfahad.darkchamber.core.model.ItemFilter
import dev.ehsanfahad.darkchamber.core.model.Outcome
import dev.ehsanfahad.darkchamber.core.model.VaultError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/**
 * Thin `Outcome`-returning facade over the generated [DarkChamberDatabase].
 *
 * Constructed from an **injected** [SqlDriver] — the app resolves the platform driver + DB path
 * in the app module; here the store is host- and iOS-sim-testable against in-memory drivers. Every op
 * returns [Outcome]; any driver/SQL failure is caught and mapped to [VaultError.Db] — no exception
 * crosses the boundary. Rows are the SQLDelight-generated types carrying **ids + opaque blobs
 * only** (the session-gate rule); the catalog never sees decrypted content.
 *
 * Field encryption is NOT here — `core:db` stores already-encrypted `enc_*` / `dek_wrapped` bytes as
 * opaque blobs (hard rule #2); the feature/data layer composes `core:crypto` above the store.
 */
class CatalogStore private constructor(
    private val driver: SqlDriver,
    private val db: DarkChamberDatabase,
    private val dispatcher: CoroutineDispatcher,
) {
    private val items get() = db.itemsQueries

    /** Insert or replace an item row (opaque `enc_meta` / `dek_wrapped` stored verbatim). */
    fun upsertItem(item: Items): Outcome<Unit> =
        catchingDb {
            items.upsertItem(
                id = item.id,
                album_id = item.album_id,
                kind = item.kind,
                size = item.size,
                created_at = item.created_at,
                enc_meta = item.enc_meta,
                dek_wrapped = item.dek_wrapped,
            )
        }

    /** The item with [id], or `null` if absent. */
    fun getItem(id: ByteArray): Outcome<Items?> =
        catchingDb { items.selectItemById(id).executeAsOneOrNull() }

    /** The items [filter] covers, ordered by `created_at`. */
    fun listItems(filter: ItemFilter = ItemFilter.All): Outcome<List<Items>> =
        catchingDb { itemQuery(filter).executeAsList() }

    /**
     * File every item in [ids] into [albumId], or unfile them all when it is `null`.
     *
     * **A destination, not a filter.** `null` here means "unfile these" and is unambiguous, because
     * a destination has only two kinds of answer; a filter needs a third and so takes [ItemFilter]
     * instead. The two read alike and mean different things.
     *
     * One statement for the whole set, inside one transaction: a bulk move either happens or does
     * not, and listeners are notified once rather than once per item — a move of fifty items that
     * emitted fifty times would have the gallery above it re-decrypt every row's metadata fifty
     * times over.
     *
     * An empty [ids] short-circuits to `Ok` without running anything. Not for correctness — SQLite
     * accepts the generated `IN ()` and matches nothing — but because executing it would still
     * notify every listing that `items` changed, making each one re-read and re-decrypt itself to
     * arrive at what it already had.
     *
     * **A non-existent [albumId] is an error.** The schema declares no foreign key, so SQLite would
     * accept it and leave items filed into an album that does not exist — absent from every album
     * listing and absent from the unfiled one too, since their `album_id` is not NULL. That is an
     * item the album surfaces cannot reach, so the store enforces what the schema does not. Unknown
     * *item* ids are not an error by contrast: an item shredded between selection and assignment is
     * an ordinary race, and its absence is already the outcome the caller wanted.
     */
    fun updateItemAlbum(ids: List<ByteArray>, albumId: ByteArray?): Outcome<Unit> =
        if (ids.isEmpty()) {
            Outcome.Ok(Unit)
        } else {
            catchingDb {
                db.transaction {
                    // Inside the transaction for two reasons: the album cannot be deleted between
                    // this check and the write, and the catalog defers its listener notification to
                    // the commit — so however many statements this grows into, the listings are told
                    // once.
                    if (albumId != null && albums.selectAlbumById(albumId).executeAsOneOrNull() == null) {
                        error("assignment destination album does not exist")
                    }
                    items.updateItemAlbum(album_id = albumId, id = ids)
                }
            }
        }

    /**
     * Unfile every item currently in [albumId], leaving the album row itself alone.
     *
     * This is what deleting an album does to its contents: they become unfiled, and **nothing is
     * destroyed**. Deleting an item's row destroys the wrapped DEK living in it, which is the only
     * way of reading that file — so an album delete must go through here and never through
     * [deleteItem].
     *
     * A no-op is `Ok`: an album with nothing in it is the ordinary case for a delete, and so is an
     * album id that no longer exists. Takes a non-null id deliberately — the generated query accepts
     * one and quietly rewrites `=` to `IS`, which would make `null` mean "unfile the already
     * unfiled" rather than the error it is.
     */
    fun clearAlbumOnItems(albumId: ByteArray): Outcome<Unit> =
        catchingDb { items.clearAlbumOnItems(albumId) }

    /** Remove the item with [id]. Idempotent — an absent id is `Ok`, never a throw. */
    fun deleteItem(id: ByteArray): Outcome<Unit> =
        catchingDb { items.deleteItemById(id) }

    /** Number of item rows. */
    fun countItems(): Outcome<Long> =
        catchingDb { items.countItems().executeAsOne() }

    /**
     * A reactive list of the items [filter] covers, ordered by `created_at`, re-emitting on every
     * catalog write. Rows carry ids + opaque blobs only — never decrypted content (the session-gate
     * rule). Unlike the one-shot ops this returns a raw [Flow]; the collector maps failures, since a
     * hot stream has no single [Outcome] to return.
     */
    fun observeItems(filter: ItemFilter = ItemFilter.All): Flow<List<Items>> =
        itemQuery(filter)
            .asFlow()
            .mapToList(dispatcher)

    /**
     * The one place a filter becomes a query, shared by the listing and the stream so the two can
     * never disagree about what a filter means. Exhaustive on purpose: a fourth case has to pick a
     * query here rather than silently inherit one.
     */
    private fun itemQuery(filter: ItemFilter): Query<Items> =
        when (filter) {
            ItemFilter.All -> items.selectAllItems()
            ItemFilter.Unfiled -> items.selectUnfiledItems()
            is ItemFilter.Album -> items.selectItemsByAlbum(filter.id)
        }

    private val albums get() = db.albumsQueries

    /** Insert or replace an album row (opaque `enc_name` stored verbatim; `kek_variant` is a flag). */
    fun upsertAlbum(album: Albums): Outcome<Unit> =
        catchingDb {
            albums.upsertAlbum(
                id = album.id,
                kek_variant = album.kek_variant,
                enc_name = album.enc_name,
                created_at = album.created_at,
            )
        }

    /** The album with [id], or `null` if absent. */
    fun getAlbum(id: ByteArray): Outcome<Albums?> =
        catchingDb { albums.selectAlbumById(id).executeAsOneOrNull() }

    /** All albums, ordered by `created_at`. */
    fun listAlbums(): Outcome<List<Albums>> =
        catchingDb { albums.selectAllAlbums().executeAsList() }

    /**
     * Live album listing: every album with its item count and the id of the item that represents it.
     *
     * **Re-emits when `items` changes, not only `albums`.** Importing, shredding or moving an item
     * changes a count or a cover while every album row stays byte-identical, so a listener on
     * `albums` alone would leave the grid confidently wrong. The query reaches into `items` for both
     * derived columns, and SQLDelight registers a query's listener against every table its SQL
     * names — so a write to either notifies this. That is load-bearing rather than incidental: a
     * narrowing of this query that stopped mentioning `items` would silently stop the updates, which
     * is why it is pinned by a test.
     *
     * Counts and covers come from the plaintext index columns only; nothing here decrypts. Returns a
     * raw [Flow] for the same reason [observeItems] does — a hot stream has no single [Outcome] to
     * return, so the collector maps failures.
     */
    fun observeAlbums(): Flow<List<SelectAlbumsWithCounts>> =
        albums.selectAlbumsWithCounts()
            .asFlow()
            .mapToList(dispatcher)

    /** Remove the album with [id]. Idempotent — an absent id is `Ok`, never a throw. */
    fun deleteAlbum(id: ByteArray): Outcome<Unit> =
        catchingDb { albums.deleteAlbumById(id) }

    private val audit get() = db.audit_logQueries

    /** Append an audit entry (opaque `enc_payload` stored verbatim). */
    fun appendAudit(entry: Audit_log): Outcome<Unit> =
        catchingDb {
            audit.appendAudit(
                id = entry.id,
                timestamp = entry.timestamp,
                enc_payload = entry.enc_payload,
            )
        }

    /** The most-recent [limit] audit entries, ordered by `timestamp` descending. */
    fun listAudit(limit: Long): Outcome<List<Audit_log>> =
        catchingDb { audit.selectRecentAudit(limit).executeAsList() }

    /**
     * Close the underlying [SqlDriver], releasing its DB connection/handle. Called by the session
     * teardown on lock (the session scope owns the catalog's lifetime). Idempotent-ish: a
     * driver failure maps to [VaultError.Db]; after close the store must not be used again.
     */
    fun close(): Outcome<Unit> =
        catchingDb { driver.close() }

    companion object {
        /**
         * Open the catalog on [driver], creating or migrating schema v1 as needed.
         *
         * Fails **closed**: a create/migration failure is [VaultError.Db], and the caller must
         * not proceed on a half-open catalog. A catalog written by a **newer** app (its `user_version`
         * exceeds [DarkChamberDatabase.Schema]'s) also fails closed — the catalog analog of
         * `UnsupportedFormatVersion`, so a downgrade never silently reads a future schema. Idempotent —
         * opening an already-current driver is a no-op. The injected driver owns nothing crypto.
         */
        fun create(
            driver: SqlDriver,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): Outcome<CatalogStore> =
            try {
                val schema = DarkChamberDatabase.Schema
                when (val current = driver.userVersion()) {
                    0L -> {
                        schema.create(driver).value
                        driver.setUserVersion(schema.version)
                    }
                    schema.version -> {
                        // Already open at this schema — nothing to do.
                    }
                    in 1 until schema.version -> {
                        schema.migrate(driver, current, schema.version).value
                        driver.setUserVersion(schema.version)
                    }
                    else -> error("catalog user_version=$current newer than supported ${schema.version}")
                }
                Outcome.Ok(CatalogStore(driver, darkChamberDatabase(driver), dispatcher))
            } catch (e: Exception) {
                Outcome.Err(VaultError.Db(e))
            }
    }
}

/** Run a catalog op, mapping any driver/SQL failure to [VaultError.Db] (no throw crosses out). */
private inline fun <T> catchingDb(block: () -> T): Outcome<T> =
    try {
        Outcome.Ok(block())
    } catch (e: Exception) {
        Outcome.Err(VaultError.Db(e))
    }

/** Current `PRAGMA user_version` (0 on a fresh driver). */
private fun SqlDriver.userVersion(): Long =
    executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        parameters = 0,
    ).value ?: 0L

/** Set `PRAGMA user_version` — the injected raw driver doesn't track it the way the platform wrappers do. */
private fun SqlDriver.setUserVersion(version: Long) {
    execute(identifier = null, sql = "PRAGMA user_version = $version", parameters = 0)
}
