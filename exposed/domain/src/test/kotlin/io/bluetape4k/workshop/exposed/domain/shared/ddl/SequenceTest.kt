package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.currentDialectMetadataTest
import io.bluetape4k.workshop.exposed.currentDialectTest
import io.bluetape4k.workshop.exposed.inProperCase
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.autoIncColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable
import org.jetbrains.exposed.v1.core.nextIntVal
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.dao.UUIDEntity
import org.jetbrains.exposed.v1.dao.UUIDEntityClass
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.MigrationUtils
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
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
    fun `create sequence statements`(testDB: TestDB) {
        withDb(testDB) {
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
    fun `insert with sequence`(testDB: TestDB) {
        withTables(testDB, Developer) {
            if (currentDialectTest.supportsSequenceAsGeneratedKeys) {
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
    fun testInsertWithCustomSequence(testDB: TestDB) {
        val tester = object: Table("tester") {
            val id = integer("id").autoIncrement(myseq)  // myseq is a sequence
            val name = varchar("name", 25)

            override val primaryKey = PrimaryKey(id, name)
        }
        withDb(testDB) {
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
    fun `test insert LongIdTable with auto-increment with sequence`(testDB: TestDB) {
        withDb(testDB) {
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
    fun `test select with nextVal`(testDB: TestDB) {
        withTables(testDB, Developer) {
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
    fun testManuallyCreatedSequenceExists(testDB: TestDB) {
        withDb(testDB) {
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
    fun testExistingSequencesForAutoIncrementWithCustomSequence(testDB: TestDB) {
        val tableWithExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(myseq).entityId()
        }

        withDb(testDB) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }

            try {
                SchemaUtils.create(tableWithExplicitSequenceName)

                val sequences = currentDialectMetadataTest.sequences()
                sequences.shouldNotBeEmpty()
                sequences.any { it == myseq.name.inProperCase() }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(tableWithExplicitSequenceName)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testExistingSequencesForAutoIncrementWithExplicitSequenceName(testDB: TestDB) {
        val sequenceName = "id_seq"
        val tableWithExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement(sequenceName).entityId()
        }

        withDb(testDB) {
            Assumptions.assumeTrue { currentDialectTest.supportsSequenceAsGeneratedKeys }
            try {
                SchemaUtils.create(tableWithExplicitSequenceName)

                val sequences = currentDialectMetadataTest.sequences()

                sequences.shouldNotBeEmpty()
                sequences.any { it == sequenceName.inProperCase() }.shouldBeTrue()
            } finally {
                SchemaUtils.drop(tableWithExplicitSequenceName)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun testExistingSequencesForAutoIncrementWithoutExplicitSequenceName(testDB: TestDB) {

        val tableWithoutExplicitSequenceName = object: IdTable<Long>() {
            override val id: Column<EntityID<Long>> = long("id").autoIncrement().entityId()
        }

        withDb(testDB) {
            Assumptions.assumeTrue { currentDialect.needsSequenceToAutoInc }

            try {
                SchemaUtils.create(tableWithoutExplicitSequenceName)

                val sequences = currentDialectMetadataTest.sequences()
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
    fun testNoCreateStatementForExistingSequence(testDB: TestDB) {
        withDb(testDB) {
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

            // SchemaUtils.statementsRequiredToActualizeScheme(DeveloperWithAutoIncrementBySequence)
            MigrationUtils.statementsRequiredForDatabaseMigration(DeveloperWithAutoIncrementBySequence)
                .onEach { log.debug { "actualize: $it" } }
                .find { it.startsWith(createSequencePrefix) }
                .shouldBeNull()

            // Clean up: create table and drop it for removing sequence
            SchemaUtils.create(DeveloperWithAutoIncrementBySequence)
            SchemaUtils.drop(DeveloperWithAutoIncrementBySequence)
        }
    }

    @Test
    fun testAutoIncrementColumnAccessWithEntity() {
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS DEVELOPER (
     *      ID INT,
     *      "name" VARCHAR(25),
     *
     *      CONSTRAINT pk_Developer PRIMARY KEY (ID, "name")
     * )
     * ```
     */
    private object Developer: Table() {
        val id = integer("id")
        val name = varchar("name", 25)

        override val primaryKey = PrimaryKey(id, name)
    }

    private object DeveloperWithLongId: LongIdTable() {
        val name = varchar("name", 25)
    }

    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807
     * ```
     * ```sql
     * CREATE TABLE IF NOT EXISTS DEVELOPERWITHAUTOINCREMENTBYSEQUENCE (
     *      ID BIGINT NOT NULL,
     *      "name" VARCHAR(25) NOT NULL
     * )
     * ```
     */
    private object DeveloperWithAutoIncrementBySequence: IdTable<Long>() {
        override val id: Column<EntityID<Long>> = long("id").autoIncrement("id_seq").entityId()
        val name = varchar("name", 25)
    }

    /**
     * ```sql
     * CREATE SEQUENCE IF NOT EXISTS my_sequence START WITH 4 INCREMENT BY 2 MINVALUE 1 MAXVALUE 100 CYCLE CACHE 20
     * ```
     */
    private val myseq = org.jetbrains.exposed.v1.core.Sequence(
        name = "my_sequence", startWith = 4, incrementBy = 2, minValue = 1, maxValue = 100, cycle = true, cache = 20
    )
}
