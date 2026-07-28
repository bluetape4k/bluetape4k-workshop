# graph-event-lineage 생태계 리뷰

## 범위

- 모듈: `:graph-event-lineage`
- 경로: `graph/event-lineage`
- 리뷰 유형: 7-Tier bluetape4k 생태계/code-pattern review

## 해결한 발견 사항

- `integrationTest`는 이제 shared Gradle test mutex를 사용한다.
- `LineageNode`는 private constructor와 public factory를 사용하므로 service-owned `Empty`만 empty sentinel shape를 가질 수 있다.
- public `LineageNode` construction은 blank ID/label과 blank property key를 거부한다.
- `LineagePath`는 edge-count shape와 non-blank edge label을 검증한다.
- `supersededChain`은 이제 bounded `maxDepth`를 받고 bluetape4k helper로 이를 검증한다.
- README locale pair는 traversal depth와 sentinel construction contract를 문서화한다.

## 검증

| 점검 | 결과 |
|---|---|
| `:graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin :graph-event-lineage:cleanTest :graph-event-lineage:test --no-build-cache --rerun-tasks` | PASS, 13개 test |
| `:graph-event-lineage:integrationTest --no-build-cache --rerun-tasks` | PASS, 13개 test |
| `git diff --check` | PASS |
| Static pattern scan | PASS, 새 raw JUnit/kotlin.test assertion, `!!`, `Thread.sleep`, raw container, UUID generation 없음 |

## DoD 상태

P0/P1 issue는 닫혔다. 남은 known risk는 이 PR 범위 밖의 broader graph module consistency로 제한된다.
