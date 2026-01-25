package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone

import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.idValue
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SizedIterable

object ManyToOneSchema {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS brewery (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object BreweryTable: IntIdTable("brewery") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS beer (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      brewery_id INT NOT NULL,
     *
     *      CONSTRAINT fk_beer_brewery_id__id FOREIGN KEY (brewery_id)
     *      REFERENCES brewery(id) ON DELETE CASCADE ON UPDATE CASCADE
     * );
     * ```
     */
    object BeerTable: IntIdTable("beer") {
        val name = varchar("name", 255)
        val brewery = reference(
            "brewery_id",
            BreweryTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )  // many-to-one
    }

    class Brewery(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Brewery>(BreweryTable)

        var name by BreweryTable.name
        val beers: SizedIterable<Beer> by Beer referrersOn BeerTable.brewery   // one-to-many

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .toString()
    }

    class Beer(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Beer>(BeerTable)

        var name by BeerTable.name
        var brewery: Brewery by Brewery referencedOn BeerTable.brewery       // many-to-one

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .add("brewery id", brewery.idValue)
            .toString()
    }

    fun withBeerTables(
        testDB: TestDB,
        statement: JdbcTransaction.(brewerys: BreweryTable, beers: BeerTable) -> Unit,
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS jugs (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object JugTable: IntIdTable("jugs") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS jug_meters (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      jug_id INT NOT NULL,
     *
     *      CONSTRAINT fk_jug_meters_jug_id__id FOREIGN KEY (jug_id)
     *      REFERENCES jugs(id) ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object JugMeterTable: IntIdTable("jug_meters") {
        val name = varchar("name", 255)
        val jug = reference("jug_id", JugTable)
    }

    class Jug(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Jug>(JugTable)

        var name by JugTable.name

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .toString()
    }

    class JugMeter(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<JugMeter>(JugMeterTable)

        var name by JugMeterTable.name
        var memberOf: Jug by Jug referencedOn JugMeterTable.jug     // many-to-one

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .add("jug id", memberOf.idValue)
            .toString()
    }

    fun withJugTables(
        testDB: TestDB,
        statement: JdbcTransaction.(jugs: JugTable, jugMeters: JugMeterTable) -> Unit,
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

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS sales_forces (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object SalesForceTable: IntIdTable("sales_forces") {
        val name = varchar("name", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS sales_guys (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      sales_force_id INT NOT NULL,
     *
     *      CONSTRAINT fk_sales_guys_sales_force_id__id FOREIGN KEY (sales_force_id)
     *      REFERENCES sales_forces(id) ON DELETE CASCADE ON UPDATE CASCADE
     * )
     * ```
     */
    object SalesGuyTable: IntIdTable("sales_guys") {
        val name = varchar("name", 255)
        val salesForce = reference(
            "sales_force_id",
            SalesForceTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        )
    }

    class SalesForce(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SalesForce>(SalesForceTable)

        var name by SalesForceTable.name
        val salesGuys: SizedIterable<SalesGuy> by SalesGuy referrersOn SalesGuyTable.salesForce // one-to-many

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .toString()
    }

    class SalesGuy(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<SalesGuy>(SalesGuyTable)

        var name by SalesGuyTable.name
        var salesForce: SalesForce by SalesForce referencedOn SalesGuyTable.salesForce    // many-to-one

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String = entityToStringBuilder()
            .add("name", name)
            .add("salesForce id", salesForce.idValue)
            .toString()
    }

    fun withSalesTables(
        testDB: TestDB,
        statement: JdbcTransaction.(salesForces: SalesForceTable, salesGuys: SalesGuyTable) -> Unit,
    ) {
        withTables(testDB, SalesForceTable, SalesGuyTable) {
            statement(SalesForceTable, ManyToOneSchema.SalesGuyTable)
        }
    }

}
