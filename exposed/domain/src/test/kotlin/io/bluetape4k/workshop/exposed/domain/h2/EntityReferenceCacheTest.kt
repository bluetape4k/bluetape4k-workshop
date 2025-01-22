package io.bluetape4k.workshop.exposed.domain.h2


import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.AbstractExposedTest
import io.bluetape4k.workshop.exposed.TestDB
import io.bluetape4k.workshop.exposed.TestDB.H2
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Cities
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.City
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.User
import io.bluetape4k.workshop.exposed.domain.demo.dao.SamplesDao.Users
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Board
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Boards
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Categories
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Post
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTest.Posts
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.BEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.XTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YEntity
import io.bluetape4k.workshop.exposed.domain.shared.entities.EntityTestData.YTable
import io.bluetape4k.workshop.exposed.domain.shared.entities.VNumber
import io.bluetape4k.workshop.exposed.domain.shared.entities.VString
import io.bluetape4k.workshop.exposed.domain.shared.entities.ViaTestData
import io.bluetape4k.workshop.exposed.withTables
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.load
import org.jetbrains.exposed.dao.with
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SizedCollection
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import kotlin.properties.Delegates
import kotlin.test.assertFails

class EntityReferenceCacheTest: AbstractExposedTest() {

    companion object: KLogging()

    private val db by lazy { TestDB.H2.connect() }

    private val dbWithCache by lazy {
        TestDB.H2.connect {
            /**
             * Turns on "mode" for Exposed DAO to store relations (after they were loaded) within the entity that will
             * allow access to them outside the transaction.
             * Useful when [eager loading](https://github.com/JetBrains/Exposed/wiki/DAO#eager-loading) is used.
             */
            keepLoadedReferencesOutOfTransaction = true
        }
    }

    private fun executeOnH2(vararg tables: Table, body: () -> Unit) {
        Assumptions.assumeTrue { TestDB.H2 in TestDB.enabledDialects() }
        var testWasStarted = false
        transaction(db) {
            SchemaUtils.create(*tables)
            testWasStarted = true
        }
        Assumptions.assumeTrue(testWasStarted)
        if (testWasStarted) {
            try {
                body()
            } finally {
                transaction(db) {
                    SchemaUtils.drop(*tables)
                }
            }
        }
    }

    @Test
    fun `referenceOn works out of transaction`() {
        var y1: YEntity by Delegates.notNull()
        var b1: BEntity by Delegates.notNull()

        executeOnH2(XTable, YTable) {
            transaction(db) {
                y1 = YEntity.new { this.x = true }
                b1 = BEntity.new {
                    this.b1 = true
                    this.y = y1
                }
            }
            assertFails { y1.b }
            assertFails { b1.y }

            transaction(dbWithCache) {
                y1.refresh()
                b1.refresh()
                y1.b?.id shouldBeEqualTo b1.id
                b1.y?.id shouldBeEqualTo y1.id
            }

            y1.b?.id shouldBeEqualTo b1.id
            b1.y?.id shouldBeEqualTo y1.id
        }
    }

