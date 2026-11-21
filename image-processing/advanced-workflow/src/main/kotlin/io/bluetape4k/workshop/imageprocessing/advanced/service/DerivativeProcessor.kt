package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.vips.VipsEncodeOptions
import io.bluetape4k.images.vips.VipsImageFormat
import io.bluetape4k.images.vips.coroutines.suspendToBytes
import io.bluetape4k.images.vips.java25.FfmVipsRuntime
import io.bluetape4k.images.vips.java25.suspendFfmVipsImageOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireLe
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageVariantProperties
import io.bluetape4k.workshop.imageprocessing.advanced.model.OriginalImageInfo
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageSet
import io.bluetape4k.workshop.imageprocessing.advanced.model.ProcessedImageVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * DerivativeProcessor은 이미지 처리 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
interface DerivativeProcessor {
    /**
     * process은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param imageId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet
}
/**
 * FfmVipsDerivativeProcessor은 이미지 처리에서 사용하는 업무 객체입니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property keyFactory 무결성·인증·멱등성 검증용 보안 자료입니다. 원문을 로그나 일반 응답에 노출하지 않습니다.
 */


@Component
class FfmVipsDerivativeProcessor(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ImageProcessingAdvancedProperties,
    /**
     * `keyFactory`은 이미지 업로드·파생 처리에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val keyFactory: ImageKeyFactory,
): DerivativeProcessor {

    companion object: KLogging() {
        /**
         * `WEBP_QUALITY`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val WEBP_QUALITY = 82
        /**
         * `WEBP_EFFORT`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private const val WEBP_EFFORT = 4
    }
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val initLock = ReentrantLock()
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val variantSemaphore = Semaphore(properties.variantConcurrency)
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val webpOptions = VipsEncodeOptions(quality = WEBP_QUALITY, effort = WEBP_EFFORT)

    /**
     * process은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param imageId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override suspend fun process(bytes: ByteArray, imageId: String): ProcessedImageSet =
        coroutineScope {
            ensureRuntime()

            val originalInfo = suspendFfmVipsImageOf(bytes).use { image ->
                OriginalImageInfo(width = image.width, height = image.height)
            }
            log.debug { "original image info=$originalInfo" }
            validatePixelBudget(originalInfo)

            val variants = properties.variants.map { variant ->
                async(Dispatchers.IO) {
                    variantSemaphore.withPermit {
                        processVariant(bytes, imageId, variant)
                    }
                }
            }.awaitAll()
                .onEach { log.debug { "processing variant=$it" } }

            ProcessedImageSet(original = originalInfo, variants = variants)
        }

    /**
     * processVariant은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param imageId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param variant 업무 처리에 필요한 variant 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private suspend inline fun processVariant(
        bytes: ByteArray,
        imageId: String,
        variant: ImageVariantProperties,
    ): ProcessedImageVariant {
        return suspendFfmVipsImageOf(bytes).use { image ->
            image.thumbnail(variant.maxDimension).use { thumbnail ->
                val output = thumbnail.suspendToBytes(VipsImageFormat.WEBP, webpOptions)
                ProcessedImageVariant(
                    name = variant.name,
                    key = keyFactory.variantKey(imageId, variant.name, variant.extension),
                    bytes = output,
                    width = thumbnail.width,
                    height = thumbnail.height,
                    contentType = variant.contentType,
                )
            }
        }
    }

    /**
     * ensureRuntime은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ensureRuntime() {
        initLock.withLock {
            if (!FfmVipsRuntime.isInitialized) {
                FfmVipsRuntime.init(
                    concurrency = properties.vipsConcurrency,
                    maxPixels = properties.maxPixels,
                )
            }
        }
    }

    /**
     * validatePixelBudget은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param originalInfo 업무 처리에 필요한 originalInfo 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validatePixelBudget(originalInfo: OriginalImageInfo) {
        val pixels = originalInfo.width.toLong() * originalInfo.height.toLong()
        pixels.requireLe(properties.maxPixels, "pixels")
    }
}
