package io.bluetape4k.workshop.exposed.domain.shared.entities

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Cities.default
import io.bluetape4k.workshop.exposed.domain.withDb
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import org.postgresql.util.PGobject

class EnumerationTest: AbstractExposedTest() {

    // NOTE: MYSQL_V8 은 지원하지 않습니다.
    private val supportsCustomEnumerationDB = TestDB.ALL_POSTGRES + TestDB.ALL_H2 // + TestDB.MYSQL_V5

    internal enum class Foo {
        Bar, Baz;

        override fun toString(): String = "Foo Enum ToString: $name"
    }

    class PGEnum<T: Enum<T>>(enumTypeName: String, enumValue: T?): PGobject() {
        init {
            value = enumValue?.name
            type = enumTypeName
        }
    }

    object EnumTable: IntIdTable("EnumTable") {
        internal var enumColumn: Column<Foo> = enumeration("enumColumn")

        internal fun initEnumColumn(sql: String) {
            (columns as MutableList<Column<*>>).remove(enumColumn)
            enumColumn = customEnumeration(
                "enumColumn", sql,
                { value -> Foo.valueOf(value as String) },
                { value ->
                    when (currentDialect) {
                        is PostgreSQLDialect -> PGEnum(sql, value)
                        else                 -> value.name
                    }
                }
            )
        }
    }

    internal class EnumEntity(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<EnumEntity>(EnumTable)

        var enum by EnumTable.enumColumn
    }

    @ParameterizedTest
    @FieldSource("supportsCustomEnumerationDB")
    fun `custom enumeration 01`(dialect: TestDB) {
        withDb(dialect) {
            val sqlType = when (currentDialect) {
                is H2Dialect, is MysqlDialect -> "ENUM('Bar', 'Baz')"
                is PostgreSQLDialect          -> "FooEnum"
                else                          -> error("Unsupported case")
            }


//            open class EnumEntity(id: EntityID<Int>): IntEntity(id) {
//                var enum by EnumTable.enumColumn
//            }
//
//            val enumClass = object: IntEntityClass<EnumEntity>(EnumTable, EnumEntity::class.java) {}

            try {
                if (currentDialect is PostgreSQLDialect) {
                    exec("DROP TYPE IF EXISTS FooEnum;")
                    exec("CREATE TYPE FooEnum AS ENUM ('Bar', 'Baz');")
                }
                EnumTable.initEnumColumn(sqlType)
                SchemaUtils.create(EnumTable)

                // drop shared table object's unique index if created in other test
                if (EnumTable.indices.isNotEmpty()) {
                    exec(EnumTable.indices.first().dropStatement().single())
                }

                EnumTable.insert {
                    it[EnumTable.enumColumn] = Foo.Bar
                }
                EnumTable.selectAll().single()[EnumTable.enumColumn] shouldBeEqualTo Foo.Bar

                EnumTable.update {
                    it[EnumTable.enumColumn] = Foo.Baz
                }
                val entity = EnumEntity.new { enum = Foo.Baz }
                entity.enum shouldBeEqualTo Foo.Baz
                entity.id.value // flush entity
                entity.enum = Foo.Baz
                EnumEntity.reload(entity)!!.enum shouldBeEqualTo Foo.Baz

                entity.enum = Foo.Bar
                EnumEntity.reload(entity)!!.enum shouldBeEqualTo Foo.Bar
            } finally {
                runCatching {
                    SchemaUtils.drop(EnumTable)
                }
            }
        }
    }

