package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The only JDBC transaction entry point for virtual-thread request and worker lanes.
 * Acquiring the lane permit before [transaction] keeps pool saturation from becoming an
 * unbounded Hikari wait queue.
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
