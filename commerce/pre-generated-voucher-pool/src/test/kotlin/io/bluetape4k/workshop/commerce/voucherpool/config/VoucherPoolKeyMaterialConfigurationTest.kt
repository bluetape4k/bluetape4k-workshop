@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.trimAllWhitespace
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.RedemptionService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.VoucherPoolWorkers
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.util.*
import javax.sql.DataSource

internal class VoucherPoolKeyMaterialConfigurationTest {

    private companion object: KLogging() {
        const val MARKER = "raw-key-marker-must-never-escape"
        const val PRODUCTION_STABLE_SEED = 101
        const val PRODUCTION_RING_SEED = 111

        val MAPPER: ObjectMapper = Jackson.defaultJsonMapper
    }

    @TempDir
    lateinit var directory: Path

    @Test
    fun `owner-only mounted JSON constructs runtime keys and full service graph`() {
        val secret = writeSecret(validJson())
        val provider = mountedProvider(secret)

        val runtimeKeys = provider.load()

        log.debug { "runtimeKeys: $runtimeKeys" }
        runtimeKeys.provenance shouldBeEqualTo VoucherPoolKeyProvenance.MOUNTED_SECRET
        runtimeKeys.kekRing.current.version shouldBeEqualTo "kek-v1"
        runtimeKeys.toString() shouldNotContain MARKER

        serviceContextRunner(secret).run { context ->
            context.startupFailure.shouldBeNull()
            listOf(
                VoucherPoolRuntimeKeys::class.java,
                VoucherDigestService::class.java,
                VoucherKekRing::class.java,
                VoucherEnvelopeCrypto::class.java,
                VoucherCryptoStorage::class.java,
                VoucherPoolIdempotencyRepository::class.java,
                VoucherPoolRepository::class.java,
                CampaignBatchCommandService::class.java,
                ReservationService::class.java,
                AllocationService::class.java,
                RedemptionService::class.java,
                JdbcVoucherPoolWorkerRepository::class.java,
                VoucherPoolWorkers::class.java,
            ).forEach { type ->
                context.getBeansOfType(type) shouldHaveSize 1
            }
        }
    }

    @Test
    fun `test profile receives only the test fixture provider`() {
        baseServiceContext()
            .withPropertyValues("spring.profiles.active=voucher-pool-test")
            .withUserConfiguration(
                VoucherPoolKeyMaterialConfiguration::class.java,
                VoucherPoolServiceConfiguration::class.java,
                VoucherPoolTestKeyMaterialConfiguration::class.java,
            ).run { context ->
                context.startupFailure.shouldBeNull()

                context.getBeansOfType<VoucherPoolKeyMaterialProvider>() shouldHaveSize 1
                context.getBean<VoucherPoolRuntimeKeys>().provenance shouldBeEqualTo VoucherPoolKeyProvenance.TEST_FIXTURE
            }
    }

    @Test
    fun `packaged main test profile has no provider and service graph fails`() {
        baseServiceContext()
            .withPropertyValues("spring.profiles.active=voucher-pool-test")
            .withUserConfiguration(
                VoucherPoolKeyMaterialConfiguration::class.java,
                VoucherPoolServiceConfiguration::class.java,
            ).run { context ->
                val failure = context.startupFailure.shouldNotBeNull()
                log.debug { "failure: ${failure.causeMessages()}" }
                failure.causeMessages() shouldContain "VoucherPoolKeyMaterialProvider"
            }
    }

    @Test
    fun `missing locator and missing file fail closed`() {
        assertRedactedFailure(MountedSecretVoucherPoolKeyMaterialProvider({ null }, MAPPER))
        assertRedactedFailure(mountedProvider(directory.resolve("missing.json")))

        baseServiceContext()
            .withBean(
                VoucherPoolKeyFileLocator::class.java,
                { VoucherPoolKeyFileLocator { null } },
            ).withUserConfiguration(
                VoucherPoolKeyMaterialConfiguration::class.java,
                VoucherPoolServiceConfiguration::class.java,
            ).run { context ->
                log.debug { "startupFailure: ${context.startupFailure.causeMessages()}" }
                context.startupFailure.causeMessages() shouldContain "VOUCHER_POOL_KEY_MATERIAL_MISSING_LOCATION"
            }
    }

