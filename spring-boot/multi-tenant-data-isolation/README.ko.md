# 멀티 테넌트 데이터 격리

[English](README.md) | 한국어

이 모듈은 tenant-safe read/write, cache key, lock key, rate-limit bucket,
Micrometer tag와 `2.0.0` tenant context carrier를 함께 검증합니다. 실행 환경은
H2와 인메모리 helper로 가볍게 유지해서 테스트에서 격리 계약을 바로 확인할 수 있게 했습니다.

## 아키텍처 다이어그램

![멀티 테넌트 데이터 격리 아키텍처 다이어그램](../../docs/images/readme-diagrams/spring-boot-multi-tenant-data-isolation-readme-architecture-01.png)

## 격리 시나리오

![Tenant Data Isolation scenario](../../docs/images/readme-diagrams/spring-boot-multi-tenant-data-isolation-readme-scenario-01.png)

테넌트별 데이터 접근, 캐시 키, 락 키, rate-limit 버킷, 메트릭 태그를 안전하게 분리하는 고급 Spring Boot 워크숍입니다.

## 개요

Repository 쿼리, 캐시 키, 락 키, rate-limit 버킷이 공용 리소스 ID만 사용하면 테넌트 간 데이터가 새어 나갈 수 있습니다. 이 모듈은 H2, 인메모리 lock, fixed-window rate-limit bucket으로 실행 환경을 가볍게 유지하고, 테스트로 격리 경계를 증명합니다.

## 주요 구성

| 클래스 | 역할 |
|---|---|
| `TenantId` | 모든 격리 경계에 사용하는 정규화된 테넌트 값 |
| `InvoiceTable` | 필수 `tenant_id` 컬럼을 가진 공유 테이블 |
| `TenantInvoiceRepository` | tenant predicate를 강제하는 안전한 `LongJdbcRepository` 구현 |
| `UnsafeInvoiceRepository` | tenant predicate를 빠뜨린 baseline 경로 |
| `TenantKeyFactory` | 테넌트 prefix가 붙은 캐시, 락, rate-limit 키 생성 |
| `TenantInvoiceService` | repository, cache, lock, rate-limit, metrics 경로 조합 |
| `TenantContextCarrierService` | ThreadLocal, ScopedValue, Reactor context를 안전한 service에 연결 |

## 사용된 Bluetape4k 기능

| 기능 | 모듈/아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| Exposed JDBC repository helper | `bluetape4k-exposed-jdbc` | `TenantInvoiceRepository : LongJdbcRepository` | Bluetape4k repository 기본 동작을 재사용하면서 tenant predicate를 명시 |
| Spring Boot support | `bluetape4k-spring-boot4-core` | `build.gradle.kts` | Spring Boot 4 워크숍 모듈과 일관성 유지 |
| Logging | `bluetape4k-logging` | `KLogging` companion | 공통 Bluetape4k 로깅 관례 사용 |
| Metrics bridge | `bluetape4k-micrometer` | `TenantMetrics` | bounded tenant fingerprint가 붙은 Micrometer counter를 Bluetape4k 의존성 집합 안에서 사용 |
| Tenant context | `bluetape4k-tenant` | `TenantContextCarrierService` | blocking/virtual-thread 경계에서 lexical ThreadLocal·ScopedValue tenant 공급 |
| Reactor tenant context | `bluetape4k-tenant-reactor` | `TenantContextCarrierService` | scheduler hop에서도 tenant 상태를 immutable subscription scope로 유지 |
| JUnit support and assertions | `bluetape4k-junit5`, `bluetape4k-assertions` | `TenantIsolationTest` | 저장소 표준 테스트 lifecycle과 matcher 사용 |

## Before / After

### Repository

