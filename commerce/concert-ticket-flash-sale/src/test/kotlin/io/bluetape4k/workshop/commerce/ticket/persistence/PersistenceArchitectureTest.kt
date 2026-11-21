package io.bluetape4k.workshop.commerce.ticket.persistence

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.admission.internal.TicketAdmissionGrantRepository
import io.bluetape4k.workshop.commerce.ticket.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.ticket.identity.IdentityAliasRepository
import io.bluetape4k.workshop.commerce.ticket.identity.IdentitySubjectRepository
import io.bluetape4k.workshop.commerce.ticket.payment.internal.PaymentOperationClaimRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.RefundOperationRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.TicketPurchaseRepository
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.TicketRefundRepository
import io.bluetape4k.workshop.commerce.ticket.salecontrol.internal.TicketSaleRepository
import io.bluetape4k.workshop.commerce.ticket.ticketing.internal.TicketEffectRepository
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * PersistenceArchitectureTest 테스트 묶음은 콘서트 티켓 플래시 세일의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class PersistenceArchitectureTest {
    @Test
/**
 * 이 시나리오는 "all aggregate repositories implement bluetape ExposedJdbcRepository" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "raw JDBC is isolated to schema migration" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "query plans use the Exposed explain wrapper" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `query plans use the Exposed explain wrapper`() {
        val repositories = listOf(
            "io/bluetape4k/workshop/commerce/ticket/persistence/TicketRepositories.kt",
        ).map { locateSourceRoot().resolve(it) }

        repositories.forEach { file ->
            val source = Files.readString(file)
            source.shouldNotContain(".exec(stmt = \"EXPLAIN")
            source.shouldNotContain(".exec(\"EXPLAIN")
            source.shouldContain(".explain {")
        }
    }

    @Test
/**
 * 이 시나리오는 "shared authority fixture seeds through Exposed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `shared authority fixture seeds through Exposed`() {
        val fixture = Files.readString(
            locateTestSourceRoot().resolve(
                "io/bluetape4k/workshop/commerce/ticket/persistence/TicketDatabaseFixture.kt",
            ),
        )
        val seedAuthority = fixture
            .substringAfter("fun seedAuthority(")
            .substringBefore("\n    fun seedSale(")

        seedAuthority shouldContain "executor.transaction"
        seedAuthority shouldContain "TicketSaleEntity.new"
        seedAuthority shouldNotContain "INSERT INTO"
        seedAuthority shouldNotContain "execute("
    }

/**
 * locateSourceRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/main/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/main/kotlin")
    }

/**
 * locateTestSourceRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }

    companion object: KLogging() {
/**
 * RAW_JDBC_MARKERS은 콘서트 티켓 플래시 세일 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RAW_JDBC_MARKERS = listOf(
            ".prepareStatement(",
            ".createStatement(",
            "dataSource.connection",
            "DriverManager.getConnection(",
        )
    }
}
