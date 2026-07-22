package io.bluetape4k.workshop.commerce.voucherpool.config

import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration(proxyBeanMethods = false)
internal class VoucherPoolTestKeyMaterialConfiguration {
    @Bean
    fun voucherPoolTestKeyMaterialProvider(): VoucherPoolKeyMaterialProvider =
        VoucherPoolKeyMaterialProvider {
            val digests =
                VoucherDigestService(
                    stableDedupKey = DigestKey.of(STABLE_DEDUP_SEED, deterministicMaterial(STABLE_DEDUP_SEED)),
                    commandTombstoneKey =
                        DigestKey.of(COMMAND_TOMBSTONE_SEED, deterministicMaterial(COMMAND_TOMBSTONE_SEED)),
                    rotatingKeys =
                        VOUCHER_POOL_TEST_ROTATING_SEEDS.mapValues { (_, version) ->
                            DigestKeyRing.of(DigestKey.of(version, deterministicMaterial(version)))
                        },
                )
            val kekRing =
                VoucherKekRing.of(VoucherKek.of("fixture-kek-v1", deterministicMaterial(KEK_SEED)))
            VoucherPoolRuntimeKeys(digests, kekRing, VoucherPoolKeyProvenance.TEST_FIXTURE)
        }
}

internal val VOUCHER_POOL_TEST_ROTATING_SEEDS =
    mapOf(
        DigestPurpose.VERIFICATION to 11,
        DigestPurpose.USER_IDENTITY to 12,
        DigestPurpose.REDIS_SIGNAL to 14,
        DigestPurpose.AUDIT to 15,
    )

internal val VOUCHER_POOL_TEST_KEY_MATERIAL_SEEDS =
    setOf(STABLE_DEDUP_SEED, COMMAND_TOMBSTONE_SEED, KEK_SEED) + VOUCHER_POOL_TEST_ROTATING_SEEDS.values

private const val STABLE_DEDUP_SEED = 1
private const val COMMAND_TOMBSTONE_SEED = 2
private const val KEK_SEED = 31

private fun deterministicMaterial(seed: Int): ByteArray = ByteArray(32) { index -> (seed + index).toByte() }
