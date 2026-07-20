# Spring Job Console Adapter

[한국어](README.ko.md) | English

This Java 25 adapter exposes the shared contract through Spring Boot MVC,
`SseEmitter`, scheduled outbox polling, an application-owned virtual-thread
worker executor, and the shared operations UI.

## Safety boundary

Routes exist only under the `demo` profile. `X-Demo-Tenant`,
`X-Demo-Submitter`, and `X-Demo-Operator` are trusted demo headers, not
production authentication. REST snapshots remain authoritative after every SSE
notification.

## Run

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
export SPRING_PROFILES_ACTIVE=demo
# Optional advisory cancellation acceleration:
# export JOB_CONSOLE_REDIS_URI=redis://localhost:6379
./gradlew :operations-job-console-spring:bootRun
```

## Verify

```bash
./gradlew :operations-job-console-spring:test
./gradlew :operations-job-console-spring:integrationTest --max-workers=1
```
