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
/**
 * VoucherPoolTestKeyMaterialConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolTestKeyMaterialConfiguration {

    @Bean
/**
 * voucherPoolTestKeyMaterialProvider은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun voucherPoolTestKeyMaterialProvider(): VoucherPoolKeyMaterialProvider =
        VoucherPoolKeyMaterialProvider {
/**
 * digests은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val digests = VoucherDigestService(
                stableDedupKey = DigestKey.of(STABLE_DEDUP_SEED, deterministicMaterial(STABLE_DEDUP_SEED)),
                commandTombstoneKey = DigestKey(
                    version = COMMAND_TOMBSTONE_SEED,
                    material = deterministicMaterial(COMMAND_TOMBSTONE_SEED)
                ),
                rotatingKeys = VOUCHER_POOL_TEST_ROTATING_SEEDS.mapValues { (_, version) ->
                    DigestKeyRing(DigestKey.of(version, deterministicMaterial(version)))
                },
            )
/**
 * kekRing은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val kekRing = VoucherKekRing(
                current = VoucherKek(
                    version = "fixture-kek-v1",
                    material = deterministicMaterial(KEK_SEED)
                )
            )
            VoucherPoolRuntimeKeys(
                digests = digests,
                kekRing = kekRing,
                provenance = VoucherPoolKeyProvenance.TEST_FIXTURE
            )
        }
}

/**
 * VOUCHER_POOL_TEST_ROTATING_SEEDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
internal val VOUCHER_POOL_TEST_ROTATING_SEEDS = mapOf(
    DigestPurpose.VERIFICATION to 11,
    DigestPurpose.USER_IDENTITY to 12,
    DigestPurpose.REDIS_SIGNAL to 14,
    DigestPurpose.AUDIT to 15,
)

/**
 * VOUCHER_POOL_TEST_KEY_MATERIAL_SEEDS은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
internal val VOUCHER_POOL_TEST_KEY_MATERIAL_SEEDS =
    setOf(
        STABLE_DEDUP_SEED,
        COMMAND_TOMBSTONE_SEED,
        KEK_SEED
    ) + VOUCHER_POOL_TEST_ROTATING_SEEDS.values

/**
 * STABLE_DEDUP_SEED은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val STABLE_DEDUP_SEED = 1
/**
 * COMMAND_TOMBSTONE_SEED은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val COMMAND_TOMBSTONE_SEED = 2
/**
 * KEK_SEED은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val KEK_SEED = 31

/**
 * deterministicMaterial은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun deterministicMaterial(seed: Int): ByteArray =
    ByteArray(32) { index -> (seed + index).toByte() }
