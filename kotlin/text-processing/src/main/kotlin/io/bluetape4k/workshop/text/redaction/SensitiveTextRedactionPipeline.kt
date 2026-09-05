package io.bluetape4k.workshop.text.redaction

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.text.search.AhoCorasickAutomaton
import io.bluetape4k.text.search.AhoCorasickMatch
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.ahoCorasick
import io.bluetape4k.workshop.text.detection.LanguageDetectionService
import io.bluetape4k.workshop.text.normalize.TextNormalizer
import java.io.Serializable
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 민감 text span 을 가리키는 half-open source range 입니다.
 *
 * ## Behavior / Contract
 * - [startInclusive] 는 zero-based inclusive offset 입니다.
 * - [endExclusive] 는 exclusive offset 이며 [startInclusive] 보다 커야 합니다.
 * - 이 range 는 normalized offset 이 아니라 원본 Kotlin `String` code-unit offset 을 사용합니다.
 */
@ConsistentCopyVisibility
data class SensitiveTextRange private constructor(
    val startInclusive: Int,
    val endExclusive: Int,
): Serializable {

    val length: Int
        get() = endExclusive - startInclusive

    override fun toString(): String =
        "SensitiveTextRange(start=$startInclusive, end=$endExclusive, length=$length)"

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 검증된 half-open range 를 생성합니다.
         */
        fun of(startInclusive: Int, endExclusive: Int): SensitiveTextRange {
            startInclusive.requireInRange(0, Int.MAX_VALUE, "startInclusive")
            endExclusive.requireGt(startInclusive, "endExclusive")
            return SensitiveTextRange(startInclusive, endExclusive)
        }
    }
}

/**
 * [SensitiveTextRedactionPipeline] 이 사용하는 detector rule 입니다.
 *
 * ## Behavior / Contract
 * - [id] 와 [category] 는 안전한 metadata slug 이며 caller secret 을 절대 포함하지 않습니다.
 * - keyword rule 은 policy가 선택한 normalization 아래에서 bluetape4k Aho-Corasick detector 로 match 합니다.
 * - regex rule 은 ambiguous 하거나 느린 matching 을 흔히 만드는 unsafe expression 을 거부합니다.
 */
class SensitiveRedactionRule private constructor(
    val id: String,
    val category: String,
    val priority: Int,
    internal val kind: SensitiveRedactionRuleKind,
    internal val keywordText: String?,
    internal val regex: Regex?,
): Serializable {

    override fun toString(): String =
        "SensitiveRedactionRule(id=$id, category=$category, priority=$priority, kind=$kind)"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 1_000

        /**
         * keyword redaction rule 을 생성합니다.
         */
        fun keyword(
            id: String,
            category: String,
            keyword: String,
            priority: Int = 50,
        ): SensitiveRedactionRule {
            val normalizedKeyword = keyword.trim()
            normalizedKeyword.requireNotBlank("keyword")
            return createRule(
                id = id,
                category = category,
                priority = priority,
                kind = SensitiveRedactionRuleKind.KEYWORD,
                keywordText = normalizedKeyword,
                regex = null,
            )
        }

        /**
         * safety check 를 적용한 뒤 regular-expression redaction rule 을 생성합니다.
         */
        fun regex(
            id: String,
            category: String,
            patternSource: String,
            priority: Int = 50,
        ): SensitiveRedactionRule {
            val source = patternSource.trim()
            source.requireNotBlank("patternSource")
            validateRegexSource(source)
            return createRule(
                id = id,
                category = category,
                priority = priority,
                kind = SensitiveRedactionRuleKind.REGEX,
                keywordText = null,
                regex = Regex(source),
            )
        }

        private fun createRule(
            id: String,
            category: String,
            priority: Int,
            kind: SensitiveRedactionRuleKind,
            keywordText: String?,
            regex: Regex?,
        ): SensitiveRedactionRule {
            val safeId = id.trim()
            val safeCategory = category.trim()
            validateMetadataSlug(safeId, "id")
            validateMetadataSlug(safeCategory, "category")
            priority.requireInRange(MIN_PRIORITY, MAX_PRIORITY, "priority")
            return SensitiveRedactionRule(
                id = safeId,
                category = safeCategory,
                priority = priority,
                kind = kind,
                keywordText = keywordText,
                regex = regex,
            )
        }

        private fun validateRegexSource(source: String) {
            BACKREFERENCE_PATTERN.containsMatchIn(source).requireFalse("patternSource.backreferences")
            NESTED_UNBOUNDED_QUANTIFIER_PATTERN.containsMatchIn(source)
                .requireFalse("patternSource.nestedUnboundedQuantifiers")
            UNBOUNDED_DOT_STAR_PATTERN.containsMatchIn(source).requireFalse("patternSource.unboundedDotStar")
        }

        private fun validateMetadataSlug(value: String, parameterName: String) {
            SAFE_METADATA_SLUG_PATTERN.matches(value).requireTrue("$parameterName.slug")
            EMAIL_LIKE_PATTERN.containsMatchIn(value).requireFalse("$parameterName.email")
            PHONE_LIKE_PATTERN.containsMatchIn(value).requireFalse("$parameterName.phone")
            TOKEN_LIKE_PATTERN.containsMatchIn(value).requireFalse("$parameterName.token")
            CUSTOMER_TICKET_PATTERN.containsMatchIn(value).requireFalse("$parameterName.customerTicket")
        }

    }
}

