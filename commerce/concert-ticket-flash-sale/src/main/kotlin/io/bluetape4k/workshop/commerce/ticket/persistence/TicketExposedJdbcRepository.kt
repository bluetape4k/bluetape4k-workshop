package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.dao.Entity

/**
 * Concrete workshop adapter for Bluetape4k's Spring Data [ExposedJdbcRepository].
 *
 * Tests construct repositories directly, while production can expose the same
 * repository contract through Spring. Every operation still runs inside
 * [TicketJdbcExecutor]'s bounded Exposed transaction.
 */
abstract class TicketExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
