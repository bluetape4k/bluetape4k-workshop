# Issue 65 Jackson3 Workshop Spring Modules

## Context

Some workshop modules still referenced `bluetape4k-jackson2`. Spring Boot 4
modules should prefer Jackson3 where upstream support exists.

## Decision

Migrate supported Spring workshop dependencies and normalize
`spring-data/elasticsearch` to Jackson3. Leave Quarkus Jackson extension modules
unchanged because they still expose Jackson2 APIs in local source.

## Outcome

The Spring Kafka reply example and Spring Data Elasticsearch example no longer
depend on `bluetape4k-jackson2`. The version catalog duplicate
`bluetape4k-exposed-jdbc` alias was removed because it blocked every Gradle
configuration before module validation could start.

## Verification

- `./gradlew :messaging-kafka-reply:testClasses :spring-data-elasticsearch:testClasses`

## Future Notes

When Gradle fails before task selection during dependency migration, inspect the
version catalog first; unrelated duplicate aliases can mask module-level
Jackson compatibility results.
