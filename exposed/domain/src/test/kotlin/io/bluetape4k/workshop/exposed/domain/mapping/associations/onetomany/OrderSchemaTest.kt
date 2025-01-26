package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.OrderSchema.Order
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.OrderSchema.OrderItem
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.OrderSchema.OrderItemTable
import io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany.OrderSchema.OrderTable
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.selectAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class OrderSchemaTest: AbstractExposedTest() {

    companion object: KLogging()

    private val ordersTables = arrayOf(OrderTable, OrderItemTable)

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many bidirectional`(testDB: TestDB) {
        withTables(testDB, *ordersTables) {
            val order1 = Order.new { no = "N-123" }
            val item1 = OrderItem.new { name = "Item 1"; order = order1 }
            val item2 = OrderItem.new { name = "Item 2";order = order1 }
            val item3 = OrderItem.new { name = "Item 3";order = order1 }

            flushCache()
            entityCache.clear()

            /**
             * Fetch lazy loading `OrderItem` entity
             * ```sql
             * SELECT ORDERS.ID, ORDERS."no" FROM ORDERS WHERE ORDERS.ID = 1
             * ```
             */
            val loaded = Order.findById(order1.id)!!

            loaded shouldBeEqualTo order1
            loaded.items.count() shouldBeEqualTo 3
            loaded.items.toList() shouldBeEqualTo listOf(item1, item2, item3)

            /**
             * Fetch eager loading `OrderItem` entity
             * ```sql
             * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID
             *   FROM ORDER_ITEMS
             *  WHERE ORDER_ITEMS.ORDER_ID = 1
             * ```
             */
            val loaded2 = Order.all().with(Order::items).single()
            loaded2 shouldBeEqualTo order1
            loaded2.items.count() shouldBeEqualTo 3
            loaded2.items.toList() shouldBeEqualTo listOf(item1, item2, item3)

            /**
             * join loading
             * ```sql
             * SELECT ORDERS.ID, ORDERS."no", ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDERS INNER JOIN ORDER_ITEMS ON ORDERS.ID = ORDER_ITEMS.ORDER_ID
             * ```
             */
            val query = (OrderTable innerJoin OrderItemTable).selectAll()
            val loaded3 = OrderItem.wrapRows(query).toList()
            loaded3 shouldHaveSize 3
            loaded3.all { it.order == order1 }.shouldBeTrue()
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `one-to-many bidirectional delete items`(testDB: TestDB) {
        withTables(testDB, *ordersTables) {
            val order1 = Order.new { no = "N-123" }
            val item1 = OrderItem.new { name = "Item 1"; order = order1 }
            val item2 = OrderItem.new { name = "Item 2";order = order1 }
            val item3 = OrderItem.new { name = "Item 3";order = order1 }

            flushCache()
            entityCache.clear()

            item1.delete()
            OrderItem.all().count() shouldBeEqualTo 2

            order1.items.count() shouldBeEqualTo 2

            order1.delete()
            OrderItem.all().count() shouldBeEqualTo 0
        }
    }

    /**
     * Fetch eager loading with pagination
     *
     * ```sql
     * SELECT ORDERS.ID, ORDERS."no" FROM ORDERS LIMIT 10
     * SELECT ORDER_ITEMS.ID,
     *        ORDER_ITEMS."name",
     *        ORDER_ITEMS.PRICE,
     *        ORDER_ITEMS.ORDER_ID
     *   FROM ORDER_ITEMS
     *  WHERE ORDER_ITEMS.ORDER_ID IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `fetch eager loading with pagination`(testDB: TestDB) {
        withTables(testDB, *ordersTables) {
            val order1 = Order.new { no = "N-123" }
            val item1 = OrderItem.new { name = "Item 1"; order = order1 }
            val item2 = OrderItem.new { name = "Item 2";order = order1 }
            val item3 = OrderItem.new { name = "Item 3";order = order1 }

            repeat(10) {
                val orderN = Order.new { no = "ORDER=$it" }
                OrderItem.new { name = "item1-$it"; order = orderN }
                OrderItem.new { name = "item2-$it"; order = orderN }
                OrderItem.new { name = "item3-$it"; order = orderN }
            }

            entityCache.clear()


            val loaded2 = Order.all().offset(0).limit(10).with(Order::items).toList()

            loaded2 shouldContain order1
            loaded2.first().items.toList() shouldBeEqualTo listOf(item1, item2, item3)
            loaded2.forEach {
                it.items shouldHaveSize 3
            }
        }
    }

    /**
     * Fetch lazy loading with pagination
     *
     * ```sql
     * SELECT ORDERS.ID, ORDERS."no" FROM ORDERS LIMIT 10
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 1
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 2
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 3
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 4
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 5
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 6
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 7
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 8
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 9
     * SELECT ORDER_ITEMS.ID, ORDER_ITEMS."name", ORDER_ITEMS.PRICE, ORDER_ITEMS.ORDER_ID FROM ORDER_ITEMS WHERE ORDER_ITEMS.ORDER_ID = 10
     * ```
     */
    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `fetch lazy loading with pagination`(testDB: TestDB) {
        withTables(testDB, *ordersTables) {
            val order1 = Order.new { no = "N-123" }
            val item1 = OrderItem.new { name = "Item 1"; order = order1 }
            val item2 = OrderItem.new { name = "Item 2";order = order1 }
            val item3 = OrderItem.new { name = "Item 3";order = order1 }

            repeat(10) {
                val orderN = Order.new { no = "ORDER=$it" }
                OrderItem.new { name = "item1-$it"; order = orderN }
                OrderItem.new { name = "item2-$it"; order = orderN }
                OrderItem.new { name = "item3-$it"; order = orderN }
            }

            entityCache.clear()


            val loaded2 = Order.all().offset(0).limit(10).toList()

            loaded2 shouldContain order1
            loaded2.first().items.toList() shouldBeEqualTo listOf(item1, item2, item3)
            loaded2.forEach {
                it.items shouldHaveSize 3
            }
        }
    }
}
