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

## High-contention evidence

![High-contention profile runner architecture](../../docs/images/readme-diagrams/high-contention-profile-runner-architecture-01.png)

Run the shared core and Spring adapter profiles with a running Docker daemon,
JDK 25, and at least 4 GiB of memory available to Gradle and the containers:

```bash
CI_RUN_ID=developer-ci-001
REFERENCE_RUN_ID=developer-reference-001
./gradlew highContentionCi -PhighContentionRunId="$CI_RUN_ID" --max-workers=1
./gradlew highContentionLocalReference -PhighContentionRunId="$REFERENCE_RUN_ID" --max-workers=1
```

The correctness gate is `highContentionCi`. `highContentionLocalReference`
records environment-specific execution observations; it does not rank
frameworks and does not establish production capacity. Canonical reports are
written under `build/reports/high-contention/<run-id>/`. Use a new run ID for
every command; local-reference execution also requires a clean worktree.

The Spring `DataSource` uses HikariCP, while PostgreSQL remains authoritative
for leases, fencing, checkpoints, deduplication, and terminal state. Toxiproxy
cuts and restores only the advisory Redis path, including old and newly opened
connections; it does not replace PostgreSQL authority or database failover.
