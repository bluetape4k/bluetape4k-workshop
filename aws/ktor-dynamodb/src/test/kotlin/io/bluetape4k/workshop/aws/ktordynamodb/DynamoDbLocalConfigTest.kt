package io.bluetape4k.workshop.aws.ktordynamodb

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

class DynamoDbLocalConfigTest {

    @Test
    fun `local mode requires an explicit endpoint`() {
        val failure = assertFailsWith<OrderSessionValidationException> {
            DynamoDbLocalConfig.fromProperties(
                properties(
                    "bluetape4k.aws.access-key-id" to "test",
                    "bluetape4k.aws.secret-access-key" to "test",
                ),
            )
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.VALIDATION_FAILED
    }

    @Test
    fun `local mode requires dummy credentials`() {
        val failure = assertFailsWith<OrderSessionValidationException> {
            DynamoDbLocalConfig.fromProperties(
                properties(
                    "bluetape4k.aws.dynamodb.endpoint-url" to "http://localhost:8000",
                ),
            )
        }

        failure.errorCode shouldBeEqualTo OrderSessionErrorCode.VALIDATION_FAILED
    }

    @Test
    fun `local mode defaults to the floci emulator when endpoint and credentials are present`() {
        val config = DynamoDbLocalConfig.fromProperties(
            properties(
                "bluetape4k.aws.dynamodb.endpoint-url" to "http://localhost:8000",
                "bluetape4k.aws.access-key-id" to "test",
                "bluetape4k.aws.secret-access-key" to "test",
            ),
        )

        config.mode shouldBeEqualTo AwsWorkshopMode.LOCAL
        config.emulator shouldBeEqualTo AwsWorkshopEmulator.FLOCI
        config.region shouldBeEqualTo "ap-northeast-2"
        config.tableName shouldBeEqualTo "workshop-order-sessions"
    }

    @Test
    fun `real mode does not require local endpoint or dummy credentials`() {
        val config = DynamoDbLocalConfig.fromProperties(
            properties(
                "bluetape4k.aws.mode" to "real",
                "bluetape4k.aws.region" to "us-east-1",
            ),
        )

        config.mode shouldBeEqualTo AwsWorkshopMode.REAL
        config.emulator shouldBeEqualTo null
        config.endpointUrl shouldBeEqualTo null
        config.region shouldBeEqualTo "us-east-1"
    }

    private fun properties(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = pairs.toMap()
        return values::get
    }
}