/**
 * immutable redaction policy snapshot 입니다.
 *
 * ## Behavior / Contract
 * - rule 은 construction 시점에 copy 되므로 이후 caller mutation 이 pipeline 을 바꿀 수 없습니다.
 * - [maskChar] 는 visible non-whitespace character 여야 합니다.
 * - [maxTextLength] 는 example pipeline 을 unbounded caller input 으로부터 보호합니다.
 * - [keywordNormalization] 은 keyword와 입력에 함께 적용하며 match range는 원문 offset으로 복원됩니다.
 */
class SensitiveRedactionPolicy private constructor(
    val rules: List<SensitiveRedactionRule>,
    val maskChar: Char,
    val maxTextLength: Int,
    val keywordNormalization: NormalizationForm,
): Serializable {

    override fun toString(): String =
        "SensitiveRedactionPolicy(ruleCount=${rules.size}, maskChar=<redacted>, " +
            "maxTextLength=$maxTextLength, keywordNormalization=$keywordNormalization)"

    companion object {
        private const val serialVersionUID: Long = 1L
        const val DEFAULT_MAX_TEXT_LENGTH: Int = 4_096

        /**
         * immutable policy snapshot 을 생성합니다.
         */
        fun of(
            rules: Collection<SensitiveRedactionRule>,
            maskChar: Char = '*',
            maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH,
            keywordNormalization: NormalizationForm = NormalizationForm.NFC,
        ): SensitiveRedactionPolicy {
            rules.requireNotEmpty("rules")
            maskChar.isWhitespace().requireFalse("maskChar.whitespace")
            maxTextLength.requireInRange(1, Int.MAX_VALUE, "maxTextLength")
            return SensitiveRedactionPolicy(
                rules = Collections.unmodifiableList(rules.toList()),
                maskChar = maskChar,
                maxTextLength = maxTextLength,
                keywordNormalization = keywordNormalization,
            )
        }

        /**
         * contact, token, support-keyword masking 을 위한 workshop default policy 를 생성합니다.
         */
        fun default(): SensitiveRedactionPolicy =
            of(
                rules = listOf(
                    SensitiveRedactionRule.regex(
                        id = "email",
                        category = "contact",
                        patternSource = """\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,24}\b""",
                        priority = 10,
                    ),
                    SensitiveRedactionRule.regex(
                        id = "phone",
                        category = "contact",
                        patternSource = """\b(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b""",
                        priority = 20,
                    ),
                    SensitiveRedactionRule.regex(
                        id = "token",
                        category = "secret",
                        patternSource = """\b(?:(?:Bearer\s+|token=|api_key=)[A-Za-z0-9._-]{12,}|[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b""",
                        priority = 1,
                    ),
                    SensitiveRedactionRule.keyword(
                        id = "support-keyword",
                        category = "keyword",
                        keyword = "account number",
                        priority = 30,
                    ),
                )
            )
    }
}

/**
 * redaction 된 sensitive span metadata 입니다.
 *
 * ## Behavior / Contract
 * - [range] 는 원본 source text 내부를 가리킵니다.
 * - [ruleIds] 는 안전한 rule metadata 만 포함하며 deterministic order 를 가집니다.
 * - raw matched text 는 저장하지 않습니다.
 */
@ConsistentCopyVisibility
data class SensitiveSpan private constructor(
    val range: SensitiveTextRange,
    val category: String,
    val ruleIds: List<String>,
    val matchedLength: Int,
): Serializable {

    override fun toString(): String =
        "SensitiveSpan(range=$range, category=$category, ruleIds=$ruleIds, matchedLength=$matchedLength)"

    companion object {
        private const val serialVersionUID: Long = 1L

        internal fun of(range: SensitiveTextRange, category: String, ruleIds: List<String>): SensitiveSpan {
            category.requireNotBlank("category")
            ruleIds.requireNotEmpty("ruleIds")
            return SensitiveSpan(
                range = range,
                category = category,
                ruleIds = Collections.unmodifiableList(ruleIds.toList()),
                matchedLength = range.length,
            )
        }
    }
}

