package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.vips.java25.FfmVipsRuntime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@Tag("vips-required")
/**
 * AbstractFfmVipsWorkshopTest 테스트 묶음은 이미지 처리의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
abstract class AbstractFfmVipsWorkshopTest {

    companion object: KLogging()

    @BeforeAll
    fun initRuntime() {
        if (System.getProperty("vips.enabled") != "true") {
            assumeTrue(false, "vips tests require explicit opt-in via -Dvips.enabled=true")
        }
        try {
            FfmVipsRuntime.init()
        } catch (e: Exception) {
            log.warn(e) { "FfmVipsRuntime.init() failed, skipping vips tests" }
            assumeTrue(false, "libvips not available: ${e.message}")
        }
    }
}
