package io.bluetape4k.workshop.exposed.domain.shared.entities

import MigrationUtils
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentTestDB
import io.bluetape4k.workshop.exposed.domain.expectException
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Author
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Authors
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Book
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Books
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Office
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Offices
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Publisher
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Publishers
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Review
import io.bluetape4k.workshop.exposed.domain.mapping.composite.Reviews
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.idParam
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.inTopLevelTransaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Connection
import java.util.*
import kotlin.test.assertIs

// SQLite excluded from most tests as it only allows auto-increment on single column PKs.
// SQL Server is sometimes excluded because it doesn't allow inserting explicit values for identity columns.
class CompositeIdTableEntityTest: AbstractExposedTest() {

    companion object: KLogging()

    private val allTables = arrayOf(Publishers, Authors, Books, Reviews, Offices)

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create and drop CompositeIdTable`(testDB: TestDB) {
        withDb(testDB) {
            try {
                SchemaUtils.create(tables = allTables)

                allTables.forEach { it.exists().shouldBeTrue() }

                if (testDB !in TestDB.ALL_H2) {
                    MigrationUtils.statementsRequiredForDatabaseMigration(tables = allTables).shouldBeEmpty()
                }
            } finally {
                SchemaUtils.drop(tables = allTables)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select using DAO`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            val p1 = Publisher.new {
                name = "Publisher A"
            }

            flushCache()

            val result1 = Publisher.all().single()
            result1.name shouldBeEqualTo "Publisher A"

            // can compare entire entity object
            result1 shouldBeEqualTo p1
            // or entire entity ids
            result1.id shouldBeEqualTo p1.id
            // or the value wrapped by entity id
            result1.id.value shouldBeEqualTo p1.id.value
            // or the composite id compoinents
            result1.id.value[Publishers.pubId] shouldBeEqualTo p1.id.value[Publishers.pubId]
            result1.id.value[Publishers.isbn] shouldBeEqualTo p1.id.value[Publishers.isbn]

            Publisher.new {
                name = "Publisher B"
            }
            Publisher.new {
                name = "Publisher C"
            }

            val result2 = Publisher.all().toList()
            result2 shouldHaveSize 3
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select using DSL`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            Publishers.insert {
                it[name] = "Publisher A"
            }

            flushCache()

            val result = Publishers.selectAll().single()
            result[Publishers.name] shouldBeEqualTo "Publisher A"

            // test all id column components are accessible from single ResultRow access
            val idResult = result[Publishers.id]
            assertIs<EntityID<CompositeID>>(idResult)
            val pubIdResult = idResult.value[Publishers.pubId]
            pubIdResult shouldBeEqualTo result[Publishers.pubId]
            idResult.value[Publishers.isbn] shouldBeEqualTo result[Publishers.isbn]

            // test that using composite id column in DSL query builder works
            val dslQuery = Publishers
                .select(Publishers.id)
                .where { Publishers.id eq idResult }
                .prepareSQL(this)

            log.debug { "DSL Query: $dslQuery" }

//            val params = listOf(
//                Publishers.pubId.columnType to pubIdResult,
//                Publishers.isbn.columnType to result[Publishers.isbn]
//            )
//            val results = exec(dslQuery, params) { it }
//            results?.close()

            val selectClause = dslQuery.substringAfter("SELECT ").substringBefore(" FROM")
            // id column should deconstruct to 2 columns from PK
            selectClause.split(", ", ignoreCase = true) shouldHaveSize 2
            val whereClause = dslQuery.substringAfter(" WHERE ")
            // 2 column in composite PK to check, joined by single AND operator
            whereClause.split(" AND ", ignoreCase = true) shouldHaveSize 2

            // test equality comparison fails if composite columns do not match
            expectException<IllegalStateException> {
                val fake = EntityID(CompositeID { it[Publishers.pubId] = 7 }, Publishers)
                Publishers.selectAll().where { Publishers.id eq fake }
            }

            // test equality comparison succeeds with partial match to composite column unwrapped value
            val pubIdValue: Int = pubIdResult.value
            Publishers.selectAll().where { Publishers.pubId neq pubIdValue }.count().toInt() shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with compositeId auto generated parts using DAO`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            // test missing autoGenerated UUID
            val p1 = Publisher.new(
                CompositeID {
                    it[Publishers.pubId] = 578
                }
            ) {
                name = "Publisher A"
            }
            flushCache()

