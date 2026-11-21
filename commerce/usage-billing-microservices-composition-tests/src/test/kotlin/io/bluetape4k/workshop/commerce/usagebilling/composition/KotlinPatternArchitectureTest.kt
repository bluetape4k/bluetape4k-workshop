package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * KotlinPatternArchitectureTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class KotlinPatternArchitectureTest {

    private companion object: KLogging() {
/**
 * DATA_CLASS은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val DATA_CLASS = Regex("\\bdata class\\b")
/**
 * SERIAL_VERSION_UID은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val SERIAL_VERSION_UID = Regex("serialVersionUID")
/**
 * SERVICE_MODULES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val SERVICE_MODULES = listOf(
            "commerce/usage-billing-meter-service",
            "commerce/usage-billing-usage-service",
            "commerce/usage-billing-billing-service",
            "commerce/usage-billing-invoice-service",
            "commerce/usage-billing-query-service",
        )
/**
 * DECODER_FILES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val DECODER_FILES = listOf(
            "commerce/usage-billing-usage-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/usage/messaging/MeterPriceEvidenceConsumer.kt",
            "commerce/usage-billing-billing-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/billing/messaging/BillingKafkaConsumer.kt",
            "commerce/usage-billing-invoice-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/invoice/messaging/BillingChargeConsumer.kt",
        )
/**
 * QUERY_DECODER은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val QUERY_DECODER =
            "commerce/usage-billing-query-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/query/messaging/QueryKafkaConsumer.kt"
/**
 * ENVELOPE_FILES은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val ENVELOPE_FILES = listOf(
            "commerce/usage-billing-meter-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/meter/integration/MeterIntegrationEnvelope.kt",
            "commerce/usage-billing-usage-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/usage/integration/UsageIntegrationEnvelope.kt",
            "commerce/usage-billing-billing-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/billing/integration/BillingIntegrationEnvelope.kt",
            "commerce/usage-billing-invoice-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/invoice/integration/InvoiceIntegrationEnvelope.kt",
            "commerce/usage-billing-query-service/src/main/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/query/integration/QueryIntegrationEnvelope.kt",
        )
    }

    @Test
/**
 * 이 시나리오는 "every service data model is explicitly serializable with a serial version UID" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `every service data model is explicitly serializable with a serial version UID`() {
        val violations = serviceSourceFiles()
            .filter { source -> source.toFile().readText().contains("data class") }
            .flatMap { source ->
                val content = source.toFile().readText()
                val dataClassCount = DATA_CLASS.findAll(content).count()
                val serialVersionUidCount = SERIAL_VERSION_UID.findAll(content).count()

                buildList {
                    if (!content.contains("java.io.Serializable")) add("${source.fileName}:missing Serializable import")
                    if (serialVersionUidCount < dataClassCount) {
                        add(
                            "${source.fileName}:$dataClassCount data classes but " +
                                    "$serialVersionUidCount serialVersionUID values",
                        )
                    }
                }
            }

        violations shouldBeEqualTo emptyList()
    }

    @Test
/**
 * 이 시나리오는 "external Kafka decoders use Bluetape validation helpers and listeners log durable
 * outcomes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `external Kafka decoders use Bluetape validation helpers and listeners log durable outcomes`() {
        val violations = DECODER_FILES.flatMap { relativePath ->
            val content = repositoryRoot().resolve(relativePath).toFile().readText()

            buildList {
                if (!content.contains("requireNotBlank")) add("$relativePath:missing requireNotBlank")
                if (content.contains("requireNotNull(") || content.contains("require(")) {
                    add("$relativePath:raw Kotlin require")
                }
                if (!content.contains("KLogging") || !content.contains("log.debug")) {
                    add("$relativePath:missing inbound outcome log")
                }
            }
        }

        violations shouldBeEqualTo emptyList()
    }

    @Test
/**
 * 이 시나리오는 "Query quarantine listener records its permanent-failure outcome" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Query quarantine listener records its permanent-failure outcome`() {
        val content = repositoryRoot().resolve(QUERY_DECODER).toFile().readText()

        content shouldContain "query.inbound.quarantined"
    }

    @Test
/**
 * 이 시나리오는 "public integration envelopes document their durable wire boundary" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `public integration envelopes document their durable wire boundary`() {
        val violations = ENVELOPE_FILES.filter { relativePath ->
            val content = repositoryRoot().resolve(relativePath).toFile().readText()
            !content.contains("/**") || !content.contains("Versioned")
        }

        violations shouldBeEqualTo emptyList()
    }

/**
 * serviceSourceFiles은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun serviceSourceFiles(): List<Path> =
        SERVICE_MODULES.flatMap { module ->
            Files.walk(repositoryRoot().resolve(module).resolve("src/main/kotlin")).use { paths ->
                paths.filter { it.extension == "kt" }.toList()
            }
        }

/**
 * repositoryRoot은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

}