    @Test
    fun `referenceOn works out of transaction via with`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()
                listOf(p1, p2).with(Post::board)
            }

            p1.board?.id shouldBeEqualTo b1.id
            p2.board?.id shouldBeEqualTo b1.id
        }
    }

    @Test
    fun `referrersOn works out of transaction`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()

                p1.board?.id shouldBeEqualTo b1.id
                p2.board?.id shouldBeEqualTo b1.id
                b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
            }

            p1.board?.id shouldBeEqualTo b1.id
            p2.board?.id shouldBeEqualTo b1.id
            b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
        }
    }

    @Test
    fun `optionalReferrersOn works out of transaction via warmup`() {
        var b1: Board by Delegates.notNull()
        var p1: Post by Delegates.notNull()
        var p2: Post by Delegates.notNull()

        executeOnH2(Boards, Posts, Categories) {
            transaction(db) {
                b1 = Board.new { this.name = "test-board" }
                p1 = Post.new { board = b1 }
                p2 = Post.new { board = b1 }
            }
            assertFails { b1.posts.toList() }
            assertFails { p1.board?.id }
            assertFails { p2.board?.id }

            transaction(dbWithCache) {
                b1.refresh()
                p1.refresh()
                p2.refresh()

                b1.load(Board::posts)
                b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
            }

            b1.posts.map { it.id } shouldBeEqualTo listOf(p1.id, p2.id)
        }
    }

    @Test
    fun `referrersOn work out of transaction via warmup`() {
        var c1: City by Delegates.notNull()
        var u1: User by Delegates.notNull()
        var u2: User by Delegates.notNull()

        executeOnH2(Cities, Users) {
            transaction(dbWithCache) {
                c1 = City.new { name = "test-city" }
                u1 = User.new {
                    name = "a"
                    city = c1
                    age = 5
                }
                u2 = User.new {
                    name = "b"
                    city = c1
                    age = 27
                }
                City.all().with(City::users).toList()
            }
            c1.users.map { it.id } shouldBeEqualTo listOf(u1.id, u2.id)
        }
    }

    @Test
    fun `via refreence out of transaction`() {
        var n: VNumber by Delegates.notNull()
        var s1: VString by Delegates.notNull()
        var s2: VString by Delegates.notNull()

        executeOnH2(*ViaTestData.allTables) {
            transaction(db) {
                n = VNumber.new { number = 10 }
                s1 = VString.new { text = "aaa" }
                s2 = VString.new { text = "bbb" }
                n.connectedStrings = SizedCollection(s1, s2)
            }

            assertFails { n.connectedStrings.toList() }
            transaction(dbWithCache) {
                n.refresh()
                s1.refresh()
                s2.refresh()
                n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id, s2.id)
            }
            n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id, s2.id)
        }
    }

    /**
     * ```sql
     * SELECT STRINGS.ID,
     *        STRINGS.TEXT,
     *        CONNECTION.ID,
     *        CONNECTION."stringId",
     *        CONNECTION."numId"
     *   FROM STRINGS INNER JOIN CONNECTION ON STRINGS.ID = CONNECTION."stringId"
     *  WHERE CONNECTION."numId" = '1efca978-f029-6c49-9384-d587ce40f191'
     * ```
     */
    @Test
    fun `via reference load out of transaction`() {
        var n: VNumber by Delegates.notNull()
        var s1: VString by Delegates.notNull()
        var s2: VString by Delegates.notNull()

        executeOnH2(*ViaTestData.allTables) {
            transaction(db) {
                n = VNumber.new { number = 10 }
                s1 = VString.new { text = "aaa" }
                s2 = VString.new { text = "bbb" }
                n.connectedStrings = SizedCollection(s1, s2)
            }

            assertFails { n.connectedStrings.toList() }
            transaction(dbWithCache) {
                n.refresh()
                s1.refresh()
                s2.refresh()
                n.load(VNumber::connectedStrings)
                n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id, s2.id)
            }
            n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id, s2.id)

            transaction(dbWithCache) {
                n.connectedStrings = SizedCollection(s1)
                n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id)

                n.load(VNumber::connectedStrings)
                n.connectedStrings.map { it.id } shouldBeEqualTo listOf(s1.id)
            }
        }
    }

    object Customers: IntIdTable() {
        val name = varchar("name", 10)
    }

    object Orders: IntIdTable() {
        val customer = reference("customer", Customers)
        val ref = varchar("name", 10)
    }

    object OrderItems: IntIdTable() {
        val order = reference("order", Orders)
        val sku = varchar("sku", 10)
    }

    object Addresses: IntIdTable() {
        val customer = reference("customer", Customers)
        val street = varchar("street", 10)
    }

    object Roles: IntIdTable() {
        val name = varchar("name", 10)
    }

    object CustomerRoles: IntIdTable() {
        val customer = reference("customer", Customers, onDelete = ReferenceOption.CASCADE)
        val role = reference("role", Roles, onDelete = ReferenceOption.CASCADE)
    }

    class Customer(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Customer>(Customers)

        var name by Customers.name
        val orders by Order referrersOn Orders.customer
        val addresses by Address referrersOn Addresses.customer
        val customerRoles by CustomerRole referrersOn CustomerRoles.customer
    }

    class Order(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Order>(Orders)

        var customer by Customer referencedOn Orders.customer
        var ref by Orders.ref
        val items by OrderItem referrersOn OrderItems.order
    }

    class OrderItem(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<OrderItem>(OrderItems)

        var order by Order referencedOn OrderItems.order
        var sku by OrderItems.sku
    }

    class Address(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Address>(Addresses)

        var customer by Customer referencedOn Addresses.customer
        var street by Addresses.street
    }

    class Role(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Role>(Roles)

        var name by Roles.name
    }

    class CustomerRole(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<CustomerRole>(CustomerRoles)

        var customer by Customer referencedOn CustomerRoles.customer
        var role by Role referencedOn CustomerRoles.role
    }

    @Test
    fun `dont flush indirectly related entities on insert`() {
        withTables(H2, Customers, Orders, OrderItems, Addresses) {
            val customer1 = Customer.new { name = "Test" }
            val order1 = Order.new {
                customer = customer1
                ref = "Test"
            }
            val orderItem1 = OrderItem.new {
                order = order1
                sku = "Test"
            }

            customer1.orders.toList() shouldBeEqualTo listOf(order1)
            customer1.addresses.toList().shouldBeEmpty()
            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldNotBeNull()
            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldNotBeNull()

            order1.items.toList() shouldHaveSize 1
            order1.items.single() shouldBeEqualTo orderItem1
            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldNotBeNull()

            Address.new {
                customer = customer1
                street = "Test"
            }
            flushCache()

            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldBeNull()
            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldNotBeNull()
            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldNotBeNull()

            val customer2 = Customer.new { name = "Test2" }

            flushCache()

            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldBeNull()
            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldNotBeNull()

            entityCache.getReferrers<Address>(customer2.id, Addresses.customer).shouldBeNull()
            entityCache.getReferrers<Order>(customer2.id, Orders.customer).shouldBeNull()

            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldNotBeNull()
        }
    }

    @Test
    fun `dont flush indirectly related entities on delete`() {
        withTables(H2, Customers, Orders, OrderItems, Addresses) {
            val customer1 = Customer.new { name = "Test" }
            val order1 = Order.new {
                customer = customer1
                ref = "Test"
            }

            val order2 = Order.new {
                customer = customer1
                ref = "Test2"
            }

            OrderItem.new {
                order = order1
                sku = "Test"
            }

            val orderItem2 = OrderItem.new {
                order = order2
                sku = "Test2"
            }

            Address.new {
                customer = customer1
                street = "Test"
            }

            flushCache()

            // Load caches
            customer1.orders.toList()
            customer1.addresses.toList()
            order1.items.toList()
            order2.items.toList()

            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldNotBeNull()
            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldNotBeNull()
            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldNotBeNull()
            entityCache.getReferrers<OrderItem>(order2.id, OrderItems.order).shouldNotBeNull()

            orderItem2.delete()

            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldNotBeNull()
            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldNotBeNull()
            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldBeNull()
            entityCache.getReferrers<OrderItem>(order2.id, OrderItems.order).shouldBeNull()

            // Load caches
            customer1.orders.toList()
            customer1.addresses.toList()
            order1.items.toList()
            order2.items.toList()

            order2.delete()
            entityCache.getReferrers<Order>(customer1.id, Orders.customer).shouldBeNull()
            entityCache.getReferrers<Address>(customer1.id, Addresses.customer).shouldNotBeNull()
            entityCache.getReferrers<OrderItem>(order1.id, OrderItems.order).shouldBeNull()
            entityCache.getReferrers<OrderItem>(order2.id, OrderItems.order).shouldBeNull()
        }
    }

    @Test
    fun `dont flush indirectly related entities with inner table`() {
        withTables(H2, Customers, Roles, CustomerRoles) {
            val customer1 = Customer.new { name = "Test" }
            val role1 = Role.new { name = "Test" }
            val customerRole1 = CustomerRole.new {
                customer = customer1
                role = role1
            }

            flushCache()
            customer1.customerRoles.toList() shouldBeEqualTo listOf(customerRole1)
            val role2 = Role.new { name = "Test2" }

            flushCache()
            entityCache.getReferrers<CustomerRole>(customer1.id, CustomerRoles.customer).shouldNotBeNull()

            val customerRole2 = CustomerRole.new {
                customer = customer1
                role = role2
            }
            flushCache()

            entityCache.getReferrers<Address>(customer1.id, CustomerRoles.customer).shouldBeNull()

            customer1.customerRoles.toList() shouldBeEqualTo listOf(customerRole1, customerRole2)
            entityCache.getReferrers<Address>(customer1.id, CustomerRoles.customer).shouldNotBeNull()

            role2.delete()
            entityCache.getReferrers<Address>(customer1.id, CustomerRoles.customer).shouldBeNull()
        }
    }
}
