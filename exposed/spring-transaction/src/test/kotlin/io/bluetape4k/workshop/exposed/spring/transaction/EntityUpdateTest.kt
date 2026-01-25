package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.fail
import org.springframework.test.annotation.Commit
import org.springframework.transaction.annotation.Transactional

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
open class EntityUpdateTest: SpringTransactionTestBase() {

    /**
     * ```sql
     * -- H2
     * CREATE TABLE IF NOT EXISTS T1 (
     *      ID SERIAL PRIMARY KEY,
     *      C1 VARCHAR(11) NOT NULL,
     *      C2 VARCHAR(11) NULL
     * )
     * ```
     */
    object T1: IntIdTable() {
        val c1 = varchar("c1", Int.MIN_VALUE.toString().length)
        val c2 = varchar("c2", Int.MIN_VALUE.toString().length).nullable()
    }

    class DAO(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<DAO>(T1)

        var c1 by T1.c1

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("c1", c1)
            .toString()
    }

    @BeforeAll
    fun beforeAll() {
        transactionManager.execute {
            SchemaUtils.create(T1)
        }
    }

    @AfterAll
    fun afterAll() {
        transactionManager.execute {
            SchemaUtils.drop(T1)
        }
    }

    @Test
    @Transactional
    @Commit
    @Order(0)
    open fun `insert new entity`() {
        T1.insert {
            it[c1] = "new"
        }
        DAO.findById(1)?.c1 shouldBeEqualTo "new"
    }

    /**
     * ```sql
     * UPDATE T1
     *    SET C1='updated',
     *        C2='new'
     *  WHERE T1.ID = 1
     * ```
     */
    @Test
    @Transactional
    @Commit
    @Order(1)
    open fun `update existing entity`() {
        val entity = DAO.findById(1) ?: fail("Entity not found")

        T1.update({ T1.id eq entity.id }) {
            it[c1] = "updated"
            it[c2] = "new"
        }

        DAO.findById(1)?.c1 shouldBeEqualTo "updated"
    }

    @Test
    @Transactional
    @Commit
    @Order(2)
    open fun `find updated entity`() {
        val entity = DAO.findById(1) ?: fail("Entity not found [1]")
        entity.c1 shouldBeEqualTo "updated"
    }
}
