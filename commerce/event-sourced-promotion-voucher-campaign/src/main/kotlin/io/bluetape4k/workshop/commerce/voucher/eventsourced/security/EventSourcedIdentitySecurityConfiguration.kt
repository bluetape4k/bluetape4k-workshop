package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.util.*
/**
 * EventSourcedHmacProperties은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property activeVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property activeKeyBase64 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property retired 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


@ConfigurationProperties("voucher.security.hmac")
internal data class EventSourcedHmacProperties(
    /**
     * `activeVersion`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val activeVersion: Int = 1,
    /**
     * `activeKeyBase64`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val activeKeyBase64: String = "",
    /**
     * `retired`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retired: List<RetiredHmacKeyProperties> = emptyList(),
)
/**
 * RetiredHmacKeyProperties은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property version 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property keyBase64 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property retainUntil 프로모션 바우처의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class RetiredHmacKeyProperties(
    /**
     * `version`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val version: Int,
    /**
     * `keyBase64`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val keyBase64: String,
    /**
     * `retainUntil`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retainUntil: Instant,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventSourcedHmacProperties::class)
/**
 * EventSourcedIdentitySecurityConfiguration은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태
 * 전이,
 * 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class EventSourcedIdentitySecurityConfiguration {

    companion object: KLogging()

    /**
     * eventSourcedHmacKeyRing은 프로모션 바우처 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를
     * 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean
    fun eventSourcedHmacKeyRing(
        properties: EventSourcedHmacProperties,
        clock: Clock,
    ): EventSourcedHmacKeyRing =
        EventSourcedHmacKeyRing(
            active =
                EventSourcedHmacKey(
                    version = properties.activeVersion.requirePositiveNumber("voucher.security.hmac.active-version"),
                    material = decodeKey(properties.activeKeyBase64, "voucher.security.hmac.active-key-base64"),
                ),
            retired =
                properties.retired
                    .filter { retired -> retired.retainUntil.isAfter(clock.instant()) }
                    .map { retired ->
                        EventSourcedHmacKey(
                            version = retired.version.requirePositiveNumber("voucher.security.hmac.retired.version"),
                            material = decodeKey(retired.keyBase64, "voucher.security.hmac.retired.key-base64"),
                        )
                    },
        )

    /**
     * subjectIdentityRepository은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param keyRing 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param clock 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean
    fun subjectIdentityRepository(
        keyRing: EventSourcedHmacKeyRing,
        clock: Clock,
    ): SubjectIdentityRepository = SubjectIdentityRepository(keyRing, clock)

    /**
     * subjectIdentityService은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param permits 업무 처리에 필요한 permits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun subjectIdentityService(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
        repository: SubjectIdentityRepository,
    ): SubjectIdentityService =
        ExposedSubjectIdentityService(
            database = registration.database,
            permits = permits,
            repository = repository,
        )

    /**
     * decodeKey은 프로모션 바우처 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param encoded 업무 처리에 필요한 encoded 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param propertyName 업무 처리에 필요한 propertyName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decodeKey(
        encoded: String,
        propertyName: String,
    ): ByteArray =
        Base64
            .getDecoder()
            .decode(encoded.requireNotBlank(propertyName))
}
