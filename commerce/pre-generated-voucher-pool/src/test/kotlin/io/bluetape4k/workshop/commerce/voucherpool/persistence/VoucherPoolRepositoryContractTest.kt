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

internal class VoucherPoolRepositoryContractTest {

    companion object: KLogging()

    @Test
    fun `repository boundary does not expose JDBC connections`() {
        val connectionMethods = VoucherPoolRepository::class.java.declaredMethods
            .filter { method -> Connection::class.java in method.parameterTypes }
            .map { method -> method.name }
            .sorted()

        connectionMethods.shouldBeEmpty()
    }

    @Test
    fun `repository implementation is stateless and does not own a data source`() {
        val dataSourceParameters = JdbcVoucherPoolRepository::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.toList() }
            .filter { parameterType -> DataSource::class.java.isAssignableFrom(parameterType) }
            .map { parameterType -> parameterType.name }

        dataSourceParameters.shouldBeEmpty()
    }

    @Test
    fun `crypto storage boundary does not expose JDBC connections`() {
        val connectionMethods = VoucherCryptoStorage::class.java.declaredMethods
            .filter { method -> Connection::class.java in method.parameterTypes }
            .map { method -> method.name }
            .sorted()

        connectionMethods.shouldBeEmpty()
    }

    @Test
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

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start).also { check(it >= 0) { "missing source marker: $start" } }
        val endIndex = source.indexOf(end, startIndex + start.length)
            .also { check(it >= 0) { "missing source marker: $end" } }
        return source.substring(startIndex, endIndex)
    }

    private fun sourceFile(relativePath: String): String {
        val current = Path.of("").toAbsolutePath().normalize()
        val path = generateSequence(current) { it.parent }
            .map { it.resolve(relativePath) }
            .firstOrNull(Files::isRegularFile)
            ?: error("source file not found from $current: $relativePath")
        return Files.readString(path)
    }
}
