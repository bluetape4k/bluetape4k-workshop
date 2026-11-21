@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.jdbc.sql.singleInt
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource

/**
 * lockWaiters은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun DataSource.lockWaiters(backendPids: Set<Int>): Int =
    connection.use { conn ->
/**
 * sql은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * awaitLockWaiters은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun DataSource.awaitLockWaiters(backendPids: Set<Int>, expected: Int): Int {
    val deadline = System.nanoTime() + 2.seconds().toNanos()
    var observed = lockWaiters(backendPids)

    while (observed < expected && System.nanoTime() < deadline) {
        LockSupport.parkNanos(10.millis().toNanos())
        observed = lockWaiters(backendPids)
    }
    return observed
}

/**
 * sharedLockHolders은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun DataSource.sharedLockHolders(
    backendPids: Set<Int>,
    relation: String = "voucher_pool_campaigns",
): Int = connection.use { conn ->
    require(relation in setOf("voucher_pool_campaigns", "voucher_pool_batches"))

/**
 * sql은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
