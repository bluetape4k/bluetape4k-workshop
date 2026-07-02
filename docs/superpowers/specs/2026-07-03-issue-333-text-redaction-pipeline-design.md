# Issue #333 Text Redaction Pipeline Design

## Context

Issue #333 asks for a workshop example that goes beyond abuse-word filtering:
normalize incoming text, detect language, find sensitive spans, redact output,
and return audit-safe metadata. The example must stay small, deterministic, and
learner-friendly.

Current source evidence:

- `kotlin/text-processing` already owns `AbuseWordFilter`,
  `LanguageDetectionService`, `CoroutineLanguageDetectionService`,
  `TextNormalizer`, `MultilingualSearchIndex`, and
  `CoroutineMultilingualSearchIndex`.
- `kotlin/text-processing/build.gradle.kts` already depends on
  `bluetape4k.text.search`, `bluetape4k.text.lingua`,
  `bluetape4k.text.korean`, `bluetape4k.text.japanese`,
  `kotlinx.coroutines.core.lib`, `bluetape4k.logging`, and the standard
  bluetape4k test dependencies. No new dependency is needed.
- Issue #316 `spring-boot/text-moderation-api` shows the web/API moderation
  slice. This issue should be an in-process pipeline example, not another HTTP
  controller.
- Issue #332 extended `kotlin/text-processing` with sync/coroutine
  multilingual search. The same module is the closest learner context for
  normalized text and source-span handling.
- The bluetape4k-text security audit warned that exception messages can leak
  user input and PII. This example must avoid raw sensitive values in logs,
  exceptions, metadata, and `toString()`.

## Goal

Extend `kotlin/text-processing` with a deterministic PII/sensitive-text
redaction pipeline that teaches:

1. Unicode/whitespace normalization before classification.
2. Language detection for routing/confidence metadata.
3. Keyword and pattern based span detection for email, phone, token-like
   values, and small configured sensitive terms.
4. Deterministic overlap resolution and mask generation.
5. Audit-safe metadata that exposes rule ids, span offsets, length, and
   redaction category without raw sensitive values.

## Scope

In scope:

- New package `io.bluetape4k.workshop.text.redaction` inside
  `kotlin/text-processing`.
- Reuse `TextNormalizer`, `LanguageDetectionService`, and
  `AhoCorasickAutomaton`, but first sanitize raw-text debug logging in reused
  text-processing collaborators touched by this pipeline.
- Small fixture-backed default policies for configured sensitive terms and
  regex-backed email/phone/token-like values.
- Serializable public result/value types with English KDoc.
- Tests for deterministic redaction, multilingual text, overlap handling,
  invalid input, audit-safe output, and `toString()` redaction.
- `README.md` and `README.ko.md` parity, including policy limitations and when
  a stronger detector is required.
- README architecture/flow diagrams updated as SVG+PNG and validated through
  the full diagram checklist plus rendered PNG eye inspection.

Out of scope:

- New Gradle module `kotlin/text-redaction-pipeline`.
- External PII detection services, ML/NLP entity recognition, DLP products, or
  country-specific legal compliance.
- Persistence, HTTP API, Spring Boot wiring, or Testcontainers.
- Perfect phone/email validation. Regex rules are intentionally heuristic,
  ReDoS-safe workshop defaults and documented as such.
- Logging raw source text or raw matched sensitive values.

## Design Options

### Option A: Add a new `kotlin/text-redaction-pipeline` module

This would make the example boundary visually separate, but it duplicates the
text-processing dependency set, requires settings/CI/smoke/README registration,
and makes learners jump between two modules for normalization, language
detection, and span matching.

Decision: rejected. The issue allows "or similar module", and current evidence
shows `kotlin/text-processing` is already the canonical text utility workshop.

### Option B: Extend `kotlin/text-processing` with a `redaction` package

This keeps the example next to the existing normalizer, detector, abuse filter,
and multilingual search index. It reuses current bluetape4k-text dependencies
and avoids new module workflow risk. README can show the pipeline as a fifth
component without changing repository registration or CI topology.

Decision: selected. It is the smallest coherent design and best matches the
learner path from simple filtering to audit-safe redaction.

### Option C: Extend `spring-boot/text-moderation-api`

