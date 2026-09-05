# Issue #892 설계 리뷰 결과

## 결과

PASS. 독립 리뷰의 P0/P1은 모두 0건이다.

## 고정한 경계

- Fast path는 exact `GlobalId`, `skip=0`, non-aggregate, filter 없음이라는 명시적 allowlist다.
- Redis 구간은 inclusive negative index `range(-limit, -1)` 한 번으로 읽는다.
- Allowlist 밖의 QueryParams는 upstream repository에 위임한다.
- `getHistory(id)` JVM descriptor는 유지하고 결과 ordering migration을 문서화한다.
- Limit validation에는 identifier나 snapshot payload를 포함하지 않는다.
- Empty history는 존재 확인 수단이 아니며 외부 API가 raw snapshot을 노출할 때 authorization과
  redaction을 적용한다.

## 구현 후 필수 증거

- Redis decode count와 newest revision/type
- Empty/short/default/max/invalid history
- Unsupported query fallback
- Kotlin/Java reflection overload
- BOM-only dependency resolution과 hosted exact-head CI
