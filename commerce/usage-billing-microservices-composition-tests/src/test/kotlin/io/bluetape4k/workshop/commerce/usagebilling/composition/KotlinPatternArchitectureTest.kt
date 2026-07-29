package io.bluetape4k.workshop.commerce.usagebilling.composition

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

class KotlinPatternArchitectureTest {
    @Test
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
    fun `Query quarantine listener records its permanent-failure outcome`() {
        val content = repositoryRoot().resolve(QUERY_DECODER).toFile().readText()

        (content.contains("query.inbound.quarantined")) shouldBeEqualTo true
    }

    @Test
    fun `public integration envelopes document their durable wire boundary`() {
        val violations = ENVELOPE_FILES.filter { relativePath ->
            val content = repositoryRoot().resolve(relativePath).toFile().readText()
            !content.contains("/**") || !content.contains("Versioned")
        }

        violations shouldBeEqualTo emptyList()
    }

    private fun serviceSourceFiles(): List<Path> =
        SERVICE_MODULES.flatMap { module ->
            Files.walk(repositoryRoot().resolve(module).resolve("src/main/kotlin")).use { paths ->
                paths.filter { it.extension == "kt" }.toList()
            }
        }

    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

    private companion object {
        val DATA_CLASS = Regex("\\bdata class\\b")
        val SERIAL_VERSION_UID = Regex("serialVersionUID")
        val SERVICE_MODULES = listOf(
            "commerce/usage-billing-meter-service",
            "commerce/usage-billing-usage-service",
            "commerce/usage-billing-billing-service",
            "commerce/usage-billing-invoice-service",
            "commerce/usage-billing-query-service",
        )
        val DECODER_FILES = listOf(
            "commerce/usage-billing-usage-service/src/main/kotlin/" +
                "io/bluetape4k/workshop/commerce/usagebilling/usage/messaging/MeterPriceEvidenceConsumer.kt",
            "commerce/usage-billing-billing-service/src/main/kotlin/" +
                "io/bluetape4k/workshop/commerce/usagebilling/billing/messaging/BillingKafkaConsumer.kt",
            "commerce/usage-billing-invoice-service/src/main/kotlin/" +
                "io/bluetape4k/workshop/commerce/usagebilling/invoice/messaging/BillingChargeConsumer.kt",
        )
        const val QUERY_DECODER =
            "commerce/usage-billing-query-service/src/main/kotlin/" +
                "io/bluetape4k/workshop/commerce/usagebilling/query/messaging/QueryKafkaConsumer.kt"
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
}
