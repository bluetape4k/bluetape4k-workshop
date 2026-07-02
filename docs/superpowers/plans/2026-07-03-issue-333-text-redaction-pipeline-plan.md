# Text Redaction Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `kotlin/text-processing` with a deterministic, audit-safe sensitive-text redaction pipeline for issue #333.

**Architecture:** Add a focused `io.bluetape4k.workshop.text.redaction` package inside the existing text-processing module. The pipeline validates input, normalizes Unicode to NFC, reuses text-processing language detection and Aho-Corasick utilities, merges sensitive spans deterministically, returns raw-value-free internal metadata, and updates README diagrams/docs plus CI smoke coverage.

**Tech Stack:** Kotlin/JVM, bluetape4k-text search/Lingua, bluetape4k validation helpers, bluetape4k logging, JUnit 5, bluetape4k-assertions, bluetape4k-junit5 `MultithreadingTester`, Logback `ListAppender`, CairoSVG, repo-local diagram QA, GitHub Actions Examples workflow.

---

## File Map

- Create `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipeline.kt`: public value types, policy compilation, span detection, redaction, and safe logging.
- Create `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipelineTest.kt`: TDD tests for redaction, metadata safety, overlap behavior, validation, logging, and thread safety.
- Modify `kotlin/text-processing/build.gradle.kts`: change `testRuntimeOnly(libs.logback.lib)` to `testImplementation(libs.logback.lib)` so Logback `ListAppender` test code compiles.
- Modify `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionService.kt`: remove raw source text from debug logs.
- Modify `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/normalize/TextNormalizer.kt`: remove raw source/normalized text/keyword values from debug logs.
- Modify `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilter.kt`: remove raw source/filtered text from debug logs.
- Modify `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/MultilingualSearchIndex.kt`: remove raw query/text values from debug logs in the same module.
- Modify `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/CoroutineMultilingualSearchIndex.kt`: remove raw query/text values from coroutine search debug logs so sync and coroutine examples follow the same audit-safe logging rule.
- Modify `kotlin/text-processing/README.md` and `README.ko.md`: add redaction pipeline usage, limits, metadata guidance, and dependency notes.
- Modify `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg/png` and `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg/png`: add the redaction path.
- Modify `.github/workflows/Examples.yml`: add `kotlin/text-processing/**` path filters, `:kotlin-text-processing:test`, and artifact paths.
- Modify `scripts/smoke-validate.sh`: add `:kotlin-text-processing:test` to `all-smoke`.
- Create `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md`: Step 6-R review evidence.
- Create `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md`: Step 7 lesson evidence before PR.

## Task 1: Add Red Failing Redaction Tests

**Complexity:** high
**Skills:** `test-driven-development`, `bluetape4k-code-patterns`, `ecc-kotlin-testing`

**Files:**

- Modify: `kotlin/text-processing/build.gradle.kts`
- Create: `kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipelineTest.kt`

- [ ] **Step 1: Add test compile dependency for Logback capture**

Change the existing test dependency from runtime-only to compile-visible before writing the test file:

```kotlin
testImplementation(libs.logback.lib)
```

Expected: `SensitiveTextRedactionPipelineTest` can import `Logger`, `ILoggingEvent`, and `ListAppender`, so the first RED proof fails on the missing redaction API instead of missing Logback classes.

- [ ] **Step 2: Create the test class and fixture constants**

Use `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`, `bluetape4k-assertions`, and reserved/synthetic inputs only.

```kotlin
package io.bluetape4k.workshop.text.redaction

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.measureTime

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitiveTextRedactionPipelineTest {
    private val pipeline = SensitiveTextRedactionPipeline.default()

    private val email = "user@example.test"
    private val phone = "555-010-1234"
    private val token = "token=demo_token_value_123456"
    private val keyword = "account number"
}
```

- [ ] **Step 3: Add deterministic redaction test**

