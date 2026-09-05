# spring-boot-text-moderation-api

[English](README.md) | 한국어

## 개요

**spring-boot-text-moderation-api**는 `bluetape4k-text`로 결정적인 web-safety
text moderation 경계를 만드는 Spring Boot 4 MVC 예제입니다. JSON text를 받아
가능성이 높은 language를 감지하고, Aho-Corasick automaton으로 설정된 blockword를
찾은 뒤, 위험 단어를 masking한 normalized response를 반환합니다.

이 모듈은 로컬에서 결정적으로 동작합니다. 외부 moderation service, LLM, remote
classifier를 호출하지 않으므로 테스트가 워크숍용 smoke check로 안정적으로 실행됩니다.

## 아키텍처

![spring-boot-text-moderation-api architecture](../../docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-architecture-01.png)

요청은 위에서 아래로 작은 MVC boundary를 통과합니다. Controller 입력, request
validation, 재사용 가능한 moderation service, 그리고 Spring configuration에서 한 번만
생성되는 두 개의 text component로 이어집니다.

## 요청 흐름

![spring-boot-text-moderation-api sequence](../../docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-sequence-01.png)

성공 경로는 singleton `LanguageDetector`를 재사용하고 matching과 masking에 사용할 immutable
`VersionedModerationDictionary` snapshot을 한 번 캡처합니다. 잘못된 요청은
`400 Bad Request`로, 너무 큰 요청은 `413 Content Too Large`로 짧게 종료됩니다.

## 엔드포인트

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

응답 예:

```json
{
  "detectedLanguage": "ENGLISH",
  "confidence": 0.98,
  "matchedTerms": ["spam"],
  "maskedText": "Please block **** from this English request.",
  "warnings": ["ABUSE_WORD_MATCHED"]
}
```

## 오류 응답

빈 text 또는 누락된 text:

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

너무 큰 text:

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

## 설정

| Property | 기본값 | 용도 |
|---|---:|---|
| `workshop.text-moderation.max-text-characters` | `2000` | 허용하는 request text 최대 길이 |
| `workshop.text-moderation.blockwords` | `spam,badword,abuse,hate` | moderation automaton에 등록되는 단어 |

기본 blockword 목록은 의도적으로 작게 유지했습니다. 학습자가 configuration에서 response까지
전체 흐름을 쉽게 따라갈 수 있게 하기 위한 선택입니다.

## 런타임 dictionary 교체

관리 경계는 service layer에 남겨 둡니다. 이 workshop은 공개 reload endpoint를 제공하지
않습니다. 인증된 application-owned control plane은 기존 HTTP response를 바꾸지 않으면서 다음
API로 완성된 candidate를 검증하고 공개할 수 있습니다.

```kotlin
val v1 = service.analyzeWithVersion("spam")
service.reloadDictionary(DictionaryVersion("moderation-blockwords", 2)) {
    listOf("phishing", "malware")
}
val v2 = service.analyzeWithVersion("phishing")
service.rollbackDictionary()
```

Loader 실행, bounded input 검증, Aho-Corasick 생성이 모두 끝난 뒤 새
`DictionarySnapshot`을 공개합니다. 각 요청은 snapshot을 한 번 캡처하므로 parsing과 masking이
같은 revision을 사용합니다. 실패하거나 stale인 candidate는 현재 dictionary와 rollback
history를 모두 보존합니다. Log에는 dictionary name, revision, word count, total character
count만 남기며 raw blockword와 moderation text는 기록하지 않습니다. Loader collection은
순회하면서 bounded snapshot으로 복사하고, 동시 singleton 호출의 shared Lingua detector 접근은
직렬화합니다.

## Used Bluetape4k features

| Feature | 위치 | 왜 중요한가 |
|---|---|---|
| `bluetape4k-tokenizer-core` | `VersionedDictionary`와 `DictionarySnapshot` | 완성된 blockword generation을 bounded rollback history와 함께 원자적으로 공개합니다 |
| `bluetape4k-text-search` | `ahoCorasick { ... }` | 단어마다 직접 scan하지 않고 하나의 재사용 가능한 multi-keyword matcher를 만듭니다 |
| `bluetape4k-text-lingua` | `allLanguageDetector { ... }` | remote API 없이 결정적인 language detection을 제공합니다 |
| `bluetape4k-logging` | `KLogging`과 lazy `debug` logging | 원문 moderation text를 로그에 남기지 않고 운영 metadata만 기록합니다 |
| `bluetape4k-junit5` / `bluetape4k-assertions` | Unit 및 MVC tests | 예제를 로컬에서 결정적으로 검증하기 쉽게 만듭니다 |

## 실행

```bash
./gradlew :spring-boot-text-moderation-api:bootRun
```

그다음 위의 curl 요청을 `http://localhost:8080`으로 보내면 됩니다.

## 테스트

```bash
./gradlew :spring-boot-text-moderation-api:test
```

집중 테스트 모음은 다음을 검증합니다.

- `200 OK` masking과 language detection
- blockword match가 없는 한국어 text detection
- 빈 text 또는 누락된 text에 대한 `400 Bad Request`
- 너무 큰 text에 대한 `413 Content Too Large`
- language detector와 automaton bean의 singleton 재사용
- reload, rollback, bounded history, stale/실패 candidate 보존
- 동시 요청이 완성된 이전 또는 새 revision 하나만 관찰하는지 검증

## 의존성 메모

이 모듈은 root `bluetape4k-dependencies` 2.0.0 BOM과 repository catalog alias를 사용합니다.
`VersionedDictionary`를 위해 versionless `bluetape4k-text-core`도 직접 의존합니다. 이 예제를
위해 module-local version pin이나 별도 BOM을 추가하지 않습니다.
