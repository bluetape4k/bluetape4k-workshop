# Issue 569 Order Lifecycle validation helper 경계

## Context

Order Lifecycle의 SSE 설정을 released helper로 전환한 뒤에도
`commerce/order-lifecycle-fulfillment`에 raw `require(...)`가 남아 있었다.
Issue #569는 BOM이 해석한 `bluetape4k-core` release API와 의미가 정확히 맞는
caller-input 검증만 후속 정리하는 범위를 정의했다.

## Decision or Finding

- inclusive numeric range에는 released `requireInRange`를 사용한다.
- 단순 non-empty collection에는 `requireNotEmpty`, 양수에는
  `requirePositiveNumber`를 사용한다.
- regex, 문자열 길이, operator security guard는 전용 released helper parity가
  없거나 기존 진단을 보존해야 하므로 raw predicate를 유지한다.
- `BigDecimal` 비교에는 `requirePositiveNumber` 계열을 적용하지 않는다. generic
  numeric helper의 `toDouble()` 변환이 정밀도를 손상할 수 있으므로 exact
  comparison을 유지한다.
- 새 Workshop 공통 helper를 만들지 않고, regex/length helper 필요성은
  `bluetape4k-projects#1079`에서 추적한다.

## Outcome

replay/cleanup batch, order line count와 quantity 검증이 released helper를 사용하고,
기존 regex·length·security·decimal 경계는 변경되지 않았다. 모든 caller-input
실패는 `IllegalArgumentException` 계약을 유지한다.

## Verification

- focused validation-contract test는 helper adoption과 보존된 raw 경계를 검증한다.
- RED 단계에서 helper adoption test가 실패한 뒤 GREEN 단계에서 4개 focused test가
  통과했다.
- `:commerce-order-lifecycle-fulfillment:test --max-workers=1`는 46개 테스트를
  모두 통과했다.
- root `detekt`, `scripts/smoke-validate.sh stale-check`, `git diff --check`가
  통과했다.

## Future Guidance

raw `require(...)`를 일괄 치환하지 않는다. 먼저 released API의 실제 signature와
caller-facing 진단·정밀도·security contract를 확인하고, 단순 helper parity가 있는
경우에만 전환한다.
