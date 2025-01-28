package io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone

import io.bluetape4k.collections.size
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.Brewery
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.Jug
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.JugMeter
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.SalesForce
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.SalesGuy
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.withBeerTables
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.withJugTables
import io.bluetape4k.workshop.exposed.domain.mapping.associations.manytoone.ManyToOneSchema.withSalesTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ManyToOneTest: AbstractExposedTest() {

    /**
     *
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-one unidirectional`(testDB: TestDB) {
        withJugTables(testDB) { jugs, jugMeters ->
            val jug = Jug.findById(1)!!

            val emmanuel = JugMeter.findById(1)!!
            emmanuel.memberOf shouldBeEqualTo jug

            val jerome = JugMeter.findById(2)!!
            jerome.memberOf shouldBeEqualTo jug

            jugMeters.deleteAll()
            entityCache.clear()

            jugs.selectAll().count() shouldBeEqualTo 1
            JugMeter.all().count() shouldBeEqualTo 0

            jugs.deleteWhere { jugs.id eq 1 }
            Jug.all().count() shouldBeEqualTo 0
        }
    }

    /**
     * ```sql
     * SELECT BREWERY.ID, BREWERY."name" FROM BREWERY
     * SELECT BEER.ID, BEER."name", BEER.BREWERY_ID FROM BEER WHERE BEER.BREWERY_ID = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-one bidirectional lazy loading`(testDB: TestDB) {
        withBeerTables(testDB) { brewerys, beers ->
            val brewery1 = Brewery.findById(1)!!

            // Fetch lazy loading
            val loaded = Brewery.all().first()
            loaded shouldBeEqualTo brewery1
            loaded.beers shouldHaveSize 3
        }
    }

    /**
     * ```sql
     * SELECT BREWERY.ID, BREWERY."name" FROM BREWERY
     * SELECT BEER.ID, BEER."name", BEER.BREWERY_ID FROM BEER WHERE BEER.BREWERY_ID IN (1, 2)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-one bidirectional eager loading`(testDB: TestDB) {
        withBeerTables(testDB) { brewerys, beers ->
            val berlin = Brewery.findById(1)!!

            // fetch eager loading
            val loadedBrewerys = Brewery.all().with(Brewery::beers)
            val loaded = loadedBrewerys.first()
            loaded shouldBeEqualTo berlin
            loaded.beers shouldHaveSize 3

            loadedBrewerys.forEach {
                it.beers.size() shouldBeGreaterThan 0
            }
        }
    }

    /**
     * ```sql
     * DELETE FROM BEER WHERE BEER.ID = 1
     * SELECT BREWERY.ID, BREWERY."name" FROM BREWERY WHERE BREWERY.ID = 1
     * SELECT BEER.ID, BEER."name", BEER.BREWERY_ID FROM BEER WHERE BEER.BREWERY_ID = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-one bidirectional - remove details`(testDB: TestDB) {
        withBeerTables(testDB) { brewerys, beers ->
            val brewery = Brewery.findById(1)!!
            brewery.beers shouldHaveSize 3

            val beerToRemove = brewery.beers.first()
            beerToRemove.delete()

            entityCache.clear()
            Brewery.findById(1)!!.beers shouldHaveSize 2
        }
    }

    /**
     * ```sql
     * SELECT SALES_FORCES.ID,
     *        SALES_FORCES."name"
     *   FROM SALES_FORCES
     *  WHERE SALES_FORCES.ID = 1;
     * ```
     * ```sql
     * SELECT SALES_GUYS.ID,
     *        SALES_GUYS."name",
     *        SALES_GUYS.SALES_FORCE_ID
     *   FROM SALES_GUYS
     *  WHERE SALES_GUYS.SALES_FORCE_ID = 1
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `many-to-one bidirectional with cascade all`(testDB: TestDB) {
        withSalesTables(testDB) { salesForces, salesGuys ->
            val salesForce = SalesForce.new { name = "BMW Korea" }
            val salesGuy1 = SalesGuy.new { name = "debop"; this.salesForce = salesForce }
            val salesGuy2 = SalesGuy.new { name = "smith"; this.salesForce = salesForce }
            val salesGuy3 = SalesGuy.new { name = "james"; this.salesForce = salesForce }

            flushCache()
            entityCache.clear()

            val loaded = SalesForce.findById(salesForce.id)!!.load(SalesForce::salesGuys)
            loaded shouldBeEqualTo salesForce
            loaded.salesGuys.toList() shouldBeEqualTo listOf(salesGuy1, salesGuy2, salesGuy3)

            val guyToRemove = loaded.salesGuys.last()
            guyToRemove.delete()

            entityCache.clear()

            val loaded2 = SalesForce.findById(salesForce.id)!!.load(SalesForce::salesGuys)
            loaded2 shouldBeEqualTo salesForce
            loaded2.salesGuys.toList() shouldBeEqualTo listOf(salesGuy1, salesGuy2)
        }
    }
}
