package io.bluetape4k.workshop.imageprocessing.ocr.model

import io.bluetape4k.images.ocr.OcrStructuredDetail
import java.io.Serializable

/**
 * 워크숍 API가 반환하는 OCR 처리 상태입니다.
 *
 * - [COMPLETED] — engine이 요청한 입력을 처리해 text를 반환했습니다.
 * - [UNAVAILABLE] — 입력은 유효하지만 native capability가 꺼져 있거나
 *   runtime에서 사용할 수 없어 fallback을 반환했습니다. 재시도 전에 운영자가
 *   engine 설정과 trained data를 확인해야 합니다.
 * - [FAILED] — 입력 또는 processing 단계가 계약을 만족하지 못했습니다. HTTP
 *   경계에서는 sanitized `ProblemDetail`로 매핑될 수 있습니다.
 */
enum class OcrStatus {
    /** engine이 입력을 처리했고 caller가 text와 구조화 결과를 사용할 수 있습니다. */
    /** engine이 입력을 처리했고 caller가 text와 구조화 결과를 사용할 수 있습니다. */
    COMPLETED,
    /** 입력은 유효하지만 native capability가 없어 명시적 fallback 결과를 반환합니다. */
    /** 입력은 유효하지만 native capability가 없어 명시적 fallback 결과를 반환합니다. */
    UNAVAILABLE,
    /** 입력 검증 또는 OCR 처리 계약을 만족하지 못해 sanitized 오류로 반환합니다. */
    /** 입력 검증 또는 OCR 처리 계약을 만족하지 못해 sanitized 오류로 반환합니다. */
    FAILED,
}

/**
 * 서비스 계층 OCR 요청입니다.
 *
 * [bytes]는 업로드 원본이며 서비스가 byte/pixel/TIFF budget을 검사합니다.
 * [languages]는 Tesseract language code이고 [structuredDetail]은 요청한
 * 상세 수준입니다. 이 값만으로 결과가 보장되는 것은 아니며 runtime engine
 * capability에 따라 [OcrStructuredDetail.PLAIN_TEXT]로 fallback할 수 있습니다.
 * ```kotlin
 * val request = ImageOcrRequest(
 *     bytes = imageBytes,
 *     contentType = "image/png",
 *     languages = listOf("eng", "kor"),
 *     structuredDetail = OcrStructuredDetail.WORD,
 * )
 * ```
 * @property bytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property languages 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property structuredDetail 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 */
