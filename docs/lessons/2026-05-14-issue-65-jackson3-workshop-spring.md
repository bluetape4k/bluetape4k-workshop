# Issue 65 Jackson3 Workshop Spring 모듈

## Context

일부 workshop 모듈은 여전히 `bluetape4k-jackson2`를 참조했다. Spring Boot 4
모듈은 upstream 지원이 있는 곳에서는 Jackson3를 우선해야 한다.

## Decision

지원되는 Spring workshop 의존성을 migration하고 `spring-data/elasticsearch`를
Jackson3로 정리한다. Quarkus Jackson extension 모듈은 로컬 소스에서 아직
Jackson2 API를 노출하므로 그대로 둔다.

## Outcome

Spring Kafka reply 예제와 Spring Data Elasticsearch 예제는 더 이상
`bluetape4k-jackson2`에 의존하지 않는다. version catalog의 중복
`bluetape4k-exposed-jdbc` alias는 모듈 검증을 시작하기 전에 모든 Gradle
configuration을 막고 있었으므로 제거했다.

## Verification

- `./gradlew :messaging-kafka-reply:testClasses :spring-data-elasticsearch:testClasses`

## Future Notes

의존성 migration 중 Gradle이 task 선택 전에 실패하면 version catalog를 먼저
확인한다. 관련 없어 보이는 중복 alias가 모듈 수준 Jackson 호환성 결과를
가릴 수 있다.
