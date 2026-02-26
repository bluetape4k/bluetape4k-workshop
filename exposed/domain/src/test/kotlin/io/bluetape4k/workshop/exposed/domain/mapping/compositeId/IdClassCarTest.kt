package io.bluetape4k.workshop.exposed.domain.mapping.compositeId

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.v1.core.dao.id.CompositeID
import org.jetbrains.exposed.v1.core.dao.id.CompositeIdTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.dao.CompositeEntity
import org.jetbrains.exposed.v1.dao.CompositeEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertIs

class IdClassCarTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * CompositeIdTable 를 사용하여 Entity 를 정의합니다.
     *
     * ```sql
     * CREATE TABLE IF NOT EXISTS car_table (
     *      car_brand VARCHAR(64),
     *      car_year INT,
     *      serial_no VARCHAR(32) NULL,
     *
     *      CONSTRAINT pk_car_table PRIMARY KEY (car_brand, car_year)
     * );
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
                brand.requireNotBlank("brand")
                carYear.requirePositiveNumber("carYear")

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

        val brand by CarTable.brand
        val carYear by CarTable.carYear
        var serialNo by CarTable.serialNo

        val carIdentifier get() = CarIdentifier(id.value)

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("serial no", serialNo)
            .toString()
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


            /**
             * ```sql
             * DELETE FROM CAR_TABLE
             *  WHERE (CAR_TABLE.CAR_BRAND = 'Fisher, Sipes and Kulas')
             *    AND (CAR_TABLE.CAR_YEAR = 2019)
             * ```
             */
            val deletedCount = CarTable.deleteWhere { CarTable.id eq compositeId }
            deletedCount shouldBeEqualTo 1
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `compoisite id with DSL`(testDB: TestDB) {
        withTables(testDB, CarTable) {

            val brand = faker.company().name()
            val carYear = faker.random().nextInt(1950, 2023)
            val serialNo = faker.random().nextLong().toString(32)

            val id = CarTable.insertAndGetId {
                it[CarTable.brand] = brand
                it[CarTable.carYear] = carYear
                it[CarTable.serialNo] = serialNo
            }
            entityCache.clear()

            val result = CarTable.selectAll().single()

            result[CarTable.serialNo] shouldBeEqualTo serialNo

            val idResult: EntityID<CompositeID> = result[CarTable.id]
            assertIs<EntityID<CompositeID>>(idResult)

            val resultBrand: EntityID<String> = idResult.value[CarTable.brand]
            val resultCarYear: EntityID<Int> = idResult.value[CarTable.carYear]

            resultBrand.value shouldBeEqualTo brand
            resultCarYear.value shouldBeEqualTo carYear


            // Query by CompositeID
            /**
             * ```sql
             * SELECT CAR_TABLE.CAR_BRAND,
             *        CAR_TABLE.CAR_YEAR
             *   FROM CAR_TABLE
             *  WHERE (CAR_TABLE.CAR_BRAND = ?)
             *    AND (CAR_TABLE.CAR_YEAR = ?)
             * ```
             */
            val dslQuery = CarTable.select(CarTable.id)
                .where { CarTable.id eq idResult }
                .prepareSQL(this, true)

            log.debug { "DSL Query: $dslQuery" }

            val entityId = EntityID(
                CompositeID {
                    it[CarTable.brand] = brand
                    it[CarTable.carYear] = carYear
                },
                CarTable
            )

            /**
             * CompositeID를 이용하여 Entity를 조회합니다.
             *
             * ```sql
             * SELECT CAR_TABLE.CAR_BRAND, CAR_TABLE.CAR_YEAR, CAR_TABLE.SERIAL_NO
             *   FROM CAR_TABLE
             *  WHERE (CAR_TABLE.CAR_BRAND = 'Ondricka and Sons')
             *    AND (CAR_TABLE.CAR_YEAR = 1978)
             * ```
             */
            CarTable.selectAll().where { CarTable.id eq entityId }.toList() shouldHaveSize 1

            /**
             * Composite ID의 부분 컬럼만 사용하여 조회합니다.
             */
            CarTable.selectAll()
                .where { CarTable.brand neq resultBrand }
                .count() shouldBeEqualTo 0L

        }
    }
}