```kotlin
// Before: ID만 조회하면 다른 테넌트 row가 노출될 수 있습니다.
InvoiceTable.selectAll()
    .where { InvoiceTable.id eq invoiceId }
    .firstOrNull()

// After: tenant와 ID가 모두 맞아야 합니다.
InvoiceTable.selectAll()
    .where {
        (InvoiceTable.tenantId eq tenantId.value) and (InvoiceTable.id eq invoiceId)
    }
    .firstOrNull()
```

### 캐시, 락, Rate Limit 키

```kotlin
// Before
invoice:42

// After
tenant:tenant-alpha:invoice:42
tenant:tenant-alpha:lock:invoice:42
tenant:tenant-alpha:rate-limit:reader
```

## TenantContext carrier (2.0.0)

`TenantContextCarrierService`는 기존의 명시적인 `TenantId` repository predicate를
유지하면서 실행 경계에서 값을 안전하게 공급합니다. 의존성은 루트
`platform(libs.bluetape4k.dependencies)` BOM만 사용하며,
`bluetape4k-tenant`와 `bluetape4k-tenant-reactor` alias에는 버전을 적지 않습니다.

### Spring MVC / blocking request

```kotlin
carrier.withMvcTenant(tenantId) {
    carrier.findInvoiceWithMvcTenant(invoiceId)
}
```

`ThreadLocalTenantContext`는 중첩 scope의 이전 값을 복원하고 정상 종료나 예외 뒤에
binding을 제거합니다. binding이 없으면 `MissingTenantContextException`을 그대로
전달하며 기본 tenant로 보정하지 않습니다.

### Virtual thread

```kotlin
carrier.withVirtualThreadTenant(tenantId) {
    carrier.findInvoiceWithVirtualThreadTenant(invoiceId)
}
```

`ScopedValueTenantContext`는 lexical scope이므로 JDK 25 virtual thread 안에서 안전하게
사용할 수 있습니다. 다음 task는 unbound로 시작하여 executor가 stale tenant를 실수로
상속하지 않습니다.

### Reactor

```kotlin
carrier.findInvoiceWithReactorTenant(tenantId, invoiceId)
    .publishOn(Schedulers.parallel())
```

`withReactorTenant` helper는 `ReactorTenantContext.withTenant`로 값을 저장합니다. immutable
`ContextView`는 scheduler hop을 지나도 유지되고 concurrent subscription을 서로
격리합니다. 취소하면 해당 subscription scope만 종료됩니다.
하위 연산자도 tenant를 읽어야 한다면 해당 연산자를 `withReactorTenant`에 전달하는
publisher 안에 구성합니다. `contextWrite`는 upstream 연산자에 scope를 적용합니다.

운영 metric에는 tenant 값 자체 대신 8바이트 SHA-256 `tenant_fingerprint` tag만
사용합니다. 집계에는 안정적으로 사용할 수 있지만 로그나 metric에 tenant 문자열을
노출하지 않습니다.

## 실행

```bash
./gradlew :spring-boot-multi-tenant-data-isolation:test
```

## 테스트가 증명하는 것

- ID만 사용하는 baseline repository/cache 경로는 테넌트 데이터를 누출합니다.
- 테넌트가 다른 invoice ID 조회는 `null`을 반환합니다.
- 테넌트가 다른 write는 invoice 상태를 변경하지 못합니다.
- 캐시 키, 락 키, rate-limit 키가 tenant scope를 포함합니다.
- ThreadLocal과 ScopedValue 중첩은 이전 binding을 복원하고 실패 뒤 정리합니다.
- Reactor context는 scheduler hop을 지나며 concurrent subscription을 격리하고 취소에도 안전합니다.
- metric에는 원문 대신 bounded `tenant_fingerprint`만 노출됩니다.

## Gap Notes

이 모듈은 인메모리 상태로 lock/rate-limit 격리를 보여줍니다. 실제 Redis/Redisson 락,
Bucket4j backend, 분산 트랜잭션, 테넌트별 인증 정책, schema 변경은 의도적으로 제외합니다.
carrier 예제는 요청 경계의 전파와 정리에 집중합니다.