            val found1 = Publisher.find { Publishers.pubId eq 578 }.single()
            found1 shouldBeEqualTo p1
            found1.name shouldBeEqualTo "Publisher A"

            // test missing autoIncrement ID
            val isbn = UUID.randomUUID()
            val p2 = Publisher.new(
                CompositeID {
                    it[Publishers.isbn] = isbn
                }
            ) {
                name = "Publisher B"
            }
            val found2 = Publisher.find { Publishers.isbn eq isbn }.single()
            found2.id shouldBeEqualTo p2.id

            val expectedNextVal1 =
                if (currentTestDB in TestDB.ALL_MYSQL_LIKE || currentTestDB == TestDB.H2_V1) 579 else 1
            found2.id.value[Publishers.pubId].value shouldBeEqualTo expectedNextVal1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with composite id auto generated parts and missing not generated part using DAO`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val publisherA = Publisher.new {
                name = "Publisher A"
            }
            val authorA = Author.new {
                publisher = publisherA
                penName = "Author A"
            }
            val boolA = Book.new {
                title = "Book A"
                author = authorA
            }
            flushCache()

            val compositeID = CompositeID {
                it[Reviews.rank] = 10L
            }
            // Reviews 는 rank, content 둘 다 autoIncrement 이 아니므로, content 를 지정하지 않으면 에러가 발생해야 한다.
            expectException<IllegalStateException> {
                Review.new(compositeID) {
                    book = boolA
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and get compositeIds`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            // insert individual components
            val id1: EntityID<CompositeID> = Publishers.insertAndGetId {
                it[pubId] = 725
                it[isbn] = UUID.randomUUID()
                it[name] = "Publisher A"
            }
            flushCache()

            id1.value[Publishers.pubId].value shouldBeEqualTo 725

            val id2 = Publishers.insertAndGetId {
                it[name] = "Publisher B"
            }
            // MYSQL 을 제외하면, pubId 는 autoIncrement 이므로 1 이어야 한다.
            val expectedNextVal1 =
                if (currentTestDB in TestDB.ALL_MYSQL_LIKE || currentTestDB == TestDB.H2_V1) 726 else 1
            id2.value[Publishers.pubId].value shouldBeEqualTo expectedNextVal1

            // insert as composite ID
            val id3 = Publishers.insertAndGetId {
                it[id] = CompositeID { id ->
                    id[pubId] = 999
                    id[isbn] = UUID.randomUUID()
                }
                it[name] = "Publisher C"
            }
            id3.value[Publishers.pubId].value shouldBeEqualTo 999

            // insert as EntityID<CompositeID>
            val id4 = Publishers.insertAndGetId {
                it[id] = EntityID(
                    CompositeID { id ->
                        id[pubId] = 111
                        id[isbn] = UUID.randomUUID()
                    }, Publishers
                )
                it[name] = "Publisher D"
            }
            id4.value[Publishers.pubId].value shouldBeEqualTo 111

            // insert as partially filled composite ID with generated UUID part
            val id5 = Publishers.insertAndGetId {
                it[id] = CompositeID { id ->
                    id[pubId] = 1001
                }
                it[name] = "Publisher E"
            }
            id5.value[Publishers.pubId].value shouldBeEqualTo 1001

