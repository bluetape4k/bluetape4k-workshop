@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleInt
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

internal fun DataSource.lockWaiters(backendPids: Set<Int>): Int =
    connection.use { conn ->
        val sql = """
            SELECT count(*) FROM pg_stat_activity
            WHERE state='active' AND wait_event_type='Lock' AND pid=ANY(?)
            """.trimIndent()

        conn.prepareStatement(sql).use { statement ->
            statement.setArray(1, conn.createArrayOf("integer", backendPids.toTypedArray()))
            statement.executeQuery().use { result ->
                result.singleInt()
            }
        }
    }

internal fun DataSource.awaitLockWaiters(backendPids: Set<Int>, expected: Int): Int {
    val deadline = System.nanoTime() + 2.seconds().toNanos()
    var observed = lockWaiters(backendPids)

    while (observed < expected && System.nanoTime() < deadline) {
        LockSupport.parkNanos(10.millis().toNanos())
        observed = lockWaiters(backendPids)
    }
    return observed
}

internal fun DataSource.sharedLockHolders(
    backendPids: Set<Int>,
    relation: String = "voucher_pool_campaigns",
): Int = connection.use { conn ->
    require(relation in setOf("voucher_pool_campaigns", "voucher_pool_batches"))

    val sql = """
            SELECT count(DISTINCT pid) FROM pg_locks
            WHERE granted AND mode='RowShareLock' AND relation=?::regclass AND pid=ANY(?)
            """.trimIndent()

    conn.prepareStatement(sql).use { statement ->
        statement.setString(1, relation)
        statement.setArray(2, conn.createArrayOf("integer", backendPids.toTypedArray()))

        statement.executeQuery().use { result ->
            result.singleInt()
        }
    }
}
