# graph-social-network 생태계 리뷰

## 범위

- 모듈: `:graph-social-network`
- 경로: `graph/social-network`
- 리뷰 유형: 7-Tier bluetape4k 생태계/code-pattern review

## 해결한 발견 사항

- `connect`, `follow`, `addWorkExperience` endpoint validation은 이제 expected vertex label을 강제한다.
- same-person relationship mutation은 edge creation 전에 빠르게 실패한다.
- `graphName` blank input은 service construction 중 거부된다.
- recommendation DTO invariant는 count consistency를 위해 bluetape4k validation helper를 사용한다.
- blocking 및 suspend service test는 wrong endpoint label, self-edge rejection, DTO invariant failure를 커버한다.
- module integration test source set은 Gradle, Examples CI, Nightly, smoke validation에 연결되어 있다.
- README locale pair는 이제 stale BOM/mavenLocal/API/schema example을 제거하고 실제 graph backend를 반영한다.

## 검증

| 점검 | 결과 |
|---|---|
| `:graph-social-network:compileKotlin :graph-social-network:compileTestKotlin :graph-social-network:cleanTest :graph-social-network:test --no-build-cache --rerun-tasks --max-workers=1` | PASS, 80개 test |
| `:graph-social-network:integrationTest --no-build-cache --rerun-tasks --max-workers=1` | PASS, 160개 test |
| `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml` | PASS |
| `./scripts/smoke-validate.sh stale-check` | PASS, active module 101개, stale README ref 없음 |
| `git diff --check` | PASS |
| Static pattern scan | PASS, 새 raw JUnit/kotlin.test assertion, `!!`, `Thread.sleep`, raw container, UUID generation 없음 |

## DoD 상태

P0/P1 issue는 닫혔다. 남은 known risk는 PR 생성 후 repository-wide CI 실행으로 제한된다.
