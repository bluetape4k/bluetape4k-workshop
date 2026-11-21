package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.logging.KLogging
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * multipart 추출과 세 가지 결정적 fixture 경로를 노출하는 작은 HTTP 예제입니다.
 * @property extractionService 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property fixtures 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api/barcodes")
internal class BarcodeApiController(
    /**
     * `extractionService`은 이미지 업로드·파생 처리에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val extractionService: BarcodeExtractionService,
    /**
     * `fixtures`은 이미지 업로드·파생 처리에서 결정적인 테스트 입력과 외부 경계 대역을 제공하는 fixture입니다.
     *
     * 실제 업무 결과를 대신하지 않으며 시나리오가 검증하려는 불변조건을 드러내도록 최소 상태를 구성합니다.
     */
    private val fixtures: BarcodeExampleFixtures,
) {

    private companion object: KLogging()

    /**
     * multipart image에서 barcode를 추출합니다.
     *
     * ```bash
     * curl -i -F "file=@label.png;type=image/png" \
     *   http://localhost:8080/api/barcodes/extract
     * ```
     * 성공 시 `count`와 `results`를 반환하며, 크기/차원 제한은 decoder 호출
     * 전에 검사합니다. 결과가 없으면 `count=0`인 정상 응답입니다.
     */
    @PostMapping("/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun extract(@RequestParam("file") file: MultipartFile): BarcodeExtractionResponse =
        extractionService.extract(file)

    /**
     * 결정적인 성공 fixture입니다. 실제 업로드나 scanner 정확도 검증 대신
     * HTTP smoke/계약 테스트에서 사용합니다.
     *
     * ```bash
     * curl http://localhost:8080/api/barcodes/sample
     * ```
     */
    @GetMapping("/sample")
    suspend fun sample(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))

    /**
     * barcode가 없는 유효한 image fixture입니다. “결과 없음”을 오류와
     * 구분하는 `count=0` 계약을 확인합니다.
     *
     * ```bash
     * curl http://localhost:8080/api/barcodes/no-result
     * ```
     */
    @GetMapping("/no-result")
    suspend fun noResult(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.NO_RESULT))

    /**
     * decoder가 거부해야 하는 malformed fixture입니다. production input
     * generator가 아니며, 안정적인 `400` 오류 mapping을 읽기 위한 경로입니다.
     *
     * ```bash
     * curl -i http://localhost:8080/api/barcodes/malformed
     * ```
     */
    @GetMapping("/malformed")
    suspend fun malformed(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
}
