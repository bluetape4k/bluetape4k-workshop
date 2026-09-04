# #886 social-network weighted shortest path 설계

## 목적

`graph/social-network`가 기존 hop 기반 `findConnectionPath`와 함께
`bluetape4k-graph 2.0.0`의 weighted shortest path를 보여주도록 확장한다. `KNOWS`
간선의 기존 `strength` 문자열 속성을 양수 비용으로 사용하고, 누적 비용이 가장 낮은
경로를 선택한다. 기존 unweighted API와 seed·moderation 등 다른 예제의 계약은
변경하지 않는다.

## 근거와 upstream 계약

- Workshop Issue #886과 `bluetape4k-graph` Issue #559 / merged PR #584를 기준으로 한다.
- `PathOptions(weightProperty, missingWeightPolicy, maxDepth, maxVisited, direction)`은
  sync·suspend·virtual-thread backend가 공유하는 계약이다.
- weighted `shortestPath`는 공통 JVM Dijkstra fallback을 사용한다. `maxDepth`는
  inclusive edge bound이고 `maxDepth=0`은 source와 target이 같은 vertex일 때만
  허용한다. `maxVisited` 초과와 경로 없음은 partial path가 아닌 `null`이다.
- weight는 숫자, 숫자 문자열 모두 허용하지만 `NaN`, infinity, 0 이하와 잘못된
  문자열은 `IllegalArgumentException`이다. 결측은 `Fail`, `Skip`, `UseDefault`로
  명시한다. 동률 frontier는 vertex ID 기준으로 결정적으로 정렬된다.

## API 결정

- 기존 `findConnectionPath`/`findAllConnectionPaths`는 hop semantics 그대로 둔다.
- 두 서비스에 `findWeightedConnectionPath`를 추가한다.
  - 기본 weight property는 `KnowsLabel.strength.name`이다.
  - `maxDepth`는 `0..MAX_TRAVERSAL_DEPTH`, `maxVisited`는 양수로 제한한다.
  - `missingWeightPolicy`와 `direction`을 호출자가 선택할 수 있다.
  - 반환 `GraphPath.totalWeight`와 vertex 순서는 sync/suspend에서 동일해야 한다.
- `connect`의 기존 `strength` 저장 타입(String)과 1..10 validation은 유지한다. 문서와
  seed fixture에서 strength를 weighted cost로 읽는 의미를 명시하되, public graph
  schema나 기존 저장 데이터를 마이그레이션하지 않는다.

## 예제 흐름

1. seed graph의 Alice→Dave에 hop path(Alice→Bob→Dave)와 weighted path를 나란히
   조회한다.
2. weighted fixture에는 낮은 strength의 3-hop 우회 경로와 높은 비용의 2-hop 경로를
   두어 hop 수와 누적 cost 선택이 다름을 검증한다.
3. `maxDepth=2`에서 3-hop 우회가 제외되고, `maxDepth=1`에서 결과가 `null`임을
   확인한다.
4. `maxVisited`, `Fail`/`Skip`/`UseDefault`, OUTGOING/INCOMING, 음수·0·NaN·infinity,
   동률 정렬을 TinkerGraph에서 검증한다. 같은 계약을 Neo4j/Memgraph integration
   테스트에 재사용한다.

## 범위 밖

새 graph backend, A* 휴리스틱, 기존 unweighted API 제거, strength의 데이터 타입
변경, 추천 점수 알고리즘 변경은 다루지 않는다.

## 위험과 완화

| 위험 | 완화 |
|---|---|
| String strength가 비용으로 오해될 수 있음 | README/KDoc에 낮을수록 traversal cost가 낮다는 의미를 명시하고 `totalWeight`를 출력 |
| backend별 fallback 결과 차이 | 공통 `PathOptions`를 사용하고 TinkerGraph + Neo4j/Memgraph conformance를 같은 assertion으로 실행 |
| 깊이/방문 제한이 partial path를 노출 | `null` 계약과 maxDepth=0/1, maxVisited 경계 테스트를 추가 |
| 결측·invalid weight가 조용히 통과 | `MissingWeightPolicy`를 노출하고 invalid value는 fail-fast로 검증 |
| cumulative PR gate가 graph docs를 누락 | manifest allowed paths와 workflow/stale-check/coverage matrix/lesson을 함께 갱신 |

## 롤백

새 weighted wrapper, seed cost fixture, 테스트와 문서만 되돌리면 된다. 기존
`findConnectionPath`와 `PathOptions` 호출부·저장 스키마에는 변경이 없다.
