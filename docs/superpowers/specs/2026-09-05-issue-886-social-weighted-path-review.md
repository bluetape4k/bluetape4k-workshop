# #886 social-network weighted shortest path 설계 review

## 판정

**KEEP WITH REVISION** — 기존 social-network API를 보존하면서 `PathOptions` 기반
weighted fallback과 maxDepth conformance를 예제로 노출하는 범위는 적절하다. 구현 시
아래 수정 경계를 지킨다.

## 관점별 검토

- 기능/API: 기존 hop 메서드는 유지하고 명시적 `findWeightedConnectionPath`만 추가한다.
  `totalWeight`와 path vertices를 public 응답 모델에 복제하지 않는다.
- Kotlin/동시성: suspend wrapper는 기존 `GraphSuspendOperations` 경계를 재사용하고
  sync/suspend가 같은 options를 전달한다. 별도 `runBlocking`을 추가하지 않는다.
- 알고리즘: `maxDepth`/`maxVisited`를 `PathOptions`에 위임하고, partial path 대신
  `null`을 기대한다. upstream deterministic tie ordering을 fixture로 고정한다.
- 데이터/호환성: `strength`는 기존 String 저장과 1..10 검증을 유지한다. weighted
  cost 의미를 문서화하지만 schema migration은 하지 않는다.
- 테스트: TinkerGraph에서 cost-vs-hop 차이, maxDepth=0/1/2, maxVisited, 결측 정책,
  invalid numeric, direction과 sync/suspend parity를 먼저 검증한다.
- 운영/문서: Neo4j/Memgraph integration은 Docker 태그로 분리하고, README·matrix·
  workflow/stale-check·lesson을 양국 언어로 함께 갱신한다.

## 위험과 완화

| 위험 | 완화 |
|---|---|
| hop API 회귀 | 기존 34개 추상 테스트를 그대로 실행하고 새 메서드는 별도 테스트로 분리 |
| strength 의미 혼동 | 낮을수록 비용이 낮다는 설명과 `totalWeight` 예시를 양국 README에 추가 |
| invalid weight 누락 | `WeightExtractor`의 0 이하/NaN/infinity/비수치 예외를 직접 assertion |
| backend drift | TinkerGraph reference와 Neo4j/Memgraph integration에서 동일 maxDepth 계약 확인 |

## 결론

P0/P1 blocker는 없다. P2는 fixture 의미, invalid weight, deterministic tie, cumulative
manifest evidence를 구현·검증 단계에서 닫는다. A*와 새 backend는 후속 범위로 남긴다.
