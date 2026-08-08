package io.bluetape4k.workshop.imageprocessing.barcode

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * multipart 추출과 세 가지 결정적 fixture 경로를 노출하는 작은 HTTP 예제입니다.
 */
@RestController
@RequestMapping("/api/barcodes")
internal class BarcodeApiController(
    private val extractionService: BarcodeExtractionService,
    private val fixtures: BarcodeExampleFixtures,
) {

    @PostMapping("/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun extract(@RequestParam("file") file: MultipartFile): BarcodeExtractionResponse =
        extractionService.extract(file)

    @GetMapping("/sample")
    suspend fun sample(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.SAMPLE))

    @GetMapping("/no-result")
    suspend fun noResult(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.NO_RESULT))

    @GetMapping("/malformed")
    suspend fun malformed(): BarcodeExtractionResponse =
        extractionService.extract(fixtures.bytes(BarcodeExampleFixture.MALFORMED))
}
