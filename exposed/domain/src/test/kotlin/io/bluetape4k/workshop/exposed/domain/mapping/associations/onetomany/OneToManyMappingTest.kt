package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.Menu
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.MenuTable
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.Restaurant
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.ResturantSchema.RestaurantTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.jetbrains.exposed.v1.dao.entityCache
import org.jetbrains.exposed.v1.dao.with
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OneToManyMappingTest: AbstractExposedTest() {

    companion object: KLogging()

    /**
     * ```sql
     * INSERT INTO menu ("name", price, restaurant_id) VALUES ('Chicken', 10.0, 1);
     * INSERT INTO menu ("name", price, restaurant_id) VALUES ('Burger', 5.0, 1);
     * ```
     * 참고: [Eager Loaing](https://jetbrains.github.io/Exposed/dao-relationships.html#eager-loading)
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `handle one-to-many relationship`(testDB: TestDB) {
        withTables(testDB, RestaurantTable, MenuTable) {
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

            /**
             * Earger loading `Menu` entities (`with(Restaurant::menus)`)
             *
             * ```sql
             * SELECT restaurant.id,
             *        restaurant."name"
             *   FROM restaurant;
             *
             * SELECT menu.id,
             *        menu."name",
             *        menu.price,
             *        menu.restaurant_id
             *   FROM menu
             *  WHERE menu.restaurant_id = 1;
             * ```
             */
            val restaurants = Restaurant.all().with(Restaurant::menus).toList()
            val restaurant = restaurants.first()
            log.debug { "Restaurant: $restaurant" }

            restaurant.menus.count() shouldBeEqualTo 2L
            restaurant.menus.forEach { menu ->
                log.debug { ">> Menu: $menu" }
            }
        }
    }
}
