package io.bluetape4k.workshop.exposed.domain.shared.ddl

import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Cities.default
import io.bluetape4k.workshop.exposed.domain.shared.ddl.EnumerationTest.Foo.Bar
import io.bluetape4k.workshop.exposed.domain.shared.ddl.EnumerationTest.Foo.Baz
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
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
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.postgresql.util.PGobject

class EnumerationTest: AbstractExposedTest() {

    // NOTE: MYSQL_V8 은 지원하지 않습니다.
    private val supportsCustomEnumerationDB =
        TestDB.ALL_POSTGRES + TestDB.ALL_H2 // + TestDB.MYSQL_V5

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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS ENUMTABLE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "enumColumn" ENUM('Bar', 'Baz') NOT NULL
     * );
     *
     * ALTER TABLE ENUMTABLE ADD CONSTRAINT ENUMTABLE_ENUMCOLUMN_UNIQUE UNIQUE ("enumColumn");
     * ```
     */
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
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom enumeration 01`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportsCustomEnumerationDB }

        withDb(testDB) {
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
                SchemaUtils.create(EnumTable)

                // drop shared table object's unique index if created in other test
                if (EnumTable.indices.isNotEmpty()) {
                    exec(EnumTable.indices.first().dropStatement().single())
                }

                EnumTable.insert {
                    it[enumColumn] = Bar
                }
                EnumTable.selectAll().single()[EnumTable.enumColumn] shouldBeEqualTo Bar

                EnumTable.update {
                    it[enumColumn] = Baz
                }
                val entity = EnumEntity.new { enum = Baz }
                entity.enum shouldBeEqualTo Baz
                entity.id.value // flush entity
                entity.enum = Baz
                EnumEntity.reload(entity)!!.enum shouldBeEqualTo Baz

                entity.enum = Bar
                EnumEntity.reload(entity)!!.enum shouldBeEqualTo Bar
            } finally {
                runCatching {
                    SchemaUtils.drop(EnumTable)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom enumeration with default value`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportsCustomEnumerationDB }

        withDb(testDB) {
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
                EnumTable.enumColumn.default(Bar)
                SchemaUtils.create(EnumTable)

                // drop shared table object's unique index if created in other test
                if (EnumTable.indices.isNotEmpty()) {
                    exec(EnumTable.indices.first().dropStatement().single())
                }

                // insert with default value (Foo.Bar)
                EnumTable.insert {}

                val default = EnumTable.selectAll().single()[EnumTable.enumColumn]
                default shouldBeEqualTo Bar
            } finally {
                runCatching {
                    SchemaUtils.drop(EnumTable)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `custom enumeration with reference`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportsCustomEnumerationDB }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTER (
         *      ENUM_COLUMN INT NOT NULL,
         *      ENUM_NAME_COLUMN VARCHAR(32) NOT NULL
         * )
         * ```
         */
        val referenceTable = object: Table("ref_table") {
            var referenceColumn: Column<Foo> = enumeration("ref_column")

            fun initRefColumn() {
                (columns as MutableList<Column<*>>).remove(referenceColumn)
                referenceColumn = reference("ref_column", EnumTable.enumColumn)
            }
        }

        withDb(testDB) {
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

                val fooBar = Bar
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
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `enumeration columns with reference`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB in supportsCustomEnumerationDB }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS TESTER (
         *      ENUM_COLUMN INT NOT NULL,
         *      ENUM_NAME_COLUMN VARCHAR(32) NOT NULL
         * );
         * ALTER TABLE TESTER ADD CONSTRAINT TESTER_ENUM_COLUMN_UNIQUE UNIQUE (ENUM_COLUMN);
         * ALTER TABLE TESTER ADD CONSTRAINT TESTER_ENUM_NAME_COLUMN_UNIQUE UNIQUE (ENUM_NAME_COLUMN);
         * ```
         */
        val tester = object: Table("tester") {
            val enumColumn = enumeration<Foo>("enum_column").uniqueIndex()
            val enumNameColumn = enumerationByName<Foo>("enum_name_column", 32).uniqueIndex()
        }

        /**
         * ```sql
         * CREATE TABLE IF NOT EXISTS REF_TABLE (
         *      REF_COLUMN ENUM('Bar', 'Baz') NOT NULL,
         *
         *      CONSTRAINT FK_REF_TABLE_REF_COLUMN__ENUMCOLUMN
         *          FOREIGN KEY (REF_COLUMN) REFERENCES ENUMTABLE("enumColumn")
         *          ON DELETE RESTRICT ON UPDATE RESTRICT
         * )
         * ```
         */
        val referenceTable = object: Table("ref_table") {
            val referenceColumn: Column<Foo> = reference("ref_column", tester.enumColumn)
            val referenceNameColumn: Column<Foo> = reference("ref_name_column", tester.enumNameColumn)
        }

        withTables(testDB, tester, referenceTable) {
            val fooBar = Bar
            val fooBaz = Baz

            val entry = tester.insert {
                it[enumColumn] = fooBar
                it[enumNameColumn] = fooBaz
            }

            referenceTable.insert {
                it[referenceColumn] = entry[tester.enumColumn]
                it[referenceNameColumn] = entry[tester.enumNameColumn]
            }

            tester.selectAll().single().apply {
                this[tester.enumColumn] shouldBeEqualTo fooBar
                this[tester.enumNameColumn] shouldBeEqualTo fooBaz
            }

            referenceTable.selectAll().single().apply {
                this[referenceTable.referenceColumn] shouldBeEqualTo fooBar
                this[referenceTable.referenceNameColumn] shouldBeEqualTo fooBaz
            }
        }
    }
}
