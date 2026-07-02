package io.bluetape4k.workshop.text.redaction

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
 * Half-open source range for sensitive text spans.
 *
 * ## Behavior / Contract
 * - [startInclusive] is zero-based and inclusive.
 * - [endExclusive] is exclusive and must be greater than [startInclusive].
 * - The range uses original Kotlin `String` code-unit offsets, not normalized offsets.
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
         * Creates a validated half-open range.
         */
        fun of(startInclusive: Int, endExclusive: Int): SensitiveTextRange {
            startInclusive.requireInRange(0, Int.MAX_VALUE, "startInclusive")
            require(endExclusive > startInclusive) {
                "endExclusive must be greater than startInclusive."
            }
            return SensitiveTextRange(startInclusive, endExclusive)
        }
    }
}

/**
 * Detector rule used by [SensitiveTextRedactionPipeline].
 *
 * ## Behavior / Contract
 * - [id] and [category] are safe metadata slugs and never contain caller secrets.
 * - Keyword rules are matched with the bluetape4k Aho-Corasick detector under NFC normalization.
 * - Regex rules reject unsafe expressions that commonly create ambiguous or slow matching.
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
         * Creates a keyword redaction rule.
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
         * Creates a regular-expression redaction rule after applying safety checks.
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
            require(!BACKREFERENCE_PATTERN.containsMatchIn(source)) {
                "patternSource must not use backreferences."
            }
            require(!NESTED_UNBOUNDED_QUANTIFIER_PATTERN.containsMatchIn(source)) {
                "patternSource must not use nested unbounded quantifiers."
            }
            require(!UNBOUNDED_DOT_STAR_PATTERN.containsMatchIn(source)) {
                "patternSource must not use unbounded dot-star matching."
            }
        }

        private fun validateMetadataSlug(value: String, parameterName: String) {
            require(SAFE_METADATA_SLUG_PATTERN.matches(value)) {
                "$parameterName must be a safe metadata slug."
            }
            require(!EMAIL_LIKE_PATTERN.containsMatchIn(value)) {
                "$parameterName must not contain sensitive metadata."
            }
            require(!PHONE_LIKE_PATTERN.containsMatchIn(value)) {
                "$parameterName must not contain sensitive metadata."
            }
            require(!TOKEN_LIKE_PATTERN.containsMatchIn(value)) {
                "$parameterName must not contain sensitive metadata."
            }
            require(!CUSTOMER_TICKET_PATTERN.containsMatchIn(value)) {
                "$parameterName must not contain sensitive metadata."
            }
        }

    }
}

/**
 * Immutable redaction policy snapshot.
 *
 * ## Behavior / Contract
 * - Rules are copied on construction so later caller mutations cannot change a pipeline.
 * - [maskChar] must be a visible non-whitespace character.
 * - [maxTextLength] protects the example pipeline from unbounded caller input.
 */
class SensitiveRedactionPolicy private constructor(
    val rules: List<SensitiveRedactionRule>,
    val maskChar: Char,
    val maxTextLength: Int,
): Serializable {

    override fun toString(): String =
        "SensitiveRedactionPolicy(ruleCount=${rules.size}, maskChar=<redacted>, maxTextLength=$maxTextLength)"

    companion object {
        private const val serialVersionUID: Long = 1L
        const val DEFAULT_MAX_TEXT_LENGTH: Int = 4_096

        /**
         * Creates an immutable policy snapshot.
         */
        fun of(
            rules: Collection<SensitiveRedactionRule>,
            maskChar: Char = '*',
            maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH,
        ): SensitiveRedactionPolicy {
            rules.requireNotEmpty("rules")
            require(!maskChar.isWhitespace()) {
                "maskChar must be a non-whitespace character."
            }
            maxTextLength.requireInRange(1, Int.MAX_VALUE, "maxTextLength")
            return SensitiveRedactionPolicy(
                rules = Collections.unmodifiableList(rules.toList()),
                maskChar = maskChar,
                maxTextLength = maxTextLength,
            )
        }

        /**
         * Creates the workshop default policy for contact, token, and support-keyword masking.
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
 * Redacted sensitive span metadata.
 *
 * ## Behavior / Contract
 * - [range] points into the original source text.
 * - [ruleIds] contains safe rule metadata only and is ordered deterministically.
 * - No raw matched text is stored.
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
 * Result returned by [SensitiveTextRedactionPipeline.redact].
 *
 * ## Behavior / Contract
 * - [redactedText] always has the same length as the original input.
 * - [spans] contains merged non-overlapping half-open ranges.
 * - [detectedLanguage] and [bestConfidence] are optional, safe metadata for learners.
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
 * Thread-safe sensitive text redaction pipeline for the workshop text-processing examples.
 *
 * ## Behavior / Contract
 * - Uses regex rules for structured secrets and Aho-Corasick keyword rules for configured terms.
 * - Merges overlapping spans while keeping adjacent spans separate.
 * - Masks each source code unit with [SensitiveRedactionPolicy.maskChar] so offsets remain stable.
 * - Serializes access to the shared Lingua detector through an explicit lock.
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
                    normalization = NormalizationForm.NFC
                    rules.forEach { rule -> keyword(rule.keywordText.orEmpty(), rule) }
                }
            }

    /**
     * Redacts sensitive values from [text] and returns safe metadata.
     */
    fun redact(text: String): SensitiveRedactionResult {
        require(text.isNotBlank()) {
            "blank-text"
        }
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

private val SAFE_METADATA_SLUG_PATTERN = Regex("""^[a-z0-9._-]{1,64}$""")
private val BACKREFERENCE_PATTERN = Regex("""\\[1-9]""")
private val NESTED_UNBOUNDED_QUANTIFIER_PATTERN = Regex("""\([^)]*[+*][^)]*\)[+*]""")
private val UNBOUNDED_DOT_STAR_PATTERN = Regex("""(?<!\\)\.\*""")
private val EMAIL_LIKE_PATTERN = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
private val PHONE_LIKE_PATTERN = Regex("""\d{3}[-.\s]?\d{3}[-.\s]?\d{4}""")
private val TOKEN_LIKE_PATTERN = Regex("""(?:token=|api_key=|Bearer\s+|[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})""")
private val CUSTOMER_TICKET_PATTERN = Regex("""(?:customer|ticket)-?\d{4,}""")
