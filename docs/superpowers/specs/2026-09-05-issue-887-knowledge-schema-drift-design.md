# #887 knowledge-graph schema drift planner 설계

## 목적

`graph/knowledge-graph`가 `bluetape4k-graph 2.0.0`의
`GraphSchemaDriftPlanner`를 사용해 Entity, Concept, Document의 desired schema와
backend live metadata 차이를 계획하도록 확장한다. 기본 동작은 dry-run이며,
schema plan을 만들 때 graph DDL이나 seed data를 변경하지 않는다. 기존 graph 생성,
traversal, seed API의 의미는 유지한다.

## 근거와 upstream 계약

- Workshop Issue #887과 `bluetape4k-graph` Issue #315 및 merged PR #506의 공개 API를
  기준으로 한다.
- `GraphSchemaDefinition`은 `GraphIndex`와 `GraphConstraint` 집합을 선언하고,
  `GraphSchemaManager.plan(desired, options)`은 live metadata와 비교해 결정적인
  `GraphSchemaPlan`을 반환한다.
- `GraphSchemaPlanOptions()`의 기본값은 `dryRun=true`,
  `allowDestructiveDrops=false`이다. destructive drop은 두 옵션을 명시적으로
  켜야 하며, constraint drop은 공통 API가 없어 `UNSUPPORTED`로 보고된다.
- schema mutation을 지원하지 않는 backend는 `UnsupportedOperationException`으로
  실패해야 하며 성공한 것처럼 처리하지 않는다. TinkerGraph는 index metadata를
  보유하지만 unique constraint 적용은 명시적으로 unsupported이다.

## Desired schema

다음 안정적인 도메인 키를 조회 성능과 중복 방지의 기준으로 선언한다.

| Label | Property | Object |
|---|---|---|
| Entity | `entityId` | lookup index + UNIQUE constraint |
| Concept | `conceptId` | lookup index + UNIQUE constraint |
| Document | `documentId` | lookup index + UNIQUE constraint |

backend 이름은 직접 만들지 않고 `GraphSchemaNames.indexName`/
`uniqueConstraintName`을 사용한다. planner 비교는 label/property/entity type/type을
기준으로 하므로 backend가 synthetic name을 반환해도 drift 판단이 흔들리지 않는다.

## API 결정

- `KnowledgeGraphSchema.desiredSchema()`를 추가해 desired definition을 한 곳에서
  재사용한다.
- blocking/coroutine 서비스에 `planSchema(options)`를 추가한다.
  - blocking 서비스는 upstream `GraphSchemaManager.plan`을 직접 호출한다.
  - coroutine 서비스는 `GraphSuspendSchemaManager`의 metadata를 IO 경계에서 읽고
    동일한 planner semantics를 유지하는 suspend adapter를 사용한다.
- `initialize(options)`는 먼저 `planSchema(options)`을 실행하고, 성공한 뒤에만
  graph가 없을 때 생성한다. plan은 절대로 자동 적용하지 않으며 기존 호출은 반환
  값을 무시해도 동작한다.
- 실제 DDL 적용은 호출자가 반환된 blocking `GraphSchemaPlan`에 대해
  `plan.apply(ops.schemaManager())`를 명시적으로 호출하는 별도 경계로 남긴다.
  기본 dry-run plan은 apply해도 live schema를 변경하지 않는다.
- schema plan 실패는 `initialize`에서 예외를 그대로 전달해 seed 호출 전에 중단한다.
  자동 rollback, data backfill, migration tool은 범위 밖이다.

## 테스트 전략

1. TinkerGraph blocking/suspend에서 desired definition의 index/constraint 항목과
   동일 호출의 deterministic plan을 검증한다.
2. dry-run 전후 `listIndexes`/`listConstraints`가 동일해 schema mutation이 없음을
   검증한다.
3. TinkerGraph의 명시적 non-dry-run apply가 unique constraint를
   `UNSUPPORTED`로 보고하고 성공으로 위장하지 않는지 검증한다.
4. blocking/coroutine 서비스가 같은 desired schema와 option 계약을 노출하는지
   확인한다. Neo4j/Memgraph integration suite는 기존 abstract backend matrix를
   재사용한다.
5. planner 예외를 발생시키는 fake operations에서 `initialize`가 graph/seed 쓰기
   전에 실패하는 순서를 고정한다.

## 범위 밖

자동 schema migration/rollback, constraint drop 구현, graph backend 추가,
기존 seed 데이터 backfill, schema version 저장은 다루지 않는다.

## 위험과 완화

| 위험 | 완화 |
|---|---|
| TinkerGraph가 unique constraint를 지원하지 않음 | apply report의 `UNSUPPORTED`를 명시적으로 assertion하고 dry-run은 계속 사용 |
| backend별 metadata name 차이 | planner의 semantic 비교와 `GraphSchemaNames`를 사용 |
| 기존 initialize 호출의 반환 타입 변화 | 반환 plan은 기존 호출에서 무시 가능하고 graph 생성 side effect는 유지 |
| schema plan 실패 뒤 seed 쓰기 | initialize가 plan을 graph 생성보다 먼저 실행하고 예외를 전파 |
| 누락된 consumer BOM/문서 guard | versionless graph alias, root BOM, README 양국, matrix/workflow/stale/manifest를 함께 갱신 |

## 롤백

새 desired schema 선언, plan API, 테스트와 문서를 되돌리면 된다. 기존 graph
traversal 및 seed fixture에는 데이터 migration이 없으므로 rollback 시 저장 데이터
호환성 위험이 없다.
