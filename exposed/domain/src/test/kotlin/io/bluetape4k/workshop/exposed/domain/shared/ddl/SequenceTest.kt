package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.inProperCase
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.autoIncColumnType
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.nextIntVal
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*

class SequenceTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS my_sequence
     * START WITH 4
     * INCREMENT BY 2
     * MINVALUE 1
     * MAXVALUE 100
     * CYCLE CACHE 20
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `create sequence statements`(testDb: TestDB) {
        withDb(testDb) {
            Assumptions.assumeTrue { currentDialect.supportsCreateSequence }
            log.debug { "myseq: ${myseq.ddl.single()}" }
            myseq.ddl.single() shouldBeEqualTo "CREATE SEQUENCE " + addIfNotExistsIfSupported() + "${myseq.identifier} " + "START WITH ${myseq.startWith} " + "INCREMENT BY ${myseq.incrementBy} " + "MINVALUE ${myseq.minValue} " + "MAXVALUE ${myseq.maxValue} " + "CYCLE " + "CACHE ${myseq.cache}"
        }
    }


    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS my_sequence START WITH 4 INCREMENT BY 2 MINVALUE 1 MAXVALUE 100 CYCLE CACHE 20;
     *
     * INSERT INTO DEVELOPER (ID, "name") VALUES (NEXT VALUE FOR my_sequence, 'John Doe');
     * INSERT INTO DEVELOPER (ID, "name") VALUES (NEXT VALUE FOR my_sequence, 'Jane Doe');
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `insert with sequence`(testDb: TestDB) {
        withTables(testDb, Developer) {
            if (currentDialectTest.supportsCreateSequence) {
                try {
                    SchemaUtils.createSequence(myseq)

                    var developerId = Developer.insert {
                        it[id] = myseq.nextIntVal()
                        it[name] = "John Doe"
                    } get Developer.id

                    developerId.toLong() shouldBeEqualTo myseq.startWith

                    developerId = Developer.insert {
                        it[id] = myseq.nextIntVal()
                        it[name] = "Jane Doe"
                    } get Developer.id

                    developerId.toLong() shouldBeEqualTo myseq.startWith!! + myseq.incrementBy!!
                } finally {
                    SchemaUtils.dropSequence(myseq)
                }
            }
        }
    }

    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS my_sequence START WITH 4 INCREMENT BY 2 MINVALUE 1 MAXVALUE 100 CYCLE CACHE 20;
     *
     * CREATE TABLE IF NOT EXISTS TESTER (ID INT, "name" VARCHAR(25), CONSTRAINT pk_tester PRIMARY KEY (ID, "name"));
     *
     * INSERT INTO TESTER ("name", ID) VALUES ('Hichem', NEXT VALUE FOR my_sequence);
     * INSERT INTO TESTER ("name", ID) VALUES ('Andrey', NEXT VALUE FOR my_sequence);
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testInsertWithCustomSequence(testDb: TestDB) {
        val tester = object: Table("tester") {
            val id = integer("id").autoIncrement(myseq)  // myseq is a sequence
            val name = varchar("name", 25)

            override val primaryKey = PrimaryKey(id, name)
        }
        withDb(testDb) {
            if (currentDialectTest.supportsSequenceAsGeneratedKeys) {
                try {
                    SchemaUtils.create(tester)
                    myseq.exists().shouldBeTrue()

                    var testerId = tester.insert {
                        it[name] = "Hichem"
                    } get tester.id

                    testerId.toLong() shouldBeEqualTo myseq.startWith

                    testerId = tester.insert {
                        it[name] = "Andrey"
                    } get tester.id

                    testerId.toLong() shouldBeEqualTo myseq.startWith!! + myseq.incrementBy!!
                } finally {
                    SchemaUtils.drop(tester)
                    myseq.exists().shouldBeFalse()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `test insert LongIdTable with auto-increment with sequence`(testDb: TestDB) {
        withDb(testDb) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }

            try {
                SchemaUtils.create(DeveloperWithAutoIncrementBySequence)
                val developerId = DeveloperWithAutoIncrementBySequence.insertAndGetId {
                    it[name] = "Hichem"
                }
                developerId.shouldNotBeNull()

                val developerId2 = DeveloperWithAutoIncrementBySequence.insertAndGetId {
                    it[name] = "Andrey"
                }
                developerId2.value shouldBeEqualTo developerId.value + 1
            } finally {
                SchemaUtils.drop(DeveloperWithAutoIncrementBySequence)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `test select with nextVal`(testDb: TestDB) {
        withTables(testDb, Developer) {
            Assumptions.assumeTrue { currentDialectTest.supportsCreateSequence }

            try {
                SchemaUtils.createSequence(myseq)
                val nextVal = myseq.nextIntVal()
                Developer.insert {
                    it[id] = nextVal
                    it[name] = "Hichem"
                }

                val firstValue = Developer.select(nextVal).single()[nextVal]
                val secondValue = Developer.select(nextVal).single()[nextVal]

                val expFirstValue = myseq.startWith!! + myseq.incrementBy!!
                firstValue.toLong() shouldBeEqualTo expFirstValue
                secondValue.toLong() shouldBeEqualTo expFirstValue + myseq.incrementBy!!
            } finally {
                SchemaUtils.dropSequence(myseq)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testManuallyCreatedSequenceExists(testDb: TestDB) {
        withDb(testDb) {
            Assumptions.assumeTrue { currentDialectTest.supportsCreateSequence }
            try {
                SchemaUtils.createSequence(myseq)
                myseq.exists().shouldBeTrue()
            } finally {
                SchemaUtils.dropSequence(myseq)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testExistingSequencesForAutoIncrementWithCustomSequence(testDb: TestDB) {
        val tableWithExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(myseq).entityId()
        }

        withDb(testDb) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }

            try {
                SchemaUtils.create(tableWithExplicitSequenceName)

                val sequences = currentDialectTest.sequences()
                sequences.shouldNotBeEmpty()
                sequences.any { it == myseq.name.inProperCase() }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(tableWithExplicitSequenceName)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testExistingSequencesForAutoIncrementWithExplicitSequenceName(testDb: TestDB) {
        val sequenceName = "id_seq"
        val tableWithExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()
        }

        withDb(testDb) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }
            try {
                SchemaUtils.create(tableWithExplicitSequenceName)

                val sequences = currentDialectTest.sequences()

                sequences.shouldNotBeEmpty()
                sequences.any { it == sequenceName.inProperCase() }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(tableWithExplicitSequenceName)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testExistingSequencesForAutoIncrementWithoutExplicitSequenceName(testDb: TestDB) {

        val tableWithoutExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
        }

        withDb(testDb) {
            Assumptions.assumeTrue { currentDialect.needsSequenceToAutoInc }

            try {
                SchemaUtils.create(tableWithoutExplicitSequenceName)

                val sequences = currentDialectTest.sequences()
                sequences.shouldNotBeEmpty()

                val expected = tableWithoutExplicitSequenceName.id.autoIncColumnType!!.autoincSeq!!
                sequences.any { it == expected }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(tableWithoutExplicitSequenceName)
            }
        }
    }

    /**
     * 첫번째 Create
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807;
     * CREATE TABLE IF NOT EXISTS DEVELOPERWITHAUTOINCREMENTBYSEQUENCE (
     *      ID BIGINT NOT NULL,
     *      "name" VARCHAR(25) NOT NULL
     * );
     * ```
     * Drop Table
     * ```sql
     * DROP TABLE DEVELOPERWITHAUTOINCREMENTBYSEQUENCE;
     * ```
     *
     * createStatements
     * ```sql
     * CREATE TABLE IF NOT EXISTS DEVELOPERWITHAUTOINCREMENTBYSEQUENCE (ID BIGINT NOT NULL, "name" VARCHAR(25) NOT NULL)
     * ```
     *
     * statementsRequiredToActualizeScheme
     * ```sql
     * CREATE TABLE IF NOT EXISTS DEVELOPERWITHAUTOINCREMENTBYSEQUENCE (ID BIGINT NOT NULL, "name" VARCHAR(25) NOT NULL)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testNoCreateStatementForExistingSequence(testDb: TestDB) {
        withDb(testDb) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }

            val createSequencePrefix = "CREATE SEQUENCE"

            SchemaUtils.createStatements(DeveloperWithAutoIncrementBySequence)
                .onEach { log.debug { "create: $it" } }
                .find { it.startsWith(createSequencePrefix) }
                .shouldNotBeNull()

            SchemaUtils.create(DeveloperWithAutoIncrementBySequence)

            // Remove table without removing sequence
            exec("DROP TABLE ${DeveloperWithAutoIncrementBySequence.nameInDatabaseCase()}")

            SchemaUtils.createStatements(DeveloperWithAutoIncrementBySequence)
                .onEach { log.debug { "create: $it" } }
                .find { it.startsWith(createSequencePrefix) }
                .shouldBeNull()

            SchemaUtils.statementsRequiredToActualizeScheme(DeveloperWithAutoIncrementBySequence)
                .onEach { log.debug { "actualize: $it" } }
                .find { it.startsWith(createSequencePrefix) }
                .shouldBeNull()

            // Clean up: create table and drop it for removing sequence
            SchemaUtils.create(DeveloperWithAutoIncrementBySequence)
            SchemaUtils.drop(DeveloperWithAutoIncrementBySequence)
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testAutoIncrementColumnAccessWithEntity(testDb: TestDB) {
        Assumptions.assumeTrue { testDb == TestDB.POSTGRESQL }

        TestDB.POSTGRESQL.connect()

        try {
            transaction {
                SchemaUtils.create(TesterTable)
            }
            val testerEntity = transaction {
                TesterEntity.new {
                    name = "test row"
                }
            }
            testerEntity.index shouldBeEqualTo 1
        } finally {
            transaction {
                SchemaUtils.drop(TesterTable)
            }
        }
    }


    object TesterTable: UUIDTable("Tester") {
        val index = integer("index").autoIncrement()
        val name = text("name")
    }

    class TesterEntity(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<TesterEntity>(TesterTable)

        var index by TesterTable.index
        var name by TesterTable.name
    }

    private object Developer: Table() {
        val id = integer("id")
        val name = varchar("name", 25)

        override val primaryKey = PrimaryKey(id, name)
    }

    private object DeveloperWithLongId: LongIdTable() {
        val name = varchar("name", 25)
    }

    private object DeveloperWithAutoIncrementBySequence: IdTable<Long>() {
        override val id: Column<EntityID<Long>> = long("id").autoIncrement("id_seq").entityId()
        val name = varchar("name", 25)
    }

    private val myseq = org.jetbrains.exposed.sql.Sequence(
        name = "my_sequence", startWith = 4, incrementBy = 2, minValue = 1, maxValue = 100, cycle = true, cache = 20
    )
}
