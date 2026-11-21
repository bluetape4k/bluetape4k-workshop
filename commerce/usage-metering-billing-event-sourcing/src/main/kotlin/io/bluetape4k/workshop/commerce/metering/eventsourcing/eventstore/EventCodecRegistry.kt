package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber

/**
 * UnknownEventSchemaException은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class UnknownEventSchemaException(eventType: String, version: Int) :
    IllegalStateException("unknown_event_schema:$eventType:$version")

/**
 * EventCodecRegistry은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class EventCodecRegistry {

    companion object: KLogging()
/**
 * Decoder은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property latestVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property decode 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


    private data class Decoder(val latestVersion: Int, val decode: (String) -> Any)
/**
 * UpcasterKey은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property eventType 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property fromVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */

    private data class UpcasterKey(val eventType: String, val fromVersion: Int)
    /**
     * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val decoders = linkedMapOf<String, Decoder>()
    /**
     * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val upcasters = linkedMapOf<UpcasterKey, (String) -> String>()

    /**
     * register은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param latestVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param decoder 업무 처리에 필요한 decoder 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun register(eventType: String, latestVersion: Int, decoder: (String) -> Any): EventCodecRegistry = apply {
        eventType.requireNotBlank { "event_type_invalid" }
        latestVersion.requirePositiveNumber { "schema_version_invalid" }

        check(decoders.putIfAbsent(eventType, Decoder(latestVersion, decoder)) == null) {
            "duplicate_event_decoder:$eventType"
        }
    }

    /**
     * registerUpcaster은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param fromVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param upcaster 업무 처리에 필요한 upcaster 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun registerUpcaster(
        eventType: String,
        fromVersion: Int,
        upcaster: (String) -> String,
    ): EventCodecRegistry = apply {
        fromVersion.requirePositiveNumber { "schema_version_invalid" }
        check(upcasters.putIfAbsent(UpcasterKey(eventType, fromVersion), upcaster) == null) {
            "duplicate_event_upcaster:$eventType:$fromVersion"
        }
    }

    /**
     * validate은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun validate(): EventCodecRegistry = apply {
        decoders.forEach { (eventType, decoder) ->
            for (version in 1 until decoder.latestVersion) {
                check(UpcasterKey(eventType, version) in upcasters) {
                    "broken_upcast_chain:$eventType:$version"
                }
            }
        }
    }

    /**
     * decode은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param schemaVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param payload 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun decode(eventType: String, schemaVersion: Int, payload: String): Any {
        val decoder = decoderFor(eventType, schemaVersion)
        if (schemaVersion > decoder.latestVersion || schemaVersion <= 0) {
            throw UnknownEventSchemaException(eventType, schemaVersion)
        }
        var currentPayload = payload
        var currentVersion = schemaVersion

        while (currentVersion < decoder.latestVersion) {
            val upcaster = upcasterFor(eventType, currentVersion)
            currentPayload = upcaster(currentPayload)
            currentVersion += 1
        }

        return decoder.decode(currentPayload)
    }

    /**
     * decoderFor은 사용량 계량·청구 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성 오류를 업무 오류로
     * 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun decoderFor(eventType: String, version: Int): Decoder =
        decoders[eventType] ?: throw UnknownEventSchemaException(eventType, version)

    /**
     * upcasterFor은 사용량 계량·청구 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param eventType 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param version 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    private fun upcasterFor(eventType: String, version: Int): (String) -> String =
        upcasters[UpcasterKey(eventType, version)]
            ?: throw UnknownEventSchemaException(eventType, version)
}
