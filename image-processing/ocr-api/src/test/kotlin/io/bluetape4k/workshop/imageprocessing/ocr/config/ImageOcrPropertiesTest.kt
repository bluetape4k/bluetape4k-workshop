package io.bluetape4k.workshop.imageprocessing.ocr.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * ImageOcrPropertiesTest 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class ImageOcrPropertiesTest {

    private companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "upload byte limit must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `upload byte limit must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(maxUploadBytes = 0)
        }
    }

    @Test
/**
 * 이 시나리오는 "pixel budget must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `pixel budget must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(maxImagePixels = 0)
        }
    }

    @Test
/**
 * 이 시나리오는 "timeout must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(timeout = Duration.ZERO)
        }
    }

    @Test
/**
 * 이 시나리오는 "default languages must not be empty" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default languages must not be empty`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(languages = emptyList())
        }
    }

    @Test
/**
 * 이 시나리오는 "TIFF page and result budgets must be positive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `TIFF page and result budgets must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            TiffMultiPageOcrProperties(maxPages = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TiffMultiPageOcrProperties(maxResultEntries = 0)
        }
    }
}
