package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone

import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.Transaction

object ManyToOneSchema {

    object BreweryTable: IntIdTable("brewery") {
        val name = varchar("name", 255)
    }

    object BeerTable: IntIdTable("beer") {
        val name = varchar("name", 255)
        val brewery = reference("brewery_id", BreweryTable, onDelete = CASCADE, onUpdate = CASCADE)  // many-to-one
    }

    class Brewery(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Brewery>(BreweryTable)

        var name by BreweryTable.name
        val beers: SizedIterable<Beer> by Beer referrersOn BeerTable.brewery   // one-to-many

        override fun equals(other: Any?): Boolean = other is Brewery && id._value == other.id._value

        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "Brewery(id=$id, name=$name)"
        }
    }

    class Beer(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Beer>(BeerTable)

        var name by BeerTable.name
        var brewery: Brewery by Brewery referencedOn BeerTable.brewery       // many-to-one

        override fun equals(other: Any?): Boolean = other is Beer && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "Beer(id=$id, name=$name)"
        }
    }

    fun withBeerTables(
        testDB: TestDB,
        statement: Transaction.(brewerys: BreweryTable, beers: BeerTable) -> Unit,
    ) {
        withTables(testDB, BreweryTable, BeerTable) {
            val brewery1 = Brewery.new { name = "Berlin" }
            val brewery2 = Brewery.new { name = "Munich" }

            val beer1 = Beer.new { name = "Normal"; this.brewery = brewery1 }
            val beer2 = Beer.new { name = "Special"; this.brewery = brewery1 }
            val beer3 = Beer.new { name = "Extra"; this.brewery = brewery1 }

            val beer4 = Beer.new { name = "Black"; this.brewery = brewery2 }
            val beer5 = Beer.new { name = "White"; this.brewery = brewery2 }

            commit()
            entityCache.clear()

            statement(BreweryTable, BeerTable)
        }
    }

    object JugTable: IntIdTable("jugs") {
        val name = varchar("name", 255)
    }

    object JugMeterTable: IntIdTable("jug_meters") {
        val name = varchar("name", 255)
        val jug = reference("jug_id", JugTable)
    }

    class Jug(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Jug>(JugTable)

        var name by JugTable.name

        override fun equals(other: Any?): Boolean = other is Jug && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "Jug(id=$id, name=$name)"
        }
    }

    class JugMeter(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<JugMeter>(JugMeterTable)

        var name by JugMeterTable.name
        var memberOf: Jug by Jug referencedOn JugMeterTable.jug     // many-to-one

        override fun equals(other: Any?): Boolean = other is JugMeter && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "JugMeter(id=$id, name=$name)"
        }
    }

    fun withJugTables(
        testDB: TestDB,
        statement: Transaction.(jugs: JugTable, jugMeters: JugMeterTable) -> Unit,
    ) {
        withTables(testDB, JugTable, JugMeterTable) {
            val jug = Jug.new { name = "Jug Summer camp" }
            val emmanuel = JugMeter.new { name = "Emmanuel Bernard"; this.memberOf = jug }
            val jerome = JugMeter.new { name = "Jerome"; this.memberOf = jug }

            commit()
            entityCache.clear()

            statement(JugTable, JugMeterTable)
        }
    }


    object SalesForceTable: IntIdTable("sales_forces") {
        val name = varchar("name", 255)
    }

    object SalesGuyTable: IntIdTable("sales_guys") {
        val name = varchar("name", 255)
        val salesForce = reference("sales_force_id", SalesForceTable, onDelete = CASCADE, onUpdate = CASCADE)
    }

    class SalesForce(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SalesForce>(SalesForceTable)

        var name by SalesForceTable.name
        val salesGuys: SizedIterable<SalesGuy> by SalesGuy referrersOn SalesGuyTable.salesForce // one-to-many

        override fun equals(other: Any?): Boolean = other is SalesForce && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "SalesForce(id=$id, name=$name)"
        }
    }

    class SalesGuy(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SalesGuy>(SalesGuyTable)

        var name by SalesGuyTable.name
        var salesForce: SalesForce by SalesForce referencedOn SalesGuyTable.salesForce    // many-to-one

        override fun equals(other: Any?): Boolean = other is SalesGuy && id._value == other.id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String {
            return "SalesGuy(id=$id, name=$name)"
        }
    }

    fun withSalesTables(
        testDB: TestDB,
        statement: Transaction.(salesForces: SalesForceTable, salesGuys: SalesGuyTable) -> Unit,
    ) {
        withTables(testDB, SalesForceTable, SalesGuyTable) {
            statement(SalesForceTable, SalesGuyTable)
        }
    }

}
