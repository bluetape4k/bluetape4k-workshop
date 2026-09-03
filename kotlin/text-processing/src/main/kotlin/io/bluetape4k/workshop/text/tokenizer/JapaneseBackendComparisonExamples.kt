@file:Suppress("MatchingDeclarationName")

package io.bluetape4k.workshop.text.tokenizer

import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.PathAnchor
import com.worksap.nlp.sudachi.Tokenizer
import io.bluetape4k.tokenizer.japanese.JapaneseProcessor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

private const val DEFAULT_COMPARISON_TEXT = "選挙管理委員会"
private const val SUDACHI_SYSTEM_DICTIONARY_PROPERTY = "bluetape4k.sudachi.system-dictionary"
private const val SUDACHI_DICTIONARY_VERSION = "20260428"
private const val SUDACHI_DICTIONARY_ARCHIVE_SHA256 =
    "40c8ffc095283f07aa06cae922e7b8147bf2919ec8830567b0b3f7a7efa3239f"
private const val SUDACHI_DICTIONARY_SHA256 =
    "6c1d5adc8a2389875713056e7b39bbcd0073d6122ffd509866e1d3a196f8608e"
private const val SUDACHI_DICTIONARY_SIZE = 217_374_303L
private const val SUDACHI_DICTIONARY_RELATIVE_PATH =
    "build/sudachi-dictionary/v$SUDACHI_DICTIONARY_VERSION/system_core.dic"

private val COMPARISON_CORPUS = listOf(
    "選挙管理委員会",
    "東京都へ行く",
    "外国人参政権",
)

/** tokenizer backend가 실제 실행되었는지 또는 준비되지 않았는지를 나타냅니다. */
internal enum class BackendExecution {
    LIVE,
    UNAVAILABLE,
}

/** 두 backend의 POS 체계를 동일하다고 판정하지 않고 observation field만 채웠는지 나타냅니다. */
internal enum class PosMappingStatus {
    MAPPED,
    UNMAPPED,
}

internal data class JapaneseTokenObservation(
    val surface: String,
    val partOfSpeech: String,
)

internal data class CandidateSplitModeObservation(
    val mode: String,
    val surfaces: List<String>,
)

internal data class JapaneseBackendObservation(
    val backend: String,
    val dictionary: String,
    val license: String,
    val runtimeFootprint: String,
    val gradleDependency: String,
    val dictionaryVersion: String,
    val dictionaryArchiveSha256: String,
    val dictionarySha256: String,
    val dictionarySize: Long,
    val execution: BackendExecution,
    val posMapping: PosMappingStatus,
    val tokens: List<JapaneseTokenObservation>,
    val splitModes: List<CandidateSplitModeObservation>,
    val statusMessage: String?,
)

internal data class JapaneseBackendComparisonReport(
    val input: String,
    val current: JapaneseBackendObservation,
    val candidate: JapaneseBackendObservation,
)

private data class SudachiAnalysis(
    val tokens: List<JapaneseTokenObservation>,
    val splitModes: List<CandidateSplitModeObservation>,
)

/** 승인된 동일 corpus를 두 일본어 tokenizer backend에 전달해 비교 observation을 만듭니다. */
internal fun runJapaneseBackendComparisons(
    texts: List<String> = comparisonCorpus(),
): List<JapaneseBackendComparisonReport> {
    require(texts.isNotEmpty()) { "The comparison corpus must not be empty." }
    texts.forEach { text ->
        require(text in COMPARISON_CORPUS) {
            "The comparison fixture only supports the approved corpus."
        }
    }

    val currentObservations = texts.map { text ->
        val tokens = JapaneseProcessor.tokenize(text).map { token ->
            JapaneseTokenObservation(
                surface = token.surface,
                partOfSpeech = token.allFeaturesArray.firstOrNull().orEmpty(),
            )
        }
        kuromojiObservation(tokens)
    }
    val dictionaryPath = sudachiSystemDictionaryPathOrNull()
    val candidateObservations = if (dictionaryPath == null) {
        texts.map { unavailableSudachiObservation() }
    } else {
        openSudachiDictionary(dictionaryPath).use { dictionary ->
            val tokenizer = dictionary.create()
            texts.map { text -> sudachiObservation(analyzeSudachi(tokenizer, text)) }
        }
    }

    return texts.indices.map { index ->
        JapaneseBackendComparisonReport(
            input = texts[index],
            current = currentObservations[index],
            candidate = candidateObservations[index],
        )
    }
}

