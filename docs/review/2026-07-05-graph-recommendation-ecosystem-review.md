# graph-recommendation 생태계 리뷰

## 범위

- 모듈: `:graph-recommendation`
- 경로: `graph/recommendation`
- 리뷰 유형: 7-Tier bluetape4k 생태계/code-pattern review

## 해결한 발견 사항

- `purchase`와 `follow` endpoint validation은 edge 생성 전에 expected vertex label을 강제한다.
- `graphName` blank input은 service construction 중 거부된다.
- Recommendation DTO invariants use bluetape4k validation helpers for score/count consistency.
- blocking 및 suspend service test는 endpoint label failure와 DTO invariant failure를 커버한다.
- module integration test source set은 Gradle, Examples CI, Nightly, smoke validation에 연결되어 있다.
- README locale pair는 이제 실제 dependency shape를 반영한다. core/tinkerpop과 optional backend compile-only adapter를 구분한다.

## 검증

| 점검 | 결과 |
|---|---|
| `:graph-recommendation:compileKotlin :graph-recommendation:compileTestKotlin :graph-recommendation:cleanTest :graph-recommendation:test --no-build-cache --rerun-tasks --max-workers=1` | PASS, 84개 test |
| `:graph-recommendation:integrationTest --no-build-cache --rerun-tasks --max-workers=1` | PASS, 168개 test |
| `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` | PASS |
| `./scripts/smoke-validate.sh stale-check` | PASS, active module 101개, stale README ref 없음 |
| `git diff --check` | PASS |
| Static pattern scan | PASS, 새 raw JUnit/kotlin.test assertion, `!!`, `Thread.sleep`, raw container, UUID generation 없음 |

## DoD 상태

P0/P1 issue는 닫혔다. 남은 known risk는 PR 생성 후 repository-wide CI 실행으로 제한된다.