    @ParameterizedTest
    @FieldSource("supportsCustomEnumerationDB")
    fun `custom enumeration with default value`(dialect: TestDB) {
        withDb(dialect) {
            val sqlType = when (currentDialect) {
                is H2Dialect, is MysqlDialect -> "ENUM('Bar', 'Baz')"
                is PostgreSQLDialect          -> "FooEnum"
                else                          -> error("Unsupported case")
            }

            try {
                if (currentDialect is PostgreSQLDialect) {
                    exec("DROP TYPE IF EXISTS FooEnum;")
                    exec("CREATE TYPE FooEnum AS ENUM ('Bar', 'Baz');")
                }
                EnumTable.initEnumColumn(sqlType)
                EnumTable.enumColumn.default(Foo.Bar)
                SchemaUtils.create(EnumTable)

                // drop shared table object's unique index if created in other test
                if (EnumTable.indices.isNotEmpty()) {
                    exec(EnumTable.indices.first().dropStatement().single())
                }

                // insert with default value (Foo.Bar)
                EnumTable.insert {}

                val default = EnumTable.selectAll().single()[EnumTable.enumColumn]
                default shouldBeEqualTo Foo.Bar
            } finally {
                runCatching {
                    SchemaUtils.drop(EnumTable)
                }
            }
        }
    }

    @ParameterizedTest
    @FieldSource("supportsCustomEnumerationDB")
    fun `custom enumeration with reference`(dialect: TestDB) {
        val referenceTable = object: Table("ref_table") {
            var referenceColumn: Column<Foo> = enumeration("ref_column")

            fun initRefColumn() {
                (columns as MutableList<Column<*>>).remove(referenceColumn)
                referenceColumn = reference("ref_column", EnumTable.enumColumn)
            }
        }

        withDb(dialect) {
            val sqlType = when (currentDialect) {
                is H2Dialect, is MysqlDialect -> "ENUM('Bar', 'Baz')"
                is PostgreSQLDialect          -> "FooEnum"
                else                          -> error("Unsupported case")
            }

            try {
                if (currentDialect is PostgreSQLDialect) {
                    exec("DROP TYPE IF EXISTS FooEnum;")
                    exec("CREATE TYPE FooEnum AS ENUM ('Bar', 'Baz');")
                }
                EnumTable.initEnumColumn(sqlType)
                with(EnumTable) {
                    if (indices.isEmpty()) enumColumn.uniqueIndex()
                }
                SchemaUtils.create(EnumTable)

                referenceTable.initRefColumn()
                SchemaUtils.create(referenceTable)

                val fooBar = Foo.Bar
                val id1 = EnumTable.insert {
                    it[enumColumn] = fooBar
                } get EnumTable.enumColumn
                referenceTable.insert {
                    it[referenceColumn] = id1
                }

                EnumTable.selectAll().single()[EnumTable.enumColumn] shouldBeEqualTo fooBar
                referenceTable.selectAll().single()[referenceTable.referenceColumn] shouldBeEqualTo fooBar

            } finally {
                runCatching {
                    SchemaUtils.drop(referenceTable)
                    exec(EnumTable.indices.first().dropStatement().single())
                    SchemaUtils.drop(EnumTable)
                }
            }
        }
    }

    @ParameterizedTest
    @FieldSource("supportsCustomEnumerationDB")
    fun `enumeration columns with reference`(dialect: TestDB) {
        val tester = object: Table("tester") {
            val enumColumn = enumeration<Foo>("enum_column").uniqueIndex()
            val enumNameColumn = enumerationByName<Foo>("enum_name_column", 32).uniqueIndex()
        }
        val referenceTable = object: Table("ref_table") {
            val referenceColumn: Column<Foo> = reference("ref_column", tester.enumColumn)
            val referenceNameColumn: Column<Foo> = reference("ref_name_column", tester.enumNameColumn)
        }

        withTables(dialect, tester, referenceTable) {
            val fooBar = Foo.Bar
            val fooBaz = Foo.Baz

            val entry = tester.insert {
                it[enumColumn] = fooBar
                it[enumNameColumn] = fooBaz
            }

            referenceTable.insert {
                it[referenceColumn] = entry[tester.enumColumn]
                it[referenceNameColumn] = entry[tester.enumNameColumn]
            }

            tester.selectAll().single()[tester.enumColumn] shouldBeEqualTo fooBar
            tester.selectAll().single()[tester.enumNameColumn] shouldBeEqualTo fooBaz

            referenceTable.selectAll().single()[referenceTable.referenceColumn] shouldBeEqualTo fooBar
            referenceTable.selectAll().single()[referenceTable.referenceNameColumn] shouldBeEqualTo fooBaz
        }
    }
}
