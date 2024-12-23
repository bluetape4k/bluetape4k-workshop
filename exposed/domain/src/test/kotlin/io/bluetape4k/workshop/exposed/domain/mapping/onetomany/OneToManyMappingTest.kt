package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.domain.AbstractExposedDomainTest
import io.bluetape4k.workshop.exposed.domain.runWithTables
import org.jetbrains.exposed.dao.entityCache
import org.junit.jupiter.api.Test

class OneToManyMappingTest: AbstractExposedDomainTest() {

    companion object: KLogging()

    @Test
    fun `handle one-to-many relationship`() {
        runWithTables(RestaurantTable, MenuTable) {
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

            val restaurants = Restaurant.all().toList()
            val restaurant = restaurants.first()
            log.debug { "Restaurant: $restaurant" }

            val menus = restaurant.menus.toList()
            menus.forEach { menu ->
                log.debug { ">> Menu: $menu" }
            }
        }
    }
}