            // insert as partially filled composite ID with autoincrement part
            val id6 = Publishers.insertAndGetId {
                it[id] = CompositeID { id ->
                    id[isbn] = UUID.randomUUID()
                }
                it[name] = "Publisher F"
            }
            val expectedNextVal2 =
                if (currentTestDB in TestDB.ALL_MYSQL_LIKE || currentTestDB == TestDB.H2_V1) 1002 else 2
            id6.value[Publishers.pubId].value shouldBeEqualTo expectedNextVal2
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert using manual composite ids`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            // manual using DSL
            Publishers.insert {
                it[pubId] = 725
                it[isbn] = UUID.randomUUID()
                it[name] = "Publisher A"
            }
            flushCache()

            Publishers.selectAll().single()[Publishers.pubId].value shouldBeEqualTo 725

            // manual using DAO - all PK columns
            val fullId = CompositeID {
                it[Publishers.pubId] = 611
                it[Publishers.isbn] = UUID.randomUUID()
            }
            val p2Id = Publisher.new(fullId) {
                name = "Publisher B"
            }.id

            p2Id.value[Publishers.pubId].value shouldBeEqualTo 611
            Publisher.findById(p2Id)?.id?.value?.get(Publishers.pubId)?.value shouldBeEqualTo 611
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find by composite id`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            val id1: EntityID<CompositeID> = Publishers.insertAndGetId {
                it[pubId] = 725
                it[isbn] = UUID.randomUUID()
                it[name] = "Publisher A"
            }
            flushCache()

            val p1: Publisher? = Publisher.findById(id1)
            p1.shouldNotBeNull()
            p1.id.value[Publishers.pubId].value shouldBeEqualTo 725

            val id2: EntityID<CompositeID> = Publisher.new {
                name = "Publisher B"
            }.id

            val p2: Publisher? = Publisher.findById(id2)
            p2.shouldNotBeNull()
            p2.name shouldBeEqualTo "Publisher B"
            p2.id.value[Publishers.pubId] shouldBeEqualTo id2.value[Publishers.pubId]

            // test findById() using CompositeID value
            val compositeId1: CompositeID = id1.value
            val p3: Publisher? = Publisher.findById(compositeId1)
            p3.shouldNotBeNull()
            p3 shouldBeEqualTo p1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `find with DSL Builder`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            val p1 = Publisher.new {
                name = "Publisher A"
            }
            flushCache()

            Publisher.find { Publishers.name like "% A" }.single().id shouldBeEqualTo p1.id

            val p2 = Publisher.find { Publishers.id eq p1.id }.single()
            p2 shouldBeEqualTo p1

            // test select using partial match to composite column unwrapped value
            val existingIsbnValue: UUID = p1.id.value[Publishers.isbn].value
            val p3 = Publisher.find { Publishers.isbn eq existingIsbnValue }.single()
            p3 shouldBeEqualTo p1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `update composite entity`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            val p1 = Publisher.new {
                name = "Publisher A"
            }
            flushCache()

            Publisher.all().count() shouldBeEqualTo 1L
            Publisher.all().single().name shouldBeEqualTo "Publisher A"

