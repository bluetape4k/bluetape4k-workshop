package io.bluetape4k.workshop.imageprocessing.advanced.model

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.storage.ImageObjectMetadataReader
import io.bluetape4k.support.hashOf
import io.bluetape4k.workshop.imageprocessing.advanced.service.ImageKeyFactory
import java.io.Serializable

/**
 * 업로드된 원본 object의 외부 응답용 metadata입니다.
 *
 * `key`는 storage 식별자이고 `url`은 이 예제의 public URL resolver가 만든
 * 읽기 주소입니다. 원본 bytes 자체를 응답에 넣지 않으며, `sizeBytes`와
 * `contentType`은 업로드 결과 또는 선택적 [ImageObjectMetadataReader] snapshot에서
 * 확정합니다. 이 예제의 unsigned URL은 비공개 접근 제어를 의미하지 않습니다.
 * ```json
 * { "key": "images/id/original/photo.jpg", "width": 1600, "height": 1200,
 *   "contentType": "image/jpeg", "sizeBytes": 245763 }
 * ```
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property url 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property sizeBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 */
data class OriginalImageMetadata(
    /**
     * `key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: String,
    /**
     * `url`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val url: String,
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
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String,
    /**
     * `sizeBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val sizeBytes: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * delivery 용도로 생성된 단일 파생 이미지의 응답 metadata입니다.
 *
 * [name]은 `thumb-128` 같은 업무 variant 이름이고 [key]와 [url]은 원본과
 * 분리된 object 주소입니다. [contentType]은 이 workflow가 실제로 저장한
 * 형식(기본적으로 WebP)을 나타내므로 화면 클라이언트가 파일 확장자만 믿지
 * 않고 응답 계약을 따를 수 있습니다.
 * @property name 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property url 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 * @property sizeBytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 */
data class ImageVariantMetadata(
    /**
     * `name`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: String,
    /**
     * `url`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val url: String,
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
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String,
    /**
     * `sizeBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val sizeBytes: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `POST /api/images/derivatives` 성공 응답입니다.
 *
 * 하나의 [imageId]에 원본과 모든 [variants]를 묶어 반환합니다. [warnings]는
 * 성공을 취소하지 않는 비치명적 상황을 전달하는 용도이며, 처리 실패는 이
 * 응답 대신 job/asset failure 경계로 전달됩니다.
 * ```kotlin
 * val response = client.post("/api/images/derivatives")
 * // response.imageId -> asset 조회와 history 조회의 공통 식별자
 * // response.variants.map { it.url } -> 화면 전달용 URL
 * ```
 * @property imageId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property original 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property thumbnailUrl 클라이언트가 읽을 수 있는 전달 주소입니다. 접근 제어·서명 만료·파일 형식 정책을 저장 키와 분리해 적용합니다.
 * @property variants 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 * @property durationMillis 재시도·임대·보존·처리 제한을 결정하는 시간 길이입니다. 이 값은 장애 시 무한 대기나 중복 실행을 막는 정책 경계입니다.
 * @property warnings 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 */
data class ImageProcessingResponse(
    /**
     * `imageId`은 이미지 업로드·파생 처리에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val imageId: String,
    /**
     * `original`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val original: OriginalImageMetadata,
    /**
     * `thumbnailUrl`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val thumbnailUrl: String,
    /**
     * `variants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val variants: List<ImageVariantMetadata>,
    /**
     * `durationMillis`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val durationMillis: Long,
    /**
     * `warnings`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val warnings: List<String> = emptyList(),
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * native 처리 결과에서 원본의 decoded dimension만 전달하는 내부 값입니다.
 *
 * 원본 bytes는 보관하지 않습니다. [width]와 [height]는 pixel budget 검증이
 * 끝난 뒤 만들어지며, 이 값은 최종 응답의 [OriginalImageMetadata]를 조립하는
 * 데 사용됩니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 */
data class OriginalImageInfo(
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
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * native processor가 만든 업로드 전 파생 이미지입니다.
 *
 * [bytes]는 아직 storage에 쓰기 전의 단일 variant payload이고 [key]는
 * [ImageKeyFactory]가 검증한 object key입니다. [sizeBytes]는 별도 상태가
 * 아니라 bytes 길이에서 계산되므로 payload와 크기가 어긋나지 않습니다.
 * 실제 업로드 뒤의 authoritative size/content type은 storage metadata
 * snapshot이 덮어쓸 수 있습니다.
 * @property name 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property key 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 비교 시 변조 여부를 확인합니다.
 * @property bytes 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property width 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property height 입력·파생물의 크기 또는 이미지 처리 예산입니다. 상한을 넘으면 작업을 거부하거나 안전한 실패로 종료해 메모리·저장소 고갈을 막습니다.
 * @property contentType 업로드 또는 파생물의 미디어 형식 메타데이터입니다. 허용 목록·실제 바이트 검사 결과와 일치할 때만 외부에 반환합니다.
 */
data class ProcessedImageVariant(
    /**
     * `name`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `key`은 이미지 업로드·파생 처리에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val key: ImageObjectKey,
    /**
     * `bytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val bytes: ByteArray,
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
    /**
     * `contentType`은 이미지 업로드·파생 처리에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val contentType: String,
): Serializable {
    /**
     * `sizeBytes`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val sizeBytes: Long get() = bytes.size.toLong()

    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }

    /**
     * equals은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param other 업무 처리에 필요한 other 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    override fun equals(other: Any?): Boolean {
        return this === other ||
                other is ProcessedImageVariant &&
                name == other.name &&
                key == other.key &&
                bytes.contentEquals(other.bytes) &&
                width == other.width &&
                height == other.height &&
                contentType == other.contentType
    }

    /**
     * hashCode은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun hashCode(): Int = hashOf(name, key, bytes.contentHashCode(), width, height, contentType)

    /**
     * toString은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun toString(): String =
        ToStringBuilder(this)
            .add("name", name)
            .add("key", key)
            .add("bytesSize", bytes.size)
            .add("width", width)
            .add("height", height)
            .add("contentType", contentType)
            .toString()
}

/**
 * 하나의 입력에서 계산된 원본 dimension과 파생 이미지 목록의 묶음입니다.
 *
 * ```kotlin
 * val processed = processor.process(bytes, imageId)
 * processed.variants.forEach { storage.upload(it.key, it.bytes, options) }
 * ```
 * 이 값은 processing과 storage 사이의 경계이며, 여기서 성공했다고 해서
 * 아직 database job이 성공한 것은 아닙니다. 저장이 끝난 뒤 saga가 완료
 * event를 기록합니다.
 * @property original 이미지 업로드·검사·파생물 처리 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property variants 이미지 업로드·검사·파생물 처리 흐름의 전달 데이터입니다. 호출 경계에서 검증하고 후속 상태 전이·감사·응답 조립에 필요한 정보만
 * 포함합니다.
 */
data class ProcessedImageSet(
    /**
     * `original`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val original: OriginalImageInfo,
    /**
     * `variants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val variants: List<ProcessedImageVariant>,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 이미지 업로드·파생 처리에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
