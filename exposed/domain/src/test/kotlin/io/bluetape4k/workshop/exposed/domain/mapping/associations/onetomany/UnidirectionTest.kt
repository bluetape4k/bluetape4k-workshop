package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class UnidirectionTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * -- MySQL V8
     * CREATE TABLE IF NOT EXISTS clouds (
     *      id INT AUTO_INCREMENT PRIMARY KEY,
     *      kind VARCHAR(255) NOT NULL,
     *      `length` DOUBLE PRECISION NOT NULL
     * )
     * ```
     */
    object CloudTable: IntIdTable("clouds") {
        val kind = varchar("kind", 255)
        val length = double("length")
    }

    /**
     * ```sql
     * -- MySQL V8
     * CREATE TABLE IF NOT EXISTS snowflakes (
     *      id INT AUTO_INCREMENT PRIMARY KEY,
     *      `name` VARCHAR(255) NOT NULL,
     *      description text NULL
     * )
     * ```
     */
    object SnowflakeTable: IntIdTable("snowflakes") {
        val name = varchar("name", 255)
        val description = text("description").nullable()
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS cloud_snowflakes (
     *      cloud_id INT,
     *      snowflake_id INT,
     *
     *      CONSTRAINT PK_CLOUD_SNOWFLAKES PRIMARY KEY (cloud_id, snowflake_id),
     *
     *      CONSTRAINT fk_cloud_snowflakes_cloud_id__id FOREIGN KEY (cloud_id)
     *      REFERENCES clouds(id) ON DELETE CASCADE ON UPDATE CASCADE,
     *
     *      CONSTRAINT fk_cloud_snowflakes_snowflake_id__id FOREIGN KEY (snowflake_id)
     *      REFERENCES snowflakes(id) ON DELETE CASCADE ON UPDATE CASCADE
     * );
     * ```
     */
    object CloudSnowflakeTable: Table("cloud_snowflakes") {
        val cloudId = reference(
            "cloud_id",
            CloudTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
        val snowflakeId = reference(
            "snowflake_id",
            SnowflakeTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )

        override val primaryKey = PrimaryKey(cloudId, snowflakeId, name = "PK_CLOUD_SNOWFLAKES")
    }

    class Cloud(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Cloud>(CloudTable)

        var kind by CloudTable.kind
        var length by CloudTable.length
        val producedSnowflakes by Snowflake via CloudSnowflakeTable

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("kind", kind)
                .toString()
    }

    class Snowflake(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Snowflake>(SnowflakeTable)

        var name by SnowflakeTable.name
        var description by SnowflakeTable.description

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .toString()
    }

    private fun fakeCound() = Cloud.new {
        kind = faker.name().name()
        length = faker.random().nextDouble(0.0, 40.0)
    }

    private fun fakeSnowflake() = Snowflake.new {
        name = faker.name().name()
        description = faker.lorem().paragraph()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many unidirectional association`(testDB: TestDB) {
        withTables(testDB, CloudTable, SnowflakeTable, CloudSnowflakeTable) {
            val snowflake1 = fakeSnowflake()
            val snowflake2 = fakeSnowflake()
            val cloud = fakeCound()

            CloudSnowflakeTable.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake1.id
            }
            CloudSnowflakeTable.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake2.id
            }

            flushCache()
            entityCache.clear()

            val cloud2 = Cloud.findById(cloud.id)!!
            cloud2.producedSnowflakes.count() shouldBeEqualTo 2L
            cloud2.producedSnowflakes.toSet() shouldContainSame setOf(snowflake1, snowflake2)

            // Remove snowflake
            val snowflakeToRemove = cloud2.producedSnowflakes.first()
            snowflakeToRemove.delete()

            val snowflake3 = fakeSnowflake()
            CloudSnowflakeTable.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake3.id
            }

            entityCache.clear()

            Snowflake.count() shouldBeEqualTo 2L

            val cloud3 = Cloud.findById(cloud.id)!!

            /**
             * ```sql
             * -- Postgres
             * SELECT COUNT(*)
             *   FROM snowflakes INNER JOIN cloud_snowflakes ON snowflakes.id = cloud_snowflakes.snowflake_id
             *  WHERE cloud_snowflakes.cloud_id = 1
             * ```
             */
            cloud3.producedSnowflakes.count() shouldBeEqualTo 2L

            /**
             * ```sql
             * -- Postgres
             * SELECT snowflakes.id,
             *        snowflakes."name",
             *        snowflakes.description,
             *        cloud_snowflakes.snowflake_id,
             *        cloud_snowflakes.cloud_id
             *   FROM snowflakes INNER JOIN cloud_snowflakes ON snowflakes.id = cloud_snowflakes.snowflake_id
             *  WHERE cloud_snowflakes.cloud_id = 1
             * ```
             */
            cloud3.producedSnowflakes.toSet() shouldContainSame setOf(
                snowflake1,
                snowflake2,
                snowflake3
            ) - snowflakeToRemove
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `violation of duplicated primary key`(testDB: TestDB) {
        withTables(testDB, CloudTable, SnowflakeTable, CloudSnowflakeTable) {
            val snowflake1 = fakeSnowflake()
            val snowflake2 = fakeSnowflake()
            val cloud = fakeCound()

            flushCache()

            CloudSnowflakeTable.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake1.id
            }
            CloudSnowflakeTable.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake2.id
            }

            // Duplicated entry
            assertFailsWith<ExposedSQLException> {
                CloudSnowflakeTable.insert {
                    it[cloudId] = cloud.id
                    it[snowflakeId] = snowflake1.id
                }
            }
        }
    }
}
