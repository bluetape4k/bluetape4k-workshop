package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetoone

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class CavalierHorseTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS cavalier (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      horse_id INT NULL,
     *
     *      CONSTRAINT fk_cavalier_horse_id__id FOREIGN KEY (horse_id)
     *      REFERENCES horse(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Cavaliers: IntIdTable("cavalier") {
        val name = varchar("name", 255)
        val horseId = optReference("horse_id", Horses)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS horse (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object Horses: IntIdTable("horse") {
        val name = varchar("name", 255)
    }

    class Cavalier(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Cavalier>(Cavaliers)

        var name by Cavaliers.name
        var horse by Horse optionalReferencedOn Cavaliers.horseId

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .toString()
    }

    class Horse(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Horse>(Horses)

        var name by Horses.name

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .toString()
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unidirectional one-to-one, cavalier has ownership`(testDB: TestDB) {
        withTables(testDB, Cavaliers, Horses) {
            val horse = Horse.new {
                name = "White Horse"
            }
            val cavalier = Cavalier.new {
                name = "Clint Eastwood"
                this.horse = horse
            }

            val loaded = Cavalier.findById(cavalier.id)!!
            loaded shouldBeEqualTo cavalier
            loaded.horse shouldBeEqualTo horse

            val horse2 = cavalier.horse!!
            horse2 shouldBeEqualTo horse

            // 현재로는 horse는 cavalier가 소유하므로, 삭제할 수 없다.
            // ReferenceOption.SET_NULL, ReferenceOption.CASCADE 를 사용하면 삭제할 수 있다.
            if (testDB !in TestDB.ALL_POSTGRES) {
                assertFailsWith<ExposedSQLException> {
                    horse.delete()
                }
            }
            cavalier.delete()
            Cavalier.count() shouldBeEqualTo 0L

            // cavalier가 삭제되었으므로, horse를 삭제할 수 있다.
            Horse.count() shouldBeEqualTo 1L
            horse.delete()
            Horse.count() shouldBeEqualTo 0L
        }
    }
}
