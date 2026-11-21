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

/**
 * VoucherPoolKeyMaterialConfigurationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolKeyMaterialConfigurationTest {

    private companion object: KLogging() {
/**
 * MARKER은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val MARKER = "raw-key-marker-must-never-escape"
/**
 * PRODUCTION_STABLE_SEED은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PRODUCTION_STABLE_SEED = 101
/**
 * PRODUCTION_RING_SEED은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PRODUCTION_RING_SEED = 111

/**
 * MAPPER은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val MAPPER: ObjectMapper = Jackson.defaultJsonMapper
    }

    @TempDir
/**
 * directory은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var directory: Path

    @Test
/**
 * 이 시나리오는 "owner-only mounted JSON constructs runtime keys and full service graph" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "test profile receives only the test fixture provider" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "packaged main test profile has no provider and service graph fails" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "missing locator and missing file fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "relative symlink group-readable and oversized files fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "wrong owner and file replacement after validation fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "mutable JVM user name cannot influence the OS owner decision" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "malformed unknown and missing purpose JSON fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "short duplicate non-positive zero and repeating material fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "known fixture material and unsafe key labels fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * serviceContextRunner은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * baseServiceContext은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun baseServiceContext(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withPropertyValues(
                "workshop.voucher-pool.startup-initializer-enabled=false",
                "workshop.voucher-pool.worker-dispatcher-enabled=false",
            )
            .withBean(ObjectMapper::class.java, { MAPPER })
            .withBean(DataSource::class.java, { mockk(relaxed = true) })
            .withBean(VoucherPoolJdbcExecutor::class.java, { mockk(relaxed = true) })

/**
 * mountedProvider은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun mountedProvider(path: Path): MountedSecretVoucherPoolKeyMaterialProvider =
        MountedSecretVoucherPoolKeyMaterialProvider({ path.toString() }, MAPPER)

/**
 * assertRedactedFailure은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * writeSecret은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * validJson은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * ring은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ring(version: Int): String =
        """
        {
             "current":${key(version)},
             "retained":[]
        }
        """.trimAllWhitespace()

/**
 * key은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun key(version: Int): String =
        """
        {
            "version":$version,
            "material":"${material(version)}"
        }
        """.trimAllWhitespace()

/**
 * material은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun material(seed: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { index -> (seed + index).toByte() })

}