```kotlin
@Test
fun `redacts email phone token and configured keyword deterministically`() {
    val input = "Contact $email at $phone with $token for $keyword review."
    val expected = "Contact ${"*".repeat(email.length)} at ${"*".repeat(phone.length)} " +
        "with ${"*".repeat(token.length)} for ${"*".repeat(keyword.length)} review."

    val result = pipeline.redact(input)
    val repeated = pipeline.redact(input)

    result.redactedText.length shouldBeEqualTo input.length
    result.redactedText shouldBeEqualTo expected
    result.redactedText.startsWith("Contact ").shouldBeTrue()
    result.redactedText.endsWith(" review.").shouldBeTrue()
    repeated shouldBeEqualTo result
    result.redactedText shouldNotContain email
    result.redactedText shouldNotContain phone
    result.redactedText shouldNotContain token
    result.redactedText shouldNotContain keyword
    result.spans.map { it.range.startInclusive } shouldBeEqualTo result.spans.map { it.range.startInclusive }.sorted()
    result.spans.map { it.category } shouldBeEqualTo listOf("contact", "contact", "secret", "keyword")
    result.spans.map { it.ruleIds.single() } shouldBeEqualTo listOf("email", "phone", "token", "support-keyword")
    result.spans.map { it.matchedLength } shouldBeEqualTo listOf(email.length, phone.length, token.length, keyword.length)
    result.spans.map { it.range.startInclusive } shouldBeEqualTo listOf(
        input.indexOf(email),
        input.indexOf(phone),
        input.indexOf(token),
        input.indexOf(keyword),
    )
    result.spans.map { it.range.endExclusive } shouldBeEqualTo listOf(
        input.indexOf(email) + email.length,
        input.indexOf(phone) + phone.length,
        input.indexOf(token) + token.length,
        input.indexOf(keyword) + keyword.length,
    )
}
```

- [ ] **Step 4: Add metadata and `toString()` safety test**

```kotlin
@Test
fun `metadata and toString do not expose raw sensitive values`() {
    val keywordRule = SensitiveRedactionRule.keyword("keyword.safe", "keyword", keyword)
    val keywordPolicy = SensitiveRedactionPolicy.of(rules = listOf(keywordRule))
    val result = pipeline.redact("Support note $email $token")
    val rendered = result.toString() +
        result.spans.joinToString() +
        keywordRule.toString() +
        keywordPolicy.toString()

    rendered shouldNotContain email
    rendered shouldNotContain token
    rendered shouldNotContain keyword
    result.spans.forEach { span ->
        span.matchedLength shouldBeEqualTo (span.range.endExclusive - span.range.startInclusive)
        span.ruleIds.any { it.contains("@") }.shouldBeFalse()
    }
}
```

- [ ] **Step 5: Add overlap and offset behavior tests**

```kotlin
@Test
fun `overlapping spans merge and adjacent spans stay separate`() {
    val policy = SensitiveRedactionPolicy.of(
        rules = listOf(
            SensitiveRedactionRule.keyword("keyword.low", "keyword", "account", priority = 30),
            SensitiveRedactionRule.keyword("keyword.high", "keyword", "account number", priority = 10),
            SensitiveRedactionRule.keyword("keyword.next", "keyword", "review", priority = 30),
        )
    )
    val localPipeline = SensitiveTextRedactionPipeline.of(policy)

    val result = localPipeline.redact("account number review")

    result.spans shouldHaveSize 2
    result.spans.first().range.startInclusive shouldBeEqualTo 0
    result.spans.first().range.endExclusive shouldBeEqualTo "account number".length
    result.spans.first().ruleIds shouldBeEqualTo listOf("keyword.high", "keyword.low")
}
```

Also add named tests for deterministic edge cases:

- `adjacent keyword spans do not merge`: use keyword rules `abc` and `def` with input `abcdef`; assert two spans `[0, 3)` and `[3, 6)`.
- `equal priority overlaps choose category by rule id then category`: use overlapping keyword rules with the same priority and different ids/categories; assert one merged span and the expected category/rule-id order.
- `keyword detector inclusive end converts to half open range`: use one Aho-Corasick keyword rule and assert `endExclusive == startInclusive + keyword.length`.

