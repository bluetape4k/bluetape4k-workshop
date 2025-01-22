package io.bluetape4k.workshop.exposed.domain.shared.dml

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.H2_MYSQL
import io.bluetape4k.workshop.exposed.expectException
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.exceptions.UnsupportedByDialectException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.concat
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.times
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.BatchUpsertStatement
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.statements.UpdateStatement
import org.jetbrains.exposed.sql.statements.UpsertBuilder
import org.jetbrains.exposed.sql.stringLiteral
import org.jetbrains.exposed.sql.stringParam
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.*
import kotlin.properties.Delegates

class UpsertTest: AbstractExposedTest() {

    companion object: KLogging()

    // these DB require key columns from ON clause to be included in the derived source table (USING clause)
    private val upsertViaMergeDB = TestDB.ALL_H2

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with PK conflict`(testDB: TestDB) {
        withTables(testDB, AutoIncTable) {
            val id1 = AutoIncTable.insert {
                it[name] = "A"
            } get AutoIncTable.id

            /**
             * MySQL:
             * ```sql
             * INSERT INTO auto_inc_table (`name`) VALUES ('B')
             *    AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
             * ```
             *
             * Postgres:
             * ```sql
             * INSERT INTO auto_inc_table ("name") VALUES ('B')
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */

            /**
             * MySQL:
             * ```sql
             * INSERT INTO auto_inc_table (`name`) VALUES ('B')
             *    AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
             * ```
             *
             * Postgres:
             * ```sql
             * INSERT INTO auto_inc_table ("name") VALUES ('B')
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            AutoIncTable.upsert {
                if (testDB in upsertViaMergeDB)
                    it[id] = 2
                it[name] = "B"
            }

            /**
             * MySQL:
             * ```sql
             * INSERT INTO auto_inc_table (id, `name`) VALUES (1, 'C')
             * AS NEW ON DUPLICATE KEY UPDATE id=NEW.id, `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO auto_inc_table (id, "name") VALUES (1, 'C')
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */

