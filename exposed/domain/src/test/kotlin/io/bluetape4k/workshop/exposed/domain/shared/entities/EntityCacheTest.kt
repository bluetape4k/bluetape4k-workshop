package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.random.Random

class EntityCacheTest: AbstractExposedTest() {

    companion object: KLogging()

    object TestTable: IntIdTable("TestCache") {
        val value = integer("value")
    }

    class TestEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<TestEntity>(TestTable)

        var value by TestTable.value
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `global entity cache limit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        val entitiesCount = 25
        val cacheSize = 10
        val db = TestDB.H2.connect {
            maxEntitiesToStoreInCachePerEntity = cacheSize
        }

        transaction(db) {
            try {
                SchemaUtils.create(TestTable)

                repeat(entitiesCount) {
                    TestEntity.new {
                        value = Random.nextInt()
                    }
                }

                val allEntities = TestEntity.all().toList()
                allEntities shouldHaveSize entitiesCount

                // 캐시로부터 특정 엔티티 조회하기 
                val allCachedEntities = entityCache.findAll(TestEntity)
                allCachedEntities shouldHaveSize cacheSize
                allCachedEntities shouldContainSame allEntities.drop(entitiesCount - cacheSize)
            } finally {
                SchemaUtils.drop(TestTable)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `global entity cache limit zero`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        val entitiesCount = 25
        val db = TestDB.H2.connect()
        val dbNoCache = TestDB.H2.connect {
            maxEntitiesToStoreInCachePerEntity = 10
        }

        val entityIds = transaction(db) {
            SchemaUtils.create(TestTable)

            repeat(entitiesCount) {
                TestEntity.new {
                    value = Random.nextInt()
                }
            }
            val entityIds = TestTable.selectAll().map { it[TestTable.id] }
            val initialStatementCount = statementCount
            entityIds.forEach {
                TestEntity[it]
            }
            // All read from cache
            statementCount shouldBeEqualTo initialStatementCount
            log.debug { "Statement count (before): $statementCount" }

            entityCache.clear()
            // Load all into cache
            TestEntity.all().toList()

            entityIds.forEach {
                TestEntity[it]
            }
            log.debug { "Statement count (after): $statementCount" }
            statementCount shouldBeEqualTo initialStatementCount + 1
            entityIds
        }

        entityIds shouldHaveSize entitiesCount

        transaction(dbNoCache) {
            entityCache.clear()
            debug = true
            TestEntity.all().toList()
            statementCount shouldBeEqualTo 1

            val initialStatementCount = statementCount
            log.debug { "Statement count (before): $statementCount" }
            entityIds.forEach {
                TestEntity[it]
            }
            log.debug { "Statement count (after): $statementCount" }
            statementCount shouldBeEqualTo initialStatementCount + entitiesCount

            SchemaUtils.drop(TestTable)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `per transaction entity cache limit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        val entitiesCount = 25
        val cacheSize = 10

        withTables(testDB, TestTable) {
            // 트랜잭션별 캐시 제한 설정 (기본값: Int.MAX_VALUE)
            entityCache.maxEntitiesToStore = cacheSize

            repeat(entitiesCount) {
                TestEntity.new {
                    value = Random.nextInt()
                }
            }

            val allEntities = TestEntity.all().toList()
            allEntities shouldHaveSize entitiesCount

            val allCachedEntities = entityCache.findAll(TestEntity)
            allCachedEntities shouldHaveSize cacheSize
            allCachedEntities shouldContainSame allEntities.drop(entitiesCount - cacheSize)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `change entity cache maxEntitiesToStore in middle of transaction`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TestTable) {
            repeat(20) {
                TestEntity.new {
                    value = Random.nextInt()
                }
            }
            entityCache.clear()

            TestEntity.all().limit(15).toList()
            entityCache.findAll(TestEntity) shouldHaveSize 15

            entityCache.maxEntitiesToStore = 18
            TestEntity.all().toList()
            entityCache.findAll(TestEntity) shouldHaveSize 18

            // Resize current cache
            entityCache.maxEntitiesToStore = 10
            entityCache.findAll(TestEntity) shouldHaveSize 10

            entityCache.maxEntitiesToStore = 18
            TestEntity.all().toList()
            entityCache.findAll(TestEntity) shouldHaveSize 18

            // Disable cache
            entityCache.maxEntitiesToStore = 0
            entityCache.findAll(TestEntity) shouldHaveSize 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `EntityCache should not be cleaned on explicit commit`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_H2 }

        withTables(testDB, TestTable) {
            val entity = TestEntity.new {
                value = Random.nextInt()
            }
            TestEntity.testCache(entity.id) shouldBeEqualTo entity

            // 명시적 commint 후에도 캐시는 유지되어야 함
            commit()
            TestEntity.testCache(entity.id) shouldBeEqualTo entity

            entityCache.clear()
            TestEntity.testCache(entity.id).shouldBeNull()
        }
    }
}
