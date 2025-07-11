package io.bluetape4k.workshop.bucket4j.hazelcast.controller

import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricHandler
import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricTagResult
import com.giffing.bucket4j.spring.boot.starter.context.metrics.MetricType
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.stereotype.Component

@Component
class DebugMetricHandler: MetricHandler {

    companion object: KLoggingChannel()

    override fun handle(
        type: MetricType,
        name: String,
        tokens: Long,
        tags: MutableList<MetricTagResult>,
    ) {
        val tagsStr = tags.joinToString(",") { it.key + ":" + it.value }
        val message = "type: $type; name: $name; tags: $tagsStr; tokens: $tokens"
        println(message)
    }
}
