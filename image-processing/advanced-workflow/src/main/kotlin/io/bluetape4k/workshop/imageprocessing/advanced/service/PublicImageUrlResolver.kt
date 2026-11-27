package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.images.spring.policy.PublicBaseUriPolicy
import org.springframework.stereotype.Component
import java.net.URI
/**
 * PublicImageUrlResolver은 이미지 처리에서 사용하는 업무 객체입니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class PublicImageUrlResolver(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ImageProcessingAdvancedProperties,
    storageProperties: ImageStorageProperties,
) {
    private val baseUri: URI = validateBaseUri(properties, storageProperties)
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val baseUrl: String = baseUri.toString().trimEnd('/')

    /**
     * resolve은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun resolve(key: ImageObjectKey): String =
        "$baseUrl/${key.fullKey}"

    /**
     * validateBaseUri은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param properties 업무 처리에 필요한 properties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param storageProperties 업무 처리에 필요한 storageProperties 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateBaseUri(
        properties: ImageProcessingAdvancedProperties,
        storageProperties: ImageStorageProperties,
    ): URI {
        return PublicBaseUriPolicy(
            allowInsecurePublicBaseUrl = properties.allowInsecurePublicBaseUrl,
            allowLocalStorageRemotePublicBaseUrl = properties.allowLocalStorageRemotePublicBaseUrl,
        ).validate(
            publicBaseUrl = properties.publicBaseUrl,
            storageBackendIsLocal = storageProperties.backend == ImageStorageProperties.Backend.LOCAL,
        )
    }
}
