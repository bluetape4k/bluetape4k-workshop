package io.bluetape4k.workshop.commerce.voucherpool.persistence

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

internal fun DataSource.lockWaiters(backendPids: Set<Int>): Int = connection.use { connection ->
    connection.prepareStatement(
        """SELECT count(*) FROM pg_stat_activity
            WHERE state='active' AND wait_event_type='Lock' AND pid=ANY(?)""",
    ).use { statement ->
        statement.setArray(1, connection.createArrayOf("integer", backendPids.toTypedArray()))
        statement.executeQuery().use { result -> result.next(); result.getInt(1) }
    }
}

internal fun DataSource.awaitLockWaiters(backendPids: Set<Int>, expected: Int): Int {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    var observed = lockWaiters(backendPids)
    while (observed < expected && System.nanoTime() < deadline) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        observed = lockWaiters(backendPids)
    }
    return observed
}

internal fun DataSource.sharedLockHolders(backendPids: Set<Int>): Int = connection.use { connection ->
    connection.prepareStatement(
        """SELECT count(DISTINCT pid) FROM pg_locks
            WHERE granted AND mode='RowShareLock' AND relation='voucher_pool_campaigns'::regclass
              AND pid=ANY(?)""",
    ).use { statement ->
        statement.setArray(1, connection.createArrayOf("integer", backendPids.toTypedArray()))
        statement.executeQuery().use { result -> result.next(); result.getInt(1) }
    }
}
