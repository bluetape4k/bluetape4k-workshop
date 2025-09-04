package io.bluetape4k.workshop.exposed.domain.shared.types

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.booleanParam
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class BooleanColumnTypeTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS booleantable (
     *      id SERIAL PRIMARY KEY,
     *      "boolColumn" BOOLEAN NOT NULL
     * )
     * ```
     */
    object BooleanTable: IntIdTable("booleanTable") {
        val boolColumn = bool("boolColumn")
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `true value`(testDB: TestDB) {
        withTables(testDB, BooleanTable) {
            val id = BooleanTable.insertAndGetId {
                it[boolColumn] = true
            }

            val result = BooleanTable.selectAll().where { BooleanTable.id eq id }.singleOrNull()
            result?.get(BooleanTable.boolColumn).shouldNotBeNull().shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `false value`(testDB: TestDB) {
        withTables(testDB, BooleanTable) {
            val id = BooleanTable.insertAndGetId {
                it[boolColumn] = false
            }

            val result = BooleanTable.selectAll().where { BooleanTable.id eq id }.singleOrNull()
            result?.get(BooleanTable.boolColumn).shouldNotBeNull().shouldBeFalse()
        }
    }

    /**
     * ```sql
     * SELECT booleantable.id, booleantable."boolColumn"
     *   FROM booleantable
     *  WHERE booleantable."boolColumn" = TRUE;
     *
     * SELECT booleantable.id, booleantable."boolColumn"
     *   FROM booleantable
     *  WHERE booleantable."boolColumn" = TRUE;
     *
     * SELECT booleantable.id, booleantable."boolColumn"
     *   FROM booleantable
     *  WHERE booleantable."boolColumn" = FALSE;
     *
     * SELECT booleantable.id, booleantable."boolColumn"
     *   FROM booleantable
     *  WHERE booleantable."boolColumn" = FALSE;
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `bool in a condition`(testDB: TestDB) {
        withTables(testDB, BooleanTable) {
            val idTrue = BooleanTable.insertAndGetId {
                it[boolColumn] = true
            }
            val idFalse = BooleanTable.insertAndGetId {
                it[boolColumn] = booleanParam(false)
            }

            BooleanTable
                .selectAll()
                .where { BooleanTable.boolColumn eq true }
                .single()[BooleanTable.id] shouldBeEqualTo idTrue

            BooleanTable
                .selectAll()
                .where { BooleanTable.boolColumn eq booleanParam(true) }
                .single()[BooleanTable.id] shouldBeEqualTo idTrue


            BooleanTable
                .selectAll()
                .where { BooleanTable.boolColumn eq false }
                .single()[BooleanTable.id] shouldBeEqualTo idFalse

            BooleanTable
                .selectAll()
                .where { BooleanTable.boolColumn eq booleanParam(false) }
                .single()[BooleanTable.id] shouldBeEqualTo idFalse
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom Char Boolean Column Type`(testDB: TestDB) {

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS tester (
         *      "charBooleanColumn" VARCHAR(1) NOT NULL,
         *      "charBooleanColumnWithDefault" VARCHAR(1) DEFAULT 'N' NOT NULL
         * )
         * ```
         */
        val tester = object: Table("tester") {
            val charBooleanColumn = charBoolean("charBooleanColumn")
            val charBooleanColumnWithDefault = charBoolean("charBooleanColumnWithDefault")
                .default(false)
        }

        withTables(testDB, tester) {
            tester.insert {
                it[charBooleanColumn] = true
            }

            tester.selectAll().single()[tester.charBooleanColumn].shouldBeTrue()

            /**
             * ```sql
             * SELECT COUNT(*)
             *   FROM tester
             *  WHERE (tester."charBooleanColumn" = 'Y')
             *    AND (tester."charBooleanColumnWithDefault" = 'N')
             * ```
             */
            tester
                .select(tester.charBooleanColumn)
                .where { tester.charBooleanColumn eq true }
                .andWhere { tester.charBooleanColumnWithDefault eq false }
                .count() shouldBeEqualTo 1
        }
    }

    /**
     * BOOLEAN을 표현하는 CHAR(1) 컬럼 타입
     */
    class CharBooleanColumnType(
        private val characterColumnType: VarCharColumnType = VarCharColumnType(1),
    ): ColumnType<Boolean>() {
        override fun sqlType(): String = characterColumnType.preciseType()

        override fun valueFromDB(value: Any): Boolean =
            when (characterColumnType.valueFromDB(value).uppercase()) {
                "Y" -> true
                else -> false
            }

        override fun valueToDB(value: Boolean?): Any? =
            characterColumnType.valueToDB(value.toChar().toString())

        override fun nonNullValueToString(value: Boolean): String =
            characterColumnType.nonNullValueToString(value.toChar().toString())

        private fun Boolean?.toChar() = when (this) {
            true -> 'Y'
            false -> 'N'
            else -> ' '
        }
    }

    /**
     * CHAR(1) 컬럼 타입을 생성하는 확장 함수
     */
    fun Table.charBoolean(name: String): Column<Boolean> =
        registerColumn(name, CharBooleanColumnType())
}
