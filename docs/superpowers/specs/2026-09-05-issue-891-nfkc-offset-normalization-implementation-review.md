# Issue #891 NFKC offset·normalization 구현 리뷰

## 리뷰 범위

- `bluetape4k-dependencies` 2.0.0의 `text-search` NFC/NFKC 소비 경계
- `AbuseWordFilter`, `SensitiveRedactionPolicy`, Spring moderation property/configuration
- 원문 offset, same-length masking, overlap/adjacent, 1,024 segment bound
- 테스트, EN/KO README, coverage/manifest, stale guard, lesson

## Six-lens 결과

| 관점 | 결과 | 근거 |
|---|---|---|
| 기능 | PASS | NFKC `㈜` fixture가 `(주)`와 일치하고 반환 range와 mask는 원문 한 code-unit을 사용한다. |
| API/호환성 | PASS | 세 option 모두 NFC default이며 기존 constructor, policy caller와 HTTP JSON을 유지한다. |
| 성능/안정성 | PASS | upstream incremental offset mapping과 1,024 segment fail-fast를 그대로 소비한다. |
| 보안/운영 | PASS | bound 오류에 원문을 추가하지 않고 normalization mutation endpoint나 raw log를 만들지 않는다. |
| Kotlin/Spring | PASS | immutable public enum을 policy/property로 전달하고 Spring enum binding을 context test로 검증한다. |
| 사용자/문서 | PASS | EN/KO README가 NFC/NFKC 차이, 원문 offset, `㈜` masking과 bound를 동등하게 설명한다. |

## 발견 사항과 해소

- Normalized `(주)` 길이로 mask하면 `㈜` 원문 길이가 바뀔 수 있음 — upstream match의 원문
  `length`만 사용한다.
- Issue 문구의 code-point와 public API의 offset 단위가 혼동될 수 있음 — 기존 API 계약대로
  Kotlin `String` code-unit half-open range임을 설계와 README에 명시했다.
- Spring enum binding이 설정 문자열을 실제 automaton에 전달하지 않을 수 있음 —
  `ApplicationContextRunner`에서 `NFKC` property와 compatibility fixture를 실행한다.
- 과도한 combining sequence가 CPU 경로를 다시 열 수 있음 — 1,025 mark 회귀가
  `IllegalArgumentException`과 raw-free message를 고정한다.
- normalization 인자 추가로 기존 JVM descriptor가 사라질 수 있음 — `@JvmOverloads`와 reflection
  회귀 테스트로 `AbuseWordFilter(Collection)` 및 `SensitiveRedactionPolicy.of(Collection, char, int)`를 보존한다.
- English README의 language guard를 지키기 위해 compatibility 문자를 `U+3231`/`\u3231`로 표기했지만
  stale guard가 literal `㈜`만 요구해 GNU grep 환경에서 실패함 — English와 Korean 표기 계약을 분리해 검증한다.

## 검증 증거

- Targeted NFKC red 단계가 기존 signature 부재로 compile fail한 뒤 구현 후 통과
- Clean module tests: `kotlin-text-processing` 65개, `spring-boot-text-moderation-api` 11개,
  합계 76개 통과, failure/error 0
- Root detekt 112 task, README language/parity, stale guard, ecosystem checker unit 113개,
  actionlint, manifest JSON과 diff-check 통과
- 두 module에서 `io.github.bluetape4k.text:text-search:1.0.0`이 root
  `bluetape4k-dependencies:2.0.0` constraint로 resolve됨
- BSD/GNU grep stale guard, README language/parity와 exact-head hosted CI는 PR head 갱신 후 확인한다.

## 판정

현재 diff 기준 P0/P1/P2 0건이다. Locale 자동 선택, streaming scanner와 새 PII classifier는
Issue #891 범위 밖이다.