/** 단일 승인 fixture를 비교합니다. 여러 문장은 [runJapaneseBackendComparisons]를 사용하세요. */
internal fun runJapaneseBackendComparison(
    text: String = DEFAULT_COMPARISON_TEXT,
): JapaneseBackendComparisonReport = runJapaneseBackendComparisons(listOf(text)).single()

/** 이 예제가 고정해 둔 공식 비교 corpus를 원래 순서로 반환합니다. */
internal fun comparisonCorpus(): List<String> = COMPARISON_CORPUS.toList()

private fun analyzeSudachi(
    tokenizer: Tokenizer,
    text: String,
): SudachiAnalysis {
    val modeATokens = tokenizer.tokenize(Tokenizer.SplitMode.A, text)
    val modeBTokens = tokenizer.tokenize(Tokenizer.SplitMode.B, text)
    val modeCTokens = tokenizer.tokenize(Tokenizer.SplitMode.C, text)
    val splitModes = listOf(
        CandidateSplitModeObservation(mode = "A", surfaces = modeATokens.map { it.surface() }),
        CandidateSplitModeObservation(mode = "B", surfaces = modeBTokens.map { it.surface() }),
        CandidateSplitModeObservation(mode = "C", surfaces = modeCTokens.map { it.surface() }),
    )
    val tokens = modeCTokens.map { morpheme ->
        JapaneseTokenObservation(
            surface = morpheme.surface(),
            partOfSpeech = morpheme.partOfSpeech().firstOrNull().orEmpty(),
        )
    }
    return SudachiAnalysis(tokens = tokens, splitModes = splitModes)
}

private fun kuromojiObservation(tokens: List<JapaneseTokenObservation>) = JapaneseBackendObservation(
    backend = "Kuromoji IPADic",
    dictionary = "IPADic bundled in kuromoji-ipadic",
    license = "Apache-2.0",
    runtimeFootprint = "bundled IPADic artifact",
    gradleDependency = "existing Bluetape Japanese tokenizer",
    dictionaryVersion = "bundled by kuromoji-ipadic",
    dictionaryArchiveSha256 = "not applicable",
    dictionarySha256 = "not applicable",
    dictionarySize = -1L,
    execution = BackendExecution.LIVE,
    posMapping = PosMappingStatus.MAPPED,
    tokens = tokens,
    splitModes = emptyList(),
    statusMessage = null,
)

private fun sudachiObservation(analysis: SudachiAnalysis) = JapaneseBackendObservation(
    backend = "Sudachi JVM",
    dictionary = "external SudachiDict system dictionary",
    license = "Apache-2.0",
    runtimeFootprint = "external system_core.dic in module build cache (not committed)",
    gradleDependency = "libs.sudachi (com.worksap.nlp:sudachi:0.8.0)",
    dictionaryVersion = "SudachiDict v$SUDACHI_DICTIONARY_VERSION core",
    dictionaryArchiveSha256 = SUDACHI_DICTIONARY_ARCHIVE_SHA256,
    dictionarySha256 = SUDACHI_DICTIONARY_SHA256,
    dictionarySize = SUDACHI_DICTIONARY_SIZE,
    execution = BackendExecution.LIVE,
    posMapping = PosMappingStatus.MAPPED,
    tokens = analysis.tokens,
    splitModes = analysis.splitModes,
    statusMessage = null,
)