/**
 * [SensitiveTextRedactionPipeline.redact] 가 반환하는 result 입니다.
 *
 * ## Behavior / Contract
 * - [redactedText] 는 항상 원본 input 과 같은 길이를 가집니다.
 * - [spans] 는 merge 된 non-overlapping half-open range 를 포함합니다.
 * - [detectedLanguage] 와 [bestConfidence] 는 학습자를 위한 선택적 safe metadata 입니다.
 */
@ConsistentCopyVisibility
data class SensitiveRedactionResult internal constructor(
    val redactedText: String,
    val spans: List<SensitiveSpan>,
    val detectedLanguage: Language?,
    val bestConfidence: Double?,
    val normalizedLength: Int,
): Serializable {

    val matchCount: Int
        get() = spans.size

    override fun toString(): String =
        "SensitiveRedactionResult(length=${redactedText.length}, matchCount=$matchCount, " +
            "detectedLanguage=$detectedLanguage, normalizedLength=$normalizedLength)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * workshop text-processing 예제를 위한 thread-safe sensitive text redaction pipeline 입니다.
 *
 * ## Behavior / Contract
 * - structured secret 에는 regex rule 을 사용하고 configured term 에는 Aho-Corasick keyword rule 을 사용합니다.
 * - adjacent span 은 분리해 유지하면서 overlapping span 은 merge 합니다.
 * - offset 이 안정적으로 유지되도록 각 source code unit 을 [SensitiveRedactionPolicy.maskChar] 로 masking 합니다.
 * - 명시적 lock 으로 shared Lingua detector 접근을 직렬화합니다.
 */
class SensitiveTextRedactionPipeline private constructor(
    private val policy: SensitiveRedactionPolicy,
    private val detectionService: LanguageDetectionService,
) {

    private val detectorLock = ReentrantLock()

    private val regexRules: List<SensitiveRedactionRule> =
        policy.rules.filter { it.kind == SensitiveRedactionRuleKind.REGEX }

    private val keywordRules: List<SensitiveRedactionRule> =
        policy.rules.filter { it.kind == SensitiveRedactionRuleKind.KEYWORD }

    private val keywordAutomaton: AhoCorasickAutomaton<SensitiveRedactionRule>? =
        keywordRules
            .takeIf { it.isNotEmpty() }
            ?.let { rules ->
                ahoCorasick {
                    ignoreCase = true
                    allowOverlaps = true
                    normalization = policy.keywordNormalization
                    rules.forEach { rule -> keyword(rule.keywordText.orEmpty(), rule) }
                }
            }

    /**
     * [text] 에서 sensitive value 를 redaction 하고 safe metadata 를 반환합니다.
     */
    fun redact(text: String): SensitiveRedactionResult {
        text.trim().length.requireInRange(1, Int.MAX_VALUE, "text.trimmed.length")
        text.length.requireInRange(1, policy.maxTextLength, "text.length")

        val normalized = TextNormalizer.normalize(text)
        val languageMetadata = computeLanguageMetadata(text)
        val spans = mergeMatches(findMatches(text)).map { it.toSpan() }
        val redactedText = maskText(text, spans)

        log.debug {
            "redact length=${text.length} normalizedLength=${normalized.length} " +
                "matches=${spans.size} language=${languageMetadata.detectedLanguage}"
        }

        return SensitiveRedactionResult(
            redactedText = redactedText,
            spans = Collections.unmodifiableList(spans),
            detectedLanguage = languageMetadata.detectedLanguage,
            bestConfidence = languageMetadata.bestConfidence,
            normalizedLength = normalized.length,
        )
    }

    private fun computeLanguageMetadata(text: String): LanguageMetadata =
        detectorLock.withLock {
            val confidenceValues = detectionService.computeConfidenceValues(text)
            val detectedLanguage = confidenceValues.keys
                .firstOrNull()
                ?.takeUnless { it == Language.UNKNOWN }
            LanguageMetadata(
                detectedLanguage = detectedLanguage,
                bestConfidence = confidenceValues.values.firstOrNull(),
            )
        }

    private fun findMatches(text: String): List<SensitiveMatch> =
        regexRules.flatMap { rule -> rule.findRegexMatches(text) } +
            keywordAutomaton
                ?.parseText(text)
                .orEmpty()
                .map { match -> match.toSensitiveMatch() }

    private fun SensitiveRedactionRule.findRegexMatches(text: String): List<SensitiveMatch> =
        regex.orEmpty()
            .findAll(text)
            .filter { it.range.first <= it.range.last }
            .map { match ->
                SensitiveMatch(
                    range = SensitiveTextRange.of(match.range.first, match.range.last + 1),
                    rule = this,
                )
            }
            .toList()

    private fun AhoCorasickMatch<SensitiveRedactionRule>.toSensitiveMatch(): SensitiveMatch =
        SensitiveMatch(
            range = SensitiveTextRange.of(start, end + 1),
            rule = value,
        )

    private fun mergeMatches(matches: List<SensitiveMatch>): List<SensitiveMergedMatch> {
        if (matches.isEmpty()) return emptyList()

        val sorted = matches.sortedWith(
            compareBy<SensitiveMatch> { it.range.startInclusive }
                .thenByDescending { it.range.endExclusive }
                .thenBy { it.rule.priority }
                .thenBy { it.rule.id }
        )

        val merged = mutableListOf<SensitiveMergedMatch>()
        var current = SensitiveMergedMatch.of(sorted.first())
        sorted.drop(1).forEach { next ->
            if (next.range.startInclusive < current.range.endExclusive) {
                current = current.merge(next)
            } else {
                merged += current
                current = SensitiveMergedMatch.of(next)
            }
        }
        merged += current
        return merged
    }

    private fun SensitiveMergedMatch.toSpan(): SensitiveSpan =
        SensitiveSpan.of(
            range = range,
            category = bestRule.category,
            ruleIds = rules.sortedByPriority().map { it.id },
        )

    private fun maskText(text: String, spans: List<SensitiveSpan>): String {
        if (spans.isEmpty()) return text
        val chars = text.toCharArray()
        spans.forEach { span ->
            for (index in span.range.startInclusive until span.range.endExclusive) {
                chars[index] = policy.maskChar
            }
        }
        return chars.concatToString()
    }

    companion object: KLogging() {
        /**
         * Creates a pipeline with the workshop default policy.
         */
        fun default(detectionService: LanguageDetectionService = LanguageDetectionService()): SensitiveTextRedactionPipeline =
            of(SensitiveRedactionPolicy.default(), detectionService)

        /**
         * Creates a pipeline from a caller-provided immutable policy snapshot.
         */
        fun of(
            policy: SensitiveRedactionPolicy,
            detectionService: LanguageDetectionService = LanguageDetectionService(),
        ): SensitiveTextRedactionPipeline =
            SensitiveTextRedactionPipeline(policy, detectionService)
    }
}

internal enum class SensitiveRedactionRuleKind {
    KEYWORD,
    REGEX,
}

private data class LanguageMetadata(
    val detectedLanguage: Language?,
    val bestConfidence: Double?,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private data class SensitiveMatch(
    val range: SensitiveTextRange,
    val rule: SensitiveRedactionRule,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

private data class SensitiveMergedMatch(
    val range: SensitiveTextRange,
    val rules: List<SensitiveRedactionRule>,
) : Serializable {
    val bestRule: SensitiveRedactionRule
        get() = rules.sortedByPriority().first()

    fun merge(next: SensitiveMatch): SensitiveMergedMatch =
        SensitiveMergedMatch(
            range = SensitiveTextRange.of(
                range.startInclusive.coerceAtMost(next.range.startInclusive),
                range.endExclusive.coerceAtLeast(next.range.endExclusive),
            ),
            rules = rules + next.rule,
        )

    companion object {
        private const val serialVersionUID: Long = 1L

        fun of(match: SensitiveMatch): SensitiveMergedMatch =
            SensitiveMergedMatch(
                range = match.range,
                rules = listOf(match.rule),
            )
    }
}

private fun Regex?.orEmpty(): Regex =
    this ?: Regex("""a\A""")

private fun List<SensitiveRedactionRule>.sortedByPriority(): List<SensitiveRedactionRule> =
    sortedWith(
        compareBy<SensitiveRedactionRule> { it.priority }
            .thenBy { it.id }
            .thenBy { it.category }
    )

private fun Boolean.requireTrue(parameterName: String) {
    (if (this) 0 else 1).requireInRange(0, 0, parameterName)
}

private fun Boolean.requireFalse(parameterName: String) {
    (if (this) 1 else 0).requireInRange(0, 0, parameterName)
}

private val SAFE_METADATA_SLUG_PATTERN = Regex("""^[a-z0-9._-]{1,64}$""")
private val BACKREFERENCE_PATTERN = Regex("""\\[1-9]""")
private val NESTED_UNBOUNDED_QUANTIFIER_PATTERN = Regex("""\([^)]*[+*][^)]*\)[+*]""")
private val UNBOUNDED_DOT_STAR_PATTERN = Regex("""(?<!\\)\.\*""")
private val EMAIL_LIKE_PATTERN = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE_LIKE_PATTERN = Regex("""\d{3}[-.\s]?\d{3}[-.\s]?\d{4}""")
private val TOKEN_LIKE_PATTERN = Regex("""(?:token=|api_key=|Bearer\s+|[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})""")
private val CUSTOMER_TICKET_PATTERN = Regex("""(?:customer|ticket)-?\d{4,}""")
