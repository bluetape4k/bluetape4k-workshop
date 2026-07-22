package io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.dao.Entity

abstract class EventSourcingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
        ExposedEntityInformationImpl(domainClass),
    )

abstract class AppendOnlyEventSourcingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : EventSourcingExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T = throw UnsupportedOperationException("append-only repository")
}
