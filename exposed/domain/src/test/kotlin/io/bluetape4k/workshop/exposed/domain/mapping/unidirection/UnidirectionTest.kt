package io.bluetape4k.workshop.exposed.domain.mapping.unidirection

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContainSame
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class UnidirectionTest: AbstractExposedTest() {

    companion object: KLogging()

    object Clouds: IntIdTable("clouds") {
        val kind = varchar("kind", 255)
        val length = double("length")
    }

    object Snowflakes: IntIdTable("snowflakes") {
        val name = varchar("name", 255)
        val description = text("description").nullable()
    }

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

        override fun equals(other: Any?): Boolean = other is Cloud && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Cloud(id=$id, kind=$kind)"
    }

    class Snowflake(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Snowflake>(Snowflakes)

        var name by Snowflakes.name
        var description by Snowflakes.description

        override fun equals(other: Any?): Boolean = other is Snowflake && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Snowflake(id=$id, name=$name)"
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
            CloudSnowflakes.insert {
                it[cloudId] = cloud.id
                it[snowflakeId] = snowflake3.id
            }

            entityCache.clear()

            Snowflake.count() shouldBeEqualTo 2L

            val cloud3 = Cloud.findById(cloud.id)!!
            cloud3.producedSnowflakes.count() shouldBeEqualTo 2L
            cloud3.producedSnowflakes.toSet() shouldContainSame setOf(
                snowflake1,
                snowflake2,
                snowflake3
            ) - snowflakeToRemove
        }
    }
}
