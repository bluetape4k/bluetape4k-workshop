# Issue #953 Okio BufferedSuspendedSink 계획 리뷰

## 검토 대상

- 설계: `docs/superpowers/specs/2026-09-06-issue-953-okio-buffered-suspended-sink-design.md`
- 계획: `docs/superpowers/plans/2026-09-06-issue-953-okio-buffered-suspended-sink-plan.md`
- 기준 artifact: `bluetape4k-okio:2.0.0`

## 1차 리뷰

Architecture/API와 test/operations 두 관점에서 P0 0건, P1 5건, P2 4건을 확인했다.

- `write`와 `writeAll`의 no-progress 메시지 및 회귀를 분리한다.
- 두 no-progress 테스트가 각각 새 source를 사용하고 `runTest` 가상 시간으로 종료되게 한다.
- 실패한 첫 `close()` 뒤 두 번째 호출도 underlying write/close를 반복하지 않는지 확인한다.
- Examples smoke 결과의 XML/HTML artifact를 업로드한다.
- manifest/review artifact 생성 전에는 stale-check 성공을 기대하지 않는다.
- same-FQCN 경계는 전체 source 호환이 아니라 이번 호출 경로의 JVM method ABI로 제한한다.
- stale guard가 양쪽 no-progress, 8회 read, idempotent close와 exact payload를 확인하게 한다.

## 반영 결과

모든 P1과 관련 P2를 실행 계획에 반영했다. stable 2.0.0 JAR bytecode에서 확인한 예외 메시지는 다음과 같다.

- `write`: `Unable to write from SuspendedSource: no progress.`
- `writeAll`: `Unable to writeAll from SuspendedSource: no progress.`

## 재검토 판정

| 관점 | P0 | P1 | 판정 |
|---|---:|---:|---|
| Architecture/API | 0 | 0 | PASS |
| Test/operations | 0 | 0 | PASS |

계획 단계의 미해결 차단 항목은 없다. 구현은 local buffered sink 두 파일만 제거하고 공개 `buffered()` 경계를
행동 테스트와 hosted CI로 증명한다.
