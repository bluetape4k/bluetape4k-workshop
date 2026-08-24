package io.bluetape4k.workshop.optimization.shiftcoverage.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** mutation transaction에서 PostgreSQL lock/statement timeout을 고정하는 좁은 seam입니다. */
object ShiftCoverageTransactionSupport {
    fun <T> inMutation(block: () -> T): T = transaction {
        exec("SET LOCAL lock_timeout = '2s'")
        exec("SET LOCAL statement_timeout = '5s'")
        block()
    }
}
