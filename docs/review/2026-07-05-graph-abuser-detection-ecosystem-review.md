# graph-abuser-detection 생태계 리뷰

## 범위

- 모듈: `:graph-abuser-detection`
- 경로: `graph/abuser-detection`
- 리뷰 유형: 7-Tier bluetape4k 생태계/code-pattern review

## 해결한 발견 사항

- `integrationTest`는 이제 test source set/runtime classpath와 shared Gradle test mutex를 연결한다.
- blocking 및 suspend service는 blank graph name을 거부한다.
- edge mutator는 graph edge 생성 전에 endpoint 존재와 expected label을 검증한다.
- `IdentifierEdgeLabel`, `SuspiciousUserScore`, `AbuserDetectionSeed`는 bluetape4k validation/serialization rule을 따른다.
- Suspend Flow cancellation은 명시적인 collector-cancellation regression test가 커버한다.
- `explainSuspicion` documentation과 test는 이제 outgoing-identifier-path contract를 명시한다.

## 검증

| 점검 | 결과 |
|---|---|
| `:graph-abuser-detection:compileKotlin :graph-abuser-detection:compileTestKotlin :graph-abuser-detection:cleanTest :graph-abuser-detection:test --no-build-cache --rerun-tasks` | PASS, 43개 test |
| `:graph-abuser-detection:integrationTest --no-build-cache --rerun-tasks` | PASS, 86개 test |
| `git diff --check` | PASS |
| Static pattern scan | PASS, 새 raw JUnit/kotlin.test assertion, `!!`, `Thread.sleep`, raw container, UUID generation 없음 |

## DoD 상태

P0/P1 issue는 닫혔다. 남은 known risk는 이 PR 범위 밖의 broader graph module consistency로 제한된다.
