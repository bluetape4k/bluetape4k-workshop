# Spring 작업 콘솔 어댑터

[English](README.md) | 한국어

이 Java 25 어댑터는 Spring Boot MVC, `SseEmitter`, scheduled outbox polling,
애플리케이션이 소유하는 virtual-thread worker executor, 공유 운영 UI를 통해
core 계약을 노출합니다.

## 안전 경계

route는 `demo` profile에서만 존재합니다. `X-Demo-Tenant`,
`X-Demo-Submitter`, `X-Demo-Operator`는 신뢰하는 데모 헤더이지 운영 인증이
아닙니다. 모든 SSE 알림 뒤에도 REST 스냅샷이 권위를 유지합니다.

## 실행

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/postgres
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
export SPRING_PROFILES_ACTIVE=demo
# 선택 사항인 취소 알림 가속 경로:
# export JOB_CONSOLE_REDIS_URI=redis://localhost:6379
./gradlew :operations-job-console-spring:bootRun
```

## 검증

```bash
./gradlew :operations-job-console-spring:test
./gradlew :operations-job-console-spring:integrationTest --max-workers=1
```
