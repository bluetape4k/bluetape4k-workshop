# spring-boot-text-moderation-api

[한국어](README.ko.md) | English

## Overview

**spring-boot-text-moderation-api** is a Spring Boot 4 MVC example for building
a deterministic web-safety text moderation boundary with `bluetape4k-text`.
It accepts JSON text, detects the likely language, matches configured blockwords
with an Aho-Corasick automaton, masks the unsafe terms, and returns a normalized
response.

The module stays local and deterministic. It does not call an external
moderation service, LLM, or remote classifier, so the tests can run as a focused
workshop smoke check.

## Architecture

![spring-boot-text-moderation-api architecture](../../docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-architecture-01.png)

The request moves top-to-bottom through a small MVC boundary: controller input,
request validation, a reusable moderation service, and two long-lived text
components built once by Spring configuration.

## Request Flow

![spring-boot-text-moderation-api sequence](../../docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-sequence-01.png)

The success path reuses the singleton `LanguageDetector` and
`AhoCorasickAutomaton`. Invalid requests short-circuit to `400 Bad Request`;
oversized requests return `413 Content Too Large`.

## Endpoint

```text
POST /api/moderation/analyze
Content-Type: application/json

{
  "text": "Please block spam from this English request."
}
```

```bash
curl -s -X POST http://localhost:8080/api/moderation/analyze \
  -H 'Content-Type: application/json' \
  -d '{"text":"Please block spam from this English request."}'
```

Example response:

```json
{
  "detectedLanguage": "ENGLISH",
  "confidence": 0.98,
  "matchedTerms": ["spam"],
  "maskedText": "Please block **** from this English request.",
  "warnings": ["ABUSE_WORD_MATCHED"]
}
```

## Error Responses

Blank or missing text:

```bash
curl -s -X POST http://localhost:8080/api/moderation/analyze \
  -H 'Content-Type: application/json' \
  -d '{"text":"   "}'
```

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "text must not be blank"
}
```

Oversized text:

```bash
python3 - <<'PY' | curl -s -X POST http://localhost:8080/api/moderation/analyze \
  -H 'Content-Type: application/json' \
  --data-binary @-
import json
print(json.dumps({"text": "x" * 2001}))
PY
```

```json
{
  "status": 413,
  "error": "Content Too Large",
  "message": "text exceeds 2000 characters"
}
```

## Configuration

| Property | Default | Purpose |
|---|---:|---|
| `workshop.text-moderation.max-text-characters` | `2000` | Maximum accepted request text length |
| `workshop.text-moderation.blockwords` | `spam,badword,abuse,hate` | Terms registered in the moderation automaton |

The default blockwords are intentionally small so learners can follow the full
flow from configuration to response.

## Used Bluetape4k features

| Feature | Where | Why it matters |
|---|---|---|
| `bluetape4k-text-search` | `ahoCorasick { ... }` | Builds one reusable multi-keyword matcher instead of scanning each word manually |
| `bluetape4k-text-lingua` | `allLanguageDetector { ... }` | Provides deterministic language detection without a remote API |
| `bluetape4k-logging` | `KLogging` and lazy `debug` logging | Records operational metadata without logging raw moderation text |
| `bluetape4k-junit5` / `bluetape4k-assertions` | Unit and MVC tests | Keeps the example deterministic and easy to validate locally |

## Run

```bash
./gradlew :spring-boot-text-moderation-api:bootRun
```

Then send the curl requests above to `http://localhost:8080`.

## Tests

```bash
./gradlew :spring-boot-text-moderation-api:test
```

The focused test suite verifies:

- `200 OK` masking and language detection
- Korean text detection without blockword matches
- `400 Bad Request` for blank or missing text
- `413 Content Too Large` for oversized text
- singleton reuse for the language detector and automaton beans

## Dependency Note

The module uses the root `bluetape4k-dependencies` BOM and the existing
`bluetape4k-text` aliases from the repository catalog. Do not add a module-local
version pin or a separate BOM for this example.
