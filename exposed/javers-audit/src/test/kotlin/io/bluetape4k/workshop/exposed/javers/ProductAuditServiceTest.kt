package io.bluetape4k.workshop.exposed.javers

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.tests.AbstractExposedTest
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javers.diff.changesByType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.exposed.javers.model.Product
import io.bluetape4k.workshop.exposed.javers.model.ProductTable
import io.bluetape4k.workshop.exposed.javers.service.ProductAuditService
import org.javers.core.JaversBuilder
import org.javers.core.diff.changetype.ValueChange
import org.javers.core.metamodel.`object`.SnapshotType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.math.BigDecimal

class ProductAuditServiceTest: AbstractExposedTest() {

    companion object: KLogging()

    private val javers get() = JaversBuilder.javers().build()

    private inline fun withProductAuditService(
        testDB: TestDB,
        crossinline statement: (ProductAuditService) -> Unit,
    ) {
        withTables(testDB, ProductTable) {
            statement(ProductAuditService(javers))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `save product creates a single initial snapshot`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")

            service.save("alice", product)

            val history = service.getHistory(1L).onEach { log.debug { "history=$it" } }
            log.debug { "history=$history" }
            history shouldHaveSize 1
            history.single().type shouldBeEqualTo SnapshotType.INITIAL
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `save product twice creates two snapshots with second as UPDATE`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val original = Product(id = 2L, name = "Gadget", price = BigDecimal("19.99"), category = "Electronics")
            val updated = original.copy(price = BigDecimal("24.99"))

            service.save("bob", original)
            service.save("bob", updated)

            val history = service.getHistory(2L).onEach { log.debug { "history=$it" } }
            history shouldHaveSize 2
            history[0].type shouldBeEqualTo SnapshotType.INITIAL
            history[1].type shouldBeEqualTo SnapshotType.UPDATE
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `diff detects price change between two versions`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val original = Product(
                id = 3L,
                name = "Sprocket",
                price = 5.00.toBigDecimal(),
                category = "Parts"
            )
            val updated = original.copy(price = 7.50.toBigDecimal())

            val diff = service.diff(original, updated)
            log.debug { "diff=$diff" }
            diff.hasChanges().shouldBeTrue()

            val priceChange = diff.changesByType<ValueChange>().firstOrNull { it.propertyName == "price" }
            log.debug { "priceChange=$priceChange" }
            priceChange.shouldNotBeNull()
            priceChange.left shouldBeEqualTo 5.00.toBigDecimal()
            priceChange.right shouldBeEqualTo 7.50.toBigDecimal()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `diff reports no changes when product is unchanged`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val product = Product(id = 4L, name = "Bolt", price = BigDecimal("0.99"), category = "Hardware")

            val diff = service.diff(
                product,
                product.copy()
            )

            log.debug { "diff=$diff" }
            diff.hasChanges().shouldBeFalse()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getLatestSnapshot returns null before any save`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            service.getLatestSnapshot(999L).shouldBeNull()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `getLatestSnapshot returns most recent state after multiple saves`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val v1 = Product(id = 5L, name = "Cog", price = 3.00.toBigDecimal(), category = "Parts")
            val v2 = v1.copy(category = "Mechanical Parts")
            val v3 = v2.copy(price = 3.50.toBigDecimal())

            service.save("carol", v1)
            service.save("carol", v2)
            service.save("carol", v3)

            val snapshot = service.getLatestSnapshot(5L).shouldNotBeNull()

            log.debug { "snapshot=$snapshot" }
            snapshot.getPropertyValue("category") shouldBeEqualTo "Mechanical Parts"
            snapshot.getPropertyValue("price") shouldBeEqualTo 3.50.toBigDecimal()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete records a TERMINAL snapshot`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val product = Product(id = 6L, name = "Pin", price = 0.10.toBigDecimal(), category = "Hardware")
            service.save("dave", product)

            service.delete("dave", product)

            val history = service.getHistory(6L)
            history shouldHaveSize 2
            history.last().type shouldBeEqualTo SnapshotType.TERMINAL
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete rejects missing product without creating terminal history`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val missing = Product(id = 66L, name = "Pin", price = "0.10".toBigDecimal(), category = "Hardware")

            assertFailsWith<IllegalArgumentException> {
                service.delete("dave", missing)
            }

            service.getHistory(66L).shouldBeEmpty()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `delete records terminal snapshot for current row instead of stale caller value`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            val saved = Product(id = 67L, name = "Pin", price = "0.10".toBigDecimal(), category = "Hardware")
            val stale = Product(id = 67L, name = "Stale Pin", price = BigDecimal("9.99"), category = "Stale")
            service.save("dave", saved)

            service.delete("dave", stale)

            val latest = service.getLatestSnapshot(67L).shouldNotBeNull()
            latest.type shouldBeEqualTo SnapshotType.TERMINAL
            transaction {
                ProductTable.selectAll()
                    .where { ProductTable.id eq 67L }
                    .count()
            } shouldBeEqualTo 0L
            assertFailsWith<IllegalArgumentException> {
                service.delete("dave", saved)
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `diff detects category change`(testDB: TestDB) = withProductAuditService(testDB) { service ->
        val original = Product(id = 7L, name = "Lever", price = 15.00.toBigDecimal(), category = "Mechanical")
        val updated = original.copy(category = "Industrial")

        val diff = service.diff(original, updated)

        log.debug { "diff=$diff" }
        diff.hasChanges().shouldBeTrue()

        val categoryChange = diff.changesByType<ValueChange>()
            .firstOrNull { it.propertyName == "category" }
            .shouldNotBeNull()

        log.debug { "categoryChange=$categoryChange" }
        categoryChange.left shouldBeEqualTo "Mechanical"
        categoryChange.right shouldBeEqualTo "Industrial"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `product factory rejects invalid values`(testDB: TestDB) {
        withProductAuditService(testDB) { _ ->
            assertFailsWith<IllegalArgumentException> {
                Product(id = 8L, name = "Washer", price = BigDecimal("-1.00"), category = "Hardware")
            }
            assertFailsWith<IllegalArgumentException> {
                Product(id = 8L, name = " ", price = BigDecimal("1.00"), category = "Hardware")
            }
            assertFailsWith<IllegalArgumentException> {
                Product(id = 8L, name = "Washer", price = BigDecimal("1.00"), category = "")
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `lookup rejects invalid product id`(testDB: TestDB) {
        withProductAuditService(testDB) { service ->
            assertFailsWith<IllegalArgumentException> {
                service.getHistory(0L)
            }

            assertFailsWith<IllegalArgumentException> {
                service.getLatestSnapshot(-1L)
            }
        }
    }
}
