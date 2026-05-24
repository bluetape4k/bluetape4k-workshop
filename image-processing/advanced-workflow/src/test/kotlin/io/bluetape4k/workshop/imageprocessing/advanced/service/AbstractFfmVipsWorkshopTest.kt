package io.bluetape4k.workshop.imageprocessing.advanced.service

import io.bluetape4k.images.vips.java25.FfmVipsRuntime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag

@Tag("vips-required")
abstract class AbstractFfmVipsWorkshopTest {

    companion object : KLogging() {
        @JvmStatic
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
}