private fun unavailableSudachiObservation() = JapaneseBackendObservation(
    backend = "Sudachi JVM",
    dictionary = "external SudachiDict system dictionary",
    license = "Apache-2.0",
    runtimeFootprint = "external system_core.dic in module build cache (not committed)",
    gradleDependency = "libs.sudachi (com.worksap.nlp:sudachi:0.8.0)",
    dictionaryVersion = "SudachiDict v$SUDACHI_DICTIONARY_VERSION core",
    dictionaryArchiveSha256 = SUDACHI_DICTIONARY_ARCHIVE_SHA256,
    dictionarySha256 = SUDACHI_DICTIONARY_SHA256,
    dictionarySize = SUDACHI_DICTIONARY_SIZE,
    execution = BackendExecution.UNAVAILABLE,
    posMapping = PosMappingStatus.UNMAPPED,
    tokens = emptyList(),
    splitModes = emptyList(),
    statusMessage = "Sudachi dictionary is unavailable; run prepareSudachiDictionary and set " +
        "bluetape4k.sudachi.system-dictionary.",
)

private fun sudachiSystemDictionaryPathOrNull(): Path? {
    val configuredPath = System.getProperty(SUDACHI_SYSTEM_DICTIONARY_PROPERTY)?.trim().orEmpty()
    if (configuredPath.isEmpty()) return null

    return try {
        val expectedPath = Path.of(System.getProperty("user.dir"))
            .toAbsolutePath()
            .normalize()
            .resolve(SUDACHI_DICTIONARY_RELATIVE_PATH)
            .normalize()
        val actualPath = Path.of(configuredPath).toAbsolutePath().normalize()
        if (actualPath != expectedPath || !isPreparedDictionary(actualPath)) null else actualPath
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: java.nio.file.InvalidPathException) {
        null
    }
}

private fun isPreparedDictionary(path: Path): Boolean {
    if (!isSafePathTree(path.parent)) return false
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
    return try {
        Files.size(path) == SUDACHI_DICTIONARY_SIZE && path.sha256() == SUDACHI_DICTIONARY_SHA256
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun isSafePathTree(path: Path?): Boolean {
    var current = path ?: return true
    val existing = ArrayDeque<Path>()
    while (true) {
        if (Files.exists(current, NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
            existing.addFirst(current)
        }
        val parent = current.parent ?: break
        current = parent
    }
    return existing.all { candidate ->
        !Files.isSymbolicLink(candidate) && Files.isDirectory(candidate, NOFOLLOW_LINKS)
    }
}

private fun Path.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this, NOFOLLOW_LINKS).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

private fun openSudachiDictionary(dictionaryPath: Path) =
    DictionaryFactory().create(
        Config.defaultConfig(PathAnchor.filesystem(dictionaryPath.parent)).systemDictionary(dictionaryPath),
    )

/** 비교 결과를 문서와 issue에 옮기기 쉬운 안정된 key-value 형식으로 렌더링합니다. */
internal fun renderJapaneseBackendComparison(report: JapaneseBackendComparisonReport): String = buildString {
    appendLine("input=${report.input}")
    appendObservation("current", report.current)
    appendObservation("candidate", report.candidate)
    report.candidate.statusMessage?.let { appendLine("candidate-status-message=$it") }
    appendLine(
        "migration-note=compare same corpus and record mode-specific surface/POS mismatches before backend migration",
    )
}

private fun StringBuilder.appendObservation(
    prefix: String,
    observation: JapaneseBackendObservation,
) {
    appendLine("$prefix-backend=${observation.backend}")
    appendLine("$prefix-dictionary=${observation.dictionary}")
    appendLine("$prefix-license=${observation.license}")
    appendLine("$prefix-runtime-footprint=${observation.runtimeFootprint}")
    appendLine("$prefix-gradle-dependency=${observation.gradleDependency}")
    appendLine("$prefix-dictionary-version=${observation.dictionaryVersion}")
    appendLine("$prefix-dictionary-archive-sha256=${observation.dictionaryArchiveSha256}")
    appendLine("$prefix-dictionary-sha256=${observation.dictionarySha256}")
    appendLine("$prefix-dictionary-size=${observation.dictionarySize}")
    appendLine("$prefix-execution=${observation.execution}")
    appendLine("$prefix-pos-mapping=${observation.posMapping}")
    appendLine("$prefix-tokens=${observation.tokens.joinToString { "${it.surface}/${it.partOfSpeech}" }}")
    appendLine(
        "$prefix-split-modes=${observation.splitModes.joinToString { "${it.mode}:${it.surfaces.joinToString("/")}" }}",
    )
}
