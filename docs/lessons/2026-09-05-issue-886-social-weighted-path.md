# Issue #886 Social Network weighted shortest path

## Context

`bluetape4k-graph 2.0.0`은 `PathOptions`에 weight property, missing-weight policy,
inclusive `maxDepth`, `maxVisited`, direction을 제공한다. 기존 `graph/social-network`
예제는 hop 수 기반 `findConnectionPath`만 보여주고 있어, strength 누적 비용과 backend
conformance를 학습할 수 없었다.

## Decision or Finding

- 기존 hop API와 `KNOWS.strength` 문자열 저장/1..10 검증은 그대로 유지했다.
- blocking/coroutine 서비스에 `findWeightedConnectionPath`를 추가하고 기본 weight를
  `KnowsLabel.strength`로 고정했다.
- wrapper는 `maxDepth`를 `0..MAX_TRAVERSAL_DEPTH`로 제한하며, 나머지 탐색 정책은
  공통 `PathOptions`에 전달한다. partial path가 아니라 backend의 `null` 계약을 사용한다.
- 낮은 strength가 낮은 누적 traversal cost라는 의미와 `GraphPath.totalWeight`를
  양국 README에 함께 기록했다.

## Outcome

TinkerGraph reference tests에서 적은 hop보다 낮은 cumulative cost를 우선하는 경로,
inclusive depth와 zero-depth vertex-only 경로, 방문 한도, 결측/invalid weight,
direction, deterministic tie, backend `PathOptions` parity를 blocking/suspend 양쪽에서
검증할 수 있다. Neo4j/Memgraph integration suite는 같은 service contract를 재사용한다.

## Verification

- `./gradlew :graph-social-network:test --no-build-cache --rerun-tasks --max-workers=1`
  — TinkerGraph blocking/suspend 100개 테스트 통과
- `./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1` — PASS
- README language/parity, stale-check, ecosystem scope, `git diff --check` — exact
  implementation head에서 PASS.
- hosted CI의 Smoke/Container/High-contention 결과와 exact PR head를 PR 본문에 기록한다.

## Future Guidance

weighted 예제를 확장할 때는 edge weight의 단위와 비용 방향을 먼저 문서화하고,
`maxDepth`/`maxVisited`를 backend에 위임하되 partial path를 결과로 노출하지 않는다.
새 graph backend를 추가할 경우 동일한 abstract conformance assertions와 missing-weight
정책을 재사용한다. 소비자 프로젝트의 버전은 계속 `bluetape4k-dependencies` BOM
`2.0.0`으로만 관리한다.
