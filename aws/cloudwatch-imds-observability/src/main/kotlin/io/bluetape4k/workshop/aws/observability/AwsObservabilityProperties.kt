package io.bluetape4k.workshop.aws.observability

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

@ConfigurationProperties(prefix = "bluetape4k.workshop.aws.observability")
data class AwsObservabilityProperties(
    val namespace: String = "Bluetape4k/Workshop",
    val logGroupName: String = "/bluetape4k/workshop/orders",
    val logStreamName: String = "local",
    val serviceName: String = "order-service",
    val sourceName: String = "workshop",
    val maxFieldLength: Int = 160,
    val metadata: MetadataProperties = MetadataProperties(),
): Serializable {

    init {
        namespace.requireNotBlank("namespace")
        logGroupName.requireNotBlank("logGroupName")
        logStreamName.requireNotBlank("logStreamName")
        serviceName.requireNotBlank("serviceName")
        sourceName.requireNotBlank("sourceName")
        maxFieldLength.requirePositiveNumber("maxFieldLength")
    }

    companion object {
        private const val serialVersionUID: Long = 9038524761928456234L
    }
}

data class MetadataProperties(
    val enabled: Boolean = false,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -8540304635995274343L
    }
}
