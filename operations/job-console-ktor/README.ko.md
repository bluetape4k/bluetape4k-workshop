# Ktor 작업 콘솔 어댑터

[English](README.md) | 한국어

이 Java 25 어댑터는 Ktor Netty, Ktor SSE, 애플리케이션이 소유하는 poller
scope, 공유 운영 UI로 core 계약을 노출합니다. Blocking JDBC core 호출은
`Dispatchers.IO`에서 실행하고 request cancellation은 다시 던집니다.

## 안전 경계

route를 사용하려면 `JOB_CONSOLE_DEMO=true`가 필요합니다.
`X-Demo-Tenant`, `X-Demo-Submitter`, `X-Demo-Operator`는 신뢰하는 데모
헤더이지 운영 인증이 아닙니다. 모든 SSE 알림 뒤에도 REST 스냅샷이 권위를
유지합니다.

## 실행

```bash
export POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=postgres
export JOB_CONSOLE_DEMO=true
./gradlew :operations-job-console-ktor:run
```

## 검증

```bash
./gradlew :operations-job-console-ktor:test
./gradlew :operations-job-console-ktor:integrationTest --max-workers=1
```
