# Issue #524 planning contracts 교훈

## 1. BOM 버전과 라이브러리 버전을 분리해 말한다

`bluetape4k-dependencies:1.3.1`을 사용한다는 말은 Exposed 자체가 `1.3.1`이라는
뜻이 아니다. versionless 좌표를 선언하고 `dependencyInsight`로 실제 선택 결과를
확인해야 한다. 이번 해석 결과는 JetBrains Exposed `1.3.0`, Bluetape Exposed
`1.11.0`이었다.

## 2. Exposed repository는 CRUD 상속뿐 아니라 audit update 계약까지 사용한다

애플리케이션 전용 claim SQL은 repository 안에 둘 수 있지만, auditable table의
상태 전이는 `auditedUpdateAll`/`auditedUpdateById`를 사용해야 한다. PostgreSQL은
일반 `UPDATE ... LIMIT`을 지원하지 않으므로 unique predicate에 불필요한 limit를
붙이지 않는다.

## 3. 외부 호출은 transaction 밖, 상태 전이는 짧은 transaction 안에 둔다

Outbox claim과 완료/실패 갱신은 각각 짧은 transaction이다. provider HTTP는
virtual thread에서 실행하되 JDBC transaction을 열린 채 기다리지 않는다. 이 구조가
pool 고갈과 긴 lock 보유를 동시에 피한다.

## 4. 크기 제한은 전체를 읽은 뒤 검사하면 제한이 아니다

HTTP entity를 문자열로 만든 뒤 byte size를 검사하면 이미 메모리를 소비한 뒤다.
stream에서 `limit + 1`까지만 읽고 초과를 거부해야 한다. callback도 동일한 bounded
byte array를 signature 검증과 parsing에 재사용하면 원본 일치와 메모리 상한을 함께
지킬 수 있다.

## 5. Idempotency key만으로 provider 무결성이 완성되지 않는다

`(provider, event_id)` unique key는 duplicate delivery를 막지만, callback provider가
원 요청 provider와 같은지는 별도 확인해야 한다. 활성 engine과 request provider,
callback과 stored provider를 각각 검증해야 잘못된 adapter 경로가 accepted state를
바꾸지 못한다.

## 6. workshop module 추가는 코드만의 변경이 아니다

settings, root/local README locale pair, AGENTS module map, smoke group, container-backed
workflow, Java toolchain setup을 함께 갱신해야 한다. `stale-check`, `actionlint`, 혼합
Java 21/25 compile을 별도 증거로 남긴다.
