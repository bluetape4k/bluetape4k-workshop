package io.bluetape4k.workshop.aws.ktordynamodb

import aws.smithy.kotlin.runtime.net.url.Url
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class AwsWorkshopMode {
    LOCAL,
    REAL,
}

internal enum class AwsWorkshopEmulator {
    FLOCI,
    LOCALSTACK,
}

internal data class DynamoDbLocalConfig(
    val mode: AwsWorkshopMode,
    val emulator: AwsWorkshopEmulator?,
    val region: String,
    val tableName: String,
    val endpointUrl: Url?,
    val accessKeyId: String?,
    val secretAccessKey: String?,
    val tableReadyTimeout: Duration,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MODE_PROPERTY: String = "bluetape4k.aws.mode"
        private const val EMULATOR_PROPERTY: String = "bluetape4k.aws.emulator"
        private const val REGION_PROPERTY: String = "bluetape4k.aws.region"
        private const val TABLE_NAME_PROPERTY: String = "bluetape4k.aws.dynamodb.table-name"
        private const val ENDPOINT_URL_PROPERTY: String = "bluetape4k.aws.dynamodb.endpoint-url"
        private const val ACCESS_KEY_PROPERTY: String = "bluetape4k.aws.access-key-id"
        private const val SECRET_KEY_PROPERTY: String = "bluetape4k.aws.secret-access-key"

        fun fromSystemProperties(): DynamoDbLocalConfig =
            fromProperties(System::getProperty)

        fun fromProperties(property: (String) -> String?): DynamoDbLocalConfig {
            val mode = when (property(MODE_PROPERTY)?.trim()?.lowercase() ?: "local") {
                "local" -> AwsWorkshopMode.LOCAL
                "real" -> AwsWorkshopMode.REAL
                else -> throw OrderSessionValidationException("$MODE_PROPERTY must be local or real.")
            }
            val configuredEmulator = property(EMULATOR_PROPERTY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    when (it.lowercase()) {
                        "floci" -> AwsWorkshopEmulator.FLOCI
                        "localstack" -> AwsWorkshopEmulator.LOCALSTACK
                        else -> throw OrderSessionValidationException("$EMULATOR_PROPERTY must be floci or localstack.")
                    }
                }
            val emulator = configuredEmulator ?: AwsWorkshopEmulator.FLOCI.takeIf { mode == AwsWorkshopMode.LOCAL }

            val endpoint = property(ENDPOINT_URL_PROPERTY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(Url::parse)
            val accessKeyId = property(ACCESS_KEY_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
            val secretAccessKey = property(SECRET_KEY_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }

            if (mode == AwsWorkshopMode.LOCAL) {
                if (endpoint == null) {
                    throw OrderSessionValidationException("$ENDPOINT_URL_PROPERTY is required in local mode.")
                }
                if (accessKeyId == null || secretAccessKey == null) {
                    throw OrderSessionValidationException(
                        "$ACCESS_KEY_PROPERTY and $SECRET_KEY_PROPERTY are required in local mode.",
                    )
                }
            }

            return DynamoDbLocalConfig(
                mode = mode,
                emulator = emulator,
                region = property(REGION_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() } ?: "ap-northeast-2",
                tableName = property(TABLE_NAME_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "workshop-order-sessions",
                endpointUrl = endpoint,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                tableReadyTimeout = if (mode == AwsWorkshopMode.LOCAL) 30.seconds else 60.seconds,
            )
        }
    }
}
