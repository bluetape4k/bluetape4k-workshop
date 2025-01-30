package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetoone

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class CavalierHorseTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CAVALIER (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      HORSE_ID INT NULL,
     *
     *      CONSTRAINT FK_CAVALIER_HORSE_ID__ID FOREIGN KEY (HORSE_ID) REFERENCES HORSE(ID)
     *          ON DELETE RESTRICT ON UPDATE RESTRICT
     * )
     * ```
     */
    object Cavaliers: IntIdTable("cavalier") {
        val name = varchar("name", 255)
        val horseId = optReference("horse_id", Horses)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS HORSE (
     *      ID INT AUTO_INCREMENT PRIMARY KEY,
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

        override fun equals(other: Any?): Boolean = other is Cavalier && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Cavalier(id=$id, name=$name)"
    }

    class Horse(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Horse>(Horses)

        var name by Horses.name

        override fun equals(other: Any?): Boolean = other is Horse && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Horse(id=$id, name=$name)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unidirectional one-to-one, cavalier has ownership`(testDB: TestDB) {
        withTables(testDB, Cavaliers, Horses) {
            val horse = Horse.new {
                name = "적토마"
            }
            val cavalier = Cavalier.new {
                name = "관우"
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
