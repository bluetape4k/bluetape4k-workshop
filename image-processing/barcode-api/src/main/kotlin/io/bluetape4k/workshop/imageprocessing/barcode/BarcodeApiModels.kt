package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.barcode.BarcodeFormat
import io.bluetape4k.support.requireNotBlank
import org.springframework.http.HttpStatus
import java.io.Serializable

/**
 * barcode 추출 API의 성공 응답입니다.
 *
 * [count]는 [results]와 항상 같아야 하며, code를 찾지 못한 정상 결과는
 * 오류가 아니라 `count=0`과 빈 목록으로 표현합니다. [provider]는 응답
 * 소비자가 특정 decoder에 의존하지 않도록 문자열 식별자로만 노출합니다.
 * ```json
 * { "count": 1, "results": [
 *   { "text": "https://example.com/order/42", "format": "QR_CODE", "provider": "ZXING" }
 * ] }
 * ```
 * @property count 동시 처리량·할당량·집계 수량입니다. 음수와 한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property results 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */
internal data class BarcodeExtractionResponse(
    /**
     * `count`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val count: Int,
    /**
     * `results`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val results: List<BarcodeResultResponse>,
): Serializable {

    init {
        require(count == results.size) { "count must match results.size" }
    }

    private companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 하나의 decoded barcode입니다.
 *
 * 빈 [text]는 업무상 결과가 아니므로 생성 시 거부합니다. [format]은
 * Bluetape barcode 계약의 값이며 [provider]는 adapter가 실제로 사용한
 * decoder 이름을 설명할 뿐 application service의 분기 기준이 아닙니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property format 이미지 업로드·검사·파생물 처리의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 * @property provider 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
internal data class BarcodeResultResponse(
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `format`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val format: BarcodeFormat,
    /**
     * `provider`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val provider: String,
): Serializable {

    init {
        text.requireNotBlank("text")
        provider.requireNotBlank("provider")
    }

    private companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 추출 실패를 HTTP 응답으로 직렬화하기 위한 안정적인 오류 모델입니다.
 *
 * [error]는 기계가 읽는 bounded code이고 [message]는 사용자에게 보여줄 수
 * 있는 짧은 설명입니다. 원본 bytes, 파일 경로, native stack trace는 담지
 * 않습니다.
 * @property error 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property reason 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 * @property message 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 */
internal data class BarcodeErrorResponse(
    /**
     * `error`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val error: String,
    /**
     * `reason`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reason: String? = null,
    /**
     * `message`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val message: String,
): Serializable {

    init {
        error.requireNotBlank("error")
        reason?.requireNotBlank("reason")
        message.requireNotBlank("message")
    }

    private companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 업로드 경계에서 HTTP status와 공개 오류 code를 함께 전달하는 예외입니다.
 *
 * `413 payload_too_large`는 service의 byte/dimension/pixel 사전 확인이
 * 거부한 경우이고, provider-neutral `BarcodeException`은 decoder adapter
 * 실패를 나타냅니다. 취소 예외는 이 타입으로 감싸지 않습니다.
 * @property status 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property error 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal class BarcodeRequestException(
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: HttpStatus,
    /**
     * `error`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val error: String,
    message: String,
): RuntimeException(message)
