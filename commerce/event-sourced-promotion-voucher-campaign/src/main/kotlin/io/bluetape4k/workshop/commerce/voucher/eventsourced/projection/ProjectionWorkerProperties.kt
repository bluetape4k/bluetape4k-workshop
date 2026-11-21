package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 이 gate를 비활성화해도 projection table과 active generation은 보존됩니다. 새 polling work만 중지합니다.
 * @property enabled 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */
@ConfigurationProperties("voucher.projection.worker")
internal data class ProjectionWorkerProperties(
    /**
     * `enabled`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val enabled: Boolean = true,
)
/**
 * ProjectionWorkerGate은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property properties 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


internal class ProjectionWorkerGate(
    /**
     * `properties`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProjectionWorkerProperties,
) {
    /**
     * start은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param loop 업무 처리에 필요한 loop 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param ownerDigest 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun start(
        loop: ProjectionPollingLoop,
        key: ProjectionKey,
        ownerDigest: String,
    ): ProjectionLoopHandle? =
        if (properties.enabled) loop.start(key, ownerDigest) else null
}
