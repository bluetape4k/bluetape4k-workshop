package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.admission.internal.TicketAdmissionGrantRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.identity.IdentityAliasRepository
import io.bluetape4k.workshop.commerce.ticket.identity.IdentitySubjectRepository
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentOperationClaimRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.TicketPurchaseRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.RefundOperationRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.TicketRefundRepository
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.TicketSaleRepository
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectRepository
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class PersistenceArchitectureTest {
    @Test
    fun `all aggregate repositories implement bluetape ExposedJdbcRepository`() {
        val repositories = listOf(
            TicketInventoryRepository::class.java,
            TicketIdentityGuardRepository::class.java,
            TicketWaitingRoomRepository::class.java,
            TicketPaymentOperationRepository::class.java,
            IdentityAliasRepository::class.java,
            IdentitySubjectRepository::class.java,
            HttpIdempotencyRepository::class.java,
            TicketAdmissionGrantRepository::class.java,
            TicketSaleRepository::class.java,
            TicketPurchaseRepository::class.java,
            PaymentOperationClaimRepository::class.java,
            RefundOperationRepository::class.java,
            TicketRefundRepository::class.java,
            TicketEffectRepository::class.java,
        )

        repositories.all(ExposedJdbcRepository::class.java::isAssignableFrom).shouldBeTrue()
    }

    @Test
    fun `raw JDBC is isolated to schema migration`() {
        val sourceRoot = locateSourceRoot()
        val violations = Files.walk(sourceRoot).use { paths ->
            paths.filter { path: Path -> Files.isRegularFile(path) && path.toString().endsWith(".kt") }
                .filter { path: Path -> path.fileName.toString() != "TicketMigrationRunner.kt" }
                .filter { path: Path ->
                    val source = Files.readString(path)
                    RAW_JDBC_MARKERS.any(source::contains)
                }
                .map { path: Path -> sourceRoot.relativize(path) }
                .toList()
        }

        violations.shouldBeEmpty()
    }

    @Test
    fun `shared authority fixture seeds through Exposed`() {
        val fixture = Files.readString(
            locateTestSourceRoot().resolve(
                "io/bluetape4k/workshop/commerce/ticket/persistence/TicketDatabaseFixture.kt",
            ),
        )
        val seedAuthority = fixture
            .substringAfter("fun seedAuthority(")
            .substringBefore("\n    fun seedSale(")

        seedAuthority.contains("executor.transaction").shouldBeTrue()
        seedAuthority.contains("TicketSaleEntity.new").shouldBeTrue()
        seedAuthority.contains("INSERT INTO").shouldBeFalse()
        seedAuthority.contains("execute(").shouldBeFalse()
    }

    private fun locateSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/main/kotlin")
    }

    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }

    companion object {
        private val RAW_JDBC_MARKERS = listOf(
            ".prepareStatement(",
            ".createStatement(",
            "dataSource.connection",
            "DriverManager.getConnection(",
        )
    }
}
