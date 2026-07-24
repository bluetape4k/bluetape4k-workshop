# Workshop 재사용 Inventory: #563 / #564

## 판단 기준

- Workshop은 consumer repository이므로 BOM이 실제로 해석한 릴리스 artifact만 채택한다.
- 같은 계약이 독립적인 예제에서 반복되고, 소비자·검증 경계가 안정적일 때만 `shared`로 둔다.
- provider에 이미 있는 API가 충분하면 Workshop wrapper를 만들지 않는다. 릴리스 API에 없는
  일반화된 기능은 provider issue 후보로 남긴다.

## 릴리스 확인

`./gradlew :observability-basic:dependencyInsight --dependency bluetape4k-micrometer --configuration runtimeClasspath --console=plain`은
`io.github.bluetape4k:bluetape4k-micrometer:1.11.0`을
`bluetape4k-dependencies:1.3.1` 제약으로 해석했다.

해당 JAR의
`io.bluetape4k.micrometer.observation.coroutines.ObservationCoroutinesSupportKt`에는
다음 public API가 있다.

- `withObservationSuspending(String, ObservationRegistry, suspend block)`
- `withObservationContextSuspending(String, ObservationRegistry, suspend CoroutineScope block)`

그래프도 실제 BOM 해석 결과인 `bluetape4k-graph-core:0.5.1`로 확인했다.
`GraphVertexRepository`에는 `findVertexById`만 있고 label을 검증해 실패시키는 typed
endpoint helper는 없다.

## Inventory

| Candidate | Disposition | Evidence | Follow-up |
|---|---|---|---|
| basic/advanced Observability의 local `observed` | released-bluetape4k candidate | 두 `ObservationSupport.kt` 구현, production caller 5개 이상, BOM이 `bluetape4k-micrometer:1.11.0`을 해석했고 동등한 suspend observation API를 제공 | #561에서 parity test를 먼저 추가한 뒤 local helper 제거 |
| `shared` HTTP extensions | retain in `shared` | `WebClientExtensions`, `WebTestClientExtensions`, `RestClientExtensions`가 존재하고 독립 module group 8개가 HTTP helper를 호출 | Spring 4/API drift가 발생할 때만 contract suite와 함께 재검토 |
| voucher black-box contract | retain in `shared` | `VoucherCampaignBlackBoxContract`를 promotion-voucher와 event-sourced voucher 구현이 각각 compatibility test로 소비 | 세 번째 독립 구현이 생길 때만 추상화 경계를 재검토 |
| graph `requireEndpoint` | provider-gap candidate | social, abuser, knowledge, recommendation graph services의 local endpoint validation 반복; release `0.5.1`에 동등 typed API 없음 | `bluetape4k-graph` provider issue를 열어 synchronous/suspend/virtual-thread parity를 제안 |
| Exposed DTO mapper와 Mongo test base | example-specific | JPA QueryDSL DTO projection과 Mongo reactive/coroutine fixture가 각 persistence model·lifecycle에 결합 | extraction 하지 않음 |

## #563 결론

새 `shared` abstraction은 만들지 않는다. 관측 helper는 #561에 맡기고, graph endpoint
validation은 Workshop 내부 복제 대신 provider의 공통 API 후보로 등록한다. 남은 shared
artifact는 반복 소비와 계약 검증이 있으므로 유지한다.