            p1.name = "Publisher B"
            Publisher.all().single().name shouldBeEqualTo "Publisher B"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete composite entity`(testDB: TestDB) {
        withTables(testDB, Publishers) {
            val p1 = Publisher.new { name = "Publisher A" }
            val p2 = Publisher.new { name = "Publisher B" }

            Publisher.all().count().toInt() shouldBeEqualTo 2

            flushCache()

            /**
             *```sql
             * DELETE FROM PUBLISHERS
             *  WHERE (PUBLISHERS.ISBN_CODE = '85d5d225-0c36-41dc-a992-cb57de316dc2')
             *    AND (PUBLISHERS.PUB_ID = 1)
             * ```
             */
            p1.delete()

            val result = Publisher.all().single()
            result.name shouldBeEqualTo "Publisher B"
            result.id shouldBeEqualTo p2.id
            result shouldBeEqualTo p2

            // test delete using partial match to composite column unwrapped value
            val existingPubIdValue: Int = p2.id.value[Publishers.pubId].value
            Publishers.deleteWhere { Publishers.pubId eq existingPubIdValue }
            Publisher.all().count().toInt() shouldBeEqualTo 0
        }
    }

    object Towns: CompositeIdTable("towns") {
        val zipCode = varchar("zip_code", 8).entityId()
        val name = varchar("name", 64).entityId()
        val population = long("population").nullable()

        override val primaryKey = PrimaryKey(zipCode, name)
    }

    class Town(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<Town>(Towns)

        var population by Towns.population
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `isNull and eq with alias`(testDB: TestDB) {
        withTables(testDB, Towns) {
            val townAValue = CompositeID {
                it[Towns.zipCode] = "1A2 3B4"
                it[Towns.name] = "Town A"
            }
            val townAId = Towns.insertAndGetId { it[id] = townAValue }

            flushCache()

            val smallCity = Towns.alias("small_city")

            /**
             * ```sql
             * SELECT small_city.zip_code, small_city."name", small_city.population
             *   FROM towns small_city
             *  WHERE (small_city.population IS NULL)
             *    AND (small_city.zip_code = '1A2 3B4') AND (small_city."name" = 'Town A')
             * ```
             */
            val result1 = smallCity.selectAll()
                .where {
                    smallCity[Towns.population].isNull() and (smallCity[Towns.id] eq townAId)
                }
                .single()

            result1[smallCity[Towns.population]].shouldBeNull()

            /**
             * ```sql
             * SELECT small_city."name"
             *   FROM towns small_city
             *  WHERE (small_city.zip_code = '1A2 3B4') AND (small_city."name" = 'Town A')
             * ```
             */
            val result2 = smallCity
                .select(smallCity[Towns.name])
                .where { smallCity[Towns.id] eq townAId.value }
                .single()

            result2[smallCity[Towns.name]] shouldBeEqualTo townAValue[Towns.name]
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `id param with composite value`(testDB: TestDB) {
        withTables(testDB, Towns) {
            val townAValue = CompositeID {
                it[Towns.zipCode] = "1A2 3B4"
                it[Towns.name] = "Town A"
            }
            val townAId = Towns.insertAndGetId {
                it[id] = townAValue
                it[population] = 4
            }

            flushCache()

            /**
             * ```sql
             * SELECT TOWNS.ZIP_CODE, TOWNS."name", TOWNS.POPULATION
             *   FROM TOWNS
             *  WHERE (TOWNS.ZIP_CODE = ?) AND (TOWNS."name" = ?)
             * ```
             */
            val query: Query = Towns.selectAll()
                .where { Towns.id eq idParam(townAId, Towns.id) }

            val selectClause = query.prepareSQL(this, prepared = true)
            log.debug { "Select Clause: $selectClause" }

            val whereClause = selectClause.substringAfter("WHERE ")
            whereClause shouldBeEqualTo "(${fullIdentity(Towns.zipCode)} = ?) AND (${fullIdentity(Towns.name)} = ?)"
            query.single()[Towns.population] shouldBeEqualTo 4
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `flushing updated entity`(testDB: TestDB) {
        withTables(testDB, Towns) {
            val id = CompositeID {
                it[Towns.zipCode] = "1A2 3B4"
                it[Towns.name] = "Town A"
            }

            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                Town.new(id) {
                    population = 1000
                }
            }
            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                val town = Town[id]
                town.population = 2000
            }
            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                val town = Town[id]
                town.population shouldBeEqualTo 2000
            }
        }
    }

    /**
     * ```sql
     * SELECT reviews.code, reviews.`rank`, reviews.book_id
     *   FROM reviews
     *  WHERE reviews.book_id = 1
     * ```
     *
     * ```sql
     * SELECT authors.id, authors.publisher_id, authors.publisher_isbn, authors.pen_name
     *   FROM authors
     *  WHERE (authors.publisher_id = 1) AND (authors.publisher_isbn = '80776f58-24b0-4892-bec6-c2de2ca8fa4b')
     *```
     * ```sql
     * SELECT offices.zip_code,
     *        offices.`name`,
     *        offices.area_code,
     *        offices.staff,
     *        offices.publisher_id,
     *        offices.publisher_isbn
     *   FROM offices
     *  WHERE (offices.publisher_id = 1) AND (offices.publisher_isbn = '80776f58-24b0-4892-bec6-c2de2ca8fa4b')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert and select referenced entities`(testDB: TestDB) {
        withTables(testDB, tables = allTables) {
            val publisherA = Publisher.new {
                name = "Publisher A"
            }
            val authorA = Author.new {
                publisher = publisherA
                penName = "Author A"
            }
            val authorB = Author.new {
                publisher = publisherA
                penName = "Author B"
            }
            val bookA = Book.new {
                title = "Book A"
                author = authorB
            }
            val bookB = Book.new {
                title = "Book B"
                author = authorB
            }
            val reviewIdValue = CompositeID {
                it[Reviews.content] = "Not bad"
                it[Reviews.rank] = 12345L
            }
            val reviewA = Review.new(reviewIdValue) {
                book = bookA
            }
            val officeAIdValue = CompositeID {
                it[Offices.zipCode] = "1A2 3B4"
                it[Offices.name] = "Office A"
                it[Offices.areaCode] = 789
            }
            val officeA = Office.new(officeAIdValue) { }
            val officeBIdValue = CompositeID {
                it[Offices.zipCode] = "5C6 7D8"
                it[Offices.name] = "Office B"
                it[Offices.areaCode] = 456
            }
            val officeB = Office.new(officeBIdValue) {
                publisher = publisherA
            }

            flushCache()

            // child entity references
            authorA.publisher.id.value[Publishers.pubId] shouldBeEqualTo publisherA.id.value[Publishers.pubId]
            authorA.publisher shouldBeEqualTo publisherA
            authorB.publisher shouldBeEqualTo publisherA
            bookA.author?.publisher shouldBeEqualTo publisherA
            bookA.author shouldBeEqualTo authorB
            reviewA.book shouldBeEqualTo bookA
            reviewA.book.author shouldBeEqualTo authorB
            officeA.publisher.shouldBeNull()
            officeB.publisher shouldBeEqualTo publisherA

            // parent entity references
            bookA.review.shouldNotBeNull() shouldBeEqualTo reviewA
            publisherA.authors.toList() shouldContainSame listOf(authorA, authorB)
            publisherA.office.shouldNotBeNull()

            // if multiple children reference parent, backReference & optBackReferencedOn save last one
            publisherA.office shouldBeEqualTo officeB
            publisherA.allOffices.toList() shouldContainSame listOf(officeB)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `inList with CompositeID Entities`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val publisherA = Publisher.new {
                name = "Publisher A"
            }
            val authorA = Author.new {
                publisher = publisherA
                penName = "Author A"
            }
            val authorB = Author.new {
                publisher = publisherA
                penName = "Author B"
            }
            val officeAIdValue = CompositeID {
                it[Offices.zipCode] = "1A2 3B4"
                it[Offices.name] = "Office A"
                it[Offices.areaCode] = 789
            }
            val officeA = Office.new(officeAIdValue) { }
            val officeBIdValue = CompositeID {
                it[Offices.zipCode] = "5C6 7D8"
                it[Offices.name] = "Office B"
                it[Offices.areaCode] = 456
            }
            val officeB = Office.new(officeBIdValue) {
                publisher = publisherA
            }

            commit()

            /**
             * Preload referencedOn - child to single parent
             *
             * ```sql
             * SELECT authors.id, authors.publisher_id, authors.publisher_isbn, authors.pen_name
             *   FROM authors
             *  WHERE authors.id = 1
             * ```
             *
             * ```sql
             * SELECT publishers.pub_id,
             *        publishers.isbn_code,
             *        publishers.publisher_name
             *   FROM publishers
             *  WHERE (publishers.pub_id, publishers.isbn_code) = (1, 'd2090a50-3290-4026-88c1-1aa92bd775b0')
             * ```
             */
            inTopLevelTransaction(Connection.TRANSACTION_READ_COMMITTED) {
                maxAttempts = 1
                // preload referencedOn - child to single parent
                Author.find { Authors.id eq authorA.id }.first().load(Author::publisher)

                val foundAuthor = Author.testCache(authorA.id)
                foundAuthor.shouldNotBeNull()
                Publisher.testCache(foundAuthor.readCompositeIDValues(Publishers))?.id shouldBeEqualTo publisherA.id
            }

            /**
             * Preload optionalReferencedOn - child to single parent?
             *
             * ```sql
             * SELECT offices.zip_code,
             *        offices."name",
             *        offices.area_code,
             *        offices.staff,
             *        offices.publisher_id,
             *        offices.publisher_isbn
             *   FROM offices
             * ```
             *
             * ```sql
             * SELECT publishers.pub_id,
             *        publishers.isbn_code,
             *        publishers.publisher_name
             *   FROM publishers
             *  WHERE (publishers.pub_id, publishers.isbn_code) = (1, 'd2090a50-3290-4026-88c1-1aa92bd775b0')
             * ```
             *
             */
            inTopLevelTransaction(Connection.TRANSACTION_READ_COMMITTED) {
                maxAttempts = 1

                // preload optionalReferencedOn - child to single parent?
                Office.all().with(Office::publisher)
                val foundOfficeA = Office.testCache(officeA.id)
                foundOfficeA.shouldNotBeNull()

                val foundOfficeB = Office.testCache(officeB.id)
                foundOfficeB.shouldNotBeNull()
                foundOfficeA.readValues[Offices.publisherId].shouldBeNull()
                foundOfficeA.readValues[Offices.publisherIsbn].shouldBeNull()
                Publisher.testCache(foundOfficeB.readCompositeIDValues(Publishers))?.id shouldBeEqualTo publisherA.id
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload backReferencedOn`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val publisherA = Publisher.new { name = "Publisher A" }
            val officeAIdValue = CompositeID {
                it[Offices.zipCode] = "1A2 3B4"
                it[Offices.name] = "Office A"
                it[Offices.areaCode] = 789
            }
            Office.new(officeAIdValue) { }
            val officeBIdValue = CompositeID {
                it[Offices.zipCode] = "5C6 7D8"
                it[Offices.name] = "Office B"
                it[Offices.areaCode] = 456
            }
            val officeB = Office.new(officeBIdValue) {
                publisher = publisherA
            }
            val bookA = Book.new { title = "Book A" }
            val reviewIdValue = CompositeID {
                it[Reviews.content] = "Not bad"
                it[Reviews.rank] = 12345L
            }
            val reviewA = Review.new(reviewIdValue) {
                book = bookA
            }

