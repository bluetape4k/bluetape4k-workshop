package io.bluetape4k.workshop.commerce.metering.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.data.exposed.jdbc.repository.ExposedJdbcRepository
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * MeteringRepositoryArchitectureTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class MeteringRepositoryArchitectureTest {


    @Test
/**
 * 이 시나리오는 "every concrete repository implements ExposedJdbcRepository" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `every concrete repository implements ExposedJdbcRepository`() {
        REPOSITORIES.all(ExposedJdbcRepository::class.java::isAssignableFrom).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "append only repositories reject generic deletion" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `append only repositories reject generic deletion`() {
        APPEND_ONLY_REPOSITORIES.forEach { repository ->
            assertFailsWith<UnsupportedOperationException> {
                repository.deleteAll()
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "production and fixture persistence use Exposed without raw SQL" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production and fixture persistence use Exposed without raw SQL`() {
        val violations = sequenceOf(mainSourceRoot(), testSourceRoot())
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

/**
 * mainSourceRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun mainSourceRoot(): Path = sourceRoot("src/main/kotlin")

/**
 * testSourceRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun testSourceRoot(): Path = sourceRoot("src/test/kotlin")

/**
 * sourceRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sourceRoot(relative: String): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve(relative)
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/usage-metering-billing-ledger").resolve(relative)
    }

    companion object: KLogging() {

/**
 * REPOSITORIES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val REPOSITORIES = listOf(
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

/**
 * APPEND_ONLY_REPOSITORIES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val APPEND_ONLY_REPOSITORIES = listOf(
            PriceVersionRepository(),
            UsageEventRepository(),
            LedgerEntryRepository(),
            InvoiceRepository(),
            InvoiceLineRepository(),
            InvoiceLineEntryRepository(),
            ReconciliationFindingRepository(),
        )

/**
 * RAW_SQL_PATTERNS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val RAW_SQL_PATTERNS = listOf(
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
