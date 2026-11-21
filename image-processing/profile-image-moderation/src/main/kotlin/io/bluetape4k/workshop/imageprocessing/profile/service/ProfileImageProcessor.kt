package io.bluetape4k.workshop.imageprocessing.profile.service

import com.sksamuel.scrimage.ImmutableImage
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.ImageMetadataReadResult
import io.bluetape4k.images.analysis.ImageMetadataReport
import io.bluetape4k.images.analysis.readImageMetadataReportStrict
import io.bluetape4k.images.immutableImageOf
import io.bluetape4k.images.privacy.PrivacyDerivativeFormat
import io.bluetape4k.images.privacy.PrivacyDerivativeOptions
import io.bluetape4k.images.privacy.PrivacyRedaction
import io.bluetape4k.images.privacy.suspendPrivacyDerivative
import io.bluetape4k.images.privacy.toPayload
import io.bluetape4k.images.thumbnail.ThumbnailCrop
import io.bluetape4k.images.thumbnail.ThumbnailSize
import io.bluetape4k.images.useGraphics
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ProcessedProfileImage
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.min
import kotlin.math.roundToInt

@Component
/**
 * 대기 중인 블러 이미지와 승인된 공개 파생 이미지를 생성합니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class ProfileImageProcessor(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ProfileImageModerationProperties,
) {

    companion object: KLoggingChannel() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val PENDING_MAX_DIMENSION = 96
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val APPROVED_MAX_DIMENSION = 512
    }

    /**
     * 기존 호출자를 위한 ImageIO derivative 생성 경로입니다.
     *
     * 새 업로드 흐름은 [processPrivacySafe]를 사용해 public output을 strict하게
     * 검증합니다.
     */
    fun process(bytes: ByteArray): ProcessedProfileImage {
        val image = decodeWithImageIo(bytes)
        validateDimensions(image)
        return createLegacyDerivatives(image)
    }

    /**
     * 원본 metadata와 output을 strict하게 확인한 privacy-safe 산출물 및 영속화 payload를 생성합니다.
     *
     * 원본 bytes는 이 함수에서 변경하거나 결과 report에 복사하지 않습니다. 제한된
     * [PrivacyDerivativeReport]만 runtime 진단용으로 보존하며, verification 실패 시 어떤
     * public derivative도 성공으로 반환하지 않습니다.
     * [sourceId]는 bounded report snapshot에 opaque 식별자로 기록합니다. 직접 호출 호환성을 위해
     * 생략할 수 있지만, 업로드 흐름에서는 재시작·메시지 경계에서 산출물을 추적할 수 있도록 upload id를 전달합니다.
     */
    suspend fun processPrivacySafe(
        bytes: ByteArray,
        redactions: List<PrivacyRedaction> = emptyList(),
        sourceId: String? = null,
    ): ProcessedProfileImage {
        /**
         * `sourceMetadata`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val sourceMetadata = readSourceMetadata(bytes)
        /**
         * `image`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val image = decodeWithImageIo(bytes)
        validateDimensions(image)
        /**
         * `sourceImage`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val sourceImage = decodeImmutable(bytes)

        /**
         * `approved`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val approved = sourceImage.suspendPrivacyDerivative(
            options = privacyOptions(sourceImage, sourceMetadata.exif.orientation, APPROVED_MAX_DIMENSION, redactions),
            sourceExif = sourceMetadata.exif,
            sourceMetadata = sourceMetadata,
        )

        /**
         * `pendingBase`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val pendingBase = immutableImageOf(writeJpeg(blur(scale(image, PENDING_MAX_DIMENSION))))
        /**
         * `pending`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val pending = pendingBase.suspendPrivacyDerivative(
            options = privacyOptions(pendingBase, sourceMetadata.exif.orientation, PENDING_MAX_DIMENSION, redactions),
            sourceExif = sourceMetadata.exif,
            sourceMetadata = sourceMetadata,
        )

        return ProcessedProfileImage(
            width = image.width,
            height = image.height,
            pendingBytes = pending.bytes,
            approvedBytes = approved.bytes,
            pendingPrivacyReport = pending.report,
            approvedPrivacyReport = approved.report,
            pendingPrivacyPayload = pending.toPayload(sourceId),
            approvedPrivacyPayload = approved.toPayload(sourceId),
        )
    }

    /**
     * createLegacyDerivatives은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은
     * revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param image 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun createLegacyDerivatives(image: BufferedImage): ProcessedProfileImage {
        validateDimensions(image)

        val approved = scaleToJpeg(image, APPROVED_MAX_DIMENSION)
        val pending = blur(scale(image, PENDING_MAX_DIMENSION))

        return ProcessedProfileImage(
            width = image.width,
            height = image.height,
            pendingBytes = writeJpeg(pending),
            approvedBytes = writeJpeg(approved),
        )
    }

    /**
     * decodeWithImageIo은 이미지 업로드·검수·파생물 처리 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전
     * 불일치·무결성 오류를 업무
     * 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decodeWithImageIo(bytes: ByteArray): BufferedImage =
        try {
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw IllegalArgumentException("uploaded image cannot be decoded")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("uploaded image cannot be decoded")
        }

    /**
     * decodeImmutable은 이미지 업로드·검수·파생물 처리 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전 불일치·무결성
     * 오류를 업무
     * 오류로 분류합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun decodeImmutable(bytes: ByteArray): ImmutableImage =
        try {
            immutableImageOf(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("uploaded image cannot be decoded")
        }

    /**
     * validateDimensions은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param image 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun validateDimensions(image: BufferedImage) {
        image.width.requireInRange(1, properties.maxWidth, "image width")
        image.height.requireInRange(1, properties.maxHeight, "image height")
        require(image.width.toLong() * image.height.toLong() <= properties.maxPixels) { "image pixels exceed maxPixels" }
    }

    /**
     * readSourceMetadata은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun readSourceMetadata(bytes: ByteArray): ImageMetadataReport =
        try {
            /**
             * `result`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val result = readImageMetadataReportStrict(
                bytes,
                ImageMetadataReadOptions(
                    maxBytes = properties.privacy.maxMetadataBytes,
                    stripSensitiveMetadata = false,
                ),
            )
            when (result) {
                is ImageMetadataReadResult.Success -> result.report
                is ImageMetadataReadResult.Failure ->
                    throw IllegalArgumentException("uploaded image metadata could not be inspected")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            if (e.message == "uploaded image metadata could not be inspected") throw e
            throw IllegalArgumentException("uploaded image metadata could not be inspected")
        } catch (e: Exception) {
            throw IllegalArgumentException("uploaded image metadata could not be inspected")
        }

    /**
     * privacyOptions은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param image 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     * @param orientation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param maxDimension 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     * @param redactions 업무 처리에 필요한 redactions 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun privacyOptions(
        image: ImmutableImage,
        orientation: Int?,
        maxDimension: Int,
        redactions: List<PrivacyRedaction>,
    ): PrivacyDerivativeOptions {
        val rotatesDimensions = properties.privacy.normalizeOrientation && orientation != null && orientation in 5..8
        val orientedWidth = if (rotatesDimensions) image.height else image.width
        val orientedHeight = if (rotatesDimensions) image.width else image.height
        val ratio = min(1.0, maxDimension.toDouble() / maxOf(orientedWidth, orientedHeight).toDouble())
        val targetWidth = (orientedWidth * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (orientedHeight * ratio).roundToInt().coerceAtLeast(1)
        return PrivacyDerivativeOptions(
            stripMetadata = properties.privacy.stripMetadata,
            removeGps = properties.privacy.removeGps,
            normalizeOrientation = properties.privacy.normalizeOrientation,
            maxPixels = properties.maxPixels,
            thumbnailSize = ThumbnailSize(targetWidth, targetHeight, suffix = "public-$maxDimension"),
            thumbnailCrop = ThumbnailCrop.Fit,
            outputFormat = PrivacyDerivativeFormat.Jpeg,
            redactions = redactions,
        )
    }

    /**
     * scale은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param source 업무 처리에 필요한 source 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param maxDimension 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun scale(source: BufferedImage, maxDimension: Int): BufferedImage {
        val ratio = min(1.0, maxDimension.toDouble() / maxOf(source.width, source.height).toDouble())
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /**
     * scaleToJpeg은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param source 업무 처리에 필요한 source 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param maxDimension 한 번에 처리할 상한입니다. backpressure와 운영 자원 한도를 넘지 않도록 적용합니다.
     */
    private fun scaleToJpeg(source: BufferedImage, maxDimension: Int): BufferedImage =
        scale(source, maxDimension)

    /**
     * blur은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param source 업무 처리에 필요한 source 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun blur(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        target.useGraphics { graphics ->
            graphics.drawImage(source, 0, 0, null)
            graphics.color = Color(255, 255, 255, 110)
            graphics.fillRect(0, 0, source.width, source.height)
            graphics.color = Color(0, 0, 0, 50)
            repeat(8) { step ->
                graphics.drawRect(
                    step,
                    step,
                    source.width - step * 2 - 1,
                    source.height - step * 2 - 1
                )
            }
        }
        return target
    }

    /**
     * writeJpeg은 이미지 업로드·검수·파생물 처리의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param image 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun writeJpeg(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        require(ImageIO.write(image, "jpg", output)) { "JPEG writer is not available" }
        return output.toByteArray()
    }
}
