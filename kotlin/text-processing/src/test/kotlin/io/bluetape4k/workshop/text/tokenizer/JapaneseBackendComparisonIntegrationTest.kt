package io.bluetape4k.workshop.text.tokenizer

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEmpty
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("sudachi-integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JapaneseBackendComparisonIntegrationTest {

    private lateinit var reports: List<JapaneseBackendComparisonReport>

    @BeforeAll
    fun prepareReports() {
        reports = runJapaneseBackendComparisons()
    }

    @Test
    fun `prepared dictionary executes all approved corpus observations`() {
        reports.forEach { report ->
            report.candidate.execution shouldBeEqualTo BackendExecution.LIVE
            report.candidate.tokens.shouldNotBeEmpty()
            report.candidate.splitModes.map { it.mode } shouldBeEqualTo listOf("A", "B", "C")
            report.candidate.splitModes.forEach { it.surfaces.shouldNotBeEmpty() }
            report.candidate.posMapping shouldBeEqualTo PosMappingStatus.MAPPED
            report.candidate.statusMessage shouldBeEqualTo null
        }
    }

    @Test
    fun `prepared dictionary preserves official split fixtures`() {
        val election = reports.first { it.input == "選挙管理委員会" }
        election.candidate.splitModes.first { it.mode == "A" }.surfaces shouldBeEqualTo
            listOf("選挙", "管理", "委員", "会")
        election.candidate.splitModes.first { it.mode == "B" }.surfaces shouldBeEqualTo
            listOf("選挙", "管理", "委員会")
        election.candidate.splitModes.first { it.mode == "C" }.surfaces shouldBeEqualTo
            listOf("選挙管理委員会")

        val tokyo = reports.first { it.input == "東京都へ行く" }
        tokyo.candidate.splitModes.first { it.mode == "B" }.surfaces shouldBeEqualTo
            listOf("東京都", "へ", "行く")

        val foreign = reports.first { it.input == "外国人参政権" }
        foreign.candidate.splitModes.first { it.mode == "A" }.surfaces shouldBeEqualTo
            listOf("外国", "人", "参政", "権")
        foreign.candidate.splitModes.first { it.mode == "C" }.surfaces shouldBeEqualTo
            listOf("外国人参政権")
    }
}
