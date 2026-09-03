package io.bluetape4k.workshop.text.tokenizer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JapaneseBackendComparisonExamplesTest {

    private var previousDictionaryPath: String? = null

    @BeforeEach
    fun clearSudachiProperty() {
        previousDictionaryPath = System.getProperty(SUDACHI_DICTIONARY_PROPERTY)
        System.clearProperty(SUDACHI_DICTIONARY_PROPERTY)
    }

    @AfterEach
    fun restoreSudachiProperty() {
        if (previousDictionaryPath == null) {
            System.clearProperty(SUDACHI_DICTIONARY_PROPERTY)
        } else {
            System.setProperty(SUDACHI_DICTIONARY_PROPERTY, requireNotNull(previousDictionaryPath))
        }
    }

    @Test
    fun `default comparison keeps Kuromoji live and Sudachi unavailable`() {
        val report = runJapaneseBackendComparison()

        report.current.backend shouldBeEqualTo "Kuromoji IPADic"
        report.current.execution shouldBeEqualTo BackendExecution.LIVE
        report.current.tokens.shouldNotBeEmpty()
        report.candidate.backend shouldBeEqualTo "Sudachi JVM"
        report.candidate.execution shouldBeEqualTo BackendExecution.UNAVAILABLE
        report.candidate.tokens.shouldBeEmpty()
        report.candidate.splitModes.shouldBeEmpty()
        report.candidate.statusMessage.orEmpty() shouldContain "prepareSudachiDictionary"
    }

    @Test
    fun `comparison corpus preserves the approved order`() {
        comparisonCorpus() shouldBeEqualTo listOf(
            "選挙管理委員会",
            "東京都へ行く",
            "外国人参政権",
        )
    }

    @Test
    fun `comparison rejects inputs outside the approved corpus`() {
        assertFailsWith<IllegalArgumentException> {
            runJapaneseBackendComparison("日本語の未承認fixture")
        }
    }

    @Test
    fun `malformed dictionary property remains unavailable without leaking a path`() {
        val rawProperty = "/tmp/private-sudachi-system.dic"
        System.setProperty(SUDACHI_DICTIONARY_PROPERTY, rawProperty)

        val report = runJapaneseBackendComparison()

        report.candidate.execution shouldBeEqualTo BackendExecution.UNAVAILABLE
        report.candidate.statusMessage.orEmpty() shouldContain "prepareSudachiDictionary"
        report.candidate.statusMessage.orEmpty() shouldNotContain rawProperty
    }

    @Test
    fun `renderer includes migration metadata and unavailable guidance`() {
        val rendered = renderJapaneseBackendComparison(runJapaneseBackendComparison())

        rendered shouldContain "current-backend=Kuromoji IPADic"
        rendered shouldContain "candidate-backend=Sudachi JVM"
        rendered shouldContain "candidate-license=Apache-2.0"
        rendered shouldContain "candidate-gradle-dependency=libs.sudachi"
        rendered shouldContain "candidate-dictionary-archive-sha256="
        rendered shouldContain "candidate-dictionary-sha256="
        rendered shouldContain "migration-note=compare same corpus"
        rendered shouldContain "candidate-status-message=Sudachi dictionary is unavailable"
    }

    private companion object {
        const val SUDACHI_DICTIONARY_PROPERTY = "bluetape4k.sudachi.system-dictionary"
    }
}
