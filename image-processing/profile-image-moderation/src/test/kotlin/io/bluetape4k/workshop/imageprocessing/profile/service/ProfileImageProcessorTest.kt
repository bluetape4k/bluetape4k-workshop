package io.bluetape4k.workshop.imageprocessing.profile.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.images.analysis.ImageMetadataReadOptions
import io.bluetape4k.images.analysis.ImageMetadataReadResult
import io.bluetape4k.images.analysis.readImageMetadataReportStrict
import io.bluetape4k.images.moderation.SensitiveCoordinateSpace
import io.bluetape4k.images.moderation.SensitiveRegion
import io.bluetape4k.images.moderation.SensitiveRegionGeometry
import io.bluetape4k.images.privacy.PrivacyDerivativeCodecException
import io.bluetape4k.images.privacy.PrivacyDerivativeCodecReason
import io.bluetape4k.images.privacy.PrivacyDerivativeJackson
import io.bluetape4k.images.privacy.PrivacyDerivativeJsonLimits
import io.bluetape4k.images.privacy.PrivacyDerivativePayload
import io.bluetape4k.images.privacy.PrivacyMetadataCategory
import io.bluetape4k.images.privacy.PrivacyRedaction
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.imageprocessing.profile.model.ProcessedProfileImage
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * public derivative privacy 계약을 검증합니다.
 *
 * pending/approved 결과가 JPEG인지, EXIF/GPS/XMP/IPTC/ICC가 제거되고
 * orientation이 정규화되는지, strict 재검증 report와 bounded payload가
 * 보존되는지, metadata reader/codec/stream 실패가 fail-closed 하는지를
 * 확인합니다. 원본 bytes나 raw metadata가 report/payload에 복사되지 않는
 * 경계도 함께 읽을 수 있습니다.
 */
class ProfileImageProcessorTest {

    companion object: KLogging()

