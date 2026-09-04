# #886 social-network weighted shortest path 구현 review

## 구현 범위

- `SocialNetworkService`와 `SocialNetworkSuspendService`에 같은 인자를 받는
  `findWeightedConnectionPath`를 추가했다.
- `PathOptions(edgeLabel, maxDepth, weightProperty, missingWeightPolicy, direction,
  maxVisited)`로 공통 graph 2.0.0 경계를 호출한다.
- 기존 `findConnectionPath`, `findAllConnectionPaths`, `connect`의 hop/string strength
  계약은 수정하지 않았다.
- TinkerGraph abstract test가 sync/suspend에서 동일한 weighted 결과와 제한/예외 계약을
  검증한다.
- root/module README, coverage matrix, workflow/stale guard, ecosystem manifest, lesson을
  함께 갱신했다.

## 검토 결과

| 등급 | 결과 | 근거 |
|---|---|---|
| P0 | 0건 | 기존 API와 데이터 저장 계약을 보존하고, 새 API는 명시적 wrapper로 격리했다. |
| P1 | 0건 | maxDepth/maxVisited, missing/invalid weight, direction, deterministic tie에 대한 sync/suspend 회귀 테스트가 있다. |
| P2 | 0건 | weighted cost 의미, `totalWeight`, 2.0.0 BOM, rollback 범위를 README/KDoc/lesson에 기록했다. |

## 검증 증거

- targeted `:graph-social-network:test`: PASS (blocking/suspend TinkerGraph suite)
- detekt: exact implementation head에서 PASS
- README language/parity, stale-check, ecosystem-reuse checker, `git diff --check`: exact implementation head에서 PASS
- hosted CI: exact head의 CI/Examples/Ecosystem Gate가 모두 PASS한 뒤 PR 본문에 run ID를 고정

## 잔여 확인

Docker가 필요한 Neo4j/Memgraph integration은 hosted Container job에서 확인한다. 이
review는 새 backend나 A* 구현을 범위에 포함하지 않으며, #887 knowledge-graph schema
계약은 다음 순차 이슈에서 별도로 다룬다.
