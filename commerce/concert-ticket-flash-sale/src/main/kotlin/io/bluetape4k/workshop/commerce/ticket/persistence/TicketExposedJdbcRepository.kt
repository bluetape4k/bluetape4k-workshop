package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.dao.Entity

/**
 * Bluetape4k Spring Data [ExposedJdbcRepository]를 사용하는 workshop concrete adapter입니다.
 *
 * 테스트는 repository를 직접 생성하고, production은 같은 repository contract를 Spring을 통해 노출할 수 있습니다.
 * 모든 operation은 여전히 [TicketJdbcExecutor]의 bounded Exposed transaction 안에서 실행됩니다.
 */
abstract class TicketExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