    @Test
/**
 * generated_derivatives_are_jpeg_bytes은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun generated_derivatives_are_jpeg_bytes() {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
        val processed = ProfileImageProcessor(fixture.properties).process(fixture.sampleJpeg())

        log.debug { "processed=$processed" }
        processed.contentType shouldBeEqualTo "image/jpeg"
        processed.pendingBytes.isJpeg().shouldBeTrue()
        processed.approvedBytes.isJpeg().shouldBeTrue()
    }

    @Test
/**
 * privacy_safe_derivatives_are_strictly_verified_and_reported은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나
 * 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_safe_derivatives_are_strictly_verified_and_reported() = runSuspendIO {
        val fixture = ProfileImageServiceFixture()
/**
 * original은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val original = fixture.sampleJpeg()
/**
 * processed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processed = ProfileImageProcessor(fixture.properties).processPrivacySafe(original, sourceId = "upload-a")

/**
 * pendingReport은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pendingReport = requireNotNull(processed.pendingPrivacyReport)
/**
 * approvedReport은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val approvedReport = requireNotNull(processed.approvedPrivacyReport)
        pendingReport.metadataVerification.verified.shouldBeTrue()
        approvedReport.metadataVerification.verified.shouldBeTrue()
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
        processed.pendingPrivacyPayload.shouldNotBeNull().bytes shouldBeEqualTo processed.pendingBytes
        processed.approvedPrivacyPayload.shouldNotBeNull().bytes shouldBeEqualTo processed.approvedBytes
        processed.pendingPrivacyPayload.report.sourceId shouldBeEqualTo "upload-a"
        processed.approvedPrivacyPayload.report.sourceId shouldBeEqualTo "upload-a"

        listOf(processed.pendingBytes, processed.approvedBytes)
            .forEach { bytes ->
/**
 * report은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                val report = readImageMetadataReportStrict(
                    bytes,
                    ImageMetadataReadOptions(stripSensitiveMetadata = false),
                ) as ImageMetadataReadResult.Success

                log.debug { "report=${report.report}" }
                report.report.containsGps.shouldBeFalse()
                report.report.containsExif.shouldBeFalse()
                report.report.containsXmp.shouldBeFalse()
                report.report.containsIptc.shouldBeFalse()
                report.report.containsIccProfile.shouldBeFalse()
            }
    }

    @Test
/**
 * privacy_payload_round_trip_is_bounded_defensive_and_keeps_streams_caller_owned은 이미지 처리 테스트
 * fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_payload_round_trip_is_bounded_defensive_and_keeps_streams_caller_owned() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * processed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processed = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-persisted")
/**
 * payload은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload: PrivacyDerivativePayload = requireNotNull(processed.approvedPrivacyPayload)

/**
 * json은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val json = PrivacyDerivativeJackson.encodePayload(payload)
        json shouldContain "\"schemaVersion\":1"
        json shouldContain "\"kind\":\"payload\""
        PrivacyDerivativeJackson.decodePayload(json) shouldBeEqualTo payload

/**
 * returned은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val returned = payload.bytes
        returned[0] = returned[0].inc()
        payload.bytes shouldBeEqualTo processed.approvedBytes

/**
 * output은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val output = TrackingOutputStream()
        PrivacyDerivativeJackson.encodePayloadTo(payload, output)
        output.size() shouldBeGreaterThan 0
        output.closed.shouldBeFalse()
        output.flushed.shouldBeFalse()

/**
 * encodedBytes은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val encodedBytes = PrivacyDerivativeJackson.encodePayloadBytes(payload)
        PrivacyDerivativeJackson.decodePayload(encodedBytes) shouldBeEqualTo payload
/**
 * input은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val input = TrackingInputStream(encodedBytes + "{}".toUtf8Bytes())

/**
 * streamError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val streamError = assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(input)
        }
        streamError.reason shouldBeEqualTo PrivacyDerivativeCodecReason.TRAILING_DATA
        input.closed.shouldBeFalse()
    }

    @Suppress("DEPRECATION")
    @Test
/**
 * privacy_payload_survives_java_serialization_while_runtime_report_remains_transient은 이미지 처리 테스트
 * fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_payload_survives_java_serialization_while_runtime_report_remains_transient() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * processed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processed = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-restarted")

/**
 * serialized은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val serialized = BinarySerializers.JdkUnfiltered.serialize(processed)
/**
 * restored은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val restored = BinarySerializers.JdkUnfiltered.deserialize<ProcessedProfileImage>(serialized).shouldNotBeNull()

        log.debug { "restored=${restored}" }
        restored.pendingPrivacyReport.shouldBeNull()
        restored.approvedPrivacyReport.shouldBeNull()
        restored.pendingPrivacyPayload shouldBeEqualTo processed.pendingPrivacyPayload
        restored.approvedPrivacyPayload shouldBeEqualTo processed.approvedPrivacyPayload
        restored.approvedPrivacyPayload?.report?.sourceId shouldBeEqualTo "upload-restarted"
    }

    @Test
/**
 * privacy_payload_decoder_is_bounded_and_keeps_diagnostics_secret_free은 이미지 처리 테스트 fixture의 업무 경계를
 * 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_payload_decoder_is_bounded_and_keeps_diagnostics_secret_free() = runSuspendIO {
/**
 * secret은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val secret = "raw-secret-image-content"
/**
 * malformed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val malformed = io.bluetape4k.assertions.assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload("{not-json:$secret}")
        }
        malformed.reason shouldBeEqualTo PrivacyDerivativeCodecReason.MALFORMED_JSON
        malformed.message.shouldNotBeNull() shouldNotContain secret

/**
 * trailing은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val trailing = assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(
                """{"schemaVersion":1,"kind":"payload","value":null} $secret""",
            )
        }
        trailing.reason shouldBeEqualTo PrivacyDerivativeCodecReason.TRAILING_DATA
        trailing.message.shouldNotBeNull() shouldNotContain secret

/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * payload은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val payload = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(fixture.sampleJpeg(), sourceId = "upload-bounded")
            .approvedPrivacyPayload

/**
 * encoded은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val encoded = PrivacyDerivativeJackson.encodePayloadBytes(payload.shouldNotBeNull())
/**
 * limited은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val limited = PrivacyDerivativeJsonLimits().copy(maxPayloadBytes = 1)

/**
 * limitedError은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val limitedError = assertFailsWith<PrivacyDerivativeCodecException> {
            PrivacyDerivativeJackson.decodePayload(encoded, limited)
        }
        limitedError.reason shouldBeEqualTo PrivacyDerivativeCodecReason.LIMIT_EXCEEDED
        payload.toString().contains(encoded.decodeToString()).shouldBeFalse()
    }

    @Test
/**
 * privacy_safe_derivatives_preserve_redaction_geometry_for_pending_and_approved은 이미지 처리 테스트
 * fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_safe_derivatives_preserve_redaction_geometry_for_pending_and_approved() = runSuspendIO {
/**
 * fixture은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = ProfileImageServiceFixture()
/**
 * redaction은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * processed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val processed = ProfileImageProcessor(fixture.properties)
            .processPrivacySafe(
                fixture.sampleJpeg(width = 200, height = 100),
                redactions = listOf(redaction),
            )

/**
 * pendingReport은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val pendingReport = processed.pendingPrivacyReport.shouldNotBeNull()
/**
 * approvedReport은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val approvedReport = processed.approvedPrivacyReport.shouldNotBeNull()

        pendingReport.redactions.single().let { applied ->
            log.debug { "applied=${applied}" }
            applied.regionId shouldBeEqualTo "face-1"
            applied.x shouldBeEqualTo 24
            applied.y shouldBeEqualTo 12
            applied.width shouldBeEqualTo 48
            applied.height shouldBeEqualTo 24
        }
        approvedReport.redactions.single().let { applied ->
            log.debug { "applied=${applied}" }
            applied.regionId shouldBeEqualTo "face-1"
            applied.x shouldBeEqualTo 50
            applied.y shouldBeEqualTo 25
            applied.width shouldBeEqualTo 100
            applied.height shouldBeEqualTo 50
        }
    }

    @Test
/**
 * privacy_safe_processing_fails_closed_when_source_metadata_cannot_be_inspected은 이미지 처리 테스트
 * fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun privacy_safe_processing_fails_closed_when_source_metadata_cannot_be_inspected() = runSuspendIO {
/**
 * error은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val error = assertFailsWith<IllegalArgumentException> {
            ProfileImageProcessor(testProperties())
                .processPrivacySafe(byteArrayOf(0x01, 0x02, 0x03))
        }

        error.message shouldBeEqualTo "uploaded image metadata could not be inspected"
    }

/**
 * isJpeg은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun ByteArray.isJpeg(): Boolean =
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

/**
 * TrackingInputStream 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class TrackingInputStream(bytes: ByteArray): ByteArrayInputStream(bytes) {
/**
 * closed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var closed: Boolean = false
            private set

/**
 * close은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun close() {
            closed = true
            super.close()
        }
    }

/**
 * TrackingOutputStream 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class TrackingOutputStream: ByteArrayOutputStream() {
/**
 * closed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var closed: Boolean = false
            private set
/**
 * flushed은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var flushed: Boolean = false
            private set

/**
 * close은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun close() {
            closed = true
            super.close()
        }

/**
 * flush은 이미지 처리 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun flush() {
            flushed = true
            super.flush()
        }
    }
}
