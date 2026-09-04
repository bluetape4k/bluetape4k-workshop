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
import io.bluetape4k.images.thumbnail.ThumbnailCrop
import io.bluetape4k.images.thumbnail.ThumbnailSize
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
 */
class ProfileImageProcessor(
    private val properties: ProfileImageModerationProperties,
) {

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
     * 원본 metadata와 output을 모두 strict하게 확인한 public-safe derivative를 만듭니다.
     *
     * 원본 bytes는 이 함수에서 변경하거나 결과 report에 복사하지 않습니다. pipeline의
     * 제한된 [PrivacyDerivativeReport]만 [ProcessedProfileImage]에 보존하며, verification
     * 실패 시 어떤 public derivative도 성공으로 반환하지 않습니다.
     */
    suspend fun processPrivacySafe(
        bytes: ByteArray,
        redactions: List<PrivacyRedaction> = emptyList(),
    ): ProcessedProfileImage {
        val sourceMetadata = readSourceMetadata(bytes)
        val image = decodeWithImageIo(bytes)
        validateDimensions(image)
        val sourceImage = decodeImmutable(bytes)

        val approved = sourceImage.suspendPrivacyDerivative(
            options = privacyOptions(sourceImage, sourceMetadata.exif.orientation, APPROVED_MAX_DIMENSION, redactions),
            sourceExif = sourceMetadata.exif,
            sourceMetadata = sourceMetadata,
        )

        val pendingBase = immutableImageOf(writeJpeg(blur(scale(image, PENDING_MAX_DIMENSION))))
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
        )
    }

    private fun createLegacyDerivatives(image: BufferedImage): ProcessedProfileImage {
        require(image.width in 1..properties.maxWidth) { "image width exceeds maxWidth" }
        require(image.height in 1..properties.maxHeight) { "image height exceeds maxHeight" }
        require(image.width.toLong() * image.height.toLong() <= properties.maxPixels) { "image pixels exceed maxPixels" }
        val approved = scaleToJpeg(image, APPROVED_MAX_DIMENSION)
        val pending = blur(scale(image, PENDING_MAX_DIMENSION))
        return ProcessedProfileImage(
            width = image.width,
            height = image.height,
            pendingBytes = writeJpeg(pending),
            approvedBytes = writeJpeg(approved),
        )
    }

    private fun decodeWithImageIo(bytes: ByteArray): BufferedImage =
        try {
            ImageIO.read(ByteArrayInputStream(bytes))
                ?: throw IllegalArgumentException("uploaded image cannot be decoded")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("uploaded image cannot be decoded")
        }

    private fun decodeImmutable(bytes: ByteArray): ImmutableImage =
        try {
            immutableImageOf(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("uploaded image cannot be decoded")
        }

    private fun validateDimensions(image: BufferedImage) {
        require(image.width in 1..properties.maxWidth) { "image width exceeds maxWidth" }
        require(image.height in 1..properties.maxHeight) { "image height exceeds maxHeight" }
        require(image.width.toLong() * image.height.toLong() <= properties.maxPixels) { "image pixels exceed maxPixels" }
    }

    private fun readSourceMetadata(bytes: ByteArray): ImageMetadataReport =
        try {
            when (
                val result = readImageMetadataReportStrict(
                    bytes,
                    ImageMetadataReadOptions(
                        maxBytes = properties.privacy.maxMetadataBytes,
                        stripSensitiveMetadata = false,
                    ),
                )
            ) {
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

    private fun scaleToJpeg(source: BufferedImage, maxDimension: Int): BufferedImage = scale(source, maxDimension)

    private fun blur(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.drawImage(source, 0, 0, null)
            graphics.color = Color(255, 255, 255, 110)
            graphics.fillRect(0, 0, source.width, source.height)
            graphics.color = Color(0, 0, 0, 50)
            repeat(8) { step -> graphics.drawRect(step, step, source.width - step * 2 - 1, source.height - step * 2 - 1) }
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun writeJpeg(image: BufferedImage): ByteArray {
        val output = ByteArrayOutputStream()
        require(ImageIO.write(image, "jpg", output)) { "JPEG writer is not available" }
        return output.toByteArray()
    }

    companion object {
        private const val PENDING_MAX_DIMENSION = 96
        private const val APPROVED_MAX_DIMENSION = 512
    }
}
