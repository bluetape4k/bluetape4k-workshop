# WIP - bluetape4k-workshop

스냅샷: 2026-06-02 KST
범위: 2026-01-01 이후 생성되고 `debop`에게 할당된 열린 GitHub 이슈입니다.
열린 이슈 수: 2개입니다.

## Recently Completed

- CI/Nightly, Spring Boot 4.0.x 정렬, Gradle 9.5.0/version-catalog 이전, 로컬 OMX 무시 규칙, assertion 이전이 병합되었습니다.
- Exposed 워크숍 모듈의 JDBC helper 의존성 수정은 PR #60과 PR #61로 병합되었습니다.
- 의존성 거버넌스, 호환성 가드, 의존성 업데이트는 PR #33부터 PR #59까지 병합되었습니다.
- GNO 기반 감사가 비활성화된 R2DBC WebFlux 통합 테스트를 추적하기 위해 `#120`을 등록했습니다.

## Current Direction

워크숍 이슈는 안정된 라이브러리 API를 소비해야 합니다. 소유 라이브러리 저장소에서 핵심 API나 의미가 확정되기 전에 graph 또는 leader 실행 예제를 만들지 않습니다.

같은 영역에서 새 예제를 확장하기 전에 비활성화된 Spring/R2DBC 커버리지를 복구합니다. pending 테스트가 남아 있는 빌드 성공만으로는 워크숍 동작을 충분히 보호하지 못합니다.

## Priority Queue

| Priority | Issue | Difficulty | Notes |
|---|---|---:|---|
| P2 | [#120](https://github.com/bluetape4k/bluetape4k-workshop/issues/120) R2DBC WebFlux tests disabled | M | `:spring-data-r2dbc-webflux:test`는 44개 pending 테스트와 함께 성공합니다. 결정적 schema/data 초기화를 연결하고 넓은 `@Disabled`를 제거해야 합니다. |
| P3 | [#14](https://github.com/bluetape4k/bluetape4k-workshop/issues/14) Ktor-first workshop example | M | 독립적으로 시작할 수 있지만 전략적 영향도는 낮습니다. |
| P3 | [#9](https://github.com/bluetape4k/bluetape4k-workshop/issues/9) bluetape4k-graph examples epic | L | 실행 가능한 워크숍 영역만 추적합니다. 라이브러리 예제는 `bluetape4k-graph`에 둡니다. |
| P3 | [#11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11) knowledge-graph example | M | graph 예제와 core API 안정성에 의존합니다. |
| P3 | [#12](https://github.com/bluetape4k/bluetape4k-workshop/issues/12) fraud-detection example | M | graph 예제와 core API 안정성에 의존합니다. |
| P3 | [#13](https://github.com/bluetape4k/bluetape4k-workshop/issues/13) recommendation example | M | graph 예제와 core API 안정성에 의존합니다. |
| P3 | [#10](https://github.com/bluetape4k/bluetape4k-workshop/issues/10) bluetape4k-leader examples epic | L | leader lease/state 의미가 안정될 때까지 대기합니다. 라이브러리 저장소 작업과 경계를 분리합니다. |

## Dependency Map

```text
bluetape4k-graph core APIs
  -> #9 graph workshop epic
      -> #11 knowledge graph
      -> #12 fraud detection
      -> #13 recommendation

bluetape4k-leader lease/state semantics
  -> #10 leader workshop epic

#14 Ktor-first workshop
  -> 독립적이지만 blocker는 아님

#120 R2DBC WebFlux disabled tests
  -> 독립적인 correctness/test lane
  -> Spring Data R2DBC 예제를 추가하기 전에 수정
```

## WIP Limits

| Lane | Limit | Current next |
|---|---:|---|
| Data/reactive test coverage | 1 | Spring Data R2DBC 예제를 건드린다면 `#120`을 먼저 처리합니다. |
| Independent workshop | 1 | 지금 예제가 필요하면 `#14`를 진행합니다. |
| Graph examples | graph core 안정 전까지 0 | `#9/#11/#12/#13`은 대기합니다. |
| Leader examples | leader 의미 안정 전까지 0 | `#10`은 대기합니다. |
