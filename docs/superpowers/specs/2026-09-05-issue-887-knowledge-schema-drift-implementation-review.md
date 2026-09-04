# #887 knowledge-graph schema drift planner 구현 review

## 구현 범위

- `KnowledgeGraphSchema`에 Entity/Concept/Document domain key의 index와 UNIQUE
  constraint desired definition을 추가했다.
- blocking/suspend knowledge graph 서비스에 `planSchema`를 노출하고,
  `initialize`가 plan을 graph 생성보다 먼저 수행하도록 했다.
- 기본 dry-run과 destructive drop 이중 opt-in을 README 양국에 기록하고,
  TinkerGraph의 unsupported unique constraint report 및 plan-first ordering을
  테스트했다.
- root/module README, coverage matrix, workflow/stale guard, ecosystem manifest,
  lesson을 함께 갱신했다.

## 검토 결과

| 등급 | 결과 | 근거 |
|---|---|---|
| P0 | 0건 | schema plan은 자동 적용하지 않고 기존 seed/traversal API를 보존한다. |
| P1 | 0건 | deterministic plan, dry-run no-mutation, unsupported report, failure-before-seed를 blocking/suspend 및 TinkerGraph에서 검증한다. |
| P2 | 0건 | 2.0.0 BOM, backend capability, destructive 경계, rollback 범위가 문서와 lesson에 기록됐다. |

## 검증 증거

- targeted `:graph-knowledge-graph:test`: TinkerGraph blocking/suspend 및 planner
  ordering suite 53개 PASS
- `:graph-knowledge-graph:integrationTest`: Neo4j/Memgraph blocking/suspend
  conformance 100개 PASS
- detekt, README language/parity, stale-check, ecosystem checker, dependency
  insight, actionlint, `git diff --check`: exact implementation head에서 PASS
- Neo4j/Memgraph integration은 hosted Container job에서 같은 abstract contract를
  확인한다.

## 잔여 확인

새 backend나 자동 migration은 범위에 포함하지 않는다. hosted CI 전체 PASS와
exact-head PR metadata/review 확인 뒤에만 다음 순차 이슈 또는 최종 merge gate로
이동한다.
