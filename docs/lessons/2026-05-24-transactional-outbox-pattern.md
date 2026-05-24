# Transactional Outbox Pattern — Issue #99

## Context

Adding a workshop example showing how to atomically publish domain events to Kafka using the Transactional Outbox pattern.

## Problem

Naive approach: write to DB then publish to Kafka — if Kafka fails after DB commit, the event is lost. If DB fails after Kafka send, duplicate events occur.

## Solution (Outbox Pattern)

1. Write domain state + outbox event in **one DB transaction**
2. Background scheduler polls `outbox_events` table for PENDING events
3. Publishes to Kafka, marks event PUBLISHED
4. On failure: increment retryCount; after MAX_RETRY → DEAD_LETTER

## Key Decisions

- **`@MockkBean(relaxed = true)`** instead of `@MockkSpyBean` — Spring Boot auto-configured `KafkaTemplate` uses type erasure; spy cannot match `KafkaTemplate<String, String>` by exact generic type
- **`tools.jackson.databind.ObjectMapper`** — Jackson 3.x changed package from `com.fasterxml.jackson` to `tools.jackson`; must import from correct package
- **`@Bean fun objectMapper()`** — Spring Boot 4 starter (`webmvc.lib` alias) does not always auto-configure `tools.jackson.databind.ObjectMapper`; explicit bean avoids `UnsatisfiedDependencyException`
- **Single `.where { cond1 and cond2 }`** — Exposed v1 `andWhere {}` returns a type that breaks `.map { it[...] }` receiver inference; combine all conditions in one `.where {}` block

## Verification

```
7 tests passing (10.7s)
./gradlew :messaging-transactional-outbox:test
```