            /**
             * MySQL:
             * ```sql
             * INSERT INTO auto_inc_table (id, `name`) VALUES (1, 'C')
             * AS NEW ON DUPLICATE KEY UPDATE id=NEW.id, `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO auto_inc_table (id, "name") VALUES (1, 'C')
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            AutoIncTable.upsert {
                it[id] = id1
                it[name] = "C"
            }

            AutoIncTable.selectAll().forEach {
                log.debug { "id: ${it[AutoIncTable.id]}, name: ${it[AutoIncTable.name]}" }
            }
            AutoIncTable.selectAll().count().toInt() shouldBeEqualTo 2
            val updatedResult = AutoIncTable.selectAll().where { AutoIncTable.id eq id1 }.single()
            updatedResult[AutoIncTable.name] shouldBeEqualTo "C"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with composite PK conflict`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val idA = integer("id_a")
            val idB = integer("id_b")
            val name = varchar("name", 64)

            override val primaryKey = PrimaryKey(idA, idB)
        }

        withTables(testDB, tester) {
            val insertStmt = tester.insert {
                it[idA] = 1
                it[idB] = 1
                it[name] = "A"
            }

            // insert because only 1 constraint is equal
            tester.upsert {
                it[idA] = 7
                it[idB] = insertStmt get tester.idB
                it[name] = "B"
            }

            // insert because both constraints differ
            tester.upsert {
                it[idA] = 99
                it[idB] = 99
                it[name] = "C"
            }

            // update because both constraints match
            tester.upsert {
                it[idA] = insertStmt get tester.idA
                it[idB] = insertStmt get tester.idB
                it[name] = "D"
            }

            tester.selectAll().forEach {
                log.debug { "idA: ${it[tester.idA]}, idB: ${it[tester.idB]}, name: ${it[tester.name]}" }
            }
            tester.selectAll().count().toInt() shouldBeEqualTo 3

            val updatedResult = tester
                .selectAll()
                .where {
                    tester.idA eq insertStmt[tester.idA]
                }
                .single()

            updatedResult[tester.name] shouldBeEqualTo "D"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with all columns in PK`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val userId = varchar("user_id", 32)
            val keyId = varchar("key_id", 32)
            override val primaryKey = PrimaryKey(userId, keyId)
        }

        fun upsertOnlyKeyColumns(values: Pair<String, String>) {
            tester.upsert {
                it[userId] = values.first
                it[keyId] = values.second
            }
        }

        withTables(testDB, tester) {
            val primaryKeyValues = Pair("User A", "Key A")

            // insert new row
            upsertOnlyKeyColumns(primaryKeyValues)

            // `update` existing row to have identical values
            upsertOnlyKeyColumns(primaryKeyValues)

            tester.selectAll().forEach {
                log.debug { "userId: ${it[tester.userId]}, keyId: ${it[tester.keyId]}" }
            }
            val result = tester.selectAll().singleOrNull()

            result.shouldNotBeNull()
            val resultValues = Pair(result[tester.userId], result[tester.keyId])
            resultValues shouldBeEqualTo primaryKeyValues
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with unique index conflict`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val wordA = Words.upsert {
                it[word] = "A"
                it[count] = 10
            } get Words.word

            // insert
            Words.upsert {
                it[word] = "B"
                it[count] = 10
            }

            // update
            Words.upsert {
                it[word] = wordA
                it[count] = 9
            }

            Words.selectAll().forEach {
                log.debug { "word: ${it[Words.word]}, count: ${it[Words.count]}" }
            }
            Words.selectAll().count().toInt() shouldBeEqualTo 2

            val updatedResult = Words.selectAll().where { Words.word eq wordA }.single()
            updatedResult[Words.count] shouldBeEqualTo 9
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with manual conflict keys`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL_LIKE + TestDB.ALL_H2_V1) }

        val tester = object: Table("tester") {
            val idA = integer("id_a").uniqueIndex()
            val idB = integer("id_b").uniqueIndex()
            val name = varchar("name", 64)
        }
        withTables(testDB, tester) {
            val oldIdA = tester.insert {
                it[idA] = 1
                it[idB] = 1
                it[name] = "A"
            } get tester.idA

            // updated
            val newIdB = tester.upsert(tester.idA) {
                it[idA] = oldIdA
                it[idB] = 2
                it[name] = "B"
            } get tester.idB        // 2

            tester.selectAll().single()[tester.name] shouldBeEqualTo "B"

            // updated
            val newIdA = tester.upsert(tester.idB) {
                it[idA] = 99
                it[idB] = newIdB
                it[name] = "C"
            } get tester.idA        // 99

            // idA: 99, idB: 2, name: C
            tester.selectAll().forEach {
                log.debug { "idA: ${it[tester.idA]}, idB: ${it[tester.idB]}, name: ${it[tester.name]}" }
            }
            tester.selectAll().single()[tester.name] shouldBeEqualTo "C"

            if (testDB in upsertViaMergeDB) {
                // passes since these DB use 'AND' within ON clause (other DB require single uniqueness constraint)
                tester.upsert(tester.idA, tester.idB) {
                    it[idA] = newIdA        // 99
                    it[idB] = newIdB        // 2
                    it[name] = "D"
                }

                val result = tester.selectAll().single()
                result[tester.idA] shouldBeEqualTo newIdA
                result[tester.idB] shouldBeEqualTo newIdB
                result[tester.name] shouldBeEqualTo "D"
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with UUID Key conflict`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val id = uuid("id").autoGenerate()
            val title = text("title")

            override val primaryKey = PrimaryKey(id)
        }

        withTables(testDB, tester) {

            // insert
            val uuid1 = tester.upsert {
                it[title] = "A"
            } get tester.id

            // update
            tester.upsert {
                it[id] = uuid1
                it[title] = "B"
            }

            val result = tester.selectAll().single()
            result[tester.id] shouldBeEqualTo uuid1
            result[tester.title] shouldBeEqualTo "B"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with no unique constraints`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val name = varchar("name", 64)
        }

        val okWithNoUniquenessDB = TestDB.ALL_MYSQL_LIKE  // + TestDB.SQLITE

        withTables(testDB, tester) {
            if (testDB in okWithNoUniquenessDB) {
                /**
                 * MySQL:
                 * ```sql
                 * INSERT INTO tester (`name`) VALUES ('A') AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
                 * ```
                 */
                /**
                 * MySQL:
                 * ```sql
                 * INSERT INTO tester (`name`) VALUES ('A') AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
                 * ```
                 */
                tester.upsert {
                    it[name] = "A"
                }
                tester.selectAll().count().toInt() shouldBeEqualTo 1
            } else {
                expectException<UnsupportedByDialectException> {
                    tester.upsert {
                        it[name] = "A"
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with manual update assignment`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val testWord = "Test"

            repeat(3) {
                Words.upsert(onUpdate = { it[Words.count] = Words.count + 1 }) {
                    it[word] = testWord
                }
            }
            Words.selectAll().single()[Words.count] shouldBeEqualTo 3

            /**
             * MySQL:
             * ```sql
             * INSERT INTO words (`name`) VALUES ('Test') AS NEW ON DUPLICATE KEY UPDATE `count`=1000
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO words ("name") VALUES ('Test') ON CONFLICT ("name") DO UPDATE SET "count"=1000
             * ```
             */
            /**
             * MySQL:
             * ```sql
             * INSERT INTO words (`name`) VALUES ('Test') AS NEW ON DUPLICATE KEY UPDATE `count`=1000
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO words ("name") VALUES ('Test') ON CONFLICT ("name") DO UPDATE SET "count"=1000
             * ```
             */
            val updatedCount = 1000
            Words.upsert(onUpdate = { it[Words.count] = updatedCount }) {
                it[word] = testWord
            }
            Words.selectAll().single()[Words.count] shouldBeEqualTo updatedCount
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with multiple manual updates`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val item = varchar("item", 64).uniqueIndex()
            val amount = integer("amount").default(25)
            val gains = integer("gains").default(100)
            val losses = integer("losses").default(100)
        }

        fun UpsertBuilder.adjustGainAndLoss(statement: UpdateStatement) {
            statement[tester.gains] = tester.gains + tester.amount
            statement[tester.losses] = tester.losses - insertValue(tester.amount)
        }

        withTables(testDB, tester) {
            val itemA = tester.upsert {
                it[item] = "Item A"
            } get tester.item

            tester.upsert(onUpdate = { adjustGainAndLoss(it) }) {
                it[item] = "Item B"
                it[gains] = 200
                it[losses] = 0
                // `amount` must be passed explicitly now due to usage of that column inside the custom onUpdate statement
                // There is an option to call `tester.amount.defaultValueFun?.let { it() }!!`,
                // it looks ugly but prevents regression on changes in default value
                it[amount] = 25
            }

            val insertResult = tester.selectAll().where { tester.item neq itemA }.single()
            insertResult[tester.amount] shouldBeEqualTo 25
            insertResult[tester.gains] shouldBeEqualTo 200
            insertResult[tester.losses] shouldBeEqualTo 0

            tester.upsert(onUpdate = { adjustGainAndLoss(it) }) {
                it[item] = itemA
                it[amount] = 10
                it[gains] = 200
                it[losses] = 0
            }

            val updateResult = tester.selectAll().where { tester.item eq itemA }.single()
            updateResult[tester.gains] shouldBeEqualTo 100 + 25
            updateResult[tester.losses] shouldBeEqualTo 100 - 10
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with column expression`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val defaultPhrase = "Phrase"
        val tester = object: Table("tester") {
            val word = varchar("word", 256).uniqueIndex()
            val phrase = varchar("phrase", 256).defaultExpression(stringParam(defaultPhrase))
        }

        withTables(testDB, tester) {
            val testWord = "Test"

            // insert default expression
            tester.upsert {
                it[word] = testWord
            }
            tester.selectAll().single()[tester.phrase] shouldBeEqualTo defaultPhrase

            tester.upsert(
                onUpdate = { it[tester.phrase] = concat(" - ", listOf(tester.word, tester.phrase)) }
            ) {
                it[word] = testWord
            }
            tester.selectAll().single()[tester.phrase] shouldBeEqualTo "$testWord - $defaultPhrase"

            val multilinePhrase =
                """
                This is a phrase with a new line
                and some other difficult strings '

                Indentation should be preserved
                """.trimIndent()

            tester.upsert(
                onUpdate = { it[tester.phrase] = multilinePhrase }
            ) {
                it[word] = testWord
            }
            tester.selectAll().single()[tester.phrase] shouldBeEqualTo multilinePhrase

            // provided expression in insert
            tester.upsert {
                it[word] = "$testWord 2"
                it[phrase] = concat(stringLiteral("foo"), stringLiteral("bar"))
            }
            tester.selectAll()
                .where { tester.word eq "$testWord 2" }
                .single()[tester.phrase] shouldBeEqualTo "foobar"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with manual update using insert values`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val id = integer("id").uniqueIndex()
            val word = varchar("name", 64)
            val count = integer("count").default(1)
        }

        withTables(testDB, tester) {
            tester.insert {
                it[id] = 1
                it[word] = "Word A"
            }

            tester.selectAll().single()[tester.count] shouldBeEqualTo 1

            // H2_Mysql & H2_Mariadb syntax does not allow VALUES() syntax to come first in complex expression
            // Syntax must be column=(1 + VALUES(column)), not column=(VALUES(column) + 1)
            tester.upsert(
                onUpdate = { it[tester.count] = intLiteral(100) times insertValue(tester.count) }
            ) {
                it[id] = 1
                it[word] = "Word B"
                it[count] = 9
            }
            val result = tester.selectAll().single()
            result[tester.count] shouldBeEqualTo 100 * 9

            val newWords = listOf(
                Triple(2, "Word B", 2),
                Triple(1, "Word A", 3),
                Triple(3, "Word C", 4),
            )
            // id: 1 인 경우에만 Update 된다.
            tester.batchUpsert(
                newWords,
                onUpdate = {
                    it[tester.word] = concat(tester.word, stringLiteral(" || "), insertValue(tester.count))
                    it[tester.count] = intLiteral(1) plus insertValue(tester.count)
                }
            ) { (id, word, count) ->
                this[tester.id] = id
                this[tester.word] = word
                this[tester.count] = count
            }
            tester.selectAll().count().toInt() shouldBeEqualTo 3
            tester.selectAll().forEach {
                log.debug { "id: ${it[tester.id]}, word: ${it[tester.word]}, count: ${it[tester.count]}" }
            }

            val updatedWord = tester.selectAll().where { tester.id eq 1 }.single()

            updatedWord[tester.word] shouldBeEqualTo "Word A || 3"
            updatedWord[tester.count] shouldBeEqualTo 1 + 3
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with update excluding columns`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("tester") {
            val item = varchar("item", 64).uniqueIndex()
            val code = uuid("code").clientDefault { UUID.randomUUID() }
            val gains = integer("gains")
            val losses = integer("losses")
        }

        withTables(testDB, tester, configure = { useNestedTransactions = true }) {
            val itemA = "Item A"
            tester.upsert {
                it[item] = itemA
                it[gains] = 50
                it[losses] = 50
            }

            val (insertCode, insertGains, insertLosses) = tester.selectAll().single().let {
                Triple(it[tester.code], it[tester.gains], it[tester.losses])
            }

            transaction {
                // all fields get updated by default, including columns with default values
                tester.upsert {
                    it[item] = itemA
                    it[gains] = 200
                    it[losses] = 0
                }

                val (updateCode, updateGain, updateLosses) = tester.selectAll().single().let {
                    Triple(it[tester.code], it[tester.gains], it[tester.losses])
                }
                updateCode shouldNotBeEqualTo insertCode
                updateGain shouldNotBeEqualTo insertGains
                updateLosses shouldNotBeEqualTo insertLosses

                rollback()
            }

            tester.upsert(onUpdateExclude = listOf(tester.code, tester.gains)) {
                it[item] = itemA
                it[gains] = 200
                it[losses] = 0
            }

            val (updateCode, updateGain, updateLosses) = tester.selectAll().single().let {
                Triple(it[tester.code], it[tester.gains], it[tester.losses])
            }

            updateCode shouldBeEqualTo insertCode       // upsert 에서 제외
            updateGain shouldBeEqualTo insertGains      // upsert 에서 제외
            updateLosses shouldNotBeEqualTo insertLosses  // updatedLoses = 0, insertLosses = 50
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with Where`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL_LIKE + upsertViaMergeDB) }

        val tester = object: IntIdTable("tester") {
            val name = varchar("name", 64).uniqueIndex()
            val address = varchar("address", 256)
            val age = integer("age")
        }

        withTables(testDB, tester) {
            val id1: EntityID<Int> = tester.insertAndGetId {
                it[name] = "A"
                it[address] = "Place A"
                it[age] = 10
            }
            val unchanged: InsertStatement<Number> = tester.insert {
                it[name] = "B"
                it[address] = "Place B"
                it[age] = 50
            }

            val ageTooLow = tester.age less intLiteral(15)
            val updatedAge = tester.upsert(tester.name, where = { ageTooLow }) {
                it[name] = "A"
                it[address] = "Address A"
                it[age] = 20
            } get tester.age
            log.debug { "updatedAge: $updatedAge" }

            tester.upsert(tester.name, where = { ageTooLow }) {
                it[name] = "B"
                it[address] = "Address B"
                it[age] = 20
            }

            tester.selectAll().forEach {
                log.debug { "id: ${it[tester.id]}, name: ${it[tester.name]}, address: ${it[tester.address]}, age: ${it[tester.age]}" }
            }
            tester.selectAll().count().toInt() shouldBeEqualTo 2

            val unchangedResult = tester.selectAll()
                .where { tester.id eq unchanged[tester.id] }
                .single()
            unchangedResult[tester.address] shouldBeEqualTo unchanged[tester.address]

            val updatedResult = tester.selectAll()
                .where { tester.id eq id1 }
                .single()
            updatedResult[tester.age] shouldBeEqualTo updatedAge
        }
    }

    /**
     * Postgres:
     * ```sql
     * INSERT INTO tester ("name", age) VALUES ('Anya', 10)
     * INSERT INTO tester ("name", age) VALUES ('Anna', 50) ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name", age=EXCLUDED.age
     * ```
     *
     * ```sql
     * INSERT INTO tester ("name", age) VALUES ('Anya', 20)
     * ON CONFLICT ("name") DO
     *      UPDATE SET age=EXCLUDED.age
     *       WHERE (tester."name" LIKE 'A%') AND (tester."name" LIKE '%a') AND (tester."name" <> 'Anna')
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with where parameterized`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL_LIKE + upsertViaMergeDB) }

        val tester = object: IntIdTable("tester") {
            val name = varchar("name", 64).uniqueIndex()
            val age = integer("age")
        }

        withTables(testDB, tester) {
            val id1 = tester.insert {
                it[name] = "Anya"
                it[age] = 10
            } get tester.id

            tester.upsert {
                it[name] = "Anna"
                it[age] = 50
            }

            val nameStartsWithA = tester.name like "A%"
            val nameEndsWithA = tester.name like stringLiteral("%a")
            val nameIsNotAnna = tester.name neq stringParam("Anna")
            val updatedAge = 20

            tester.upsert(tester.name, where = { nameStartsWithA and nameEndsWithA and nameIsNotAnna }) {
                it[name] = "Anya"
                it[age] = updatedAge
            }

            tester.selectAll().forEach {
                log.debug { "id: ${it[tester.id]}, name: ${it[tester.name]}, age: ${it[tester.age]}" }
            }
            tester.selectAll().count().toInt() shouldBeEqualTo 2

            val updatedResult = tester.selectAll().where { tester.age eq updatedAge }.single()
            updatedResult[tester.id] shouldBeEqualTo id1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `upsert with subQuery`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester1 = object: IntIdTable("tester_1") {
            val name = varchar("name", 32)
        }
        val tester2 = object: IntIdTable("tester_2") {
            val name = varchar("name", 32)
        }

        withTables(testDB, tester1, tester2) {
            val id1 = tester1.insertAndGetId {
                it[name] = "foo"
            }
            val id2 = tester1.insertAndGetId {
                it[name] = "bar"
            }

            /**
             * MySQL:
             * ```sql
             * INSERT INTO tester_2 (`name`) VALUES ((SELECT tester_1.`name` FROM tester_1 WHERE tester_1.id = 1)) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO tester_2 ("name") VALUES ((SELECT tester_1."name" FROM tester_1 WHERE tester_1.id = 1))
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            /**
             * MySQL:
             * ```sql
             * INSERT INTO tester_2 (`name`) VALUES ((SELECT tester_1.`name` FROM tester_1 WHERE tester_1.id = 1)) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO tester_2 ("name") VALUES ((SELECT tester_1."name" FROM tester_1 WHERE tester_1.id = 1))
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            val query1 = tester1.select(tester1.name).where { tester1.id eq id1 }
            val id3 = tester2.upsert {
                if (testDB in upsertViaMergeDB)
                    it[id] = 1
                it[name] = query1
            } get tester2.id

            tester2.selectAll().single()[tester2.name] shouldBeEqualTo "foo"

            /**
             * MySQL:
             * ```sql
             * INSERT INTO tester_2 (id, `name`) VALUES (1, (SELECT tester_1.`name` FROM tester_1 WHERE tester_1.id = 2)) AS NEW ON DUPLICATE KEY UPDATE id=NEW.id, `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO tester_2 (id, "name") VALUES (1, (SELECT tester_1."name" FROM tester_1 WHERE tester_1.id = 2))
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            /**
             * MySQL:
             * ```sql
             * INSERT INTO tester_2 (id, `name`) VALUES (1, (SELECT tester_1.`name` FROM tester_1 WHERE tester_1.id = 2)) AS NEW ON DUPLICATE KEY UPDATE id=NEW.id, `name`=NEW.`name`
             * ```
             * Postgres:
             * ```sql
             * INSERT INTO tester_2 (id, "name") VALUES (1, (SELECT tester_1."name" FROM tester_1 WHERE tester_1.id = 2))
             * ON CONFLICT (id) DO UPDATE SET "name"=EXCLUDED."name"
             * ```
             */
            val query2 = tester1.select(tester1.name).where { tester1.id eq id2 }
            tester2.upsert {
                it[id] = id3
                it[name] = query2
            }
            tester2.selectAll().single()[tester2.name] shouldBeEqualTo "bar"
        }
    }

    /**
     * BatchUpsert
     *
     * MySQL:
     * ```sql
     * INSERT INTO words (`name`, `count`) VALUES ('Word A', 10) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word B', 20) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word C', 30) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word D', 40) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word E', 50) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word F', 60) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word G', 70) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word H', 80) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word I', 90) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * INSERT INTO words (`name`, `count`) VALUES ('Word J', 100) AS NEW ON DUPLICATE KEY UPDATE `name`=NEW.`name`, `count`=NEW.`count`
     * ```
     *
     * Postgres:
     * ```sql
     * INSERT INTO words ("name", "count") VALUES ('Word A', 10) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word B', 20) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word C', 30) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word D', 40) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word E', 50) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word F', 60) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word G', 70) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word H', 80) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word I', 90) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * INSERT INTO words ("name", "count") VALUES ('Word J', 100) ON CONFLICT ("name") DO UPDATE SET "count"=EXCLUDED."count"
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchUpsert with no conflict`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val amountOfWords = 10
            val allWords: List<Pair<String, Int>> = List(amountOfWords) { i ->
                "Word ${'A' + i}" to amountOfWords * i + amountOfWords
            }

            val generatedIds = Words.batchUpsert(allWords) { (word, count) ->
                this[Words.word] = word
                this[Words.count] = count
            }

            generatedIds.forEach {
                log.debug { "Generated ID: ${it[Words.word]}, ${it[Words.count]}" }
            }
            generatedIds shouldHaveSize amountOfWords
            Words.selectAll().count().toInt() shouldBeEqualTo amountOfWords
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchUpsert with conflict`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val vowels = listOf("A", "E", "I", "O", "U")
            val alphabet = ('A'..'Z').map { it.toString() }
            val lettersWithDuplicates = alphabet + vowels

            Words.batchUpsert(
                lettersWithDuplicates,
                onUpdate = { it[Words.count] = Words.count + 1 }
            ) { letter ->
                this[Words.word] = letter
            }

            Words.selectAll().count().toInt() shouldBeEqualTo alphabet.size
            Words.selectAll().forEach {
                val expectedCount = if (it[Words.word] in vowels) 2 else 1
                it[Words.count] shouldBeEqualTo expectedCount
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchUpdate with sequence`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val amountOfWords = 25
            val allWords = List(amountOfWords) { Fakers.fixedString(32) }.asSequence()
            Words.batchUpsert(allWords) { word ->
                this[Words.word] = word
            }

            Words.selectAll().count().toInt() shouldBeEqualTo amountOfWords
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchUpdate with empty sequence`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, Words) {
            val allWords = emptySequence<String>()
            Words.batchUpsert(allWords) { word ->
                this[Words.word] = word
            }

            Words.selectAll().count().toInt() shouldBeEqualTo 0
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `batchUpsert with where`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in (TestDB.ALL_MYSQL_LIKE + upsertViaMergeDB) }

        withTables(testDB, Words) {
            val vowels = listOf("A", "E", "I", "O", "U")
            val alphabet = ('A'..'Z').map { it.toString() }
            val lettersWithDuplicates = alphabet + vowels

            val firstThreeVowels = vowels.take(3)

            Words.batchUpsert(
                lettersWithDuplicates,
                onUpdate = { it[Words.count] = Words.count + 1 },
                // PostgresNG throws IndexOutOfBound if shouldReturnGeneratedValues == true
                // Related issue in pgjdbc-ng repository: https://github.com/impossibl/pgjdbc-ng/issues/545
                shouldReturnGeneratedValues = false,
                where = { Words.word inList firstThreeVowels }
            ) { letter ->
                this[Words.word] = letter
            }

            Words.selectAll().count().toInt() shouldBeEqualTo alphabet.size
            Words.selectAll().forEach {
                val expectedCount = if (it[Words.word] in firstThreeVowels) 2 else 1
                it[Words.count] shouldBeEqualTo expectedCount
            }
        }
    }


    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Inserted Count With BatchUpsert`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        withTables(testDB, AutoIncTable) {
            // SQL Server requires statements to be executed before results can be obtained
            val isNotSqlServer = true // testDB != TestDB.SQLSERVER
            val data = listOf(1 to "A", 2 to "B", 3 to "C")
            val newDataSize = data.size
            var statement: BatchUpsertStatement by Delegates.notNull()

            // all new rows inserted
            AutoIncTable.batchUpsert(data, shouldReturnGeneratedValues = isNotSqlServer) { (id, name) ->
                statement = this
                this[AutoIncTable.id] = id
                this[AutoIncTable.name] = name
            }
            statement.insertedCount shouldBeEqualTo newDataSize

            // all existing rows set to their current values
            val isH2MysqlMode = testDB == H2_MYSQL // || testDB == TestDB.H2_MARIADB
            var expected = if (isH2MysqlMode) 0 else newDataSize
            AutoIncTable.batchUpsert(data, shouldReturnGeneratedValues = isNotSqlServer) { (id, name) ->
                statement = this
                this[AutoIncTable.id] = id
                this[AutoIncTable.name] = name
            }
            statement.insertedCount shouldBeEqualTo expected

            // all existing rows updated & 1 new row inserted
            val updatedData = data.map { it.first to "new${it.second}" } + (4 to "D")
            expected = if (testDB in TestDB.ALL_MYSQL_LIKE) newDataSize * 2 + 1 else newDataSize + 1
            AutoIncTable.batchUpsert(updatedData, shouldReturnGeneratedValues = isNotSqlServer) { (id, name) ->
                statement = this
                this[AutoIncTable.id] = id
                this[AutoIncTable.name] = name
            }
            statement.insertedCount shouldBeEqualTo expected

            AutoIncTable.selectAll().count().toInt() shouldBeEqualTo updatedData.size
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Upsert With UUID PrimaryKey`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }

        val tester = object: UUIDTable("upsert_test", "id") {
            val key = integer("test_key").uniqueIndex()
            val value = text("test_value")
        }

        // At present, only Postgres returns the correct UUID directly from the result set.
        // For other databases incorrect ID is returned from the 'upsert' command.
        withTables(testDB, tester) {
            val insertId = tester.insertAndGetId {
                it[key] = 1
                it[value] = "one"
            }

            val upsertId = tester.upsert(
                keys = arrayOf(tester.key),
                onUpdateExclude = listOf(tester.id),
            ) {
                it[key] = 1
                it[value] = "two"
            }.resultedValues!!.first()[tester.id]

            upsertId shouldBeEqualTo insertId
            tester.selectAll()
                .where { tester.id eq insertId }
                .first()[tester.value] shouldBeEqualTo "two"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `BatchUpsert With UUID PrimaryKey`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in TestDB.ALL_POSTGRES }

        val tester = object: UUIDTable("batch_upsert_test", "id") {
            val key = integer("test_key").uniqueIndex()
            val value = text("test_value")
        }

        withTables(testDB, tester) {
            val insertId = tester.insertAndGetId {
                it[key] = 1
                it[value] = "one"
            }

            val upsertId = tester.batchUpsert(
                data = listOf(1 to "two"),
                keys = arrayOf(tester.key),
                onUpdateExclude = listOf(tester.id),
            ) {
                this[tester.key] = it.first
                this[tester.value] = it.second
            }.first()[tester.id]

            upsertId shouldBeEqualTo insertId
            tester.selectAll()
                .where { tester.id eq insertId }
                .first()[tester.value] shouldBeEqualTo "two"
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Upsert When ColumnName Includes TableName`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: Table("my_table") {
            val myTableId = integer("my_table_id")
            val myTableValue = varchar("my_table_value", 100)
            override val primaryKey = PrimaryKey(myTableId)
        }

        withTables(testDB, tester) {
            tester.upsert {
                it[myTableId] = 1
                it[myTableValue] = "Hello"
            }

            tester.selectAll().single()[tester.myTableValue] shouldBeEqualTo "Hello"
        }
    }

    private object AutoIncTable: Table("auto_inc_table") {
        val id = integer("id").autoIncrement()
        val name = varchar("name", 64)

        override val primaryKey = PrimaryKey(id)
    }

    private object Words: Table("words") {
        val word = varchar("name", 64).uniqueIndex()
        val count = integer("count").default(1)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `Default Values And Nullable Columns Not In Arguments`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB !in TestDB.ALL_H2_V1 }

        val tester = object: UUIDTable("test_batch_insert_defaults") {
            val number = integer("number")
            val default = varchar("default", 128).default("default")
            val defaultExpression = varchar("defaultExpression", 128)
                .defaultExpression(stringLiteral("defaultExpression"))
            val nullable = varchar("nullable", 128).nullable()
            val nullableDefaultNull = varchar("nullableDefaultNull", 128).nullable()
                .default(null)
            val nullableDefaultNotNull =
                varchar("nullableDefaultNotNull", 128).nullable()
                    .default("nullableDefaultNotNull")
            val databaseGenerated = integer("databaseGenerated")
                .withDefinition("DEFAULT 1")
                .databaseGenerated()
        }

        val testerWithFakeDefaults = object: UUIDTable("test_batch_insert_defaults") {
            val number = integer("number")
            val default = varchar("default", 128).default("default-fake")
            val defaultExpression = varchar("defaultExpression", 128)
                .defaultExpression(stringLiteral("defaultExpression-fake"))
            val nullable = varchar("nullable", 128).nullable()
                .default("null-fake")
            val nullableDefaultNull = varchar("nullableDefaultNull", 128).nullable()
                .default("null-fake")
            val nullableDefaultNotNull = varchar("nullableDefaultNotNull", 128).nullable()
                .default("nullableDefaultNotNull-fake")
            val databaseGenerated = integer("databaseGenerated").default(-1)
        }

        withTables(testDB, tester) {
            testerWithFakeDefaults.batchUpsert(listOf(1, 2, 3)) {
                this[testerWithFakeDefaults.number] = 10
            }

            testerWithFakeDefaults.selectAll().forEach {

                it[testerWithFakeDefaults.default] shouldBeEqualTo "default"
                it[testerWithFakeDefaults.defaultExpression] shouldBeEqualTo "defaultExpression"
                it[testerWithFakeDefaults.nullable].shouldBeNull()
                it[testerWithFakeDefaults.nullableDefaultNull].shouldBeNull()
                it[testerWithFakeDefaults.nullableDefaultNotNull] shouldBeEqualTo "nullableDefaultNotNull"
                it[testerWithFakeDefaults.databaseGenerated] shouldBeEqualTo 1
            }
        }
    }
}