This would demonstrate a deployable HTTP boundary, but #316 already covers the
web moderation slice. PII redaction needs reusable domain behavior first; web
transport would add validation/error-mapping noise around the core pipeline.

Decision: rejected for this issue. README can reference #316 as the web API
counterpart.

## Selected Architecture

`SensitiveTextRedactionPipeline` coordinates five stages:

1. Validate caller text with bluetape4k validation helpers. Blank text fails
   fast without including the raw input in the exception message. A small
   maximum text length protects the regex rules and keeps workshop examples
   bounded.
2. Normalize Unicode to NFC for the detection path, then apply
   `TextNormalizer.normalize` for whitespace/case metadata and language
   confidence context. Source offsets still refer to the original input.
3. Detect language through a reusable `LanguageDetectionService`, exposing the
   detected language plus best confidence in the result metadata.
4. Detect sensitive spans through:
   - precompiled ReDoS-safe regex constants for email, phone, and token-like
     values;
   - one Aho-Corasick configured-term automaton built at pipeline construction
     with NFC normalization and case-insensitive matching.
5. Merge overlapping spans deterministically and render redacted output by
   replacing each merged source range with a mask of the same source length.

Public model shape:

- `SensitiveTextRange`: named half-open source range value object with
  `startInclusive`, `endExclusive`, and validated length.
- `SensitiveRedactionRule`: validated keyword or regex rule metadata.
- `SensitiveRedactionPolicy`: mask character and rule collection.
- `SensitiveSpan`: raw-value-free internal audit range, category, rule ids, and
  length.
- `SensitiveRedactionResult`: redacted text, detected language, normalized text
  length, match count, best confidence, and spans.
- `SensitiveTextRedactionPipeline`: reusable, stateless facade over immutable
  policy and reusable detector.

All public value types implement `Serializable` and define `serialVersionUID`.
Any type whose constructor needs validation uses a private constructor plus
factory methods so callers cannot bypass validation. Validated data classes must
use `@ConsistentCopyVisibility`, or be regular classes like `SearchDocument`,
so generated `copy()` cannot bypass validation. `toString()` never prints raw
source text, raw redacted text, raw normalized text, or raw matched values.

Policy construction compiles immutable state once:

- Regex rules are precompiled constants or validated compiled patterns built at
  policy construction. Defaults must avoid nested unbounded quantifiers,
  catastrophic alternation/backtracking, and backreferences.
- Configured keyword rules are copied into deterministic immutable snapshots
  and compiled into one Aho-Corasick automaton at pipeline construction.
- Public accessors expose immutable collections only.

Default rule fixtures:

| rule id | category | priority | detector | fixture boundary |
|---|---|---:|---|---|
| `email` | `contact` | 10 | regex | reserved example domains only |
| `phone` | `contact` | 20 | regex | reserved 555-style examples only |
| `token` | `secret` | 1 | regex | synthetic bearer/JWT/API-key-like strings only |
| `support-keyword` | `keyword` | 30 | Aho-Corasick | small configured non-secret terms such as `account number` |

Default token-like rule contract:

- match synthetic bearer/JWT/API-key-like strings in examples, not real
  provider secrets;
- require a clear prefix or boundary such as `Bearer `, `token=`, `api_key=`,
  or a JWT-like three-segment shape;
- require minimum length so ordinary order ids or short hashes are not masked;
- avoid provider-realistic production samples in tests, README, and diagrams.

Rule ids and categories are metadata, so they must also be safe:

- stable non-sensitive slugs;
- constrained to lowercase letters, digits, dots, underscores, and hyphens;
- bounded length;
- must not equal or contain configured keyword samples, regex samples, source
  text, email addresses, phone numbers, token-like values, or customer/ticket
  identifiers.

Caller-facing API contract:

```kotlin
val pipeline = SensitiveTextRedactionPipeline.default()
val result = pipeline.redact("Support note: user at user@example.test sent token=demo_token_value_123456")

result.redactedText   // contains masks and no raw email or token value
result.spans          // raw-value-free internal metadata
```

The README snippets must compile against the final API names or be updated
before PR creation. Examples use reserved domains, 555-style numbers, and
synthetic tokens only.

