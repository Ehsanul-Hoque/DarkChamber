package dev.ehsanfahad.darkchamber.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A real driver, watched: it records the most values any one statement bound, and can be told to fail
 * a statement whose SQL contains [failWhere].
 *
 * The host's SQLite binds tens of thousands of values per statement, so a query that would overflow
 * an older SQLite's limit of 999 runs fine here; [mostParameters] is how a test sees it anyway.
 */
internal class WatchingSqlDriver(
    private val delegate: SqlDriver,
    var failWhere: String? = null,
) : SqlDriver by delegate {
    var mostParameters = 0
        private set

    private fun watch(sql: String, parameters: Int) {
        mostParameters = maxOf(mostParameters, parameters)
        val fail = failWhere
        if (fail != null && sql.contains(fail)) throw IllegalStateException("refused: $fail")
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        watch(sql, parameters)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        watch(sql, parameters)
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