- [ ] **Step 6: Add Unicode, validation, ReDoS, log capture, and thread-safety tests**

Test names must cover:

- `preserves original offsets when source contains decomposed Unicode`
- `rejects blank text without echoing caller input`
- `rejects over limit text without echoing caller input`
- `rejects unsafe rule ids and categories`
- `rejects invalid ranges masks empty rules and unsafe regex sources`
- `handles long non matching token candidates without catastrophic regex behavior`
- `returns language metadata for multilingual Korean and English input`
- `debug logs include safe metadata and exclude synthetic sensitive values`
- `policy snapshots do not change when caller mutates original rule list`
- `shared pipeline is stable under MultithreadingTester`

Validation tests must cover:

- `SensitiveTextRange.of(-1, 2)`, `SensitiveTextRange.of(2, 2)`, and reversed ranges;
- empty rule collections;
- whitespace or ISO-control mask characters;
- rule id/category uppercase, spaces, slash, email, phone, token, customer id, ticket id, and configured keyword containment;
- unsafe regex sources containing backreferences such as `(a)\\1`, nested unbounded quantifiers such as `(a+)+`, and unbounded dot-star such as `.*secret.*`.

Unicode tests must use an NFD source with a composed/NFC keyword rule, then assert original code-unit `startInclusive`,
`endExclusive`, `matchedLength`, same-length redacted text, and no unmasked combining fragment in the matched range.

ReDoS tests must use adversarial non-match inputs near `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH`
for email, phone, and token shapes. Measure the block with `measureTime { ... }` and assert
`elapsed.inWholeMilliseconds shouldBeLessThan 300L` with `bluetape4k-assertions`; do not use JUnit
assertion APIs such as `assertTimeoutPreemptively`.

Use this Logback pattern:

```kotlin
private fun captureWorkshopLogs(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger("io.bluetape4k.workshop.text") as Logger
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val previousLevel = logger.level
    val previousAdditive = logger.isAdditive
    logger.level = ch.qos.logback.classic.Level.DEBUG
    logger.isAdditive = true
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        logger.level = previousLevel
        logger.isAdditive = previousAdditive
        appender.stop()
    }
    return appender.list.map { it.formattedMessage }
}
```

The log test must exercise the pipeline plus `LanguageDetectionService`, `TextNormalizer`, `AbuseWordFilter`,
`MultilingualSearchIndex`, and `CoroutineMultilingualSearchIndex`. Assert at least one safe event per touched
collaborator/stage includes length/count metadata before asserting logs exclude `user@example.test`,
`555-010-1234`, `demo_token_value`, `account number`, raw query strings, normalized text, redacted text, and keyword lists.

Use this concurrency pattern:

```kotlin
@Test
fun `shared pipeline is stable under MultithreadingTester`() {
    val baseline = pipeline.redact("Contact $email with $token")
    val outputs = ConcurrentLinkedQueue<SensitiveRedactionResult>()

    MultithreadingTester()
        .workers(8)
        .rounds(16)
        .add {
            outputs += pipeline.redact("Contact $email with $token")
        }
        .run()

    outputs shouldHaveSize 128
    outputs.forEach { result ->
        result shouldBeEqualTo baseline
        result.redactedText shouldNotContain email
        result.redactedText shouldNotContain token
    }
}
```

- [ ] **Step 7: Run the focused test and verify RED**

```bash
./gradlew :kotlin-text-processing:test --tests '*SensitiveTextRedactionPipelineTest' --console=plain
```

Expected: compilation fails because the redaction package does not exist yet. This is the TDD red proof.

## Task 2: Implement Redaction Value Types And Pipeline

**Complexity:** high
**Skills:** `bluetape4k-code-patterns`, `ecc-kotlin-patterns`

**Files:**

