package io.bluetape4k.workshop.exposed.spring.transaction

import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
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

    object T1: IntIdTable() {
        val c1 = varchar("c1", Int.MIN_VALUE.toString().length)
        val c2 = varchar("c2", Int.MIN_VALUE.toString().length).nullable()
    }

    class DAO(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<DAO>(T1)

        var c1 by T1.c1
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
        val entity = DAO.findById(1) ?: fail("Entity not found")
        entity.c1 shouldBeEqualTo "updated"
    }
}
