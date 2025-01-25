package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import io.bluetape4k.ToStringBuilder
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
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.Serializable

class OneToManyMapTest: AbstractExposedTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with embeddable mapped by @CollectionTable`(testDB: TestDB) {
        withTables(testDB, CarTable, CarOptionTable, CarPartTable, CarPartMapTable) {
            val car = Car.new { name = "BMW" }
            val option1 = CarOption("i-Navi", 40)
            val option2 = CarOption("JBL", 60)
            val option3 = CarOption("Diamond Black Wheel", 128)

            car.addOption("Navigation", option1)
            car.addOption("Audio", option2)
            car.addOption("Wheel", option3)

            entityCache.clear()

            val loaded = Car.findById(car.id)!!
            loaded shouldBeEqualTo car
            car.options.values shouldContainSame listOf(option1, option2, option3)
            val options = car.options
            options["Navigation"] shouldBeEqualTo option1
            options["Audio"] shouldBeEqualTo option2
            options["Wheel"] shouldBeEqualTo option3

            // Remove Option
            car.removeOption("Audio")
            entityCache.clear()

            val loaded2 = Car.findById(car.id)!!
            loaded2 shouldBeEqualTo car
            loaded2.options.values shouldContainSame listOf(option1, option3)

            // Remove Car
            car.delete()
            entityCache.clear()

            CarTable.selectAll().count() shouldBeEqualTo 0L
            CarOptionTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many with entity mapped by @JoinTable`(testDB: TestDB) {
        withTables(testDB, CarTable, CarOptionTable, CarPartTable, CarPartMapTable) {
            val car = Car.new { name = "BMW" }
            val engine = CarPart.new { name = "Engine-B48" }
            val misson = CarPart.new { name = "Misson-ZF8" }
            val fueltank = CarPart.new { name = "FuelTank-60L" }

            car.addPart("engine", engine)
            car.addPart("mission", misson)
            car.addPart("fueltank", fueltank)

            entityCache.clear()

            val loaded = Car.findById(car.id)!!
            loaded shouldBeEqualTo car
            loaded.parts.values shouldContainSame listOf(engine, misson, fueltank)

            fueltank.delete()
            entityCache.clear()

            val loaded2 = Car.findById(car.id)!!
            loaded2 shouldBeEqualTo car
            loaded2.parts.values shouldContainSame listOf(engine, misson)

            car.delete()
            entityCache.clear()

            CarTable.selectAll().count() shouldBeEqualTo 0L
            CarPartMapTable.selectAll().count() shouldBeEqualTo 0L

            // `CarPart` 엔티티는 삭제되지 않는다.
            CarPartTable.selectAll().count() shouldBeEqualTo 2L
        }
    }

    object CarTable: IntIdTable("car") {
        val name = varchar("name", 255)
    }

    object CarOptionTable: Table("car_option") {
        val carId = reference("car_id", CarTable, onDelete = CASCADE, onUpdate = CASCADE)
        val optionKey = varchar("option_key", 255)
        val name = varchar("name", 255)
        val price = integer("price").default(0)

        init {
            uniqueIndex(carId, optionKey)
        }
    }

    object CarPartTable: IntIdTable("car_part") {
        val name = varchar("name", 255)
        val descriptin = text("description").nullable()
    }

    object CarPartMapTable: Table("car_part_map") {
        val carId = reference("car_id", CarTable, onDelete = CASCADE, onUpdate = CASCADE)
        val partKey = varchar("part_key", 255)

        val partId = reference("part_id", CarPartTable, onDelete = CASCADE, onUpdate = CASCADE)

        init {
            uniqueIndex(carId, partKey)
        }
    }

    class Car(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Car>(CarTable)

        var name by CarTable.name

        // OneToMany Map with MapKeyColumn (optionKey: CarOption)  (CarOption is Component)
        // JoinTable: CarOptionTable
        val options: Map<String, CarOption>
            get() = CarOptionTable.selectAll().where { CarOptionTable.carId eq this@Car.id }
                .associate { it[CarOptionTable.optionKey] to CarOption(it) }

        fun addOption(optionKey: String, option: CarOption) {
            CarOptionTable.insert {
                it[CarOptionTable.carId] = this@Car.id
                it[CarOptionTable.optionKey] = optionKey

                it[CarOptionTable.name] = option.name
                it[CarOptionTable.price] = option.price
            }
        }

        fun removeOption(optionKey: String): Int {
            return CarOptionTable.deleteWhere {
                (CarOptionTable.carId eq this@Car.id) and (CarOptionTable.optionKey eq optionKey)
            }
        }

        // OneToMany Map with MapKeyColumn (partKey: CarPart) (CarPart is Entity)
        // JoinTable: CarPartMapTable
        val parts: Map<String, CarPart>
            get() = CarPartMapTable.innerJoin(CarPartTable)
                .select(listOf(CarPartMapTable.partKey) + CarPartTable.columns)
                .where { CarPartMapTable.carId eq this@Car.id }
                .associate { it[CarPartMapTable.partKey] to CarPart.wrapRow(it) }

        fun addPart(partKey: String, part: CarPart) {
            CarPartMapTable.insert {
                it[CarPartMapTable.carId] = this@Car.id
                it[CarPartMapTable.partKey] = partKey
                it[CarPartMapTable.partId] = part.id
            }
        }

        fun removePart(partKey: String): Int {
            return CarPartMapTable.deleteWhere {
                (CarPartMapTable.carId eq this@Car.id) and (CarPartMapTable.partKey eq partKey)
            }
        }


        override fun equals(other: Any?): Boolean = other is Car && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: name.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this).add("name", name).toString()
        }
    }

    data class CarOption(
        val name: String,
        val price: Int = 0,
    ): Serializable {
        constructor(row: ResultRow): this(
            name = row[CarOptionTable.name], price = row[CarOptionTable.price]
        )
    }

    class CarPart(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<CarPart>(CarPartTable)

        var name by CarPartTable.name
        var description by CarPartTable.descriptin

        override fun equals(other: Any?): Boolean = other is CarPart && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: name.hashCode()
        override fun toString(): String {
            return ToStringBuilder(this).add("name", name).add("description", description).toString()
        }
    }
}
