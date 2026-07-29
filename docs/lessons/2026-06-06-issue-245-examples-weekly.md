# Issue 245 Examples Weekly Gate

## 배경

`bluetape4k-workshop`에는 CI와 Nightly workflow가 있었지만, representative
consumer-facing backend scenario를 위한 dedicated weekly Examples workflow는 없었다.
Issue #245는 `bluetape4k-exposed` downstream examples epic과 연결된 별도 gate를
요청했다.

## 결정

- manual dispatch와 path-filtered PR/push trigger를 가진 weekly `Examples` workflow를
  추가한다.
- broad smoke group을 재사용하지 않고 workflow 안에 선택된 module list를 명시적으로
  유지한다.
- Docker contention을 피하기 위해 gate를 H2/default smoke example과 하나의 sequential
  Testcontainers lane으로 나눈다.

## 결과

선택된 matrix는 full CI 또는 Nightly suite를 중복하지 않으면서 data access,
Exposed/R2DBC, Spring Boot cache, Redis cache, Kafka messaging, Jackson serialization,
Resilience4j coroutine example을 포괄한다.

## 검증

- `actionlint .github/workflows/Examples.yml`
- `git diff --check`

## 향후 지침

새 consumer example은 weekly validation에 충분히 대표적이고 안정적일 때만 이 workflow에
추가한다. heavy infrastructure example은 sequential container lane 또는 Nightly에 둔다.
