package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.images.spring.autoconfigure.ImageStorageProperties
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireNull
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import org.springframework.stereotype.Component
import java.net.URI

@Component
/**
 * 공개 객체 키를 안전한 외부 프로필 이미지 URL로 변환합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageUrlResolver(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
    storageProperties: ImageStorageProperties,
) {

    companion object: KLogging() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val baseUri: URI = validateBaseUri(properties, storageProperties)
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val baseUrl = baseUri.toString().trimEnd('/')

    /**
     * resolve은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param key 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun resolve(key: ImageObjectKey): String {
        require(!key.fullKey.contains("/private/")) { "private profile image keys are not public" }
        require(key.fullKey.contains("/pending/") || key.fullKey.contains("/public/") || key.fullKey.startsWith("profile-images/default/")) {
            "profile image public URL requires pending, public, or default key"
        }
        return "$baseUrl/${key.fullKey}"
    }

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
        properties: ProfileImageModerationProperties,
        storageProperties: ImageStorageProperties,
    ): URI {
        val baseUri = URI.create(properties.publicBaseUrl)
        baseUri.scheme.requireNotNull("publicBaseUrl.scheme")
        baseUri.host.requireNotNull("publicBaseUrl.host")
        baseUri.rawQuery.requireNull("publicBaseUrl.query")
        baseUri.rawFragment.requireNull("publicBaseUrl.fragment")
        baseUri.userInfo.requireNull("publicBaseUrl.userInfo")
        require(!baseUri.path.contains("..") && baseUri.rawPath?.contains("%2e", ignoreCase = true) != true) {
            "publicBaseUrl path must not include '..'"
        }
        val loopback = baseUri.host in LOOPBACK_HOSTS
        val https = baseUri.scheme.equals("https", ignoreCase = true)
        val http = baseUri.scheme.equals("http", ignoreCase = true)
        require(https || (http && (loopback || properties.allowInsecurePublicBaseUrl))) {
            "publicBaseUrl must use https unless it is loopback local dev or allowInsecurePublicBaseUrl=true"
        }
        if (storageProperties.backend == ImageStorageProperties.Backend.LOCAL && !loopback) {
            require(properties.allowLocalStorageRemotePublicBaseUrl) {
                "local ImageStorage with remote publicBaseUrl requires allowLocalStorageRemotePublicBaseUrl=true"
            }
        }
        return baseUri
    }

}
