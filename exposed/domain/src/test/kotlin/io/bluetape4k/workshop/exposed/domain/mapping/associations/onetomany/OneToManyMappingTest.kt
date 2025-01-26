package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.Menu
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.MenuTable
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.Restaurant
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.RestaurantTable
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.with
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OneToManyMappingTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * INSERT INTO MENU ("name", PRICE, RESTAURANT_ID) VALUES ('Chicken', 10.0, 1)
     * INSERT INTO MENU ("name", PRICE, RESTAURANT_ID) VALUES ('Burger', 5.0, 1)
     * ```
     *
     * ```sql
     * SELECT RESTAURANT.ID, RESTAURANT."name" FROM RESTAURANT
     * SELECT MENU.ID, MENU."name", MENU.PRICE, MENU.RESTAURANT_ID FROM MENU WHERE MENU.RESTAURANT_ID = 1
     * ```
     *
     * 참고: [Eager Loaing](https://jetbrains.github.io/Exposed/dao-relationships.html#eager-loading)
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle one-to-many relationship`(testDb: TestDB) {
        withDb(testDb) { dialect ->
            withTables(dialect, RestaurantTable, MenuTable) {
                val kfc = Restaurant.new {
                    name = "KFC"
                }

                Menu.new {
                    name = "Chicken"
                    price = 10.0.toBigDecimal()
                    restaurant = kfc
                }

                Menu.new {
                    name = "Burger"
                    price = 5.0.toBigDecimal()
                    restaurant = kfc
                }

                entityCache.clear()

                // fetch earger loading `Menu` entities
                //
                val restaurants = Restaurant.all().with(Restaurant::menus).toList()
                val restaurant = restaurants.first()
                log.debug { "Restaurant: $restaurant" }

                restaurant.menus.count() shouldBeEqualTo 2
                restaurant.menus.forEach { menu ->
                    log.debug { ">> Menu: $menu" }
                }
            }
        }
    }
}
