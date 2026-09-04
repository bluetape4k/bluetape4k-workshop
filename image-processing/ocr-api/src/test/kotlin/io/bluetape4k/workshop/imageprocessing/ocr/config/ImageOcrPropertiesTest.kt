package io.bluetape4k.workshop.imageprocessing.ocr.config

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.time.Duration

class ImageOcrPropertiesTest {

    @Test
    fun `upload byte limit must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(maxUploadBytes = 0)
        }
    }

    @Test
    fun `pixel budget must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(maxImagePixels = 0)
        }
    }

    @Test
    fun `timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(timeout = Duration.ZERO)
        }
    }

    @Test
    fun `default languages must not be empty`() {
        assertFailsWith<IllegalArgumentException> {
            ImageOcrProperties(languages = emptyList())
        }
    }

    @Test
    fun `TIFF page and result budgets must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            TiffMultiPageOcrProperties(maxPages = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TiffMultiPageOcrProperties(maxResultEntries = 0)
        }
    }
}