- Create: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipeline.kt`

- [ ] **Step 1: Define validated serializable value types**

Implement as focused regular classes with private constructors and companion factories unless a data class uses `@ConsistentCopyVisibility`.

Required types:

- `SensitiveTextRange`
- `SensitiveRedactionRule`
- `SensitiveRedactionPolicy`
- `SensitiveSpan`
- `SensitiveRedactionResult`
- `SensitiveTextRedactionPipeline`

Validation requirements:

- use bluetape4k validation helpers such as `requireNotBlank`, `requireNotEmpty`, and `requireInRange`;
- no `!!`;
- no raw sensitive value in exception messages;
- every public class has English KDoc with a realistic usage example when it introduces a contract;
- every value type implements `Serializable` and defines `serialVersionUID`.
- every public model that can carry caller text, keyword samples, regex sources, normalized text, redacted text, or matched values implements an explicit safe `toString()` that prints only type names, counts, lengths, rule ids/categories, and ranges.

Slug contract:

- rule ids and categories must match `^[a-z0-9._-]{1,64}$`;
- reject uppercase, whitespace, slash, email, phone, token-like values, customer ids, ticket ids, and any id/category equal to or containing configured keyword samples or regex fixture samples;
- exception messages must name the field and rule kind only, never the rejected raw value.

Regex contract:

- default regexes are private precompiled constants;
- `SensitiveRedactionRule.regex(...)` validates the regex source before compiling;
- reject backreferences, nested unbounded quantifiers, and unbounded `.*...*` forms;
- no caller-supplied raw regex is accepted without this validation;
- `redact(...)` never calls `Regex(...)`, `toRegex()`, `AhoCorasickAutomaton.builder()`, or `LanguageDetectionService()`.

Max input contract:

- define `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH`;
- reject over-limit input before regex scanning;
- test at-limit success and over-limit safe failure;
- document the limit in both README files.

- [ ] **Step 2: Compile policy state once**

`SensitiveRedactionPolicy.of(...)` must defensively copy and sort rules. `SensitiveTextRedactionPipeline.of(...)` must build:

- one configured-term Aho-Corasick automaton;
- one immutable regex-rule list;
- one reusable `LanguageDetectionService`;
- one private lock-protected detector helper.

All calls to the shared `LanguageDetectionService`, including detected language and best confidence,
must go through one private lock-protected helper. The helper acquires the lock once per `redact(...)`
call and returns both values from that critical section; it must not perform regex scanning, Aho-Corasick
matching, or rendering while holding the lock. No detector access occurs outside it. Record the contention
rationale in Step 6-R.
No regex, detector, or Aho-Corasick construction is allowed inside `redact(...)`.

- [ ] **Step 3: Implement redaction flow**

`redact(text: String): SensitiveRedactionResult` must:

1. validate non-blank text and max length;
2. normalize to NFC for detection;
3. call `TextNormalizer.normalize` for normalized-length metadata;
4. detect language and best confidence;
5. collect regex and keyword matches;
6. convert Aho-Corasick inclusive ends to `SensitiveTextRange` half-open ends;
7. sort and merge spans with the spec tie-breakers;
8. render mask output without changing unmasked text;
9. return raw-value-free spans and a safe result object.

- [ ] **Step 4: Run compile and partial focused tests**

```bash
./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --console=plain
```

Expected: compilation passes. Do not require the log-capture test to pass until Task 3 sanitizes existing collaborators.

## Task 3: Sanitize Existing Text-Processing Debug Logs And Finish Focused GREEN

**Complexity:** medium
**Skills:** `bluetape4k-code-patterns`

**Files:**

- Modify: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionService.kt`
- Modify: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/normalize/TextNormalizer.kt`
- Modify: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilter.kt`
- Modify: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/MultilingualSearchIndex.kt`
- Modify: `kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/CoroutineMultilingualSearchIndex.kt`

- [ ] **Step 1: Replace raw-text logs with length/count metadata**

Allowed examples:

```kotlin
log.debug { "detectLanguage length=${text.length} -> $detected" }
log.debug { "normalize length=${text.length} -> normalizedLength=${normalized.length}" }
log.debug { "filterText length=${text.length} -> filteredLength=${filtered.length}" }
log.debug { "search length=${query.length} terms=${queryTerms.size} -> hits=${hits.size}" }
```

Forbidden examples:

```kotlin
log.debug { "text='${text.take(40)}'" }
log.debug { "query='${query.take(40)}'" }
log.debug { "keywords=$keywords" }
```

- [ ] **Step 2: Run existing text-processing tests**

```bash
./gradlew :kotlin-text-processing:test --tests '*LanguageDetectionServiceTest' --tests '*TextNormalizerTest' --tests '*AbuseWordFilterTest' --tests '*MultilingualSearchIndexTest' --tests '*CoroutineMultilingualSearchIndexTest' --console=plain
```

Expected: existing behavior remains green.

- [ ] **Step 3: Run full redaction focused tests and fix GREEN**

```bash
./gradlew :kotlin-text-processing:test --tests '*SensitiveTextRedactionPipelineTest' --console=plain
```

Expected: all redaction tests pass after collaborator log sanitization.

## Task 4: Update README Pair And Diagrams

**Complexity:** high
**Skills:** `bluetape4k-blog`, `bluetape4k-diagram`

**Files:**

- Modify: `kotlin/text-processing/README.md`
- Modify: `kotlin/text-processing/README.ko.md`
- Modify: `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg`
- Modify: `docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png`
- Modify: `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg`
- Modify: `docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png`

- [ ] **Step 1: Update README components and usage**

Add `SensitiveTextRedactionPipeline` to the component table in both locale files. Add a usage section after text normalization or before multilingual search:

```kotlin
val pipeline = SensitiveTextRedactionPipeline.default()
val result = pipeline.redact(
    "Support note: user@example.test called 555-010-1234 with token=demo_token_value_123456"
)

