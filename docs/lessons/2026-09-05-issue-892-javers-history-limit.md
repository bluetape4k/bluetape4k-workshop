# Issue #892 JaVers history limit pushdown

## Context

`bluetape4k-dependencies` 2.0.0의 JaVers 예제는 `QueryBuilder.limit`과 default 100,
newest-first history 계약을 제공한다. 기존 workshop 서비스는 전체 snapshot을 조회한 뒤
oldest-first로 정렬했고, Redis repository는 exact-instance query에서도 모든 key와 snapshot을
읽은 뒤 limit을 적용했다.

## Decision or Finding

- 두 서비스의 `getHistory(id, limit = 100)`은 `1..100`을 검증하고 limit을 query에 전달한다.
- Source/JVM 한 인자 overload는 유지하되 ordering은 newest-first로 migration한다.
- Redis 예제는 filter 없는 exact-instance query에만 `range(-limit, -1)` adapter를 사용한다.
- Skip, aggregate, author/date/version, commit/property/type filter는 upstream repository로
  fallback해 query 의미를 보존한다.
- Empty history는 entity 존재 여부를 증명하지 않으며 raw snapshot의 외부 노출은 caller가
  authorization/redaction을 담당한다.

## Outcome

Redis fast path는 요청한 byte 구간만 읽고 decode하며, in-memory approval 예제도 같은 bounded
newest-first API를 제공한다. 기존 Redis schema, snapshot codec, JaVers commit 정책은 바뀌지
않는다.

## Verification

- Redis-backed `limit=1/2`에서 decode count 1/2 및 newest revision/type 확인
- Empty/short history, default/100, invalid 0/음수/101, unknown id, JVM overload 확인
- Unsupported query parameter 9종의 delegate fallback과 기존 결과 의미 확인
- 두 module test, detekt, README language/parity, stale-check, ecosystem checker,
  dependency insight, actionlint, `git diff --check`로 검증

## Future Guidance

Query-level limit이 storage read bound를 자동으로 보장한다고 가정하지 않는다. 새로운 persistent
repository 예제는 storage command와 decode/materialization 경계를 별도로 측정한다. Filtered
query까지 Redis pushdown하려면 workshop adapter를 넓히지 말고 upstream repository의 공식
capability로 구현한다. Consumer dependency는 root `bluetape4k-dependencies` BOM 2.0.0과
versionless alias만 사용한다.