## Span And Overlap Contract

Raw rule matches are gathered first, then sorted by:

1. `start` ascending,
2. `endExclusive` descending,
3. rule priority ascending,
4. rule id ascending.

Overlapping matches are merged into one emitted `SensitiveSpan` when
`next.range.startInclusive < current.range.endExclusive`. Adjacent ranges do
not merge. The emitted span uses the earliest start, latest end, combined sorted
rule ids, and the category from the highest-priority match. Equal-priority
category ties are broken by rule id ascending, then category ascending.
Redaction renders one mask range for that merged span. This prevents partial
leaks and avoids nested/double masking.

Offsets use `startInclusive` and `endExclusive` against the original input.
This differs from existing Aho-Corasick `AhoCorasickMatch.end`, which is
inclusive; the pipeline converts it at the boundary.

## Security And Failure Modes

| Risk | Mitigation |
|---|---|
| Raw PII appears in result metadata | `SensitiveSpan` stores offsets, length, category, and rule ids only. It never stores matched text. |
| Raw PII appears in logs or `toString()` | Pipeline logs only lengths/counts/language and redacted `toString()` implementations omit source/redacted text. |
| Rule metadata leaks sensitive values | Rule ids/categories use constrained non-sensitive slugs and tests reject sensitive-looking ids/categories. |
| Regex misses real-world PII | README states the rules are heuristic and recommends dedicated DLP/PII detectors for regulated data. |
| Regex backtracking hurts untrusted input handling | Default regex rules are bounded/precompiled and avoid backreferences or nested unbounded constructs; input length is capped. |
| Overlapping matches leak partial values | Merge overlapping source ranges before rendering output. |
| Exception message echoes user input | Validation errors name the field and policy only; no raw text is included. |
| Unicode normalization changes offsets | Detection and redaction offsets are based on original input; normalized text is metadata only. |
| Reused collaborators log raw text | Sanitize touched collaborator debug logs or use safe wrappers before wiring the pipeline. |

`audit-safe` in this design means raw-value-free internal audit metadata, not
anonymous data and not automatically safe for public clients. README guidance
must say that offsets, lengths, categories, and rule ids can still reveal
structure and should not be exposed to end users or broad operational logs
without an explicit product decision.

## Observability, CI, And Rollback

Logging contract:

- no raw source text;
- no normalized text;
- no redacted text;
- no matched values;
- no query strings or extracted keyword lists;
- length, language, count, rule id/category slugs, and elapsed stage names are
  allowed.

The implementation must sanitize raw debug logging in
`LanguageDetectionService`, `TextNormalizer`, and touched redaction paths. Tests
capture DEBUG logs and prove synthetic email, phone, token, and configured terms
do not appear.

Module registration impact:

- no new Gradle module;
- no `settings.gradle.kts` project-count change;
- no new root README module row;
- add `kotlin/text-processing/**` to Examples workflow path filters;
- add `:kotlin-text-processing:test` to Examples/all-smoke coverage and test
  artifact collection because the new slice is security-sensitive.

Rollback is a normal code revert: remove the redaction package, tests, README
sections, diagram updates, and CI/smoke additions. No database migration,
external service, container, or runtime configuration cleanup is required.

## Testing Strategy

Tests are written first with `bluetape4k-assertions` only:

- deterministic email/phone/token/keyword redaction;
- multilingual Korean/English input with language metadata;
- overlapping keyword and email/token spans collapse into one emitted span;
- adjacent spans do not merge, equal-start/nested spans merge deterministically,
  equal-priority category ties are deterministic, and Aho-Corasick inclusive end
  offsets convert to half-open ranges correctly;
- NFC/decomposed Unicode source-offset preservation;
- long non-matching inputs for email/phone/token-like rules finish without
  catastrophic regex behavior;
- one pipeline instance can run repeated redactions with equivalent output and
  spans, proving compiled policy state is reused;
- `MultithreadingTester` verifies shared pipeline calls are thread-safe. The
  implementation must guard detector access if the detector itself is not safe
  for concurrent calls;
