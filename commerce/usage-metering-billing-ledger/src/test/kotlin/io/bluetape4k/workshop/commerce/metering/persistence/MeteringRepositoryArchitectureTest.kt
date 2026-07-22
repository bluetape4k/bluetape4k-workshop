package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MeteringRepositoryArchitectureTest {

    @Test
    fun `every concrete repository implements ExposedJdbcRepository`() {
        REPOSITORIES.all(ExposedJdbcRepository::class.java::isAssignableFrom).shouldBeTrue()
    }

    @Test
    fun `append only repositories reject generic deletion`() {
        APPEND_ONLY_REPOSITORIES.forEach { repository ->
            assertFailsWith<UnsupportedOperationException> { repository.deleteAll() }
        }
    }

    @Test
    fun `production and fixture persistence use Exposed without raw SQL`() {
        val violations =
            sequenceOf(mainSourceRoot(), testSourceRoot())
                .flatMap { root ->
                    Files.walk(root).use { paths ->
                        paths
                            .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                            .filter { it.fileName.toString() != "MeteringRepositoryArchitectureTest.kt" }
                            .filter { path -> RAW_SQL_PATTERNS.any { it.containsMatchIn(Files.readString(path)) } }
                            .map(root::relativize)
                            .toList()
                            .asSequence()
                    }
                }
                .sortedBy(Path::toString)
                .toList()

        violations.shouldBeEmpty()
    }

    private fun mainSourceRoot(): Path = sourceRoot("src/main/kotlin")

    private fun testSourceRoot(): Path = sourceRoot("src/test/kotlin")

    private fun sourceRoot(relative: String): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve(relative)
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/usage-metering-billing-ledger").resolve(relative)
    }

    companion object {
        private val REPOSITORIES =
            listOf(
                CommandReceiptRepository::class.java,
                MeterRepository::class.java,
                PricingScheduleRepository::class.java,
                PriceVersionRepository::class.java,
                UsageEventRepository::class.java,
                BillingCalendarRepository::class.java,
                BillingPeriodRepository::class.java,
                CloseRunRepository::class.java,
                LedgerEntryRepository::class.java,
                InvoiceRepository::class.java,
                InvoiceLineRepository::class.java,
                InvoiceLineEntryRepository::class.java,
                ReconciliationRunRepository::class.java,
                ReconciliationFindingRepository::class.java,
            )

        private val APPEND_ONLY_REPOSITORIES =
            listOf(
                PriceVersionRepository(),
                UsageEventRepository(),
                LedgerEntryRepository(),
                InvoiceRepository(),
                InvoiceLineRepository(),
                InvoiceLineEntryRepository(),
                ReconciliationFindingRepository(),
            )

        private val RAW_SQL_PATTERNS =
            listOf(
                Regex("JdbcTemplate"),
                Regex("PreparedStatement"),
                Regex("createStatement"),
                Regex("Transaction\\.exec"),
                Regex("\\bexec\\(\\s*\""),
                Regex("java\\.sql\\."),
                Regex("src/.*/db/migration"),
            )
    }
}
