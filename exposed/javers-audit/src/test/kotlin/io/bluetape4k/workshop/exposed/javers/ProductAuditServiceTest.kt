package io.bluetape4k.workshop.exposed.javers

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javers.diff.changesByType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.javers.model.Product
import io.bluetape4k.workshop.exposed.javers.model.ProductTable
import io.bluetape4k.workshop.exposed.javers.service.ProductAuditService
import org.javers.core.JaversBuilder
import org.javers.core.diff.changetype.ValueChange
import org.javers.core.metamodel.`object`.SnapshotType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductAuditServiceTest {

    companion object: KLogging()

    private val javers = JaversBuilder.javers().build()
    private lateinit var service: ProductAuditService

    @BeforeEach
    fun setUp() {
        Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(ProductTable)
        }
        service = ProductAuditService(javers)
    }

    @AfterEach
    fun tearDown() {
        transaction {
            SchemaUtils.drop(ProductTable)
        }
    }

    @Test
    fun `save product creates a single initial snapshot`() {
        val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")

        service.save("alice", product)

        val history = service.getHistory(1L)
        history shouldHaveSize 1
        history.first().type shouldBeEqualTo SnapshotType.INITIAL
    }

    @Test
    fun `save product twice creates two snapshots with second as UPDATE`() {
        val original = Product(id = 2L, name = "Gadget", price = BigDecimal("19.99"), category = "Electronics")
        val updated = original.copy(price = BigDecimal("24.99"))

        service.save("bob", original)
        service.save("bob", updated)

        val history = service.getHistory(2L)
        history shouldHaveSize 2
        history[0].type shouldBeEqualTo SnapshotType.INITIAL
        history[1].type shouldBeEqualTo SnapshotType.UPDATE
    }

    @Test
    fun `diff detects price change between two versions`() {
        val original = Product(id = 3L, name = "Sprocket", price = BigDecimal("5.00"), category = "Parts")
        val updated = original.copy(price = BigDecimal("7.50"))

        val diff = service.diff(original, updated)

        diff.hasChanges().shouldBeTrue()
        val priceChange = diff.changesByType<ValueChange>()
            .firstOrNull { it.propertyName == "price" }
        priceChange.shouldNotBeNull()
        priceChange.left shouldBeEqualTo BigDecimal("5.00")
        priceChange.right shouldBeEqualTo BigDecimal("7.50")
    }

    @Test
    fun `diff reports no changes when product is unchanged`() {
        val product = Product(id = 4L, name = "Bolt", price = BigDecimal("0.99"), category = "Hardware")

        val diff = service.diff(product, product.copy())

        diff.hasChanges().shouldBeFalse()
    }

    @Test
    fun `getLatestSnapshot returns null before any save`() {
        service.getLatestSnapshot(999L).shouldBeNull()
    }

    @Test
    fun `getLatestSnapshot returns most recent state after multiple saves`() {
        val v1 = Product(id = 5L, name = "Cog", price = BigDecimal("3.00"), category = "Parts")
        val v2 = v1.copy(category = "Mechanical Parts")
        val v3 = v2.copy(price = BigDecimal("3.50"))

        service.save("carol", v1)
        service.save("carol", v2)
        service.save("carol", v3)

        val snapshot = service.getLatestSnapshot(5L)
        snapshot.shouldNotBeNull()
        snapshot.getPropertyValue("category") shouldBeEqualTo "Mechanical Parts"
        snapshot.getPropertyValue("price") shouldBeEqualTo BigDecimal("3.50")
    }

    @Test
    fun `delete records a TERMINAL snapshot`() {
        val product = Product(id = 6L, name = "Pin", price = BigDecimal("0.10"), category = "Hardware")
        service.save("dave", product)

        service.delete("dave", product)

        val history = service.getHistory(6L)
        history shouldHaveSize 2
        history.last().type shouldBeEqualTo SnapshotType.TERMINAL
    }

    @Test
    fun `diff detects category change`() {
        val original = Product(id = 7L, name = "Lever", price = BigDecimal("15.00"), category = "Mechanical")
        val updated = original.copy(category = "Industrial")

        val diff = service.diff(original, updated)

        diff.hasChanges().shouldBeTrue()
        val categoryChange = diff.changesByType<ValueChange>()
            .firstOrNull { it.propertyName == "category" }
        categoryChange.shouldNotBeNull()
        categoryChange.left shouldBeEqualTo "Mechanical"
        categoryChange.right shouldBeEqualTo "Industrial"
    }
}
