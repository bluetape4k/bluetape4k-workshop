package io.bluetape4k.workshop.virtualthread.tomcat.domain

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceContext

abstract class AbstractDomainTest: AbstractVirtualThreadMvcTest() {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker
    }

    @PersistenceContext
    protected lateinit var em: EntityManager

    // protected val em: EntityManager get() = tem.entityManager
    protected val emf: EntityManagerFactory get() = em.entityManagerFactory

    protected fun clear() {
        em.clear()
    }

    protected fun flush() {
        em.flush()
    }

    protected fun flushAndClear() {
        em.flush()
        em.clear()
    }
}
