package io.bluetape4k.workshop.aws.sqssns

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the SQS/SNS workshop example.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.sqs-sns")
data class SqsSnsMessagingProperties(
    val topicArn: String = "arn:aws:sns:ap-northeast-2:123456789012:order-notifications",
    val queueUrl: String = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/order-notifications",
    val subject: String = "Order notification",
    val maxMessages: Int = 10,
    val waitTimeSeconds: Int = 1,
    val visibilityTimeoutSeconds: Int = 30,
    val maxReceiveCount: Int = 3,
)
