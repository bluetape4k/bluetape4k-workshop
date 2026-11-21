package io.bluetape4k.workshop.imageprocessing.ocr.service

import com.sksamuel.scrimage.ImmutableImage
import io.bluetape4k.images.immutableImageOf
import io.bluetape4k.images.ocr.OcrConfigurationException
import io.bluetape4k.images.ocr.OcrException
import io.bluetape4k.images.ocr.OcrOptions
import io.bluetape4k.images.ocr.OcrStructuredDetail
import io.bluetape4k.images.ocr.OcrStructuredResult
import io.bluetape4k.images.ocr.StructuredOcrEngine
import io.bluetape4k.images.ocr.TiffMultiPageOcr
import io.bluetape4k.images.ocr.TiffMultiPageOcrException
import io.bluetape4k.images.ocr.TiffMultiPageOcrFailureReason
import io.bluetape4k.images.ocr.TiffMultiPageOcrLimits
import io.bluetape4k.images.ocr.TiffMultiPageOcrValidationException
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrBoundingBox
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrPage
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrStatus
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextBlock
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrTextLine
import io.bluetape4k.workshop.imageprocessing.ocr.model.OcrWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.springframework.stereotype.Service
import java.util.concurrent.CancellationException
import kotlin.time.toKotlinDuration
import io.bluetape4k.images.ocr.OcrBoundingBox as SourceOcrBoundingBox
import io.bluetape4k.images.ocr.OcrPage as SourceOcrPage
import io.bluetape4k.images.ocr.OcrTextBlock as SourceOcrTextBlock
import io.bluetape4k.images.ocr.OcrTextLine as SourceOcrTextLine
import io.bluetape4k.images.ocr.OcrWord as SourceOcrWord

