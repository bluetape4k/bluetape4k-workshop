package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRedisResources
import io.bluetape4k.workshop.commerce.voucherpool.config.RecoverableVoucherPoolAdmissionBackend
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolLeaderTrigger
import io.bluetape4k.workshop.commerce.voucherpool.config.voucherPoolDistributedAdmissionBackend
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

internal class LettuceVoucherPoolAdmissionIntegrationTest {
    @Test
    fun `Bucket4j keeps operation quotas in opaque isolated namespaces`() {
        val properties =
            VoucherPoolRedisProperties(
                enabled = true,
                uri = redis.url,
                commandTimeout = Duration.ofSeconds(1),
                limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.REVEAL, 1),
            )
        flushRedis()

        VoucherPoolRedisResources.open(properties).use { resources ->
            val gate = VoucherPoolAdmissionGate(voucherPoolDistributedAdmissionBackend(resources.client, properties))
            val principal = ByteArray(32) { 0x41 }

            gate.admit(AdmissionNamespace.REVEAL, principal) shouldBeEqualTo AdmissionDecision.ALLOW
            gate.admit(AdmissionNamespace.REVEAL, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.ALLOW

            val keys =
                resources.client.connect().use { connection ->
                    connection.sync().keys("voucher-pool:admission:*")
                }
            keys.size shouldBeEqualTo 2
            keys.none { "principal" in it || "414141" in it } shouldBeEqualTo true
        }
    }

    @Test
    fun `unavailable Redis falls back to the same bounded local policy`() {
        val properties =
            VoucherPoolRedisProperties(
                enabled = true,
                uri = "redis://127.0.0.1:1",
                commandTimeout = Duration.ofMillis(50),
                limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.REDEEM, 1),
            )

        VoucherPoolRedisResources.open(properties).use { resources ->
            val gate =
                VoucherPoolAdmissionGate(
                    RecoverableVoucherPoolAdmissionBackend(resources.client, properties),
                    properties.limits,
                )
            val principal = ByteArray(32) { 0x42 }

            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.DEGRADED_ALLOW
            gate.admit(AdmissionNamespace.REDEEM, principal) shouldBeEqualTo AdmissionDecision.RATE_LIMITED
        }
    }

    @Test
    fun `leader trigger executes the supplied shared worker path at most once`() {
        val properties =
            VoucherPoolRedisProperties(
                enabled = true,
                uri = redis.url,
                commandTimeout = Duration.ofSeconds(1),
            )
        val calls = AtomicInteger()

        VoucherPoolRedisResources.open(properties).use { resources ->
            val trigger = VoucherPoolLeaderTrigger(resources::leaderElector, "test-instance")
            val result = trigger.run("voucher-pool-reconciliation") { calls.incrementAndGet() }

            (result != null) shouldBeEqualTo true
            calls.get() shouldBeEqualTo 1
        }
    }

    private fun flushRedis() {
        val client = LettuceClients.clientOf(redis.url)
        try {
            LettuceClients.connect(client).sync().flushall()
        } finally {
            LettuceClients.shutdown(client)
        }
    }

    private companion object {
        val redis: RedisServer = RedisServer.Launcher.redis
    }
}
