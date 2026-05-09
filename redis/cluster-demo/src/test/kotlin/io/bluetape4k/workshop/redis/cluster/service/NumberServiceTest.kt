package io.bluetape4k.workshop.redis.cluster.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.redis.cluster.AbstractRedisClusterTest
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.connection.RedisConnectionFactory

class NumberServiceTest(
    @param:Autowired private val numberService: NumberService,
    @param:Autowired private val connectionFactory: RedisConnectionFactory,
): AbstractRedisClusterTest() {

    companion object: KLoggingChannel()

    @BeforeEach
    fun beforeEach() {
        connectionFactory.clusterConnection.use { conn ->
            conn.serverCommands().flushDb()
        }
    }

    @Test
    fun `context loading`() {
        numberService.shouldNotBeNull()
    }

    @Test
    fun `operation to redis cluster`() {
        numberService.get(0).shouldBeNull()

        for (i in 1 until 100) {
            numberService.multiplyAndSave(i)
        }

        for (i in 1 until 100) {
            numberService.get(i) shouldBeEqualTo (i * 2)
        }
    }
}
