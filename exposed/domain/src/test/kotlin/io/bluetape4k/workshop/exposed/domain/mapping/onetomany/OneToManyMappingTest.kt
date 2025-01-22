package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.withDb
import io.bluetape4k.workshop.exposed.withTables
import org.jetbrains.exposed.dao.flushCache
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OneToManyMappingTest: AbstractExposedTest() {

    companion object: KLogging()

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

                flushCache()

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
}
