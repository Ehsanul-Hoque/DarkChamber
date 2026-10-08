package dev.ehsanfahad.darkchamber.core.vault

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A real driver that refuses one kind of write, so a multi-write operation can be interrupted
 * *between* its writes.
 *
 * Everything else is delegated, so the earlier writes genuinely commit and the state left behind is
 * the state a crash at that moment would really leave. [failOn] is matched against the SQL text,
 * which is how a specific statement in a sequence gets picked out — counting calls would depend on
 * how many statements schema creation happens to run.
 */
internal class FailingSqlDriver(
    private val delegate: SqlDriver,
    private val failOn: (String) -> Boolean,
) : SqlDriver {

    var refusals = 0
        private set

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (failOn(sql)) {
            refusals++
            throw IllegalStateException("driver refused this write")
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = delegate.executeQuery(identifier, sql, mapper, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(queryKeys = queryKeys)

    override fun close() = delegate.close()
}

/** The album-row delete. */
internal val deletesAnAlbum: (String) -> Boolean = { it.contains("DELETE FROM albums") }

/** The write that unfiles an album's items. */
internal val unfilesItems: (String) -> Boolean = { it.contains("UPDATE items SET album_id = NULL") }
