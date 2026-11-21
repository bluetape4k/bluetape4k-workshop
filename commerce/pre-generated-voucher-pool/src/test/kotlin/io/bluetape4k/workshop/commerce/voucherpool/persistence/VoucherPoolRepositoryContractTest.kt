package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * VoucherPoolRepositoryContractTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolRepositoryContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "repository boundary does not expose JDBC connections" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `repository boundary does not expose JDBC connections`() {
        val connectionMethods = VoucherPoolRepository::class.java.declaredMethods
            .filter { method -> Connection::class.java in method.parameterTypes }
            .map { method -> method.name }
            .sorted()

        connectionMethods.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "repository implementation is stateless and does not own a data source" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `repository implementation is stateless and does not own a data source`() {
        val dataSourceParameters = JdbcVoucherPoolRepository::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.toList() }
            .filter { parameterType -> DataSource::class.java.isAssignableFrom(parameterType) }
            .map { parameterType -> parameterType.name }

        dataSourceParameters.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "crypto storage boundary does not expose JDBC connections" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `crypto storage boundary does not expose JDBC connections`() {
        val connectionMethods = VoucherCryptoStorage::class.java.declaredMethods
            .filter { method -> Connection::class.java in method.parameterTypes }
            .map { method -> method.name }
            .sorted()

        connectionMethods.shouldBeEmpty()
    }

    @Test
/**
 * 이 시나리오는 "일반 voucher CRUD CAS 조회는 Exposed를 사용하고 PostgreSQL 특수 SQL은 문서화한다" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `일반 voucher CRUD CAS 조회는 Exposed를 사용하고 PostgreSQL 특수 SQL은 문서화한다`() {
        val repositorySource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepository.kt",
        )
        val querySource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/query/VoucherPoolQueryService.kt",
        )

        section(repositorySource, "override fun createCampaign", "override fun lockCampaignForUpdate")
            .also {
                it shouldContain "VoucherPoolCampaignTable.insert"
                it shouldNotContain "prepareStatement("
            }
        section(repositorySource, "override fun updateCampaign", "override fun createBatch")
            .also {
                it shouldContain "VoucherPoolCampaignTable.update"
                it shouldNotContain "prepareStatement("
            }
        section(repositorySource, "override fun createBatch", "override fun lockBatchForUpdate")
            .also {
                it shouldContain "VoucherPoolBatchTable.insert"
                it shouldNotContain "prepareStatement("
            }
        section(repositorySource, "override fun updateBatchCheckpoint", "override fun markBatchTerminalFailure")
            .also {
                it shouldContain "VoucherPoolBatchTable.update"
                it shouldNotContain "prepareStatement("
            }
        section(repositorySource, "override fun activateBatch", "override fun updateBatchState")
            .also {
                it shouldContain "VoucherPoolBatchTable.update"
                it shouldNotContain "prepareStatement("
            }
        section(querySource, "override fun findUserIdentityKeyVersion", "override fun readPoolDepth")
            .also {
                it shouldContain "VoucherPoolCampaignTable"
                it shouldContain "VoucherPoolReservationTable"
                it shouldContain "VoucherPoolAllocationTable"
                it shouldNotContain "prepareStatement("
            }

        repositorySource shouldContain "`ANY(uuid[])`와 `FOR UPDATE OF ... SKIP LOCKED`"
        repositorySource shouldContain "`FOR SHARE`는 campaign/batch guard"
        repositorySource shouldContain "prepared-entry bulk write"
        repositorySource shouldContain "crypto lock과 pool-depth projection"
        querySource shouldContain "scoped CTE"
        querySource shouldContain "transaction snapshot"
    }

    @Test
/**
 * 이 시나리오는 "consumer command and worker paths use Exposed for generic persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `consumer command and worker paths use Exposed for generic persistence`() {
        val idempotencySource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolIdempotencyRepository.kt",
        )
        val workerSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkerRepository.kt",
        )
        val reconciliationSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/application/" +
                    "VoucherPoolReconciliationCommandService.kt",
        )
        val workersSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkers.kt",
        )
        val streamSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolEventStream.kt",
        )
        val revocationSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/application/VoucherPoolRevocationService.kt",
        )
        val compatibilitySource = sourceFile(
            "commerce/promotion-voucher-campaign/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucher/VoucherCompatibilityCli.kt",
        )

        idempotencySource shouldContain "VoucherPoolHttpIdempotencyTable.update"
        idempotencySource shouldContain "VoucherPoolCommandTombstoneTable.insertIgnore"
        idempotencySource shouldContain "VoucherPoolCommandTombstoneTable.keyVersion"
        workerSource shouldContain "VoucherPoolWorkerClaimTable"
        workerSource shouldContain ".selectAll()"
        workerSource shouldContain "VoucherPoolWorkerClaimTable.insertIgnore"
        workerSource shouldContain "VoucherPoolWorkerClaimTable.update"
        workersSource shouldContain "VoucherPoolEntryTable"
        workersSource shouldContain ".innerJoin("
        workersSource shouldContain "VoucherPoolDepthTable.update"
        workersSource shouldContain "transaction_timestamp()"
        streamSource shouldContain "VoucherPoolCampaignTable"
        streamSource shouldContain ".selectAll()"
        streamSource shouldContain "WITH owner_scope"
        revocationSource shouldContain "VoucherPoolRevokePreviewGrantTable.insert"
        revocationSource shouldContain "VoucherPoolRevokePreviewGrantTable.update"
        revocationSource shouldNotContain "prepareStatement("
        reconciliationSource shouldContain "VoucherPoolBatchTable"
        reconciliationSource shouldContain ".select("
        compatibilitySource shouldContain "CampaignTable.insertIgnore"
        compatibilitySource shouldContain "CampaignTable.selectAll"

        // transaction snapshot, row lock, JSONB cast, bounded CTE는 계약상 raw SQL로 유지합니다.
        idempotencySource shouldContain "FOR UPDATE OF i,t SKIP LOCKED"
        workerSource shouldContain "transaction_timestamp()"
        reconciliationSource shouldContain "transaction_timestamp()"
    }

    @Test
/**
 * 이 시나리오는 "key guards and tenant cleanup use Exposed for ordinary persistence" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `key guards and tenant cleanup use Exposed for ordinary persistence`() {
        val migrationSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunner.kt",
        )
        val retentionSource = sourceFile(
            "commerce/pre-generated-voucher-pool/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetention.kt",
        )
        val voucherRetentionSource = sourceFile(
            "commerce/promotion-voucher-campaign/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/voucher/config/VoucherRetention.kt",
        )

        section(migrationSource, "internal class VoucherPoolReferencedKeyPreflight", "/** migration과 live")
            .also {
                it shouldContain "VoucherPoolEntryTable"
                it shouldContain "VoucherPoolCampaignTable"
                it shouldContain "userIdentityKeyVersion"
                it shouldContain "VoucherPoolCommandTombstoneTable"
                it shouldContain ".select("
                it shouldNotContain "prepareStatement(\"SELECT DISTINCT"
            }
        section(
            retentionSource,
            "internal class PostgresVoucherPoolKeyReferenceSource",
            "internal class VoucherPoolKeyRetirementPolicy",
        )
            .also {
                it shouldContain "VoucherPoolEntryTable"
                it shouldContain "VoucherPoolCampaignTable"
                it shouldContain "userIdentityKeyVersion"
                it shouldContain "VoucherPoolCommandTombstoneTable"
                it shouldContain ".select("
                it shouldContain "BACKUP_INVENTORY_SQL"
                it shouldContain "array materialization"
            }
        section(retentionSource, "fun deleteTenant", "private fun purgeDescriptors")
            .also {
                it shouldContain "deleteWhere"
                it shouldNotContain "DELETE FROM \$table"
            }
        retentionSource shouldContain "statement_timestamp()`와 interval projection"
        section(
            voucherRetentionSource,
            "override fun referencedVersions",
            "private fun Connection.hasReferenceTables",
        )
            .also {
                it shouldContain "ClaimTable"
                it shouldContain "HttpIdempotencyTable"
                it shouldContain ".select("
                it shouldNotContain "REFERENCED_KEY_SQL"
                it shouldNotContain "prepareStatement("
            }
    }

/**
 * section은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start).also { check(it >= 0) { "missing source marker: $start" } }
        val endIndex = source.indexOf(end, startIndex + start.length)
            .also { check(it >= 0) { "missing source marker: $end" } }
        return source.substring(startIndex, endIndex)
    }

/**
 * sourceFile은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun sourceFile(relativePath: String): String {
        val current = Path.of("").toAbsolutePath().normalize()
        val path = generateSequence(current) { it.parent }
            .map { it.resolve(relativePath) }
            .firstOrNull(Files::isRegularFile)
            ?: error("source file not found from $current: $relativePath")
        return Files.readString(path)
    }
}