data class ImageOcrRequest(
    /**
     * `bytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val bytes: ByteArray,
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String?,
    /**
     * `languages`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val languages: List<String>,
    /**
     * `structuredDetail`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val structuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = -1524473156985272973L
    }
}

/**
 * 구조화된 OCR API 응답입니다.
 *
 * [effectiveStructuredDetail]은 caller가 요청한 값이 아니라 engine이 실제로
 * 반환한 상세 수준입니다. [confidence]는 하위 결과를 평균내어 만들어내지
 * 않으므로 nullable이고, [pages]/[lines]/[words]도 capability가 없으면
 * 빈 목록일 수 있습니다. [warnings]는 성공/사용 불가 상태를 설명하지만
 * 원본 bytes나 OCR text를 포함해서는 안 됩니다.
 * ```json
 * { "status": "UNAVAILABLE", "engine": "disabled", "text": "",
 *   "effectiveStructuredDetail": "PLAIN_TEXT", "pages": [], "warnings": ["..."] }
 * ```
 * @property requestId 요청 추적 식별자입니다. API 오류·로그·이벤트를 하나의 업무 시도로 연결하되 비즈니스 식별자와 혼동하지 않습니다.
 * @property status 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property engine 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property languages 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property confidence 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property blocks 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 * @property warnings 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property effectiveStructuredDetail 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의
 * 의미를
 * 보존해야 합니다.
 * @property pages 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property lines 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 * @property words 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만 포함합니다.
 */
data class ImageOcrResponse(
    /**
     * `requestId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val requestId: String,
    /**
     * `status`은 이미지 업로드·파생 처리에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: OcrStatus,
    /**
     * `engine`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val engine: String,
    /**
     * `languages`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val languages: List<String>,
    /**
     * `confidence`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: Double?,
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `blocks`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val blocks: List<OcrTextBlock>,
    /**
     * `warnings`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val warnings: List<String>,
    /**
     * `effectiveStructuredDetail`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val effectiveStructuredDetail: OcrStructuredDetail = OcrStructuredDetail.PLAIN_TEXT,
    /**
     * `pages`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pages: List<OcrPage> = emptyList(),
    /**
     * `lines`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lines: List<OcrTextLine> = emptyList(),
    /**
     * `words`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val words: List<OcrWord> = emptyList(),
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = -1667282267859767983L
    }
}

/**
 * 라인 기반 OCR 텍스트 블록입니다.
 *
 * 기존 plain-text consumer를 위한 호환 필드입니다. 구조화 engine이 page 또는
 * 위치 정보를 제공하지 않으면 [pageIndex]와 [boundingBox]는 `null`이며,
 * 없는 정보를 합성해서는 안 됩니다.
 * @property index 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property confidence 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property pageIndex 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property boundingBox 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class OcrTextBlock(
    /**
     * `index`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val index: Int,
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `confidence`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: Double?,
    /**
     * `pageIndex`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pageIndex: Int? = null,
    /**
     * `boundingBox`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 2813846273462115403L
    }
}

/**
 * OCR 결과의 이미지 내 사각형 영역입니다.
 *
 * 원본 이미지 좌표계의 왼쪽 위 [x], [y]와 픽셀 단위 [width], [height]입니다.
 * 값은 engine이 제공한 경우에만 응답에 포함되고 보안/정확성 검사를 대신하지 않습니다.
 * @property x 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property y 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 */
data class OcrBoundingBox(
    /**
     * `x`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val x: Int,
    /**
     * `y`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val y: Int,
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Int,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Int,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = -8542432708570251756L
    }
}

/**
 * 페이지 단위의 구조화된 OCR 결과입니다.
 *
 * TIFF 다중 페이지 응답에서 [pageIndex]는 입력 순서(0부터 시작)를 보존합니다.
 * 페이지별 text를 합칠 때 이 순서를 바꾸면 문서 의미가 달라질 수 있습니다.
 * @property pageIndex 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property confidence 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property boundingBox 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class OcrPage(
    /**
     * `pageIndex`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pageIndex: Int,
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `confidence`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: Double?,
    /**
     * `boundingBox`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 8658553843880049498L
    }
}

/**
 * 라인 단위의 구조화된 OCR 결과입니다.
 *
 * [pageIndex]는 해당 line이 속한 페이지이고 [confidence]/[boundingBox]는
 * engine capability가 없을 때 nullable입니다.
 * @property pageIndex 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property confidence 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property boundingBox 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class OcrTextLine(
    /**
     * `pageIndex`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pageIndex: Int,
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `confidence`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: Double?,
    /**
     * `boundingBox`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 2574707970975838308L
    }
}

/**
 * 단어 단위의 구조화된 OCR 결과입니다.
 *
 * WORD 상세는 가장 많은 결과 entry를 만들 수 있으므로 TIFF result-entry
 * budget의 영향을 크게 받습니다. 소비자는 nullable metadata를 `0`이나 임의
 * 사각형으로 바꾸지 말아야 합니다.
 * @property pageIndex 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property text 외부 경계에서 전달된 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 원본 개인정보와 비밀값의 노출을 제한합니다.
 * @property confidence 현재 이미지 업로드·검사·파생물 처리 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의
 * 문자열로
 * 해석하지 않습니다.
 * @property boundingBox 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */
data class OcrWord(
    /**
     * `pageIndex`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val pageIndex: Int,
    /**
     * `text`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val text: String,
    /**
     * `confidence`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val confidence: Double?,
    /**
     * `boundingBox`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val boundingBox: OcrBoundingBox? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = -5814983464065807540L
    }
}
