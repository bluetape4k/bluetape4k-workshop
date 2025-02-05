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
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.junit.jupiter.api.Assumptions
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
    object Clouds: IntIdTable("clouds") {
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
    object Snowflakes: IntIdTable("snowflakes") {
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
    object CloudSnowflakes: Table("cloud_snowflakes") {
        val cloudId = reference("cloud_id", Clouds, onDelete = CASCADE, onUpdate = CASCADE)
        val snowflakeId = reference("snowflake_id", Snowflakes, onDelete = CASCADE, onUpdate = CASCADE)

        override val primaryKey = PrimaryKey(cloudId, snowflakeId, name = "PK_CLOUD_SNOWFLAKES")
    }

    class Cloud(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Cloud>(Clouds)

        var kind by Clouds.kind
        var length by Clouds.length
        val producedSnowflakes by Snowflake via CloudSnowflakes

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("kind", kind)
                .toString()
    }

    class Snowflake(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Snowflake>(Snowflakes)

        var name by Snowflakes.name
        var description by Snowflakes.description

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

        // FIXME: Postgres 에서 duplicate key value violates unique constraint "pk_cloud_snowflakes" 예외가 발생한다.
        Assumptions.assumeTrue { testDB !in TestDB.ALL_POSTGRES }

        withTables(testDB, Clouds, Snowflakes, CloudSnowflakes) {
            val snowflake1 = fakeSnowflake()
            val snowflake2 = fakeSnowflake()
            val cloud = fakeCound()

            CloudSnowflakes.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake1.id
            }
            CloudSnowflakes.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake2.id
            }

            // Duplicated entry
            assertFailsWith<ExposedSQLException> {
                CloudSnowflakes.insert {
                    it[cloudId] = cloud.id
                    it[snowflakeId] = snowflake1.id
                }
                commit()
            }

            // flushCache()
            entityCache.clear()

            val cloud2 = Cloud.findById(cloud.id)!!
            cloud2.producedSnowflakes.count() shouldBeEqualTo 2L
            cloud2.producedSnowflakes.toSet() shouldContainSame setOf(snowflake1, snowflake2)

            // Remove snowflake
            val snowflakeToRemove = cloud2.producedSnowflakes.first()
            snowflakeToRemove.delete()

            val snowflake3 = fakeSnowflake()
            CloudSnowflakes.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake3.id
            }

            entityCache.clear()

            Snowflake.count() shouldBeEqualTo 2L

            val cloud3 = Cloud.findById(cloud.id)!!
            cloud3.producedSnowflakes.count() shouldBeEqualTo 2L

            /**
             * ```sql
             * SELECT snowflakes.id,
             *        snowflakes.`name`,
             *        snowflakes.description,
             *        cloud_snowflakes.snowflake_id,
             *        cloud_snowflakes.cloud_id
             *   FROM snowflakes
             *          INNER JOIN cloud_snowflakes ON snowflakes.id = cloud_snowflakes.snowflake_id
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
}
