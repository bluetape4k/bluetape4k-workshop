package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.spring.ImageObjectKey
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.images.spring.policy.SafeFilenamePolicy
import org.springframework.stereotype.Component

/**
 * 원본과 파생 이미지에 사용할 검증된 [ImageObjectKey]를 만듭니다.
 *
 * object key는 storage 경로 충돌과 경로 탈출을 막기 위한 식별자일 뿐
 * authorization 정책이 아닙니다. 따라서 사용자 파일명은 마지막 path segment만
 * 사용하고 허용되지 않은 문자를 `_`로 바꾸며, 길이를 bounded하게 유지합니다.
 */
@Component
class ImageKeyFactory {

    private val filenamePolicy = SafeFilenamePolicy()

    /**
     * `images/{imageId}/original/{safeFilename}` key를 생성합니다.
     *
     * ```kotlin
     * val key = factory.originalKey("asset-42", "../receipt.jpg")
     * // images/asset-42/original/receipt.jpg
     * ```
     */
    fun originalKey(imageId: String, filename: String?): ImageObjectKey {
        imageId.requireNotBlank("imageId")
        return ImageObjectKey("images/$imageId/original", sanitizeFilename(filename))
    }

    /**
     * `images/{imageId}/variants/{variantName}.{extension}` key를 생성합니다.
     *
     * [variantName]과 [extension]은 설정된 variant 계약에서 온 값이어야 하며,
     * 빈 값은 즉시 거부합니다.
     * ```kotlin
     * factory.variantKey("asset-42", "thumb-128", "webp")
     * // images/asset-42/variants/thumb-128.webp
     * ```
     */
    fun variantKey(imageId: String, variantName: String, extension: String): ImageObjectKey {
        imageId.requireNotBlank("imageId")
        variantName.requireNotBlank("variantName")
        extension.requireNotBlank("extension")
        return ImageObjectKey("images/$imageId/variants", "$variantName.$extension")
    }

    /**
     * 업로드 파일명에서 path와 위험 문자를 제거한 bounded 이름을 반환합니다.
     *
     * 파일명이 없거나 결과가 비어 있으면 `upload.jpg`를 사용합니다. 반환값은
     * 표시용 원본 이름일 뿐 보안 검사의 대체물이 아니므로 MIME/magic-byte
     * 검사는 [UploadImageValidator]가 별도로 수행합니다.
     * ```kotlin
     * factory.sanitizeFilename("C:\\tmp\\holiday photo.jpg") // holiday_photo.jpg
     * ```
     */
    fun sanitizeFilename(filename: String?): String = filenamePolicy.sanitize(filename)


}
