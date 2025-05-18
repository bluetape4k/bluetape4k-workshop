package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.toStringBuilder
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.test.annotation.Commit
import org.springframework.transaction.annotation.Transactional
import java.util.*

class SpringTransactionEntityTest(
    @Autowired private val orderService: OrderService,
): SpringTransactionTestBase() {

    companion object: KLogging()

    @BeforeEach
    fun beforeEach() {
        orderService.init()
    }

    @AfterEach
    fun afterEach() {
        orderService.cleanUp()
    }

    @Test
    @Commit
    fun `create customer and order`() {
        val customer = orderService.createCustomer("Alice1")
        orderService.createOrder(customer, "Product1")
        val order = orderService.findOrderByProduct("Product1")
        order.shouldNotBeNull()

        orderService.transaction {
            order.customer.name shouldBeEqualTo "Alice1"
        }
    }

    @Test
    @Commit
    fun `create customer and order at both`() {
        orderService.doBoth("Bob", "Product2")
        val order = orderService.findOrderByProduct("Product2")
        order.shouldNotBeNull()
        orderService.transaction {
            log.debug { "order's customer=${order.customer}" }
            order.customer.name shouldBeEqualTo "Bob"
        }
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS CUSTOMER (
     *      ID uuid PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL
     * );
     *
     * ALTER TABLE CUSTOMER ADD CONSTRAINT CUSTOMER_NAME_UNIQUE UNIQUE ("name");
     * ```
     */
    object CustomerTable: UUIDTable(name = "customer") {
        val name = varchar(name = "name", length = 255).uniqueIndex()
    }

    class CustomerEntity(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<CustomerEntity>(CustomerTable)

        var name by CustomerTable.name

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = id.hashCode()
        override fun toString(): String = toStringBuilder()
            .add("name", name)
            .toString()
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS ORDERS (
     *      ID uuid PRIMARY KEY,
     *      CUSTOMER_ID uuid NOT NULL,
     *      PRODUCT VARCHAR(255) NOT NULL,
     *
     *      CONSTRAINT FK_ORDERS_CUSTOMER_ID__ID FOREIGN KEY (CUSTOMER_ID)
     *      REFERENCES CUSTOMER(ID) ON DELETE RESTRICT ON UPDATE RESTRICT
     * );
     * ```
     */
    object OrderTable: UUIDTable(name = "orders") {
        val customer = reference("customer_id", CustomerTable)
        val product = varchar(name = "product", length = 255)
    }

    class OrderEntity(id: EntityID<UUID>): UUIDEntity(id) {
        companion object: UUIDEntityClass<OrderEntity>(OrderTable)

        var customer: CustomerEntity by CustomerEntity referencedOn OrderTable.customer  // many-to-one
        var product: String by OrderTable.product

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = id.hashCode()
        override fun toString(): String = toStringBuilder()
            .add("customer", customer)
            .add("product", product)
            .toString()
    }

    /**
     * [TestConfig]에 의해 빈으로 등록된 [OrderService] 클래스
     */
    @Service
    @Transactional
    class OrderService {
        companion object: KLogging()

        fun init() {
            log.info { "Create schema. ${CustomerTable.tableName}, ${OrderTable.tableName}" }
            SchemaUtils.create(CustomerTable, OrderTable)
        }

        fun createCustomer(name: String): CustomerEntity {
            log.debug { "Create customer: $name" }
            return CustomerEntity.new {
                this.name = name
            }
        }

        fun createOrder(customer: CustomerEntity, product: String): OrderEntity {
            log.debug { "Create order: customer=$customer, product=$product" }
            return OrderEntity.new {
                this.customer = customer
                this.product = product
            }
        }

        fun doBoth(customerName: String, product: String): OrderEntity {
            return createOrder(createCustomer(customerName), product)
        }

        fun findOrderByProduct(product: String): OrderEntity? {
            log.debug { "Find order by product: $product" }
            return OrderEntity.find { OrderTable.product eq product }.singleOrNull()
        }

        fun transaction(block: () -> Unit) {
            block()
        }

        fun cleanUp() {
            log.info { "Drop schema. ${CustomerTable.tableName}, ${OrderTable.tableName}" }
            SchemaUtils.drop(CustomerTable, OrderTable)
        }
    }
}
