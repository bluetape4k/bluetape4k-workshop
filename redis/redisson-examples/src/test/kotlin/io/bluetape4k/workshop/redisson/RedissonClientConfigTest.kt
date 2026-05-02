package io.bluetape4k.workshop.redisson

import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.redisson.config.Config
import org.redisson.config.SingleServerConfig

class RedissonClientConfigTest: AbstractRedissonTest() {

    private fun Config.singleServerForTest(): SingleServerConfig {
        val getter = Config::class.java.getDeclaredMethod("getSingleServerConfig")
        getter.isAccessible = true
        return getter.invoke(this) as SingleServerConfig
    }

    @Test
    fun `shared redisson client uses tuned single server configuration`() {
        val config = redisson.config
        val singleServer = config.singleServerForTest()

        singleServer.address shouldBeEqualTo redis.url
        singleServer.connectionPoolSize shouldBeEqualTo 128
        singleServer.connectionMinimumIdleSize shouldBeEqualTo 32
        singleServer.idleConnectionTimeout shouldBeEqualTo 100_000
        singleServer.timeout shouldBeEqualTo 5000
        singleServer.retryAttempts shouldBeEqualTo 3
        singleServer.dnsMonitoringInterval shouldBeEqualTo 5000L

        config.threads shouldBeEqualTo 256
        config.nettyThreads shouldBeEqualTo 128
        config.codec shouldBeEqualTo RedissonCodecs.LZ4ForyComposite
        config.isTcpNoDelay.shouldBeTrue()
        config.tcpUserTimeout shouldBeEqualTo 5000
    }

    @Test
    fun `new redisson client reuses the same core configuration`() {
        val client = newRedisson()

        try {
            val actual = client.config
            val expected = newRedissonConfig()
            val shared = redisson.config
            val expectedSingleServer = expected.singleServerForTest()
            val actualSingleServer = actual.singleServerForTest()

            actualSingleServer.address shouldBeEqualTo expectedSingleServer.address
            actualSingleServer.connectionPoolSize shouldBeEqualTo expectedSingleServer.connectionPoolSize
            actualSingleServer.connectionMinimumIdleSize shouldBeEqualTo expectedSingleServer.connectionMinimumIdleSize
            actualSingleServer.timeout shouldBeEqualTo expectedSingleServer.timeout
            actualSingleServer.retryAttempts shouldBeEqualTo expectedSingleServer.retryAttempts
            actualSingleServer.dnsMonitoringInterval shouldBeEqualTo expectedSingleServer.dnsMonitoringInterval
            actual.threads shouldBeEqualTo expected.threads
            actual.nettyThreads shouldBeEqualTo expected.nettyThreads
            actual.codec shouldBeEqualTo expected.codec
            actual.isTcpNoDelay shouldBeEqualTo expected.isTcpNoDelay
            actual.tcpUserTimeout shouldBeEqualTo expected.tcpUserTimeout
            shared.toYAML() shouldBeEqualTo expected.toYAML()
        } finally {
            client.shutdown()
        }
    }
}