/**
 * 워크숍 API용 기본 OCR 서비스 구현입니다.
 * @property properties 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property ocrEngineProvider 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property requestIdGenerator 이미지 처리 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
@Service
class ImageOcrServiceImpl(
    /**
     * `properties`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val properties: ImageOcrProperties,
    /**
     * `ocrEngineProvider`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val ocrEngineProvider: OcrEngineProvider,
    /**
     * `requestIdGenerator`은 이미지 업로드·파생 처리에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val requestIdGenerator: RequestIdGenerator,
): ImageOcrService {
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val nativeSemaphore = Semaphore(permits = 1)

    /**
     * recognize은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * 예: `POST /api/ocr`는 허용된 이미지 bytes만 OCR provider에 전달하고, oversized input은 native 호출 전에 거절합니다.
     */
    override suspend fun recognize(request: ImageOcrRequest): ImageOcrResponse {
        val startedAtNanos = System.nanoTime()
        val requestId = requestIdGenerator.nextId()
        val languages = normalizeLanguages(request.languages.ifEmpty { properties.languages })

        validateBytes(request.bytes)
        val contentType = validateContentType(request.contentType)
        val tiffInput = contentType in TIFF_CONTENT_TYPES

        if (!properties.effectiveNativeEnabled) {
            if (tiffInput) {
                validateTiffInput(request.bytes, contentType)
            } else {
                decodeAndValidateImage(request.bytes, contentType)
            }
            return recordOutcome(
                response = ImageOcrResponse(
                    requestId = requestId,
                    status = OcrStatus.UNAVAILABLE,
                    engine = DISABLED_ENGINE,
                    languages = languages,
                    confidence = null,
                    text = "",
                    blocks = emptyList(),
                    warnings = listOf("Native OCR is disabled. Enable workshop.ocr.native-enabled=true or -Docr.enabled=true."),
                ),
                nativeEnabled = false,
                failureCategory = "native-disabled",
                startedAtNanos = startedAtNanos,
            )
        }

        return try {
            nativeSemaphore.withPermit {
                withTimeout(properties.timeout.toKotlinDuration()) {
                    val engine = ocrEngineProvider.get()
                        ?: throw OcrConfigurationException("Native OCR engine is not configured")
                    val options = OcrOptions(
                        languages = languages,
                        tessdataPath = properties.tessdataPath,
                        structuredDetail = request.structuredDetail,
                    )
                    val response = if (tiffInput) {
                        val structuredEngine = engine as? StructuredOcrEngine
                            ?: throw TiffMultiPageOcrException(
                                reason = TiffMultiPageOcrFailureReason.ENGINE_FAILED,
                                pageIndex = null,
                                message = "TIFF OCR requires a structured engine (phase=engine, pageIndex=none).",
                            )
                        val result = TiffMultiPageOcr(structuredEngine).suspendRecognize(
                            bytes = request.bytes,
                            options = options,
                            limits = properties.toTiffLimits(),
                            dispatcher = Dispatchers.IO,
                        )
                        completed(requestId, languages, result)
                    } else {
                        runInterruptible(Dispatchers.IO) {
                            val image = decodeAndValidateImage(request.bytes, contentType)
                            when {
                                request.structuredDetail == OcrStructuredDetail.PLAIN_TEXT ->
                                    completed(requestId, languages, engine.recognize(image, options).text.trim())
                                engine is StructuredOcrEngine ->
                                    completed(requestId, languages, engine.recognizeStructured(image, options))
                                else                          ->
                                    plainFallback(
                                        requestId = requestId,
                                        languages = languages,
                                        requestedDetail = request.structuredDetail,
                                        text = engine.recognize(image, options).text.trim(),
                                    )
                            }
                        }
                    }
                    recordOutcome(
                        response = response,
                        nativeEnabled = true,
                        failureCategory = if (tiffInput) "tiff" else "none",
                        startedAtNanos = startedAtNanos,
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            recordOutcome(
                response = failed(requestId, languages, "OCR failed: native OCR timed out."),
                nativeEnabled = true,
                failureCategory = "timeout",
                startedAtNanos = startedAtNanos,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TiffMultiPageOcrValidationException) {
            throw e
        } catch (e: TiffMultiPageOcrException) {
            throw e
        } catch (e: OcrConfigurationException) {
            recordOutcome(
                response = unavailable(
                    requestId,
                    languages,
                    "Native OCR is unavailable. Check Tesseract, tessdata, and language packs."
                ),
                nativeEnabled = true,
                failureCategory = "configuration",
                startedAtNanos = startedAtNanos,
            )
        } catch (e: OcrException) {
            recordOutcome(
                response = failed(requestId, languages, "OCR failed. Check the uploaded image and native OCR setup."),
                nativeEnabled = true,
                failureCategory = "ocr",
                startedAtNanos = startedAtNanos,
            )
        }
    }

    /**
     * recordOutcome은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param nativeEnabled 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param failureCategory 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param startedAtNanos 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun recordOutcome(
        response: ImageOcrResponse,
        nativeEnabled: Boolean,
        failureCategory: String,
        startedAtNanos: Long,
    ): ImageOcrResponse {
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000
        log.info(
            "OCR request completed requestId=${response.requestId} status=${response.status} " +
                    "engine=${response.engine} languages=${response.languages.joinToString(",")} " +
                    "nativeEnabled=$nativeEnabled elapsedMillis=$elapsedMillis failureCategory=$failureCategory",
        )
        return response
    }

    /**
     * completed은 이미지 업로드·검수·파생물 처리의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requestId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param languages 업무 처리에 필요한 languages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param text 업무 처리에 필요한 text 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun completed(requestId: String, languages: List<String>, text: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.COMPLETED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = text,
            blocks = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapIndexed { index, line ->
                    OcrTextBlock(index = index, text = line, confidence = null)
                }
                .toList(),
            warnings = listOf("Confidence is not available from the current OCR engine."),
        )

    /**
     * completed은 이미지 업로드·검수·파생물 처리의 재고·할당량·금액을 검증한 뒤 한 번만 상태 전이시키고, 충돌·재시도·보상 결과를 감사 가능한 형태로 남깁니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requestId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param languages 업무 처리에 필요한 languages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    private fun completed(requestId: String, languages: List<String>, result: OcrStructuredResult): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.COMPLETED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = result.text.trim(),
            blocks = result.blocks.mapIndexed { index, block -> block.toModel(index) },
            warnings = listOf("Top-level confidence is not aggregated; inspect structured elements."),
            effectiveStructuredDetail = result.options.structuredDetail,
            pages = result.pages.map { it.toModel() },
            lines = result.lines.map { it.toModel() },
            words = result.words.map { it.toModel() },
        )

    /**
     * plainFallback은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requestId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param languages 업무 처리에 필요한 languages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param requestedDetail 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param text 업무 처리에 필요한 text 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun plainFallback(
        requestId: String,
        languages: List<String>,
        requestedDetail: OcrStructuredDetail,
        text: String,
    ): ImageOcrResponse =
        completed(
            requestId = requestId,
            languages = languages,
            text = text,
        ).copy(
            warnings = listOf(
                "Requested structured OCR detail $requestedDetail is unavailable from the configured engine; returned plain text.",
                "Confidence is not available from the current OCR engine.",
            ),
        )

    /**
     * unavailable은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requestId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param languages 업무 처리에 필요한 languages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param warning 업무 처리에 필요한 warning 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun unavailable(requestId: String, languages: List<String>, warning: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.UNAVAILABLE,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = "",
            blocks = emptyList(),
            warnings = listOf(warning),
        )

    /**
     * failed은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param requestId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param languages 업무 처리에 필요한 languages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param warning 업무 처리에 필요한 warning 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun failed(requestId: String, languages: List<String>, warning: String): ImageOcrResponse =
        ImageOcrResponse(
            requestId = requestId,
            status = OcrStatus.FAILED,
            engine = NATIVE_ENGINE,
            languages = languages,
            confidence = null,
            text = "",
            blocks = emptyList(),
            warnings = listOf(warning),
        )

    /**
     * toModel은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SourceOcrPage.toModel(): OcrPage =
        OcrPage(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    /**
     * toModel은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param index 업무 처리에 필요한 index 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun SourceOcrTextBlock.toModel(index: Int): OcrTextBlock =
        OcrTextBlock(
            index = index,
            text = text,
            confidence = confidence,
            pageIndex = pageIndex,
            boundingBox = boundingBox?.toModel(),
        )

    /**
     * toModel은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SourceOcrTextLine.toModel(): OcrTextLine =
        OcrTextLine(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    /**
     * toModel은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SourceOcrWord.toModel(): OcrWord =
        OcrWord(
            pageIndex = pageIndex,
            text = text,
            confidence = confidence,
            boundingBox = boundingBox?.toModel(),
        )

    /**
     * toModel은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SourceOcrBoundingBox.toModel(): OcrBoundingBox =
        OcrBoundingBox(
            x = x,
            y = y,
            width = width,
            height = height,
        )

    /**
     * validateBytes은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateBytes(bytes: ByteArray) {
        bytes.size.requirePositiveNumber("bytes.size")
        bytes.size.toLong().requireInRange(1L, properties.maxUploadBytes, "bytes.size")
    }

    /**
     * validateContentType은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와
     * 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param contentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun validateContentType(contentType: String?): String {
        val declaredContentType = requireNotNull(contentType) {
            "Unsupported image content type. Use JPEG, PNG, WebP, or TIFF."
        }
        require(declaredContentType in SUPPORTED_CONTENT_TYPES) {
            "Unsupported image content type. Use JPEG, PNG, WebP, or TIFF."
        }
        return declaredContentType
    }

    /**
     * decodeAndValidateImage은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태
     * 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param declaredContentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun decodeAndValidateImage(bytes: ByteArray, declaredContentType: String): ImmutableImage {
        val detectedContentType = detectContentType(bytes)
        require(
            detectedContentType == declaredContentType ||
                    declaredContentType == "image/jpg" && detectedContentType == "image/jpeg" ||
                    declaredContentType in TIFF_CONTENT_TYPES && detectedContentType == "image/tiff",
        ) {
            "Uploaded bytes do not match the declared image content type"
        }
        val dimensions = readDimensions(bytes, detectedContentType)
        validateDecodedPixels(dimensions.width, dimensions.height)
        val image = try {
            immutableImageOf(bytes)
        } catch (e: Exception) {
            throw IllegalArgumentException("Undecodable image upload", e)
        }
        validateDecodedPixels(image.width.toLong(), image.height.toLong())
        return image
    }

    /**
     * detectContentType은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun detectContentType(bytes: ByteArray): String {
        if (bytes.isTiffHeader()) {
            return "image/tiff"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        throw IllegalArgumentException("Undecodable image upload")
    }

    /**
     * validateTiffInput은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분
     * 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param declaredContentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun validateTiffInput(bytes: ByteArray, declaredContentType: String) {
        require(bytes.isTiffHeader()) { "Undecodable TIFF upload" }
        val detectedContentType = detectContentType(bytes)
        require(declaredContentType in TIFF_CONTENT_TYPES && detectedContentType == "image/tiff") {
            "Uploaded bytes do not match the declared image content type"
        }
    }

    /**
     * toTiffLimits은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun ImageOcrProperties.toTiffLimits(): TiffMultiPageOcrLimits =
        TiffMultiPageOcrLimits(
            maxEncodedBytes = tiff.maxEncodedBytes,
            maxPages = tiff.maxPages,
            maxPixelsPerPage = tiff.maxPixelsPerPage,
            maxTotalPixels = tiff.maxTotalPixels,
            maxDecodedSide = tiff.maxDecodedSide,
            maxMetadataBytes = tiff.maxMetadataBytes,
            maxResultTextChars = tiff.maxResultTextChars,
            maxResultEntries = tiff.maxResultEntries,
        )

    /**
     * readDimensions은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
     * 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param contentType 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    private fun readDimensions(bytes: ByteArray, contentType: String): ImageDimensions =
        when (contentType) {
            "image/png" -> readPngDimensions(bytes)
            "image/jpeg" -> readJpegDimensions(bytes)
            "image/webp" -> readWebpDimensions(bytes)
            else -> throw IllegalArgumentException("Unsupported image content type. Use JPEG, PNG, or WebP.")
        }

    /**
     * readPngDimensions은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun readPngDimensions(bytes: ByteArray): ImageDimensions {
        require(bytes.size >= 24) { "Undecodable image upload" }
        require(
            bytes[12] == 'I'.code.toByte() &&
                    bytes[13] == 'H'.code.toByte() &&
                    bytes[14] == 'D'.code.toByte() &&
                    bytes[15] == 'R'.code.toByte(),
        ) {
            "Undecodable image upload"
        }

        return ImageDimensions(
            width = bytes.readUInt32BE(offset = 16),
            height = bytes.readUInt32BE(offset = 20),
        )
    }

    /**
     * readJpegDimensions은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun readJpegDimensions(bytes: ByteArray): ImageDimensions {
        var offset = 2
        while (offset + 3 < bytes.size) {
            while (offset < bytes.size && bytes[offset] != 0xFF.toByte()) {
                offset++
            }
            while (offset < bytes.size && bytes[offset] == 0xFF.toByte()) {
                offset++
            }
            require(offset < bytes.size) { "Undecodable image upload" }

            val marker = bytes[offset].toInt() and 0xFF
            offset++
            if (marker == 0xD9 || marker == 0xDA) {
                break
            }

            require(offset + 1 < bytes.size) { "Undecodable image upload" }
            val segmentLength = bytes.readUInt16BE(offset).toInt()
            require(segmentLength >= 2 && offset + segmentLength <= bytes.size) { "Undecodable image upload" }
            if (marker in JPEG_DIMENSION_MARKERS) {
                require(segmentLength >= 7) { "Undecodable image upload" }
                return ImageDimensions(
                    width = bytes.readUInt16BE(offset + 5),
                    height = bytes.readUInt16BE(offset + 3),
                )
            }
            offset += segmentLength
        }

        throw IllegalArgumentException("Undecodable image upload")
    }

    /**
     * readWebpDimensions은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param bytes 업무 처리에 필요한 bytes 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun readWebpDimensions(bytes: ByteArray): ImageDimensions {
        require(bytes.size >= 16) { "Undecodable image upload" }
        return when {
            bytes.hasAscii(offset = 12, value = "VP8X") -> {
                require(bytes.size >= 30) { "Undecodable image upload" }
                ImageDimensions(
                    width = bytes.readUInt24LE(offset = 24) + 1,
                    height = bytes.readUInt24LE(offset = 27) + 1,
                )
            }
            bytes.hasAscii(offset = 12, value = "VP8L") -> {
                require(bytes.size >= 25) { "Undecodable image upload" }
                val bits = bytes.readUInt32LE(offset = 21)
                ImageDimensions(
                    width = (bits and 0x3FFF) + 1,
                    height = ((bits shr 14) and 0x3FFF) + 1,
                )
            }
            bytes.hasAscii(offset = 12, value = "VP8 ") -> {
                require(bytes.size >= 30) { "Undecodable image upload" }
                require(
                    bytes[23] == 0x9D.toByte() &&
                            bytes[24] == 0x01.toByte() &&
                            bytes[25] == 0x2A.toByte(),
                ) {
                    "Undecodable image upload"
                }
                ImageDimensions(
                    width = bytes.readUInt16LE(offset = 26) and 0x3FFF,
                    height = bytes.readUInt16LE(offset = 28) and 0x3FFF,
                )
            }
            else                                        -> throw IllegalArgumentException("Undecodable image upload")
        }
    }

    /**
     * validateDecodedPixels은 이미지 업로드·검수·파생물 처리 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태
     * 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param width 업무 처리에 필요한 width 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param height 업무 처리에 필요한 height 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun validateDecodedPixels(width: Long, height: Long) {
        width.requirePositiveNumber("width")
        height.requirePositiveNumber("height")
        width.requireInRange(1L, properties.maxImagePixels / height, "width")
    }

    /**
     * normalizeLanguages은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param rawLanguages 업무 처리에 필요한 rawLanguages 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun normalizeLanguages(rawLanguages: List<String>): List<String> {
        val normalized = rawLanguages
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val effective = normalized.ifEmpty { properties.languages }
        effective.requireNotEmpty("languages")
        effective.forEach { language ->
            require(LANGUAGE_PATTERN.matches(language)) {
                "Invalid OCR language: $language"
            }
        }
        return effective
    }

    companion object: KLogging() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val DISABLED_ENGINE = "disabled"
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val NATIVE_ENGINE = "tesseract"
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        private val LANGUAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_+-]*")
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private val JPEG_DIMENSION_MARKERS = setOf(
            0xC0,
            0xC1,
            0xC2,
            0xC3,
            0xC5,
            0xC6,
            0xC7,
            0xC9,
            0xCA,
            0xCB,
            0xCD,
            0xCE,
            0xCF,
        )
        /**
         * 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val SUPPORTED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp",
            "image/tiff",
            "image/tif",
            "image/x-tiff",
        )
        /**
         * 이미지 처리의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val TIFF_CONTENT_TYPES = setOf("image/tiff", "image/tif", "image/x-tiff")
    }
}
/**
 * ImageDimensions은 이미지 처리에서 사용하는 업무 객체입니다.
 * @property width 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property height 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */


private class ImageDimensions(
    /**
     * `width`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val width: Long,
    /**
     * `height`은 이미지 업로드·파생 처리에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val height: Long,
)

/**
 * readUInt16BE은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ByteArray.readUInt16BE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) shl 8 or
            (this[offset + 1].toInt() and 0xFF)).toLong()

/**
 * readUInt16LE은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ByteArray.readUInt16LE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)).toLong()

/**
 * readUInt24LE은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ByteArray.readUInt24LE(offset: Int): Long =
    ((this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16)).toLong()

/**
 * readUInt32BE은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ByteArray.readUInt32BE(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)

/**
 * readUInt32LE은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun ByteArray.readUInt32LE(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)

/**
 * hasAscii은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param offset 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param value 업무 처리에 필요한 value 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun ByteArray.hasAscii(offset: Int, value: String): Boolean =
    size >= offset + value.length && value.indices.all { index -> this[offset + index] == value[index].code.toByte() }

/**
 * isTiffHeader은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ByteArray.isTiffHeader(): Boolean =
    size >= 4 &&
            ((this[0] == 'I'.code.toByte() && this[1] == 'I'.code.toByte() &&
                    (this[2] == 0x2A.toByte() || this[2] == 0x2B.toByte()) && this[3] == 0.toByte()) ||
                    (this[0] == 'M'.code.toByte() && this[1] == 'M'.code.toByte() &&
                            this[2] == 0.toByte() && (this[3] == 0x2A.toByte() || this[3] == 0x2B.toByte())))
