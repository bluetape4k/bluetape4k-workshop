package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.ExposedEntityInformationImpl
import io.bluetape4k.spring.data.exposed.jdbc.repository.support.SimpleExposedJdbcRepository
import org.jetbrains.exposed.v1.dao.Entity

/** Mandatory Bluetape Spring Data repository delegate for this example. */
abstract class MeteringExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
        ExposedEntityInformationImpl(domainClass),
    )

/**
 * Blocks generic Spring Data mutation paths for financial and source records.
 * Concrete repositories expose narrowly named append/query operations instead.
 */
abstract class AppendOnlyMeteringExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : MeteringExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()

    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()

    final override fun deleteById(id: ID): Unit = immutableMutation()

    final override fun delete(entity: E): Unit = immutableMutation()

    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()

    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()

    final override fun deleteAll(): Unit = immutableMutation()

    protected fun <T> immutableMutation(): T =
        throw UnsupportedOperationException("append-only repository")
}