- blank input throws `IllegalArgumentException` through bluetape4k validation;
- redacted output and result `toString()` do not contain raw sensitive values;
- metadata, exceptions, README examples, test names, and logs do not contain
  raw email, phone, token, or configured sensitive values; fixtures may contain
  reserved/synthetic raw inputs and must prove they do not escape;
- policy validation rejects blank rule ids, sensitive-looking ids/categories,
  blank categories, invalid mask characters, invalid ranges, unsafe regex
  defaults, and empty rule collections.

The selected pipeline is thread-safe for shared application use. It has no
coroutine API, so `SuspendedJobTester` is not used. Thread-safety verification
uses `io.bluetape4k.junit5.concurrency.MultithreadingTester`; no ad hoc
thread/coroutine stress loop is allowed.

## Documentation And Diagrams

`kotlin/text-processing/README.md` and `README.ko.md` will be updated with:

- redaction pipeline component table entry;
- usage snippet for a support-ticket style input;
- policy limitations and stronger detector guidance;
- audit-safe metadata explanation;
- offset explanation using `SensitiveTextRange` half-open ranges against
  original input, including one overlap/merge example;
- safe structured-log guidance: log metadata only by default, and store/display
  `redactedText` in user-facing/support-ticket fields only when product policy
  allows it;
- construct-once/reuse guidance for compiled regexes, Aho-Corasick automaton,
  and detector reuse;
- dependency statement showing no new dependency.

README parity requirements:

- retain the `English | 한국어` language switch;
- update equivalent sections in both locales;
- embed the same SVG/PNG diagram assets;
- include equivalent limitation, audit-metadata, offset, and dependency
  statements;
- use locale-appropriate Korean prose rather than literal translation.

The existing two README diagrams will be updated:

- Architecture: add a `Redaction Pipeline` path beside normalization, language
  detection, filter, and search. Keep layered layout and consistent card
  alignment. Label the rules as heuristic and show internal audit metadata
  separately from redacted output.
- Flow: show input -> normalize -> detect language -> detect spans -> merge
  overlaps -> redact -> audit-safe result. Use rounded orthogonal connectors,
  clear labels, and no ambiguous connector styles without legend. Do not imply
  HTTP transport, ML, external DLP, or compliance coverage.

Diagram output must pass `bluetape4k-diagram` checklist, repository validators,
SVG parse/render checks, and full-size PNG eye inspection.

## Acceptance Criteria Mapping

| Issue criterion | Design response |
|---|---|
| Uses root `bluetape4k-dependencies` BOM only | Extend existing `kotlin/text-processing`; no new dependency or module catalog entry. |
| Tests verify deterministic redaction | Exact output and span metadata tests cover email, phone, token, keyword, and overlap order. |
| Sensitive raw values are not emitted in redacted output | Tests assert raw email/phone/token/keyword values are absent from output and `toString()`. |
| README.md and README.ko.md describe limitations | Both README files include heuristic-policy limitations, internal-audit metadata limits, and stronger detector guidance. |
| Keeps policy/rule data small and fixture-based | Defaults use a small in-code fixture list and tests use local fixture strings. |

## DoD

- `./gradlew :kotlin-text-processing:compileKotlin :kotlin-text-processing:compileTestKotlin --warning-mode all --console=plain` passes.
- `./gradlew :kotlin-text-processing:cleanTest :kotlin-text-processing:test --no-build-cache --warning-mode all --console=plain` passes.
- New tests use `bluetape4k-assertions`; no AssertJ, Kluent, JUnit assertion
  APIs, or `kotlin.test` assertions are introduced.
- Public API KDoc is English and public value types are `Serializable`.
- `MultithreadingTester` verifies shared pipeline thread-safety.
- DEBUG log capture verifies raw sensitive values do not appear in logs from
  touched text-processing collaborators.
- README English/Korean parity is updated and source names grep-match code.
- Updated diagrams pass repo validators, SVG render, PNG generation, and
  full-size visual inspection.
- `actionlint .github/workflows/Examples.yml` passes if Examples workflow path
  filters or smoke jobs change.
- `./scripts/smoke-validate.sh stale-check` passes.
- `./scripts/smoke-validate.sh all-smoke` includes and passes
  `:kotlin-text-processing:test`.
- `git diff --check` passes.
- Step 6-R review records `P0=0`, `P1=0`.
