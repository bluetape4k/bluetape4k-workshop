package io.bluetape4k.workshop.exposed.domain.shared.types

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.assertFailAndRollback
import io.bluetape4k.workshop.exposed.domain.currentDialectTest
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldEndWithIgnoringCase
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.byteParam
import org.jetbrains.exposed.sql.decimalParam
import org.jetbrains.exposed.sql.doubleParam
import org.jetbrains.exposed.sql.floatParam
import org.jetbrains.exposed.sql.functions.math.RoundFunction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.intParam
import org.jetbrains.exposed.sql.longParam
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.shortParam
import org.jetbrains.exposed.sql.ubyteParam
import org.jetbrains.exposed.sql.uintParam
import org.jetbrains.exposed.sql.ulongParam
import org.jetbrains.exposed.sql.ushortParam
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class NumericColumnTypesTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `short accepts only allowed range`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val short = short("short")
        }
        withTables(testDB, tester) {
            val columnName = tester.short.nameInDatabaseCase()
            val ddlEnding = "($columnName ${tester.short.columnType} NOT NULL)"

            tester.ddl.single().shouldEndWithIgnoringCase(ddlEnding)

            tester.insert { it[short] = Short.MIN_VALUE }
            tester.insert { it[short] = Short.MAX_VALUE }
            tester.select(tester.short).count().toInt() shouldBeEqualTo 2

            val tableName = tester.nameInDatabaseCase()
            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Short.MIN_VALUE - 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }

            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Short.MAX_VALUE + 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `byte accepts only allowed range`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val byte = byte("byte")
        }
        withTables(testDB, tester) {
            val columnName = tester.byte.nameInDatabaseCase()
            val ddlEnding = when (testDB) {
                in TestDB.ALL_POSTGRES_LIKE ->
                    "CHECK ($columnName BETWEEN ${Byte.MIN_VALUE} AND ${Byte.MAX_VALUE}))"

                else                        -> "($columnName ${tester.byte.columnType} NOT NULL)"
            }

            tester.ddl.single().shouldEndWithIgnoringCase(ddlEnding)

            tester.insert { it[byte] = Byte.MIN_VALUE }
            tester.insert { it[byte] = Byte.MAX_VALUE }
            tester.select(tester.byte).count().toInt() shouldBeEqualTo 2

            val tableName = tester.nameInDatabaseCase()
            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Byte.MIN_VALUE - 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }

            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Byte.MAX_VALUE + 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `integer accepts only allowed range`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val integer = integer("integer_column")
        }
        withTables(testDB, tester) {
            val columnName = tester.integer.nameInDatabaseCase()
            val ddlEnding = "($columnName ${tester.integer.columnType} NOT NULL)"

            tester.ddl.single().shouldEndWithIgnoringCase(ddlEnding)

            tester.insert { it[integer] = Int.MIN_VALUE }
            tester.insert { it[integer] = Int.MAX_VALUE }
            tester.select(tester.integer).count().toInt() shouldBeEqualTo 2

            val tableName = tester.nameInDatabaseCase()
            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Int.MIN_VALUE.toLong() - 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }

            assertFailAndRollback("Out-of-range error") {
                val outOfRangeValue = Int.MAX_VALUE.toLong() + 1
                exec("INSERT INTO $tableName ($columnName) VALUES ($outOfRangeValue)")
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `numeric params`(testDB: TestDB) {
        val tester = object: Table("tester") {
            val byte = byte("byte_column")
            val ubyte = ubyte("ubyte_column")
            val short = short("short_column")
            val ushort = ushort("ushort_column")
            val integer = integer("integer_column")
            val uinteger = uinteger("uinteger_column")
            val long = long("long_column")
            val ulong = ulong("ulong_column")
            val float = float("float_column")
            val double = double("double_column")
            val decimal = decimal("decimal_column", 6, 3)
        }

        withTables(testDB, tester) {
            tester.insert {
                it[byte] = byteParam(Byte.MIN_VALUE)
                it[ubyte] = ubyteParam(UByte.MAX_VALUE)
                it[short] = shortParam(Short.MIN_VALUE)
                it[ushort] = ushortParam(UShort.MAX_VALUE)
                it[integer] = intParam(Int.MIN_VALUE)
                it[uinteger] = uintParam(UInt.MAX_VALUE)
                it[long] = longParam(Long.MIN_VALUE)
                it[ulong] = ulongParam(Long.MAX_VALUE.toULong())  // ULong.MAX_VALUE is not supported in Postgres
                it[float] = floatParam(3.14159F)
                it[double] = doubleParam(3.1415925435)
                it[decimal] = decimalParam(123.456.toBigDecimal())
            }

            tester.selectAll().where { tester.byte eq byteParam(Byte.MIN_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.ubyte eq ubyteParam(UByte.MAX_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.short eq shortParam(Short.MIN_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.ushort eq ushortParam(UShort.MAX_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.integer eq intParam(Int.MIN_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.uinteger eq uintParam(UInt.MAX_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.long eq longParam(Long.MIN_VALUE) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.ulong eq ulongParam(Long.MAX_VALUE.toULong()) }.count() shouldBeEqualTo 1L


            tester.selectAll().where { tester.double eq doubleParam(3.1415925435) }.count() shouldBeEqualTo 1L
            tester.selectAll().where { tester.decimal eq decimalParam(123.456.toBigDecimal()) }
                .count() shouldBeEqualTo 1L

            // Float 처리 - MySQL은 정확한 값이 아닌 근사치로 비교
            tester.selectAll()
                .where {
                    if (currentDialectTest is MysqlDialect) {
                        RoundFunction(tester.float, 5).eq<Number, BigDecimal, Float>(floatParam(3.14159F))
                    } else {
                        tester.float eq floatParam(3.14159F)
                    }
                }
                .count() shouldBeEqualTo 1L

        }
    }
}
