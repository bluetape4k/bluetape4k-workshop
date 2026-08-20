package io.bluetape4k.workshop.optimization.fieldservice.persistence

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** disposable schema transaction helper이며 production migration은 의도적으로 포함하지 않습니다. */
inline fun <T> fieldServiceTransaction(
    database: Database? = null,
    crossinline block: () -> T,
): T = if (database == null) transaction { block() } else transaction(database) { block() }