result.redactedText   // masks the email, phone, and synthetic token
result.spans          // internal raw-value-free metadata
```

Both README files must state:

- this is a heuristic workshop policy, not DLP/legal compliance/ML NER;
- the default policy is an example-only fixture policy, not production PII coverage;
- `SensitiveSpan` metadata is internal and raw-value-free, not public/anonymous;
- offsets, lengths, categories, and rule ids can still reveal structure and must not be exposed to end users or broad operational logs without an explicit product decision;
- offsets are half-open ranges against original input;
- construct and reuse the pipeline because regexes/Aho-Corasick/detector state are built once;
- `SensitiveRedactionPolicy.DEFAULT_MAX_TEXT_LENGTH` is the workshop input bound used to limit regex work;
- stronger detectors are required for regulated data or exhaustive PII coverage.

Add a custom-policy snippet to both README files:

```kotlin
val policy = SensitiveRedactionPolicy.of(
    rules = listOf(
        SensitiveRedactionRule.keyword("keyword.account", "keyword", "account number")
    )
)
val customPipeline = SensitiveTextRedactionPipeline.of(policy)
```

The snippet must use safe rule ids/categories and non-sensitive fixture terms only.

- [ ] **Step 2: Update architecture diagram**

Use `$bluetape4k-diagram` current checklist before editing. The architecture diagram must show:

- text-processing utilities layer;
- `Redaction Pipeline` path;
- heuristic rules;
- redacted output separate from internal audit metadata;
- no HTTP, ML, DLP, or compliance implication.

- [ ] **Step 3: Update processing flow diagram**

Flow must show:

`Input -> NFC normalize -> language confidence -> regex + keyword spans -> merge overlaps -> redactedText + internal metadata`

The metadata node/callout must say `original half-open offsets + length + rule ids/category, no raw values`.
Use rounded orthogonal connectors, consistent card alignment, clear line style legend if styles differ, SVG/PNG marker parity, and full-size eye inspection.

- [ ] **Step 4: Render and validate diagrams**

```bash
xmllint --noout docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg
xmllint --noout docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg
~/.local/bin/cairosvg docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg -o docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png -s 2
~/.local/bin/cairosvg docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg -o docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png -s 2
./scripts/smoke-validate.sh diagram-qa
```

Expected: XML parse, render, diagram QA, and full-size PNG eye inspection pass.

## Task 5: Add CI And Smoke Coverage

**Complexity:** medium
**Skills:** `bluetape4k-code-patterns`

**Files:**

- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] **Step 1: Add Examples path filters**

Add this path under both `push.paths` and `pull_request.paths` near other Kotlin entries:

```yaml
      - 'kotlin/text-processing/**'