    @Test
    fun `relative symlink group-readable and oversized files fail closed`() {
        assertRedactedFailure(MountedSecretVoucherPoolKeyMaterialProvider({ "relative.json" }, MAPPER))

        val target = writeSecret(validJson(), "target.json")
        val symlink = directory.resolve("linked.json")
        Files.createSymbolicLink(symlink, target)
        assertRedactedFailure(mountedProvider(symlink))

        val readable = writeSecret(validJson(), "group-readable.json")
        Files.setPosixFilePermissions(
            readable,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
            ),
        )
        assertRedactedFailure(mountedProvider(readable))

        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = "x".repeat(65 * 1024),
                    name = "oversized.json"
                )
            )
        )
    }

    @Test
    fun `wrong owner and file replacement after validation fail closed`() {
        val wrongOwner = writeSecret(validJson(), "wrong-owner.json")
        assertRedactedFailure(
            MountedSecretVoucherPoolKeyMaterialProvider(
                locator = { wrongOwner.toString() },
                mapper = MAPPER,
                expectedOwnerId = { Long.MAX_VALUE },
            ),
        )

        val original = writeSecret(validJson(), "replace-target.json")
        val replacement = writeSecret(validJson(201), "replace-source.json")
        assertRedactedFailure(
            MountedSecretVoucherPoolKeyMaterialProvider(
                locator = { original.toString() },
                mapper = MAPPER,
                beforeRead = { Files.move(replacement, original, REPLACE_EXISTING) },
            ),
        )
    }

    @Test
    fun `mutable JVM user name cannot influence the OS owner decision`() {
        val secret = writeSecret(validJson(), "user-name-independent.json")
        val previous = System.getProperty("user.name")

        try {
            System.setProperty("user.name", "forged-owner-name")
            mountedProvider(secret).load().shouldNotBeNull()
        } finally {
            if (previous == null) {
                System.clearProperty("user.name")
            } else {
                System.setProperty("user.name", previous)
            }
        }
    }

    @Test
    fun `malformed unknown and missing purpose JSON fail closed`() {
        assertRedactedFailure(mountedProvider(writeSecret("{", "malformed.json")))
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replaceFirst("{", "{\"unexpected\":true,"),
                    name = "unknown.json"
                )
            ),
        )
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replace(",\"AUDIT\":${ring(141)}", ""),
                    name = "missing-purpose.json",
                ),
            ),
        )
    }

    @Test
    fun `short duplicate non-positive zero and repeating material fail closed`() {
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replace(
                        material(PRODUCTION_STABLE_SEED),
                        Base64.getEncoder().encodeToString(ByteArray(31) { (it + 1).toByte() }),
                    ),
                    name = "short.json",
                ),
            ),
        )
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replaceFirst(
                        "\"retained\":[]",
                        "\"retained\":[${key(PRODUCTION_RING_SEED)}]"
                    ),
                    name = "duplicate.json",
                ),
            ),
        )
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replaceFirst("\"version\":$PRODUCTION_STABLE_SEED", "\"version\":0"),
                    name = "zero-version.json",
                ),
            ),
        )
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replace(
                        oldValue = material(PRODUCTION_STABLE_SEED),
                        newValue = Base64.getEncoder().encodeToString(ByteArray(32)),
                    ),
                    name = "zero.json",
                ),
            ),
        )
        assertRedactedFailure(
            mountedProvider(
                writeSecret(
                    content = validJson().replace(
                        oldValue = material(PRODUCTION_STABLE_SEED),
                        newValue = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                    ),
                    name = "repeating.json",
                ),
            ),
        )
    }

    @Test
    fun `known fixture material and unsafe key labels fail closed`() {
        listOf("test", "fixture", "sample", "demo", "default", "local", "example").forEach { label ->
            assertRedactedFailure(
                mountedProvider(
                    writeSecret(
                        content = validJson().replace("kek-v1", "$label-kek-v1"),
                        name = "$label-label.json"
                    )
                ),
            )
        }
        VOUCHER_POOL_TEST_KEY_MATERIAL_SEEDS.forEach { fixtureSeed ->
            assertRedactedFailure(
                mountedProvider(
                    writeSecret(
                        content = validJson().replace(material(PRODUCTION_STABLE_SEED), material(fixtureSeed)),
                        name = "fixture-material-$fixtureSeed.json",
                    ),
                ),
            )
        }
    }

    private fun serviceContextRunner(secret: Path): ApplicationContextRunner =
        baseServiceContext()
            .withBean(
                VoucherPoolKeyFileLocator::class.java,
                { VoucherPoolKeyFileLocator { secret.toString() } },
            )
            .withUserConfiguration(
                VoucherPoolKeyMaterialConfiguration::class.java,
                VoucherPoolServiceConfiguration::class.java,
            )

    private fun baseServiceContext(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withPropertyValues(
                "workshop.voucher-pool.startup-initializer-enabled=false",
                "workshop.voucher-pool.worker-dispatcher-enabled=false",
            )
            .withBean(ObjectMapper::class.java, { MAPPER })
            .withBean(DataSource::class.java, { mockk(relaxed = true) })
            .withBean(VoucherPoolJdbcExecutor::class.java, { mockk(relaxed = true) })

    private fun mountedProvider(path: Path): MountedSecretVoucherPoolKeyMaterialProvider =
        MountedSecretVoucherPoolKeyMaterialProvider({ path.toString() }, MAPPER)

    private fun assertRedactedFailure(provider: VoucherPoolKeyMaterialProvider) {
        val failure = runCatching(provider::load).exceptionOrNull().shouldNotBeNull()

        val rendered = failure.toString()

        log.debug { "rendered: $rendered" }
        rendered shouldNotContain MARKER
        rendered shouldNotContain material(1)
        rendered shouldNotContain directory.toString()
        rendered shouldNotContain "material"
    }

    private fun Throwable?.causeMessages(): String =
        generateSequence(this) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }

    private fun writeSecret(content: String, name: String = "keys.json"): Path =
        directory.resolve(name)
            .also { path ->
                Files.writeString(path, content)
                Files.setPosixFilePermissions(
                    path,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    )
                )
            }
            .toRealPath()

    private fun validJson(offset: Int = 0): String =
        """
        {
            "stableDedup":${key(PRODUCTION_STABLE_SEED + offset)},
            "commandTombstone":${key(102 + offset)},
            "rotating": {
                "VERIFICATION":${ring(PRODUCTION_RING_SEED + offset)},
                "USER_IDENTITY":${ring(121 + offset)},
                "REDIS_SIGNAL":${ring(131 + offset)},
                "AUDIT":${ring(141 + offset)}
            },
            "kek":{
                "current":{
                    "version":"kek-v1",
                    "material":"${material(151 + offset)}"},
                    "retained":[]
            }
        }
        """.trimAllWhitespace()

    private fun ring(version: Int): String =
        """
        {
             "current":${key(version)},
             "retained":[]
        }
        """.trimAllWhitespace()

    private fun key(version: Int): String =
        """
        {
            "version":$version,
            "material":"${material(version)}"
        }
        """.trimAllWhitespace()

    private fun material(seed: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { index -> (seed + index).toByte() })

}
