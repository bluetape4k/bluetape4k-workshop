# #887 knowledge-graph schema drift planner 설계 review

## 판정

**KEEP WITH REVISION** — desired schema를 선언하고 planner 결과를 dry-run으로
노출하는 범위는 명확하며 기존 traversal/seed 계약을 보존한다. 구현 시 아래 경계를
지킨다.

## 관점별 검토

- 기능/API: `initialize`는 plan을 먼저 만들고 반환하며, DDL apply는 호출자 명시
  경계로 남긴다. 기존 호출부는 반환 값을 무시할 수 있다.
- Kotlin/동시성: blocking/suspend 서비스가 각 backend의 schema capability를
  재사용한다. coroutine 경로에 `runBlocking`을 추가하지 않는다.
- 안전성: 기본 dry-run과 `allowDestructiveDrops` 이중 opt-in을 문서화하고,
  unsupported constraint를 성공으로 위장하지 않는다.
- 데이터: Entity/Concept/Document의 도메인 키만 index/UNIQUE 대상으로 삼고,
  graph label이나 seed payload를 변경하지 않는다.
- 테스트: deterministic plan, no-mutation, unsupported apply report, failure-before-
  seed, backend matrix를 고정한다.
- 운영/문서: README 양국, root matrix, workflow/stale guard, ecosystem manifest,
  lesson을 함께 갱신하고 2.0.0 BOM만 사용한다.

## 위험과 완화

| 위험 | 완화 |
|---|---|
| suspend planner API 부재 | suspend metadata를 비동기 IO로 읽는 동일 semantics adapter를 작게 둔다 |
| TinkerGraph schema가 인스턴스 수명 동안 유지됨 | 자동 apply를 금지하고 각 테스트 전후 metadata를 비교한다 |
| plan 실패 뒤 graph/seed side effect | plan-first ordering과 fake failure test를 둔다 |
| cumulative PR 범위 누락 | manifest allowed paths와 exact receipt/hash를 hosted gate 전에 검증한다 |

## 결론

P0/P1 blocker는 없다. P2는 backend capability, dry-run/no-mutation, unsupported
constraint, plan-first ordering, versionless BOM evidence를 구현 및 검증 단계에서
닫는다. migration/rollback은 후속 범위로 남긴다.
