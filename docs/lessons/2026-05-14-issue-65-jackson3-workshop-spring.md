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
depend on `bluetape4k-jackson2`.

## Verification

- Blocked: Gradle configuration fails before task selection because
  `gradle/libs.versions.toml` already contains a duplicate
  `bluetape4k-exposed-jdbc` alias.

## Future Notes

Resolve catalog duplicate aliases before validating or expanding workshop-wide
dependency migrations.
