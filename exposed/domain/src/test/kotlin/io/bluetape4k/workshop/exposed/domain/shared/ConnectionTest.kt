package io.bluetape4k.workshop.exposed.domain.shared

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.withTables
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.vendors.ColumnMetadata
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.sql.Types

class ConnectionTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * H2:
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS PEOPLE (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      FIRSTNAME VARCHAR(80) NULL,
     *      LASTNAME VARCHAR(42) DEFAULT 'Doe' NOT NULL,
     *      AGE INT DEFAULT 18 NOT NULL
     * )
     * ```
     */
    object People: LongIdTable() {
        val firstName = varchar("firstname", 80).nullable()
        val lastName = varchar("lastname", 42).default("Doe")
        val age = integer("age").default(18)
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getting column metadata`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB == TestDB.H2 }

        withTables(testDB, People) {
            val columnMetadata = connection.metadata {
                columns(People)[People].shouldNotBeNull()
            }.toSet()

            columnMetadata.forEach { cm: ColumnMetadata ->
                log.debug { "Column Meta: $cm" }
            }

            val idColumnMeta = if ((db.dialect as H2Dialect).isSecondVersion) {
                ColumnMetadata("ID", Types.BIGINT, false, 64, null, true, null)
            } else {
                ColumnMetadata("ID", Types.BIGINT, false, 19, null, true, null)
            }
            val expected = setOf(
                idColumnMeta,
                ColumnMetadata("FIRSTNAME", Types.VARCHAR, true, 80, null, false, null),
                ColumnMetadata("LASTNAME", Types.VARCHAR, false, 42, null, false, "Doe"),
                ColumnMetadata("AGE", Types.INTEGER, false, 32, null, false, "18"),
            )

            columnMetadata shouldContainSame expected
        }
    }

    /**
     * DDL
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS PARENT (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      "scale" INT NOT NULL
     * );
     *
     * ALTER TABLE PARENT ADD CONSTRAINT PARENT_SCALE_UNIQUE UNIQUE ("scale");
     *
     * CREATE TABLE IF NOT EXISTS CHILD (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      "scale" INT NOT NULL,
     *      CONSTRAINT FK_CHILD_SCALE__SCALE FOREIGN KEY ("scale") REFERENCES PARENT("scale") ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `table constraints`(testDB: TestDB) {
        Assumptions.assumeTrue { testDB != TestDB.MYSQL_V5 }

        val parent = object: LongIdTable("parent") {
            val scale = integer("scale").uniqueIndex()
        }
        val child = object: LongIdTable("child") {
            val scale = reference("scale", parent.scale)
        }

        withTables(testDB, parent, child) {
            val constraints = connection.metadata {
                tableConstraints(listOf(child))
            }

            /**
             * ```
             * Table: PARENT, Constraint: []
             * Table: CHILD, Constraint: [ForeignKeyConstraint(fkName='FK_CHILD_SCALE__SCALE')]
             * ```
             */
            constraints.forEach { (table, constraint) ->
                log.debug { "Table: $table, Constraint: $constraint" }
            }
            constraints.keys shouldHaveSize 2
        }
    }
}
