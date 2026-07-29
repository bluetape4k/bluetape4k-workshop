# Issue 385 Diagram Validation Coverage

## 배경

README diagram validator는 대부분의 legacy architecture/sequence asset을 skip allowlist 뒤에
숨긴 채 `failures=0`을 보고했다. 이 때문에 output이 실제 checklist coverage보다 강해 보였다.

## 결정

- compatibility를 위해 기존 `legacySkipped` field를 유지한다.
- validator output이 true coverage와 documented exception을 분리하도록 `validated`,
  `documentedExceptions`, `exceptionSlugs`를 추가한다.
- asset이 legacy guard 없이 현재 validator를 통과함을 증명한 뒤에만 allowlist entry를 제거한다.
- diagram asset이 변경되지 않았다면 SVG/PNG visual QA를 주장하지 않는다.

## 결과

- Architecture skipped count moved from `92` to `91`.
- Sequence skipped count moved from `62` to `2`.
- Remaining sequence exceptions are exact and small enough for targeted follow-up:
  `kotlin-flow-extensions-race-fallback-readme-sequence-01.svg` and
  `observability-micrometer-observation-readme-sequence-01.svg`.

## 검증

- edit 전 baseline full local build가 통과했다.
- architecture validator는 `checked=113 validated=22 legacySkipped=91 documentedExceptions=91 failures=0`로 통과했다.
- sequence validator는 `checked=88 validated=86 legacySkipped=2 documentedExceptions=2 failures=0`로 통과했다.
- SVG가 변경되지 않았으므로 Diagram QA wrapper는 `targets=0`으로 통과했다.
- post-work full build는 `BUILD SUCCESSFUL in 1m 48s`로 통과했다.

## 향후 guard

legacy diagram allowlist를 줄일 때는 먼저 candidate가 현재 validator를 이미 통과하는지
분류한다. 해당 entry는 즉시 제거하고, 남은 exact exception slug에 대해서만 focused
remediation issue를 연다.
