package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.workshop.imageprocessing.profile.config.ProfileImageModerationProperties
import io.bluetape4k.workshop.imageprocessing.profile.model.ProcessedProfileImage
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
 * Produces the pending blurred image and the approved public derivative.
 */
class ProfileImageProcessor(
    private val properties: ProfileImageModerationProperties,
) {

    fun process(bytes: ByteArray): ProcessedProfileImage {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IllegalArgumentException("uploaded image cannot be decoded")
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
