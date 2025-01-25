package io.bluetape4k.workshop.exposed.domain.mapping.onetoone

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class UnidirectionalOneToOneTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CAR (
     *      ID BIGINT AUTO_INCREMENT PRIMARY KEY,
     *      BRAND VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object CarTable: LongIdTable("car") {
        val brand = varchar("brand", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS WHEEL (
     *      CAR_ID BIGINT PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      DIAMETER DOUBLE PRECISION NULL,
     *
     *      CONSTRAINT FK_WHEEL_CAR_ID__ID FOREIGN KEY (CAR_ID) REFERENCES CAR(ID)
     *          ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object WheelTable: IdTable<Long>("wheel") {
        override val id = reference("car_id", CarTable, onDelete = CASCADE, onUpdate = CASCADE)
        val name = varchar("name", 255)
        val diameter = double("diameter").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    class Car(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Car>(CarTable)

        var brand by CarTable.brand
        val wheel by Wheel optionalBackReferencedOn WheelTable.id

        override fun equals(other: Any?): Boolean = other is Car && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Car(id=$id, brand=$brand)"
    }

    class Wheel(id: EntityID<Long>): LongEntity(id) {
        companion object: LongEntityClass<Wheel>(WheelTable)

        var name by WheelTable.name
        var diameter by WheelTable.diameter
        val car by Car referencedOn WheelTable.id

        override fun equals(other: Any?): Boolean = other is Wheel && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Wheel(id=$id, name=$name)"
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `unidirectional one to one with @MapsId`(testDB: TestDB) {
        withTables(testDB, CarTable, WheelTable) {
            val car = Car.new { brand = "BMW" }
            val wheel = Wheel.new(car.id.value) {
                name = "18-inch"
                diameter = 18.0
            }

            entityCache.clear()

            val wheel2 = Wheel.findById(wheel.id)!!
            wheel2 shouldBeEqualTo wheel
            wheel2.car shouldBeEqualTo car

            entityCache.clear()

            // Wheel 이 삭제되도, onwer 인 Car 는 삭제되지 않는다. (Car가 삭제되면 Wheel 도 삭제된다)
            wheel2.delete()
            Car.findById(car.id).shouldNotBeNull()
            car.wheel.shouldBeNull()

            entityCache.clear()

            car.delete()
            Car.count() shouldBeEqualTo 0L
            Wheel.all().count() shouldBeEqualTo 0L
        }
    }
}
