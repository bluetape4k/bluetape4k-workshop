package io.bluetape4k.workshop.text.redaction

import com.github.pemistahl.lingua.api.Language
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.text.search.NormalizationForm
import io.bluetape4k.text.search.RedactionPolicy as CoreRedactionPolicy
import io.bluetape4k.text.search.RedactionRule as CoreRedactionRule
import io.bluetape4k.text.search.RedactionSpan as CoreRedactionSpan
import io.bluetape4k.text.search.TextRedactor
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

        /** 검증된 half-open range 를 생성합니다. */
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
 * 핵심 keyword/regex matching과 span merge는 `bluetape4k-text`의 [TextRedactor]가 수행합니다.
 * 이 workshop wrapper는 기존 예제의 metadata 계약과 선택적인 교육용 regex guard만 보존합니다.
 */
class SensitiveRedactionRule private constructor(
    val id: String,
    val category: String,
    val priority: Int,
    internal val delegate: CoreRedactionRule,
    internal val kind: SensitiveRedactionRuleKind,
): Serializable {

    override fun toString(): String =
        "SensitiveRedactionRule(id=$id, category=$category, priority=$priority, kind=$kind)"

    companion object {
        private const val serialVersionUID: Long = 1L
        private const val MIN_PRIORITY = 1
        private const val MAX_PRIORITY = 1_000

        /** keyword redaction rule 을 생성합니다. */
        fun keyword(
            id: String,
            category: String,
            keyword: String,
            priority: Int = 50,
        ): SensitiveRedactionRule {
            val value = keyword.trim()
            value.requireNotBlank("keyword")
            val metadata = validateMetadata(id, category, priority)
            return SensitiveRedactionRule(
                id = metadata.first,
                category = metadata.second,
                priority = priority,
                delegate = CoreRedactionRule.keyword(metadata.first, metadata.second, value, priority),
                kind = SensitiveRedactionRuleKind.KEYWORD,
            )
        }

        /** workshop fixture의 선택적인 regex safety check 후 regular-expression rule을 생성합니다. */
        fun regex(
            id: String,
            category: String,
            patternSource: String,
            priority: Int = 50,
        ): SensitiveRedactionRule {
            val source = patternSource.trim()
            source.requireNotBlank("patternSource")
            validateRegexSource(source)
            val metadata = validateMetadata(id, category, priority)
            return SensitiveRedactionRule(
                id = metadata.first,
                category = metadata.second,
                priority = priority,
                delegate = CoreRedactionRule.regex(metadata.first, metadata.second, source, priority),
                kind = SensitiveRedactionRuleKind.REGEX,
            )
        }

        private fun validateMetadata(
            id: String,
            category: String,
            priority: Int,
        ): Pair<String, String> {
            val safeId = id.trim()
            val safeCategory = category.trim()
            validateMetadataSlug(safeId, "id")
            validateMetadataSlug(safeCategory, "category")
            priority.requireInRange(MIN_PRIORITY, MAX_PRIORITY, "priority")
            return safeId to safeCategory
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
 * 실제 redaction 설정은 provider [CoreRedactionPolicy]로 방어적으로 복사합니다. Workshop은
 * [keywordNormalization]과 기존 default composition을 독자가 확인할 수 있도록 노출합니다.
 */
class SensitiveRedactionPolicy private constructor(
    val rules: List<SensitiveRedactionRule>,
    val maskChar: Char,
    val maxTextLength: Int,
    val keywordNormalization: NormalizationForm,
    internal val delegate: CoreRedactionPolicy,
): Serializable {

    override fun toString(): String =
        "SensitiveRedactionPolicy(ruleCount=${rules.size}, maskChar=<redacted>, " +
            "maxTextLength=$maxTextLength, keywordNormalization=$keywordNormalization)"

    companion object {
        private const val serialVersionUID: Long = 1L
        const val DEFAULT_MAX_TEXT_LENGTH: Int = CoreRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH

        /** immutable policy snapshot을 생성합니다. */
        @JvmOverloads
        fun of(
            rules: Collection<SensitiveRedactionRule>,
            maskChar: Char = '*',
            maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH,
            keywordNormalization: NormalizationForm = NormalizationForm.NFC,
        ): SensitiveRedactionPolicy {
            rules.requireNotEmpty("rules")
            maskChar.isWhitespace().requireFalse("maskChar.whitespace")
            maxTextLength.requireInRange(1, Int.MAX_VALUE, "maxTextLength")
            val snapshot = Collections.unmodifiableList(rules.toList())
            val delegate = CoreRedactionPolicy.of(
                rules = snapshot.map { it.delegate },
                maskChar = maskChar,
                maxTextLength = maxTextLength,
                keywordIgnoreCase = true,
                keywordNormalization = keywordNormalization,
            )
            return SensitiveRedactionPolicy(
                rules = snapshot,
                maskChar = maskChar,
                maxTextLength = maxTextLength,
                keywordNormalization = keywordNormalization,
                delegate = delegate,
            )
        }

        /** contact, token, support-keyword masking을 위한 workshop default policy입니다. */
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

/** redaction된 sensitive span metadata입니다. */
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

        internal fun from(span: CoreRedactionSpan): SensitiveSpan =
            of(
                range = SensitiveTextRange.of(span.range.startInclusive, span.range.endExclusive),
                category = span.category,
                ruleIds = span.ruleIds,
            )

        private fun of(range: SensitiveTextRange, category: String, ruleIds: List<String>): SensitiveSpan {
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

/** [SensitiveTextRedactionPipeline.redact]가 반환하는 result입니다. */
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
 * workshop text-processing 예제를 위한 thread-safe redaction pipeline입니다.
 *
 * keyword/regex 탐지, overlap merge, priority tie-break, same-length masking은 공용
 * [TextRedactor]에 위임합니다. Workshop은 Lingua 언어 metadata와 [TextNormalizer] 예제를
 * 조합해 반환하는 소비자 facade만 유지합니다.
 */
class SensitiveTextRedactionPipeline private constructor(
    private val policy: SensitiveRedactionPolicy,
    private val detectionService: LanguageDetectionService,
) {

    private val detectorLock = ReentrantLock()
    private val redactor = TextRedactor.of(policy.delegate)

    /** [text]에서 sensitive value를 redaction하고 safe metadata를 반환합니다. */
    fun redact(text: String): SensitiveRedactionResult {
        text.trim().length.requireInRange(1, Int.MAX_VALUE, "text.trimmed.length")
        text.length.requireInRange(1, policy.maxTextLength, "text.length")

        val normalized = TextNormalizer.normalize(text)
        val languageMetadata = computeLanguageMetadata(text)
        val coreResult = redactor.redact(text)
        val spans = coreResult.spans.map(SensitiveSpan::from)

        log.debug {
            "redact length=${text.length} normalizedLength=${normalized.length} " +
                "matches=${spans.size} language=${languageMetadata.detectedLanguage}"
        }

        return SensitiveRedactionResult(
            redactedText = coreResult.redactedText,
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

    companion object: KLogging() {
        /** workshop default policy로 pipeline을 생성합니다. */
        fun default(detectionService: LanguageDetectionService = LanguageDetectionService()): SensitiveTextRedactionPipeline =
            of(SensitiveRedactionPolicy.default(), detectionService)

        /** caller policy snapshot으로 pipeline을 생성합니다. */
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
