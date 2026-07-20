# Ktor Job Console Adapter

[한국어](README.ko.md) | English

This Java 25 adapter exposes the shared contract through Ktor Netty, Ktor SSE,
an application-owned poller/worker scope, and the shared operations UI.
Blocking JDBC and worker calls run on `Dispatchers.IO`, and coroutine
cancellation is rethrown.

## Safety boundary

Routes require `JOB_CONSOLE_DEMO=true`. `X-Demo-Tenant`,
`X-Demo-Submitter`, and `X-Demo-Operator` are trusted demo headers, not
production authentication. REST snapshots remain authoritative after every SSE
notification.

## Run

```bash
export POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=postgres
export JOB_CONSOLE_DEMO=true
# Optional advisory cancellation acceleration:
# export REDIS_URI=redis://localhost:6379
./gradlew :operations-job-console-ktor:run
```

## Verify

```bash
./gradlew :operations-job-console-ktor:test
./gradlew :operations-job-console-ktor:integrationTest --max-workers=1
```
