package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * virtual-thread request와 worker lane을 위한 유일한 JDBC transaction entry point입니다.
 * [transaction] 전에 lane permit을 획득해 pool saturation이 unbounded Hikari wait queue로 번지지 않게 합니다.
 */
internal class EventSourcedPermitTransactionRunner(
    private val database: Database,
    private val permits: EventSourcedDatabasePermitGate,
    private val lane: EventSourcedDatabaseLane,
) {
    fun <T> inTransaction(block: () -> T): T =
        permits.withPermit(lane) {
            transaction(database) { block() }
        }
}
