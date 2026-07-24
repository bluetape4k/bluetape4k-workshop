# Issue #573 Commerce shared 경계 교훈

## Context

`shared` 모듈에는 여러 예제에서 쓰이는 HTTP client 확장과 테스트 기반 유틸리티가 있다. 그러나
`VoucherCampaignBlackBoxContract`는 정규 상태 기반과 이벤트 소싱 기반의 Voucher campaign 예제만
소비하는 black-box 호환성 계약이다. 요청, 정규화 결과, lifecycle 시나리오를 표현하므로 범용 utility가
아닌 commerce 도메인 계약이다.

## Decision or Finding

- `shared`는 도메인에 독립적인 utility와 test infrastructure만 유지한다.
- Voucher campaign처럼 같은 commerce 하위 예제 사이에서만 재사용하는 계약 fixture는
  `commerce/shared`의 `:commerce-shared`에 둔다.
- 이동 대상의 package는 `io.bluetape4k.workshop.commerce.shared.voucher`로 하여 소비 범위를
  import에서 드러낸다.
- 두 Voucher campaign 모듈은 main dependency가 아니라 `testImplementation(project(\":commerce-shared\"))`로
  계약을 소비한다. 이 모듈에는 production service, persistence, adapter, migration을 넣지 않는다.

## Outcome

- [Issue #573](https://github.com/bluetape4k/bluetape4k-workshop/issues/573)에 모듈 생성, 계약/테스트 이동,
  두 소비 모듈의 의존성 및 import 갱신을 별도 작업으로 기록했다.
- assertion 정리인 issue #568과 분리하여, 작은 test idiom 변경에 모듈 구조 변경이 섞이지 않게 했다.

## Verification

- `shared`의 Voucher 계약은 `VoucherCampaignBlackBoxContract.kt` 한 파일과 그 전용 테스트에 있다.
- 현재 소비자는 `commerce/promotion-voucher-campaign`과
  `commerce/event-sourced-promotion-voucher-campaign`의 compatibility test source set이다.
- 두 소비 모듈은 현재 `testImplementation(project(\":shared\"))`로 계약을 가져온다.

## Future Guidance

새 shared 후보는 먼저 재사용 축을 확인한다.

- 서로 독립된 두 개 이상 예제에서 사용하는 도구성 capability면 `shared`에 둔다.
- 한 도메인 family의 시나리오, fixture, compatibility contract면 해당 domain의 shared module에 둔다.
- 한 예제에서만 쓰는 business rule이나 implementation이면 그 예제에 둔다.

`commerce-shared`도 범용 보관함이 되면 안 된다. 다른 commerce 예제에서 실제로 소비되는
계약/fixture만 추가하고, domain behavior는 각 예제의 경계 안에 유지한다.