            commit()

            /**
             * Preload backReferencedOn - parent to single child
             * ```sql
             * SELECT books.book_id, books.title, books.author_id
             *   FROM books
             *  WHERE books.book_id = 1
             * ```
             * ```sql
             * SELECT reviews.code, reviews."rank", reviews.book_id
             *   FROM reviews
             *  WHERE reviews.book_id = (1)
             *
             * ```
             */
            inTopLevelTransaction(Connection.TRANSACTION_READ_COMMITTED) {
                maxAttempts = 1
                // preload backReferencedOn - parent to single child
                val cache = TransactionManager.current().entityCache
                Book.find { Books.id eq bookA.id }.first().load(Book::review)

                val result = cache.getReferrers<Review>(bookA.id, Reviews.book)?.map { it.id }.orEmpty()
                result shouldBeEqualTo listOf(reviewA.id)
            }

            /**
             * Preload optionalBackReferencedOn - parent to single child?
             *
             * ```
             * SELECT publishers.pub_id,
             *        publishers.isbn_code,
             *        publishers.publisher_name
             *   FROM publishers
             *  WHERE (publishers.isbn_code = '24bbfafe-1de3-4b9e-9601-4955b9f0b360') AND (publishers.pub_id = 1)
             * ```
             * ```sql
             * SELECT offices.zip_code,
             *        offices."name",
             *        offices.area_code,
             *        offices.staff,
             *        offices.publisher_id,
             *        offices.publisher_isbn
             *   FROM offices
             *  WHERE (offices.publisher_id, offices.publisher_isbn) = (1, '24bbfafe-1de3-4b9e-9601-4955b9f0b360')
             * ```
             */
            inTopLevelTransaction(Connection.TRANSACTION_READ_COMMITTED) {
                maxAttempts = 1
                // preload optionalBackReferencedOn - parent to single child?
                val cache = TransactionManager.current().entityCache
                Publisher.find { Publishers.id eq publisherA.id }.first().load(Publisher::office)

                val result = cache.getReferrers<Office>(publisherA.id, Offices.publisherId)?.map { it.id }.orEmpty()
                result shouldBeEqualTo listOf(officeB.id)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `preload referrersOn`(testDB: TestDB) {
        withTables(testDB, *allTables) {
            val publisherA = Publisher.new {
                name = "Publisher A"
            }
            val authorA = Author.new {
                publisher = publisherA
                penName = "Author A"
            }
            val authorB = Author.new {
                publisher = publisherA
                penName = "Author B"
            }
            val officeAIdValue = CompositeID {
                it[Offices.zipCode] = "1A2 3B4"
                it[Offices.name] = "Office A"
                it[Offices.areaCode] = 789
            }
            Office.new(officeAIdValue) {}
            val officeBIdValue = CompositeID {
                it[Offices.zipCode] = "5C6 7D8"
                it[Offices.name] = "Office B"
                it[Offices.areaCode] = 456
            }
            val officeB = Office.new(officeBIdValue) {
                publisher = publisherA
            }

            commit()

            /**
             * Preload referrersOn - parent to multiple children
             *
             * ```sql
             * SELECT publishers.pub_id,
             *        publishers.isbn_code,
             *        publishers.publisher_name
             *   FROM publishers
             *  WHERE (publishers.isbn_code = 'e36de68c-3425-4188-8f73-ae4b658d86c9') AND (publishers.pub_id = 1)
             * ```
             * ```sql
             * SELECT authors.id,
             *        authors.publisher_id,
             *        authors.publisher_isbn,
             *        authors.pen_name
             *   FROM authors
             *  WHERE (authors.publisher_id, authors.publisher_isbn) = (1, 'e36de68c-3425-4188-8f73-ae4b658d86c9')
             * ```
             */
            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                // preload referrersOn - parent to multiple children
                val cache = TransactionManager.current().entityCache

                Publisher.find { Publishers.id eq publisherA.id }.first().load(Publisher::authors)

                val result = cache.getReferrers<Author>(publisherA.id, Authors.publisherId)?.map { it.id }.orEmpty()
                result shouldContainSame listOf(authorA.id, authorB.id)
            }

            /**
             * Preload optionalReferrersOn - parent to multiple children?
             *
             * ```sql
             * SELECT publishers.pub_id, publishers.isbn_code, publishers.publisher_name
             *   FROM publishers
             * ```
             * ```sql
             * SELECT offices.zip_code,
             *        offices."name",
             *        offices.area_code,
             *        offices.staff,
             *        offices.publisher_id,
             *        offices.publisher_isbn
             *   FROM offices
             *  WHERE (offices.publisher_id, offices.publisher_isbn) = (1, 'e36de68c-3425-4188-8f73-ae4b658d86c9')
             * ```
             */
            inTopLevelTransaction(Connection.TRANSACTION_SERIALIZABLE) {
                maxAttempts = 1
                // preload optionalReferrersOn - parent to multiple children?
                val cache = TransactionManager.current().entityCache

                Publisher.all().with(Publisher::allOffices)

                val result = cache.getReferrers<Office>(publisherA.id, Offices.publisherId)?.map { it.id }.orEmpty()
                result shouldBeEqualTo listOf(officeB.id)
            }
        }
    }

    private fun Entity<*>.readCompositeIDValues(table: CompositeIdTable): EntityID<CompositeID> {
        val referenceColumn = this.klass.table.foreignKeys.single().references
        return EntityID(
            CompositeID {
                referenceColumn.forEach { (child, parent) ->
                    it[parent as Column<EntityID<Any>>] = this.readValues[child] as Any
                }
            },
            table
        )
    }
}
