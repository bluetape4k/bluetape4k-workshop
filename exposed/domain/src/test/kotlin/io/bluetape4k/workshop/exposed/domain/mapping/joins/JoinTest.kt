package io.bluetape4k.workshop.exposed.domain.mapping.joins

import com.impossibl.postgres.jdbc.PGSQLSimpleException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderRecord
import io.bluetape4k.workshop.exposed.domain.mapping.withOrdersTables
import io.bluetape4k.workshop.exposed.domain.mapping.withPersonsAndAddress
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.rightJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.union
import org.junit.jupiter.api.Nested
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

class JoinTest {

    companion object: KLogging()

    @Nested
    inner class SimpleJoinTest: AbstractExposedTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `single table join`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, details, _, _, _ ->
                val om = orders.alias("om")
                val od = details.alias("od")
                val rows = om.innerJoin(od) { om[orders.id] eq od[details.orderId] }
                    .select(
                        om[orders.id],
                        om[orders.orderDate],
                        od[details.lineNumber],
                        od[details.description],
                        od[details.quantity]
                    )
                    .orderBy(om[orders.id])
                    .toList()

                rows shouldHaveSize 3

                // Entity 로 만들려고 이럴 필요는 없을 것 같다
                val orderList = rows.map { OrderSchema.Order.wrapRow(it) }
                val loadedOrders = orderList.distinctBy { it.id.value }
                loadedOrders shouldHaveSize 2
                loadedOrders.forEach { order ->
                    order.details.shouldNotBeEmpty()
                }
            }
        }

        /**
         * Fetch eager loading by simple join
         *
         * ```sql
         * SELECT ORDERS.ID, ORDERS.ORDER_DATE FROM ORDERS
         * SELECT ORDER_DETAILS.ID,
         *        ORDER_DETAILS.ORDER_ID,
         *        ORDER_DETAILS.LINE_NUMBER,
         *        ORDER_DETAILS.DESCRIPTION,
         *        ORDER_DETAILS.QUANTITY
         *   FROM ORDER_DETAILS
         *  WHERE ORDER_DETAILS.ORDER_ID IN (1, 2)
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `fetch eager loading by simple join`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, details, _, _, _ ->
                val orderEntities = OrderSchema.Order
                    .all()
                    .with(OrderSchema.Order::details)
                    .toList()

                orderEntities shouldHaveSize 2
                orderEntities.forEach { order ->
                    // eager loading 했으므로 fetching 하지 않는다.
                    order.details.shouldNotBeEmpty()
                }
            }
        }

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `compound join 01`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, details, _, _, _ ->
                val rows = orders
                    .innerJoin(details) {
                        (orders.id eq details.orderId) and (orders.id eq details.orderId)  // 중복된 조건
                    }
                    .select(
                        orders.id,
                        orders.orderDate,
                        details.lineNumber,
                        details.description,
                        details.quantity
                    )
                    .orderBy(orders.id)
                    .toList()

                rows shouldHaveSize 3
            }
        }

        /**
         * ```sql
         * SELECT ORDERS.ID,
         *        ORDERS.ORDER_DATE,
         *        ORDER_LINES.LINE_NUMBER,
         *        ORDER_LINES.ITEM_ID,
         *        ORDER_LINES.QUANTITY,
         *        ITEMS.DESCRIPTION
         *   FROM ORDERS
         *          INNER JOIN ORDER_LINES ON (ORDER_LINES.ORDER_ID = ORDERS.ID)
         *          INNER JOIN ITEMS ON (ITEMS.ID = ORDER_LINES.ITEM_ID)
         *  WHERE ORDERS.ID = 2
         *  ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `multiple table join`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->
                val join = orders
                    .innerJoin(orderLines) { orderLines.orderId eq orders.id }
                    .innerJoin(items) { items.id eq orderLines.itemId }

                val rows = join.select(
                    orders.id,
                    orders.orderDate,
                    orderLines.lineNumber,
                    orderLines.itemId,
                    orderLines.quantity,
                    items.description
                )
                    .where { orders.id eq 2L }
                    .toList()

                rows.forEach { row ->
                    log.debug { row }
                }
                rows shouldHaveSize 2

                with(rows[0]) {
                    this[orders.id].value shouldBeEqualTo 2L
                    this[orderLines.lineNumber] shouldBeEqualTo 1
                    this[orderLines.itemId]?.value shouldBeEqualTo 22L
                }
                with(rows[1]) {
                    this[orders.id].value shouldBeEqualTo 2L
                    this[orderLines.lineNumber] shouldBeEqualTo 2
                    this[orderLines.itemId]?.value shouldBeEqualTo 44L
                }
            }
        }
    }

    /**
     * // NOTE: H2, MySQL 는 FULL JOIN 을 지원하지 않는다. LEFT JOIN 과 RIGHT JOIN 을 UNION 한다
     *
     * [mysql에서 full outer join 사용하기](https://wkdgusdn3.tistory.com/entry/mysql%EC%97%90%EC%84%9C-full-outer-join-%EC%82%AC%EC%9A%A9%ED%95%98%EA%B8%B0)
     */
    @Nested
    inner class FullJoinTest: AbstractExposedTest() {

        private val expected = listOf(
            OrderRecord(itemId = 55, orderId = null, quantity = null, description = "Catcher Glove"),
            OrderRecord(itemId = 22, orderId = 1, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 33, orderId = 1, quantity = 1, description = "First Base Glove"),
            OrderRecord(itemId = null, orderId = 2, quantity = 6, description = null),
            OrderRecord(itemId = 22, orderId = 2, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 44, orderId = 2, quantity = 1, description = "Outfield Glove")
        )

        /**
         *  ```sql
         *  SELECT om.ID order_id,
         *         ol.QUANTITY,
         *         im.ID item_id,
         *         im.DESCRIPTION
         *    FROM ORDERS om
         *          INNER JOIN ORDER_LINES ol ON (om.ID = ol.ORDER_ID)
         *          LEFT JOIN ITEMS im ON (ol.ITEM_ID = im.ID)
         *
         * UNION
         *
         * SELECT om.ID order_id,
         *        ol.QUANTITY,
         *        im.ID item_id,
         *        im.DESCRIPTION
         *   FROM ORDERS om
         *          INNER JOIN ORDER_LINES ol ON (om.ID = ol.ORDER_ID)
         *          RIGHT JOIN ITEMS im ON (ol.ITEM_ID = im.ID)
         *
         *  ORDER BY order_id ASC,
         *           item_id ASC
         *  ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `full join with aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.alias("om")
                val ol = orderLines.alias("ol")
                val im = items.alias("im")

                val orderIdAlias = om[orders.id].alias("order_id")
                val itemIdAlias = im[items.id].alias("item_id")

                val slice = listOf(
                    orderIdAlias,
                    ol[orderLines.quantity],
                    itemIdAlias,
                    im[items.description]
                )
                val join1 = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .leftJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val join2 = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .rightJoin(im) { ol[orderLines.itemId] eq im[items.id] }


                val records = join1.select(slice).union(join2.select(slice))
                    .orderBy(orderIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .orderBy(itemIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[itemIdAlias]?.value,
                            orderId = it[orderIdAlias]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT om.ID order_id,
         *        ol.QUANTITY,
         *        im.ID item_id,
         *        im.DESCRIPTION
         *   FROM (SELECT ORDERS.ID, ORDERS.ORDER_DATE FROM ORDERS) om
         *      INNER JOIN (SELECT ORDER_LINES.ID,
         *                         ORDER_LINES.ORDER_ID,
         *                         ORDER_LINES.ITEM_ID,
         *                         ORDER_LINES.LINE_NUMBER,
         *                         ORDER_LINES.QUANTITY
         *                    FROM ORDER_LINES) ol ON  (om.ID = ol.ORDER_ID)
         *      LEFT JOIN (SELECT ITEMS.ID,
         *                        ITEMS.DESCRIPTION
         *                   FROM ITEMS) im ON  (ol.ITEM_ID = im.ID)
         *
         * UNION
         *
         * SELECT om.ID order_id,
         *        ol.QUANTITY,
         *        im.ID item_id,
         *        im.DESCRIPTION
         *   FROM (SELECT ORDERS.ID, ORDERS.ORDER_DATE FROM ORDERS) om
         *      INNER JOIN (SELECT ORDER_LINES.ID,
         *                         ORDER_LINES.ORDER_ID,
         *                         ORDER_LINES.ITEM_ID,
         *                         ORDER_LINES.LINE_NUMBER,
         *                         ORDER_LINES.QUANTITY
         *                    FROM ORDER_LINES) ol ON (om.ID = ol.ORDER_ID)
         *      RIGHT JOIN (SELECT ITEMS.ID,
         *                         ITEMS.DESCRIPTION
         *                    FROM ITEMS) im ON (ol.ITEM_ID = im.ID)
         *
         * ORDER BY order_id ASC NULLS FIRST,
         *          item_id ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `full join with subquery`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.selectAll().alias("om")
                val ol = orderLines.selectAll().alias("ol")
                val im = items.selectAll().alias("im")

                // Ordering 을 위해 alias 를 사용한다
                val orderIdAlias = om[orders.id].alias("order_id")
                val itemIdAlias = im[items.id].alias("item_id")

                val slice = listOf(
                    orderIdAlias,
                    ol[orderLines.quantity],
                    itemIdAlias,
                    im[items.description]
                )

                val join1 = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .leftJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val join2 = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .rightJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val records = join1.select(slice).union(join2.select(slice))
                    .orderBy(orderIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .orderBy(itemIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[itemIdAlias]?.value,
                            orderId = it[orderIdAlias]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT ORDERS.ID order_id,
         *        ORDER_LINES.QUANTITY,
         *        ITEMS.ID item_id,
         *        ITEMS.DESCRIPTION
         *   FROM ORDERS
         *      INNER JOIN ORDER_LINES ON (ORDERS.ID = ORDER_LINES.ORDER_ID)
         *      LEFT JOIN ITEMS ON (ORDER_LINES.ITEM_ID = ITEMS.ID)
         *
         * UNION
         *
         * SELECT ORDERS.ID order_id,
         *        ORDER_LINES.QUANTITY,
         *        ITEMS.ID item_id,
         *        ITEMS.DESCRIPTION
         *   FROM ORDERS
         *      INNER JOIN ORDER_LINES ON (ORDERS.ID = ORDER_LINES.ORDER_ID)
         *      RIGHT JOIN ITEMS ON (ORDER_LINES.ITEM_ID = ITEMS.ID)
         *
         *  ORDER BY order_id ASC NULLS FIRST,
         *           item_id ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `full join without aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val join1 = orders
                    .innerJoin(orderLines) { orders.id eq orderLines.orderId }
                    .leftJoin(items) { orderLines.itemId eq items.id }

                val join2 = orders
                    .innerJoin(orderLines) { orders.id eq orderLines.orderId }
                    .rightJoin(items) { orderLines.itemId eq items.id }

                // Ordering 을 위해 alias 를 사용한다
                val orderIdAlias = orders.id.alias("order_id")
                val itemIdAlias = items.id.alias("item_id")


                val slice = listOf(
                    orderIdAlias,
                    orderLines.quantity,
                    itemIdAlias,
                    items.description
                )

                val records = join1
                    .select(slice)
                    .union(join2.select(slice))
                    .orderBy(orderIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .orderBy(itemIdAlias, SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[itemIdAlias]?.value,
                            orderId = it[orderIdAlias]?.value,
                            quantity = it[orderLines.quantity],
                            description = it[items.description]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }
    }

    @Nested
    inner class LeftJoinTest: AbstractExposedTest() {

        private val expected = listOf(
            OrderRecord(itemId = 22, orderId = 1, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 33, orderId = 1, quantity = 1, description = "First Base Glove"),
            OrderRecord(itemId = null, orderId = 2, quantity = 6, description = null),
            OrderRecord(itemId = 22, orderId = 2, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 44, orderId = 2, quantity = 1, description = "Outfield Glove")
        )

        /**
         * ```sql
         * SELECT om.ID,
         *        ol.QUANTITY,
         *        im.ID,
         *        im.DESCRIPTION
         *   FROM ORDERS om
         *      INNER JOIN ORDER_LINES ol ON (om.ID = ol.ORDER_ID)
         *      LEFT JOIN ITEMS im ON (ol.ITEM_ID = im.ID)
         *  ORDER BY om.ID ASC NULLS FIRST,
         *           im.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join with aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.alias("om")
                val ol = orderLines.alias("ol")
                val im = items.alias("im")

                val slice = listOf(
                    om[orders.id], // orderIdAlias,
                    ol[orderLines.quantity],
                    im[items.id], // itemIdAlias,
                    im[items.description]
                )

                val join = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .leftJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val records = join.select(slice)
                    .orderBy(om[orders.id], SortOrder.ASC_NULLS_FIRST)
                    .orderBy(im[items.id], SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[im[items.id]]?.value,
                            orderId = it[om[orders.id]]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT om.ID,
         *        ol.QUANTITY,
         *        im.ID,
         *        im.DESCRIPTION
         *   FROM (SELECT ORDERS.ID, ORDERS.ORDER_DATE FROM ORDERS) om
         *     INNER JOIN (SELECT ORDER_LINES.ORDER_ID,
         *                        ORDER_LINES.ITEM_ID,
         *                        ORDER_LINES.QUANTITY
         *                   FROM ORDER_LINES) ol ON (om.ID = ol.ORDER_ID)
         *     LEFT JOIN (SELECT ITEMS.ID, ITEMS.DESCRIPTION FROM ITEMS) im ON (ol.ITEM_ID = im.ID)
         * ORDER BY om.ID ASC NULLS FIRST,
         *          im.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join with subqueries`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.select(orders.id, orders.orderDate).alias("om")
                val ol = orderLines.select(orderLines.orderId, orderLines.itemId, orderLines.quantity).alias("ol")
                val im = items.select(items.id, items.description).alias("im")

                val slice = listOf(
                    om[orders.id], // orderIdAlias,
                    ol[orderLines.quantity],
                    im[items.id], // itemIdAlias,
                    im[items.description]
                )

                val join = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .leftJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val records = join.select(slice)
                    .orderBy(om[orders.id], SortOrder.ASC_NULLS_FIRST)
                    .orderBy(im[items.id], SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[im[items.id]]?.value,
                            orderId = it[om[orders.id]]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT ORDERS.ID,
         *        ORDER_LINES.QUANTITY,
         *        ITEMS.ID,
         *        ITEMS.DESCRIPTION
         *   FROM ORDERS
         *      INNER JOIN ORDER_LINES ON (ORDERS.ID = ORDER_LINES.ORDER_ID)
         *      LEFT JOIN ITEMS ON (ORDER_LINES.ITEM_ID = ITEMS.ID)
         * ORDER BY ORDERS.ID ASC NULLS FIRST,
         *          ITEMS.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join without aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val slice = listOf(
                    orders.id,
                    orderLines.quantity,
                    items.id,
                    items.description
                )

                val join = orders
                    .innerJoin(orderLines) { orders.id eq orderLines.orderId }
                    .leftJoin(items) { orderLines.itemId eq items.id }

                val records = join.select(slice)
                    .orderBy(orders.id, SortOrder.ASC_NULLS_FIRST)
                    .orderBy(items.id, SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[items.id]?.value,
                            orderId = it[orders.id]?.value,
                            quantity = it[orderLines.quantity],
                            description = it[items.description]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }
    }

    @Nested
    inner class RightJoinTest: AbstractExposedTest() {

        private val expected = listOf(
            OrderRecord(itemId = 55, orderId = null, quantity = null, description = "Catcher Glove"),
            OrderRecord(itemId = 22, orderId = 1, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 33, orderId = 1, quantity = 1, description = "First Base Glove"),
            OrderRecord(itemId = 22, orderId = 2, quantity = 1, description = "Helmet"),
            OrderRecord(itemId = 44, orderId = 2, quantity = 1, description = "Outfield Glove")
        )

        /**
         * ```sql
         * SELECT om.ID,
         *        ol.QUANTITY,
         *        im.ID,
         *        im.DESCRIPTION
         *   FROM ORDERS om
         *      INNER JOIN ORDER_LINES ol ON (om.ID = ol.ORDER_ID)
         *      RIGHT JOIN ITEMS im ON (ol.ITEM_ID = im.ID)
         *  ORDER BY om.ID ASC NULLS FIRST,
         *           im.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join with aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.alias("om")
                val ol = orderLines.alias("ol")
                val im = items.alias("im")

                val slice = listOf(
                    om[orders.id], // orderIdAlias,
                    ol[orderLines.quantity],
                    im[items.id], // itemIdAlias,
                    im[items.description]
                )

                val join = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .rightJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val records = join.select(slice)
                    .orderBy(om[orders.id], SortOrder.ASC_NULLS_FIRST)
                    .orderBy(im[items.id], SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[im[items.id]]?.value,
                            orderId = it[om[orders.id]]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT om.ID,
         *        ol.QUANTITY,
         *        im.ID,
         *        im.DESCRIPTION
         *   FROM (SELECT ORDERS.ID, ORDERS.ORDER_DATE FROM ORDERS) om
         *     INNER JOIN (SELECT ORDER_LINES.ORDER_ID,
         *                        ORDER_LINES.ITEM_ID,
         *                        ORDER_LINES.QUANTITY
         *                   FROM ORDER_LINES) ol ON (om.ID = ol.ORDER_ID)
         *     RIGHT JOIN (SELECT ITEMS.ID, ITEMS.DESCRIPTION FROM ITEMS) im ON (ol.ITEM_ID = im.ID)
         * ORDER BY om.ID ASC NULLS FIRST,
         *          im.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join with subqueries`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val om = orders.select(orders.id, orders.orderDate).alias("om")
                val ol = orderLines.select(orderLines.orderId, orderLines.itemId, orderLines.quantity).alias("ol")
                val im = items.select(items.id, items.description).alias("im")

                val slice = listOf(
                    om[orders.id], // orderIdAlias,
                    ol[orderLines.quantity],
                    im[items.id], // itemIdAlias,
                    im[items.description]
                )

                val join = om
                    .innerJoin(ol) { om[orders.id] eq ol[orderLines.orderId] }
                    .rightJoin(im) { ol[orderLines.itemId] eq im[items.id] }

                val records = join.select(slice)
                    .orderBy(om[orders.id], SortOrder.ASC_NULLS_FIRST)
                    .orderBy(im[items.id], SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[im[items.id]]?.value,
                            orderId = it[om[orders.id]]?.value,
                            quantity = it[ol[orderLines.quantity]],
                            description = it[im[items.description]]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }

        /**
         * ```sql
         * SELECT ORDERS.ID,
         *        ORDER_LINES.QUANTITY,
         *        ITEMS.ID,
         *        ITEMS.DESCRIPTION
         *   FROM ORDERS
         *      INNER JOIN ORDER_LINES ON (ORDERS.ID = ORDER_LINES.ORDER_ID)
         *      RIGHT JOIN ITEMS ON (ORDER_LINES.ITEM_ID = ITEMS.ID)
         * ORDER BY ORDERS.ID ASC NULLS FIRST,
         *          ITEMS.ID ASC NULLS FIRST
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `left join without aliases`(testDB: TestDB) {
            withOrdersTables(testDB) { orders, _, items, orderLines, _ ->

                val slice = listOf(
                    orders.id,
                    orderLines.quantity,
                    items.id,
                    items.description
                )

                val join = orders
                    .innerJoin(orderLines) { orders.id eq orderLines.orderId }
                    .rightJoin(items) { orderLines.itemId eq items.id }

                val records = join.select(slice)
                    .orderBy(orders.id, SortOrder.ASC_NULLS_FIRST)
                    .orderBy(items.id, SortOrder.ASC_NULLS_FIRST)
                    .map {
                        OrderRecord(
                            itemId = it[items.id]?.value,
                            orderId = it[orders.id]?.value,
                            quantity = it[orderLines.quantity],
                            description = it[items.description]
                        )
                    }

                records.forEach {
                    log.debug { it }
                }

                records shouldHaveSize expected.size
                records shouldBeEqualTo expected
            }
        }
    }

    @Nested
    inner class SelfJoinTest: AbstractExposedTest() {

        private fun assertUsers(id: Long, userName: String?, parentId: Long? = null) {
            id shouldBeEqualTo 2L
            userName shouldBeEqualTo "Barney"
            parentId shouldBeEqualTo null
        }

        /**
         * ```sql
         * SELECT u1.ID, u1.USER_NAME, u1.PARENT_ID
         *   FROM USERS u1 INNER JOIN USERS u2 ON (u1.ID = u2.PARENT_ID)
         *  WHERE u2.ID = 4
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `self join`(testDB: TestDB) {
            withOrdersTables(testDB) { _, _, _, _, users ->
                val u1 = users.alias("u1")
                val u2 = users.alias("u2")

                val join = u1
                    .innerJoin(u2) { u1[users.id] eq u2[users.parentId] }

                val rows = join
                    .select(
                        u1[users.id],
                        u1[users.userName],
                        u1[users.parentId],
                    )
                    .where { u2[users.id] eq 4L }
                    .toList()

                rows shouldHaveSize 1
                assertUsers(
                    rows[0][u1[users.id]].value,
                    rows[0][u1[users.userName]],
                    rows[0][u1[users.parentId]]?.value
                )
            }
        }

        /**
         * ```sql
         * SELECT USERS.ID, USERS.USER_NAME, USERS.PARENT_ID
         *   FROM USERS INNER JOIN USERS u2 ON (USERS.ID = u2.PARENT_ID)
         *  WHERE u2.ID = 4
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `self join with new alias`(testDB: TestDB) {
            withOrdersTables(testDB) { _, _, _, _, users ->
                val u2 = users.alias("u2")

                val join = users.innerJoin(u2) { users.id eq u2[users.parentId] }

                val rows = join
                    .select(
                        users.id,
                        users.userName,
                        users.parentId,
                    )
                    .where { u2[users.id] eq 4L }
                    .toList()

                rows shouldHaveSize 1
                assertUsers(
                    rows[0][users.id].value,
                    rows[0][users.userName],
                    rows[0][users.parentId]?.value
                )
            }
        }
    }

    @Nested
    inner class CoveringIndexTest: AbstractExposedTest() {

        /**
         * ```sql
         * SELECT PERSONS.ID,
         *        PERSONS.FIRST_NAME,
         *        PERSONS.LAST_NAME,
         *        PERSONS.BIRTH_DATE,
         *        PERSONS.EMPLOYEED,
         *        PERSONS.OCCUPATION,
         *        PERSONS.ADDRESS_ID,
         *        p2.ID
         *   FROM PERSONS
         *      INNER JOIN (SELECT PERSONS.ID
         *                    FROM PERSONS
         *                   WHERE PERSONS.ADDRESS_ID = 2
         *                  ) p2 ON  (PERSONS.ID = p2.ID)
         *  WHERE PERSONS.ID < 5
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `covering index`(testDB: TestDB) {
            withPersonsAndAddress(testDB) { persons, addresses ->
                val p2 = persons.select(persons.id).where { persons.addressId eq 2L }.alias("p2")

                val rows = persons.innerJoin(p2) { persons.id eq p2[persons.id] }
                    .selectAll()
                    .where { persons.id less 5L }
                    .toList()

                rows shouldHaveSize 1
                rows.first()[persons.id].value shouldBeEqualTo 4L
            }
        }

        /**
         * ```sql
         * SELECT PERSONS.ID,
         *        PERSONS.FIRST_NAME,
         *        PERSONS.LAST_NAME,
         *        PERSONS.BIRTH_DATE,
         *        PERSONS.EMPLOYEED,
         *        PERSONS.OCCUPATION,
         *        PERSONS.ADDRESS_ID,
         *        p2.ID
         *   FROM PERSONS
         *      INNER JOIN (SELECT PERSONS.ID
         *                    FROM PERSONS
         *                   WHERE PERSONS.ADDRESS_ID = 2
         *                   ORDER BY PERSONS.ID
         *                  ) p2 ON  (PERSONS.ID = p2.ID)
         *  WHERE PERSONS.ID < 5
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `subquery in join`(testDB: TestDB) {
            withPersonsAndAddress(testDB) { persons, addresses ->
                val p2 = persons.select(persons.id)
                    .where { persons.addressId eq 2L }
                    .orderBy(persons.id)
                    .alias("p2")

                val rows = persons.innerJoin(p2) { persons.id eq p2[persons.id] }
                    .selectAll()
                    .where { persons.id less 5L }
                    .toList()

                rows shouldHaveSize 1
                rows.first()[persons.id].value shouldBeEqualTo 4L
            }
        }
    }

    @Nested
    inner class MiscJoinTest: AbstractExposedTest() {

        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `join with no condition`(testDB: TestDB) {
            withOrdersTables(testDB) { _, _, _, _, users ->
                val u2 = users.alias("u2")
                val u3 = users.alias("u3")

                // u3 는 조인 조건이 없다
                val query = users.innerJoin(u2) { users.id eq u3[users.id] }
                    .selectAll()
                    .where { u2[users.id] eq 4L }

                if (testDB == TestDB.POSTGRESQLNG) {
                    assertFailsWith<PGSQLSimpleException> {
                        query.toList()
                    }
                } else {
                    assertFailsWith<ExposedSQLException> {
                        query.toList()
                    }
                }
            }
        }

        /**
         * ```sql
         * SELECT ol1.ORDER_ID, ol1.LINE_NUMBER
         *   FROM ORDER_LINES ol1
         *  WHERE ol1.LINE_NUMBER IN (
         *                  SELECT MAX(ol2.LINE_NUMBER)
         *                    FROM ORDER_LINES ol2
         *                   WHERE ol2.ORDER_ID = ol1.ORDER_ID
         *                   -- GROUP BY ol2.ORDER_ID
         *       )
         *  ORDER BY ol1.ID ASC
         * ```
         */
        @ParameterizedTest
        @MethodSource(ENABLE_DIALECTS_METHOD)
        fun `aliases propagate to subquery condition`(testDB: TestDB) {
            withOrdersTables(testDB) { _, _, _, orderLines, _ ->
                val ol1 = orderLines.alias("ol1")
                val ol2 = orderLines.alias("ol2")

                val rows = ol1.select(ol1[orderLines.orderId], ol1[orderLines.lineNumber])
                    .where {
                        ol1[orderLines.lineNumber] inSubQuery
                                ol2.select(ol2[orderLines.lineNumber].max())
                                    .where { ol2[orderLines.orderId] eq ol1[orderLines.orderId] }
                        //.groupBy(ol2[orderLines.orderId])

                    }
                    .orderBy(ol1[orderLines.id])
                    .toList()

                rows shouldHaveSize 2
            }
        }
    }
}
