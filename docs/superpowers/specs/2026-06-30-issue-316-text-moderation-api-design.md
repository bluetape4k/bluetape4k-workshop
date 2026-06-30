# Issue 316 Text Moderation API Design

## Context

Issue #316 asks for a web-facing text moderation workshop example for the
`bluetape4k-dependencies 1.3.1` train. The existing `kotlin/text-processing`
module already teaches in-process Lingua detection and Aho-Corasick filtering.
This example therefore adds the missing HTTP trust boundary: request validation,
payload-size rejection, deterministic response mapping, and singleton reuse of
expensive text components.

## Decision

Create `spring-boot/text-moderation-api` as a Spring MVC module.

Spring MVC is enough for this example because the core lesson is not streaming
or back-pressure; it is safe API exposure of synchronous text analysis. The
module remains local and deterministic, with no external service and no
Testcontainers dependency.

## API Contract

Endpoint:

- `POST /api/moderation/analyze`
- Request body: JSON object with `text: string`
- Success: `200 OK`
- Invalid input: `400 Bad Request`
- Oversized text: `413 Payload Too Large`

Request validation:

- Missing or blank `text` returns `400`.
- `text.length > 2_000` returns `413`.
- The service trims control flow only through validation; it does not mutate
  clean user text before matching.

Response:

```json
{
  "detectedLanguage": "ENGLISH",
  "confidence": 0.94,
  "matchedTerms": ["spam"],
  "maskedText": "No **** please",
  "warnings": ["ABUSE_WORD_MATCHED"]
}
```

## Components

| Component | Responsibility |
|---|---|
| `TextModerationApplication` | Spring Boot entrypoint |
| `TextModerationProperties` | request-size and blockword defaults |
| `TextModerationConfig` | singleton detector and matcher bean lifecycle |
| `TextModerationService` | validates input, detects language, matches terms, masks text |
| `TextModerationController` | HTTP endpoint and status mapping |
| `TextModerationModels` | serializable request/response/error value types |

## Bluetape4k Usage

| Feature | Example use |
|---|---|
| `bluetape4k-text-lingua` | build one reusable Lingua detector bean |
| `bluetape4k-text-search` | build one Aho-Corasick automaton for blockwords |
| `bluetape4k-logging` | structured workshop logging |
| `bluetape4k-assertions` | focused HTTP/service assertions |

The module uses only the root `bluetape4k-dependencies` BOM and existing
`gradle/libs.versions.toml` aliases. It must not import an individual text BOM
or pin bluetape4k text versions locally.

## Tests

Required tests:

- Valid English/Korean/Japanese requests return `200`, detected language, matched
  terms, masked text, and warnings.
- Blank or missing text returns `400`.
- Text longer than `maxTextCharacters` returns `413`.
- Singleton detector and matcher beans are reused by the service.
- Tests run with a random local port or bound MockMvc/WebTestClient and require
  no external service.

## Documentation And Diagrams

Create source-equivalent `README.md` and `README.ko.md`.

Both READMEs must include:

- language switch
- purpose and architecture
- `Used Bluetape4k features` table
- request/response examples
- error mapping table
- focused validation command:
  `./gradlew :spring-boot-text-moderation-api:test`
- architecture and sequence PNG assets with SVG sources

Diagram assets:

- `docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-architecture-01.{svg,png}`
- `docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-sequence-01.{svg,png}`

## Registration

Because `settings.gradle.kts` auto-registers `spring-boot/*`, the module is
included by directory creation. The PR must update:

- root `README.md`
- root `README.ko.md`
- `.github/workflows/Examples.yml` path filters, smoke command, and artifact paths
- `scripts/smoke-validate.sh` `all-smoke`, `spring-boot`, and stale expected count

## Risks

| Risk | Mitigation |
|---|---|
| Duplicates `kotlin/text-processing` | Keep this module focused on HTTP trust-boundary behavior |
| Oversized requests get mapped as `400` | Use a dedicated `PayloadTooLargeException` and controller advice mapping |
| Detector construction per request | Expose detector/matcher as singleton beans and assert reuse in tests |
| New module misses CI | Update Examples workflow and smoke script in the same branch |
