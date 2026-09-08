# Issue #988 Jackson3 공통 기준과 입력 호환성

## Context

Ktor NDJSON과 두 Kafka outbox 예제가 `bluetape4k-jackson3`에 의존하면서도 `jacksonObjectMapper()`를 직접 호출해 mapper 기준을 따로 만들고 있었다.

## Decision or Finding

출력 전용 Ktor 경로는 `Jackson.defaultJsonMapper`를 읽기 전용으로 공유한다. Spring bean은 외부 customizer와 수명주기를 격리하기 위해 `Jackson.createDefaultJsonMapper()`로 새 mapper를 만들고, 기존 raw mapper와 달라지는 trailing comma 허용만 다시 끈다. 교체 전 관찰 결과 unknown property는 허용되고 trailing token과 trailing comma는 거부되므로 이 동작을 회귀 테스트로 고정했다.

## Outcome

세 production callsite의 raw `jacksonObjectMapper()` 호출과 직접 `jackson3.module.kotlin` 의존성을 제거했다. NDJSON wire output과 outbox 입력 호환성은 유지했다.

## Verification

- 공유 mapper 동일 인스턴스와 Unicode/null/collection 직렬화 golden output
- NDJSON 마지막 `\n` 포함 exact output
- 두 Spring mapper의 unknown/trailing token/trailing comma 동작
- 세 모듈 전체 테스트, detekt, dependency graph, README parity, ecosystem reuse gate

## Future Guidance

일반 serialization에는 Bluetape Jackson3 기준을 재사용하되 strict/canonical 경계는 별도 mapper와 별도 계약으로 유지한다. 공유 singleton은 callsite에서 변경하지 않는다.
