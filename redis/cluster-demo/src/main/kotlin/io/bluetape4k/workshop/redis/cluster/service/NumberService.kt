package io.bluetape4k.workshop.redis.cluster.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toByteArray
import io.bluetape4k.support.toInt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class NumberService(
    @param:Autowired private val operations: StringRedisTemplate,
) {

    companion object: KLoggingChannel() {
        private val CURRENT_CHARSET = Charsets.UTF_8
    }

    fun multiplyAndSave(number: Int) {
        log.debug { "multiply and save: number=$number" }

        operations.requiredConnectionFactory.clusterConnection.use { conn ->
            conn.stringCommands()[number.toByteArray()] = (number * 2).toByteArray()
        }
    }

    fun get(number: Int): Int? {
        return operations.requiredConnectionFactory.clusterConnection.use { conn ->
            conn.stringCommands()[number.toByteArray()]?.toInt().apply {
                log.debug { "get number=$number, value=$this" }
            }
        }
    }
}
