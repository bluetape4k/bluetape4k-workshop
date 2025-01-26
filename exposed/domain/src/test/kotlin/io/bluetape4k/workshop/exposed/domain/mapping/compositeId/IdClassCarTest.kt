package io.bluetape4k.workshop.exposed.domain.mapping.compositeId

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class IdClassCarTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * CompositeIdTable 를 사용하여 Entity 를 정의합니다.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS CAR_TABLE (
     *      CAR_BRAND VARCHAR(64),
     *      CAR_YEAR INT,
     *      SERIAL_NO VARCHAR(32) NULL,
     *
     *      CONSTRAINT pk_car_table PRIMARY KEY (CAR_BRAND, CAR_YEAR)
     * )
     * ```
     */
    object CarTable: CompositeIdTable("car_table") {
        val brand = varchar("car_brand", 64).entityId()
        val carYear = integer("car_year").entityId()
        val serialNo = varchar("serial_no", 32).nullable()

        override val primaryKey = PrimaryKey(brand, carYear)
    }

    class IdClassCar(id: EntityID<CompositeID>): CompositeEntity(id) {
        companion object: CompositeEntityClass<IdClassCar>(CarTable) {
            /**
             * CompositeID를 이용한 Entity를 생성합니다.
             */
            fun new(brand: String, carYear: Int, init: IdClassCar.() -> Unit): IdClassCar {
                val compositeId = carCompositeIdOf(brand, carYear)
                return new(compositeId, init)
            }

            fun carCompositeIdOf(brand: String, carYear: Int): CompositeID {
                return CompositeID {
                    it[CarTable.brand] = brand
                    it[CarTable.carYear] = carYear
                }
            }
        }

        var brand by CarTable.brand
        var carYear by CarTable.carYear
        var serialNo by CarTable.serialNo

        val carIdentifier get() = CarIdentifier(id.value)

        override fun equals(other: Any?): Boolean =
            other is IdClassCar && id._value == other.id._value

        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "IdClassCar(brand=$brand, carYear=$carYear, serialNo=$serialNo)"
    }

    data class CarIdentifier(val compositeId: CompositeID): EntityID<CompositeID>(CarTable, compositeId) {
        val brand: String get() = compositeId[CarTable.brand].value
        val carYear: Int get() = compositeId[CarTable.carYear].value
    }

    fun newIdClassCar(): IdClassCar {
        val brand = faker.company().name()
        val carYear = faker.random().nextInt(1950, 2023)

        return IdClassCar.new(brand, carYear) {
            serialNo = faker.random().nextLong().toString(32)
        }
    }

    /**
     * CompositeID 를 사용하여 EntityID 를 생성하고 읽습니다.
     *
     * ```sql
     * INSERT INTO CAR_TABLE (CAR_BRAND, CAR_YEAR, SERIAL_NO)
     * VALUES ('Mayer and Sons', 1968, '3up3bvhomrafo')
     * ```
     *
     * ```sql
     * SELECT CAR_TABLE.CAR_BRAND, CAR_TABLE.CAR_YEAR, CAR_TABLE.SERIAL_NO
     *   FROM CAR_TABLE
     *  WHERE (CAR_TABLE.CAR_BRAND = 'Mayer and Sons')
     *    AND (CAR_TABLE.CAR_YEAR = 1968)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `composite id entity`(testDB: TestDB) {
        withTables(testDB, CarTable) {
            /**
             * CompositeID 를 사용하여 EntityID 를 생성합니다.
             */
            val compositeId = CompositeID {
                it[CarTable.brand] = faker.company().name()
                it[CarTable.carYear] = faker.random().nextInt(1950, 2023)
            }
            val car1 = IdClassCar.new(compositeId) {
                serialNo = faker.random().nextLong().toString(32)
            }

            entityCache.clear()

            val loaded1 = IdClassCar.findById(car1.carIdentifier)!!
            loaded1 shouldBeEqualTo car1
            loaded1.carIdentifier shouldBeEqualTo car1.carIdentifier

            /**
             * 새로운 new 함수를 사용하여 CompositeID를 가진 Entity 를 생성합니다.
             */
            val brand = faker.company().name()
            val carYear = faker.random().nextInt(1950, 2023)
            val car2 = IdClassCar.new(brand, carYear) {
                serialNo = faker.random().nextLong().toString(32)
            }

            entityCache.clear()

            val loaded2 = IdClassCar.findById(car2.carIdentifier)!!
            loaded2 shouldBeEqualTo car2
            loaded2.carIdentifier shouldBeEqualTo CarIdentifier(car2.id.value)

            val allCars = IdClassCar.all().toList()
            allCars.forEach { log.debug { it } }
            allCars shouldHaveSize 2


            val searched1 = IdClassCar.find { CarTable.brand eq car1.brand }.single()
            searched1 shouldBeEqualTo car1
        }
    }
}
