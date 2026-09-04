# Issue #887 Knowledge Graph schema drift planner

## Context

`bluetape4k-graph 2.0.0`은 backend-neutral `GraphSchemaDefinition`과
`GraphSchemaDriftPlanner`를 제공한다. 기존 `graph/knowledge-graph`는 Entity,
Concept, Document label을 선언했지만 domain key의 index/unique constraint를
계획하거나 backend가 지원하지 않는 DDL을 드러내지 않았다.

## Decision or Finding

- `entityId`, `conceptId`, `documentId` 각각에 lookup index와 UNIQUE constraint를
  desired schema로 선언했다.
- blocking 서비스는 upstream `GraphSchemaManager.plan`을 직접 사용하고, suspend
  서비스는 coroutine schema capability로 metadata를 읽어 같은 plan semantics를
  유지한다.
- `initialize()`는 기본 dry-run plan을 graph 생성보다 먼저 만들며 plan을 자동 적용하지
  않는다. schema plan 실패는 graph/seed 쓰기 전에 예외로 전달한다.
- destructive drop은 `dryRun=false`와 `allowDestructiveDrops=true`를 호출자가
  함께 명시해야 하며, TinkerGraph unique constraint는 `UNSUPPORTED` report로
  확인한다.

## Outcome

기존 seed/traversal 흐름은 유지하면서 두 서비스에서 deterministic schema plan을
조회할 수 있다. 계획 전후 live metadata가 같고, TinkerGraph explicit apply는 index를
적용하되 unsupported constraint를 성공으로 숨기지 않는다. Neo4j/Memgraph
integration suite는 같은 desired schema와 backend capability 경계를 재사용한다.

## Verification

- `:graph-knowledge-graph:test` — TinkerGraph blocking/suspend 및 planner ordering
  테스트 포함 53개 통과
- `:graph-knowledge-graph:integrationTest` — Neo4j/Memgraph blocking/suspend
  conformance 100개 통과
- `detekt`, README language/parity, stale-check, ecosystem reuse checker,
  dependency insight, actionlint, `git diff --check` — exact head에서 확인
- hosted CI의 Smoke/Container/High-contention 결과와 exact PR head를 PR 본문에
  기록한다.

## Future Guidance

schema plan은 계속 dry-run을 기본으로 유지하고, 승인된 migration 경계에서만
`GraphSchemaPlan.apply`를 호출한다. 새로운 label/property를 추가할 때는 desired
schema, TinkerGraph unsupported assertion, Neo4j/Memgraph integration, README 양국과
ecosystem manifest를 한 변경으로 갱신한다. 소비자 프로젝트의 graph alias는 계속
versionless로 두고 root `bluetape4k-dependencies` BOM `2.0.0`에 위임한다.
