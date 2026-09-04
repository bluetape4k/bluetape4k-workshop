# Issue #877: 2.0.0 TenantContext carrier 적용

## 결정

기존 `spring-boot/multi-tenant-data-isolation`의 명시적인 tenant predicate와
key factory는 유지하고, 호출 경계에서 tenant 값을 공급하는 carrier만
`bluetape4k-dependencies:2.0.0` 기준으로 연결했다.

- Spring MVC/blocking 경계: `ThreadLocalTenantContext`
- JDK 25 virtual thread 경계: `ScopedValueTenantContext`
- Reactor 경계: `ReactorTenantContext`

각 carrier는 scope를 직접 열고 닫는다. 누락 tenant는
`MissingTenantContextException`으로 거부하며 임의 기본값을 사용하지 않는다.
중첩 scope는 바깥 값을 복원하고, ThreadLocal은 예외 뒤에 `remove`된다. Reactor
context는 immutable하고 subscription-local이므로 scheduler hop 뒤에도 동시
요청을 섞지 않으며 취소가 전역 상태를 남기지 않는다.

## 관측 경계

기존 metrics 경로가 tenant 원문을 tag로 전송하지 않도록 8바이트 SHA-256
`tenant_fingerprint`로 바꿨다. 집계에 필요한 안정성은 유지하면서 로그와 metric에
tenant 문자열이 직접 노출되는 경로를 제거했다.

## 검증

`TenantContextCarrierExampleTest`는 다음 계약을 고정한다.

1. MVC ThreadLocal의 repository 연결, 중첩 복원, 동시 요청 격리, 실패 후 정리
2. virtual thread ScopedValue의 lexical 중첩과 다음 task의 unbound 시작
3. Reactor scheduler hop과 concurrent subscription 격리
4. Reactor cancellation 뒤 carrier context 누수 없음
5. metrics에 raw tenant가 없고 bounded fingerprint만 존재

실행 명령:

```bash
./gradlew :spring-boot-multi-tenant-data-isolation:test
```

## 범위 밖

분산 트랜잭션, 테넌트별 인증 정책, 저장소 schema 변경, 실제 Redis/Redisson
lock과 Bucket4j backend는 다루지 않는다. 실제 MVC/WebFlux filter에서 header나
인증 주체를 tenant로 해석하는 정책은 별도 보안 경계에서 검토해야 한다.
