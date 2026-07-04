package io.bluetape4k.workshop.observability.advanced.repository

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.model.Users
import io.bluetape4k.workshop.observability.advanced.model.toUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

/**
 * Repository for [User] persistence using Exposed JDBC.
 *
 * ## Behavior / Contract
 * - All methods wrap Exposed calls in `withContext(Dispatchers.IO) { transaction { ... } }`.
 * - Spring `@Transactional` is intentionally avoided: it binds the connection to a thread-local,
 *   which breaks when `withContext(Dispatchers.IO)` switches the coroutine to a different thread.
 * - No Observation spans are created here; instrumentation is the service layer's responsibility.
 * - Uses top-level `eq` from `org.jetbrains.exposed.v1.core`.
 */
@Repository
class UserRepository {

    companion object : KLogging()

    suspend fun findById(id: Long): User? = withContext(Dispatchers.IO) {
        val validId = id.requirePositiveNumber("id")
        transaction {
            Users.selectAll()
                .where { Users.id eq validId }
                .singleOrNull()
                ?.toUser()
        }
    }

    suspend fun save(user: User): Unit = withContext(Dispatchers.IO) {
        transaction {
            Users.insert {
                it[id] = user.id
                it[name] = user.name
                it[email] = user.email
            }
        }
    }

    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        transaction {
            Users.deleteAll()
        }
    }
}
