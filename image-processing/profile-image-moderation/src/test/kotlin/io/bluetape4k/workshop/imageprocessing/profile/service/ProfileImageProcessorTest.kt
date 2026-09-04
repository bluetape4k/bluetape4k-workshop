package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.ImageMetadataReadResult
import io.bluetape4k.images.analysis.readImageMetadataReportStrict
import io.bluetape4k.images.moderation.SensitiveCoordinateSpace
import io.bluetape4k.images.moderation.SensitiveRegion
import io.bluetape4k.images.moderation.SensitiveRegionGeometry
import io.bluetape4k.images.privacy.PrivacyMetadataCategory
import io.bluetape4k.images.privacy.PrivacyRedaction
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.Test

class ProfileImageProcessorTest {

    @Test
    fun generated_derivatives_are_jpeg_bytes() {
        val fixture = ProfileImageServiceFixture()
        val processed = ProfileImageProcessor(fixture.properties).process(fixture.sampleJpeg())

        processed.contentType shouldBeEqualTo "image/jpeg"
        processed.pendingBytes.isJpeg() shouldBeEqualTo true
        processed.approvedBytes.isJpeg() shouldBeEqualTo true
    }

    @Test
    fun privacy_safe_derivatives_are_strictly_verified_and_reported() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val original = fixture.sampleJpeg()
        val processed = ProfileImageProcessor(fixture.properties).processPrivacySafe(original)

        val pendingReport = requireNotNull(processed.pendingPrivacyReport)
        val approvedReport = requireNotNull(processed.approvedPrivacyReport)
        pendingReport.metadataVerification.verified shouldBeEqualTo true
        approvedReport.metadataVerification.verified shouldBeEqualTo true
        approvedReport.metadataVerification.requested shouldBeEqualTo setOf(
            PrivacyMetadataCategory.GPS,
            PrivacyMetadataCategory.EXIF,
            PrivacyMetadataCategory.XMP,
            PrivacyMetadataCategory.IPTC,
            PrivacyMetadataCategory.ICC,
            PrivacyMetadataCategory.ORIENTATION,
        )
        approvedReport.appliedActions.last().name shouldBeEqualTo "ENCODED"
        processed.pendingBytes.size shouldBeGreaterThan 0
        processed.approvedBytes.size shouldBeGreaterThan 0

        listOf(processed.pendingBytes, processed.approvedBytes).forEach { bytes ->
            val report = readImageMetadataReportStrict(
                bytes,
                ImageMetadataReadOptions(stripSensitiveMetadata = false),
            ) as ImageMetadataReadResult.Success
            report.report.containsGps shouldBeEqualTo false
            report.report.containsExif shouldBeEqualTo false
            report.report.containsXmp shouldBeEqualTo false
            report.report.containsIptc shouldBeEqualTo false
            report.report.containsIccProfile shouldBeEqualTo false
        }
    }

    @Test
    fun privacy_safe_derivatives_preserve_redaction_geometry_for_pending_and_approved() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val redaction = PrivacyRedaction(
            region = SensitiveRegion(
                id = "face-1",
                geometry = SensitiveRegionGeometry.Rectangle(
                    x = 0.25,
                    y = 0.25,
                    width = 0.5,
                    height = 0.5,
                    coordinateSpace = SensitiveCoordinateSpace.NORMALIZED,
                ),
            ),
        )

        val processed = ProfileImageProcessor(fixture.properties).processPrivacySafe(
            fixture.sampleJpeg(width = 200, height = 100),
            redactions = listOf(redaction),
        )

        val pendingReport = requireNotNull(processed.pendingPrivacyReport)
        val approvedReport = requireNotNull(processed.approvedPrivacyReport)
        pendingReport.redactions.single().let { applied ->
            applied.regionId shouldBeEqualTo "face-1"
            applied.x shouldBeEqualTo 24
            applied.y shouldBeEqualTo 12
            applied.width shouldBeEqualTo 48
            applied.height shouldBeEqualTo 24
        }
        approvedReport.redactions.single().let { applied ->
            applied.regionId shouldBeEqualTo "face-1"
            applied.x shouldBeEqualTo 50
            applied.y shouldBeEqualTo 25
            applied.width shouldBeEqualTo 100
            applied.height shouldBeEqualTo 50
        }
    }

    @Test
    fun privacy_safe_processing_fails_closed_when_source_metadata_cannot_be_inspected() = runSuspendIO {
        val error = io.bluetape4k.assertions.assertFailsWith<IllegalArgumentException> {
            ProfileImageProcessor(testProperties()).processPrivacySafe(byteArrayOf(0x01, 0x02, 0x03))
        }

        error.message shouldBeEqualTo "uploaded image metadata could not be inspected"
    }

    private fun ByteArray.isJpeg(): Boolean =
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
}
