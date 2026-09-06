package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.ImageMetadataReadResult
import io.bluetape4k.images.analysis.readImageMetadataReportStrict
import io.bluetape4k.images.moderation.SensitiveCoordinateSpace
import io.bluetape4k.images.moderation.SensitiveRegion
import io.bluetape4k.images.moderation.SensitiveRegionGeometry
import io.bluetape4k.images.privacy.PrivacyMetadataCategory
import io.bluetape4k.images.privacy.PrivacyDerivativeCodecException
import io.bluetape4k.images.privacy.PrivacyDerivativeCodecReason
import io.bluetape4k.images.privacy.PrivacyDerivativeJackson
import io.bluetape4k.images.privacy.PrivacyDerivativeJsonLimits
import io.bluetape4k.images.privacy.PrivacyDerivativePayload
import io.bluetape4k.images.privacy.PrivacyRedaction
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.workshop.imageprocessing.profile.model.ProcessedProfileImage
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

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
        val processed = ProfileImageProcessor(fixture.properties).processPrivacySafe(original, sourceId = "upload-a")

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
        requireNotNull(processed.pendingPrivacyPayload).bytes shouldBeEqualTo processed.pendingBytes
        requireNotNull(processed.approvedPrivacyPayload).bytes shouldBeEqualTo processed.approvedBytes
        processed.pendingPrivacyPayload.report.sourceId shouldBeEqualTo "upload-a"
        processed.approvedPrivacyPayload.report.sourceId shouldBeEqualTo "upload-a"

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
    fun privacy_payload_round_trip_is_bounded_defensive_and_keeps_streams_caller_owned() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val processed = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-persisted")
        val payload: PrivacyDerivativePayload = requireNotNull(processed.approvedPrivacyPayload)

        val json = PrivacyDerivativeJackson.encodePayload(payload)
        json.contains("\"schemaVersion\":1").shouldBeTrue()
        json.contains("\"kind\":\"payload\"").shouldBeTrue()
        PrivacyDerivativeJackson.decodePayload(json) shouldBeEqualTo payload

        val returned = payload.bytes
        returned[0] = returned[0].inc()
        payload.bytes shouldBeEqualTo processed.approvedBytes

        val output = TrackingOutputStream()
        PrivacyDerivativeJackson.encodePayloadTo(payload, output)
        output.size() shouldBeGreaterThan 0
        output.closed.shouldBeFalse()
        output.flushed.shouldBeFalse()

        val encodedBytes = PrivacyDerivativeJackson.encodePayloadBytes(payload)
        PrivacyDerivativeJackson.decodePayload(encodedBytes) shouldBeEqualTo payload
        val input = TrackingInputStream(encodedBytes + "{}".toByteArray())
        val streamError = io.bluetape4k.assertions.assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(input)
        }
        streamError.reason shouldBeEqualTo PrivacyDerivativeCodecReason.TRAILING_DATA
        input.closed.shouldBeFalse()
    }

    @Test
    fun privacy_payload_survives_java_serialization_while_runtime_report_remains_transient() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
        val processed = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-restarted")

        val serialized = ByteArrayOutputStream().use { bytes ->
            ObjectOutputStream(bytes).use { it.writeObject(processed) }
            bytes.toByteArray()
        }
        val restored = ObjectInputStream(ByteArrayInputStream(serialized)).use {
            it.readObject() as ProcessedProfileImage
        }

        restored.pendingPrivacyReport shouldBeEqualTo null
        restored.approvedPrivacyReport shouldBeEqualTo null
        restored.pendingPrivacyPayload shouldBeEqualTo processed.pendingPrivacyPayload
        restored.approvedPrivacyPayload shouldBeEqualTo processed.approvedPrivacyPayload
        restored.approvedPrivacyPayload?.report?.sourceId shouldBeEqualTo "upload-restarted"
    }

    @Test
    fun privacy_payload_decoder_is_bounded_and_keeps_diagnostics_secret_free() = runSuspendIO {
        val secret = "raw-secret-image-content"
        val malformed = io.bluetape4k.assertions.assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload("{not-json:$secret}")
        }
        malformed.reason shouldBeEqualTo PrivacyDerivativeCodecReason.MALFORMED_JSON
        requireNotNull(malformed.message).contains(secret).shouldBeFalse()

        val trailing = io.bluetape4k.assertions.assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(
                """{"schemaVersion":1,"kind":"payload","value":null} $secret""",
            )
        }
        trailing.reason shouldBeEqualTo PrivacyDerivativeCodecReason.TRAILING_DATA
        requireNotNull(trailing.message).contains(secret).shouldBeFalse()

        val fixture = ProfileImageServiceFixture()
        val payload = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-bounded")
            .approvedPrivacyPayload
        val encoded = PrivacyDerivativeJackson.encodePayloadBytes(requireNotNull(payload))
        val limited = PrivacyDerivativeJsonLimits().copy(maxPayloadBytes = 1)
        val limitedError = io.bluetape4k.assertions.assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(encoded, limited)
        }
        limitedError.reason shouldBeEqualTo PrivacyDerivativeCodecReason.LIMIT_EXCEEDED
        payload.toString().contains(encoded.decodeToString()).shouldBeFalse()
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

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
            private set
        var flushed: Boolean = false
            private set

        override fun close() {
            closed = true
            super.close()
        }

        override fun flush() {
            flushed = true
            super.flush()
        }
    }
}
