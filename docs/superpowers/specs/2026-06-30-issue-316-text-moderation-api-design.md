# 이슈 316 텍스트 조정 API 디자인

## 문맥

Issue #316에서는 웹 기반 텍스트 조정 워크숍 예시를 요청합니다.
`bluetape4k-dependencies 1.3.1` 열차. 기존 `kotlin/text-processing`
모듈에서는 이미 진행 중인 Lingua 감지 및 Aho-Corasick 필터링을 가르치고 있습니다.
따라서 이 예에서는 누락된 HTTP 신뢰 경계를 추가합니다: 요청 유효성 검사,
페이로드 크기 거부, 결정적 응답 매핑, 싱글톤 재사용
값비싼 텍스트 구성 요소.

## 결정

`spring-boot/text-moderation-api`을 Spring MVC 모듈로 생성합니다.

핵심 강의가 스트리밍되지 않기 때문에 이 예제에서는 Spring MVC이면 충분합니다.
또는 배압; 동기식 텍스트 분석의 API 노출은 안전합니다. 그만큼
모듈은 외부 서비스 없이 로컬 및 결정론적으로 유지됩니다.
Testcontainers 의존성.

## API 계약

엔드포인트:

- `POST /api/moderation/analyze`
- 요청 본문: `text: string`이 포함된 JSON 객체
- 성공: `200 OK`
- 잘못된 입력: `400 Bad Request`
- 너무 큰 텍스트: `413 Payload Too Large`

유효성 검사 요청:

- 누락되었거나 공백인 `text`은 `400`를 반환합니다.
- `text.length > 2_000`은 `413`을 반환합니다.
- 서비스는 유효성 검사를 통해서만 제어 흐름을 다듬습니다. 그것은 돌연변이를 일으키지 않는다
  일치하기 전에 사용자 텍스트를 정리합니다.

응답:

```json
{
  "detectedLanguage": "ENGLISH",
  "confidence": 0.94,
  "matchedTerms": ["spam"],
  "maskedText": "No **** please",
  "warnings": ["ABUSE_WORD_MATCHED"]
}
```

## 구성요소

| 구성요소 | 책임 |
|---|---|
| `TextModerationApplication` | Spring Boot 진입점 |
| `TextModerationProperties` | 요청 크기 및 블록워드 기본값 |
| `TextModerationConfig` | 싱글톤 감지기 및 Matcher Bean 수명 주기 |
| `TextModerationService` | 입력 유효성 검사, 언어 감지, 용어 일치, 텍스트 마스크 |
| `TextModerationController` | HTTP 엔드포인트 및 상태 매핑 |
| `TextModerationModels` | 직렬화 가능 request/response/error 값 유형 |

## Bluetape4k 사용법

| 기능 | 사용예 |
|---|---|
| `bluetape4k-text-lingua` | 재사용 가능한 Lingua 감지 빈 하나 만들기 |
| `bluetape4k-text-search` | 블록워드용 Aho-Corasick 자동 장치를 하나 만드세요 |
| `bluetape4k-logging` | 체계적인 워크샵 로깅 |
| `bluetape4k-assertions` | 집중된 HTTP/service 검증문 |

모듈은 루트 `bluetape4k-dependencies` BOM 및 기존 루트만 사용합니다.
`gradle/libs.versions.toml` 별칭. 개별 텍스트 BOM를 가져오면 안 됩니다.
또는 bluetape4k 텍스트 버전을 로컬로 고정하세요.

## 테스트

필수 테스트:

- 유효한 English/Korean/Japanese 요청은 `200`을 반환하고 감지된 언어는 일치합니다.
  용어, 마스크된 텍스트 및 경고.
- 비어 있거나 누락된 텍스트는 `400`을 반환합니다.
- `maxTextCharacters`보다 긴 텍스트는 `413`을 반환합니다.
- 싱글톤 감지기 및 일치자 Bean은 서비스에서 재사용됩니다.
- 테스트는 임의의 로컬 포트 ​​또는 바인딩된 MockMvc/WebTestClient으로 실행되며 다음이 필요합니다.
  외부 서비스 없음.

## 문서 및 다이어그램

소스와 동등한 `README.md` 및 `README.ko.md`을 생성합니다.

두 README에는 다음이 포함되어야 합니다.

- 언어 스위치
- 목적과 아키텍처
- `Used Bluetape4k features` 테이블
- request/response 예
- 오류 매핑 테이블
- 집중 검증 명령:
  `./gradlew :spring-boot-text-moderation-api:test`
- SVG 소스가 포함된 아키텍처 및 시퀀스 PNG 자산

다이어그램 자산:

- `docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-architecture-01.{svg,png}`
- `docs/images/readme-diagrams/spring-boot-text-moderation-api-readme-sequence-01.{svg,png}`

## 등록

`settings.gradle.kts`이 `spring-boot/*`를 자동 등록하기 때문에 모듈은
디렉토리 생성에 포함됩니다. PR를 업데이트해야 합니다.

- 루트 `README.md`
- 루트 `README.ko.md`
- `.github/workflows/Examples.yml` 경로 필터, 연기 명령 및 아티팩트 경로
- `scripts/smoke-validate.sh` `all-smoke`, `spring-boot` 및 오래된 예상 개수

## 위험

| 위험 | 완화 |
|---|---|
| 중복 `kotlin/text-processing` | 이 모듈의 초점을 HTTP 신뢰 경계 동작에 집중하세요 |
| 너무 큰 요청은 `400` | 전용 `PayloadTooLargeException` 및 컨트롤러 조언 매핑 사용 |
| 요청에 따른 감지기 구성 | detector/matcher을 싱글톤 빈으로 노출하고 테스트에서 재사용을 검증문 |
| 새 모듈 누락 CI | 동일한 분기의 예제 워크플로 및 연기 스크립트 업데이트 |