```

- [ ] **Step 2: Add Examples smoke task**

Add `:kotlin-text-processing:test` to `Run H2/default examples`.

- [ ] **Step 3: Add Examples artifact paths**

Add:

```yaml
            kotlin/text-processing/build/test-results/test/*.xml
            kotlin/text-processing/build/reports/tests/test/
```

- [ ] **Step 4: Add all-smoke task**

Add `:kotlin-text-processing:test` to `scripts/smoke-validate.sh` `all-smoke`.

- [ ] **Step 5: Validate workflow edits**

```bash
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
```

Expected: `actionlint` passes and escaped GitHub-expression quotes are absent.

## Task 6: Verification, Review Evidence, Lessons, And Commit

**Complexity:** high
**Skills:** `verification-before-completion`, `bluetape4k-code-patterns`, `bluetape4k-diagram`

**Files:**

- Create: `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md`
- Create: `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md`

- [ ] **Step 1: Run targeted compile and tests**

```bash
./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --warning-mode all --console=plain
./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain
```

Expected: compile/test pass and the final report records the test count.

- [ ] **Step 2: Run smoke, workflow, and diff checks**

```bash
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh all-smoke
actionlint .github/workflows/Examples.yml
rg -n "\\\\'" .github/workflows
git diff --check
```

Expected: all pass. If `all-smoke` is too slow, run it once before PR and record duration; do not replace it with targeted tests because the spec added all-smoke coverage.

- [ ] **Step 3: Run README/source consistency checks**

```bash
rg -n "SensitiveTextRedactionPipeline|SensitiveRedactionPolicy|SensitiveTextRange|SensitiveSpan" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/main/kotlin
rg -n "user@example\\.test|555-010|demo_token_value|account number" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction
rg --pcre2 -n "sk-|AKIA|AIza|xox[baprs]-|gh[pousr]_|eyJ[A-Za-z0-9_-]{10,}|[A-Za-z0-9._%+-]+@(?!example\\.test\\b)[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\b(?!555[-.\\s]?010[-.\\s]?)[0-9]{3}[-.\\s]?[0-9]{3}[-.\\s]?[0-9]{4}\\b" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction docs/images/readme-diagrams/kotlin-text-processing-*.svg
rg -n "Regex\\(|toRegex\\(|AhoCorasickAutomaton\\.builder|LanguageDetectionService\\(" kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction
```

Expected: API names exist in source and README; synthetic fixtures appear only in README/test input contexts, not metadata assertions or logs; provider-realistic token prefixes, real-looking JWT/API-key examples, non-reserved domains, and non-555 phone samples are absent from changed docs/tests/diagram sources; policy construction occurs outside `redact(...)`.

In Step 6-R, also inspect the `redact(...)` body specifically and fail the review if that function body contains
`Regex(`, `toRegex()`, `AhoCorasickAutomaton.builder`, or `LanguageDetectionService(`. The package-level `rg`
above is supporting evidence for where construction occurs, not sufficient proof by itself.

- [ ] **Step 4: Run Step 6-R 7-Tier review**

Review the implemented diff against `origin/develop` with six perspectives:

- performance: precompiled rules, hot-path allocations, thread-safety test;
- stability: detector guard, immutable policy snapshots, span boundaries;
- security: raw-value leaks, safe regex, metadata slug validation, logs;
- operator: Examples/all-smoke coverage, logging guidance, rollback;
- developer/API: KDoc, validation helpers, serializable values, assertions;
- user/caller: README clarity, Korean parity, diagrams.

Save the integrated artifact at `docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md` with `P0=0`, `P1=0` before proceeding.
The review must record detector-lock contention rationale, regex-source/static construction evidence, and rollback scope:
normal revert removes the redaction package/tests, README sections, diagram updates, workflow/smoke additions, and the Logback test dependency; no DB, external service, container, or runtime cleanup is required.

- [ ] **Step 5: Write lessons**

Create `docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md` with:

- why existing raw debug logs mattered for a redaction example;
- why audit-safe means raw-value-free internal metadata only;
- exact verification commands and diagram QA evidence;
- future guard for text examples with PII/logging risk.

- [ ] **Step 6: Commit implementation, docs, diagrams, review, and lessons**

Use Lore commit protocol:

```bash
git add kotlin/text-processing \
  docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.svg \
  docs/images/readme-diagrams/kotlin-text-processing-readme-architecture-01.png \
  docs/images/readme-diagrams/kotlin-text-processing-scenario-01.svg \
  docs/images/readme-diagrams/kotlin-text-processing-scenario-01.png \
  .github/workflows/Examples.yml \
  scripts/smoke-validate.sh \
  docs/review/2026-07-03-issue-333-text-redaction-pipeline-code-review.md \
  docs/lessons/2026-07-03-issue-333-text-redaction-pipeline.md
git commit -m "feat: add audit-safe text redaction pipeline"
```

The Step 3 spec/plan commit must already exist before Task 1 starts. Do not include
`docs/superpowers/specs/2026-07-03-issue-333-text-redaction-pipeline-design.md`
or `docs/superpowers/plans/2026-07-03-issue-333-text-redaction-pipeline-plan.md`
in this implementation commit unless Step 3-R forced a later plan repair.

Commit body must include:

```text
Constraint: Issue #333 requires deterministic redaction, raw-value-free metadata, README limitation guidance, and root BOM-only dependencies.
Rejected: new kotlin/text-redaction-pipeline module | existing kotlin/text-processing already owns normalization, language detection, and text-search examples.
Confidence: high
Scope-risk: moderate
Directive: Keep future text examples from logging raw caller text when PII or support-ticket workflows are in scope.
Tested: <commands that passed>
Not-tested: <explicit gaps, or none>
```

## Task 7: PR, Post-PR Review, CI, And Final DoD

**Complexity:** high
**Skills:** `verification-before-completion`, `bluetape4k-workflow`

**Files:**

- PR body temporary file: `/tmp/issue-333-redaction-pr.md`

- [ ] **Step 1: Push and create PR**

Read the issue metadata live before creating the PR:

```bash
gh issue view 333 --json assignees,labels,milestone,state,url
```

PR metadata:

- title: `feat: add audit-safe text redaction pipeline`
- base: `develop`
- assignee: `debop`
- milestone: `1.3.1`
- labels copied from issue #333 where GitHub supports them
- body final section: `## DoD Status`

Verify body after creation:

```bash
gh pr view <number> --json body,milestone,assignees,labels,state,url
```

Expected: final `##` heading is `## DoD Status`.

- [ ] **Step 2: Run Step 7-R PR review**

Run post-PR review against the actual PR diff. Record P0/P1=0 and update the PR body DoD if review/CI statuses changed.

- [ ] **Step 3: Wait for CI gate**

```bash
gh pr view <number> --json statusCheckRollup
```

Expected: required checks are `SUCCESS` or `SKIPPED`. Failure returns to Task 6/implementation.

- [ ] **Step 4: Verify CI artifact contents**

After the smoke/example CI run succeeds, inspect or download the `smoke-example-test-results` artifact and verify it contains:

```text
kotlin/text-processing/build/test-results/test/*.xml
kotlin/text-processing/build/reports/tests/test/
```

Record the artifact command/output in the final DoD. If artifact download is unavailable, record the GitHub run URL and the exact unavailable-artifact reason.

- [ ] **Step 5: Final Step 9 DoD report**

Report with the `Step | Status | Evidence` table, including:

- issue #333 metadata;
- spec and plan paths;
- local compile/test/smoke/actionlint/diff evidence;
- diagram checklist and eye-inspection evidence;
- CI artifact content evidence for `kotlin/text-processing` test XML/report paths;
- Step 6-R and Step 7-R P0/P1=0;
- PR number/body metadata/CI;
- final status `DONE - PR #<number> pending merge`.
