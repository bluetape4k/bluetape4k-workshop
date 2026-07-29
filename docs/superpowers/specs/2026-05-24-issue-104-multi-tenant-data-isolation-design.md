# Issue #104 - 다중 테넌트 데이터 격리 워크샵 설계

**날짜**: 2026-05-24
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/104
**부모 에픽**: #76
**백로그 추적기**: #92
**상태**: 초안

## 목표

리포지토리 쿼리, 캐시 키, 잠금 키, 속도 제한 버킷 및 지표 태그 전반에 걸쳐 테넌트 범위 데이터 격리를 입증하는 고급 워크숍 예제를 추가합니다.

## 범위

- 새로운 Spring Boot 워크숍 모듈: `spring-boot/multi-tenant-data-isolation`.
- H2 데이터베이스에 대한 테넌트 인식 Exposed JDBC 저장소.
- 의도적으로 테넌트 범위를 생략하고 테스트에서 누출 위험을 보여주는 기준 repository/cache 경로입니다.
- 테넌트 접두사가 붙은 캐시 키, 잠금 키, 속도 제한 버킷 키.
- 테넌트 태그가 지정된 Micrometer 카운터.
- README Bluetape4k 우선 설명 및 기능표 포함.

## 논골

- 생산 인증 또는 승인.
- 실제 Redis 지원 분산 잠금.
- 완전한 HTTP API 보안 통합.
- 공유 종속성 거버넌스 변경.

## 설계

모듈은 예제를 작고 결정적으로 유지합니다.

1. `TenantId`은 정규화된 테넌트 식별자를 나타냅니다.
2. `InvoiceTable`은 모든 행에 `tenant_id`을 저장합니다.
3. `TenantInvoiceRepository`은 bluetape4k Exposed `LongJdbcRepository` 패턴을 구현하고 테넌트 범위 메서드를 추가합니다.
4. `UnsafeInvoiceRepository`은(는) 테넌트 조건자 없이 동일한 테이블을 사용하여 실패한 기본 시나리오를 제공합니다.
5. `TenantKeyFactory`은 테넌트 접두사를 사용하여 캐시, 잠금 및 속도 제한 키를 구축합니다.
6. `TenantInvoiceService`은 저장소, 캐시, 잠금, 속도 제한 및 메트릭 동작을 구성합니다.
7. 워크샵 record/data 클래스는 `Serializable`을 구현하고 `serialVersionUID`을 정의합니다.
8. 잠금 동작은 키별 인메모리 잠금을 사용하고, 속도 제한 동작은 `TenantKeyFactory`으로 키가 지정된 고정 창 내 메모리 버킷을 사용합니다. 실제 분산 잠금 의미 체계와 Bucket4j 어댑터는 명시적으로 이 모듈의 범위를 벗어납니다.

## 중고 Bluetape4k 기능

| 기능 | Module/artifact | 코드 참조 | 혜택 |
|---|---|---|---|
| Exposed 저장소 도우미 | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc` | `TenantInvoiceRepository : LongJdbcRepository` | bluetape4k 저장소 기본값을 재사용하고 Exposed 행 매핑을 명시적으로 유지합니다. |
| Spring Boot 지원 | `io.github.bluetape4k:bluetape4k-spring-boot4-core` | 모듈 종속성 | Bluetape4k Spring Boot 4 워크숍 모듈에 맞춰 예시를 유지합니다. |
| 로깅 | `io.github.bluetape4k:bluetape4k-logging` | service/repository 동반 로거 | 기존 로깅 규칙을 사용합니다. |
| 메트릭 브리지 | `io.github.bluetape4k:bluetape4k-micrometer` | 세입자 태그가 지정된 `MeterRegistry` 카운터 | 사용자 지정 측정항목 래퍼 없이 테넌트 인식 관측 가능성을 표시합니다. |
| 테스트 런타임 | `io.github.bluetape4k:bluetape4k-junit5` | 모듈 테스트 종속성 | 공유 테스트 수명 주기 규칙에 맞게 새 모듈을 유지합니다. |
| 테스트 어설션 | `io.github.bluetape4k:bluetape4k-assertions` | 격리 테스트 | 테스트를 생태계와 일관되게 유지합니다. |

## 수락 기준

- 기준 누출 테스트는 테넌트 범위가 생략되면 테넌트 간 데이터가 누출될 수 있음을 보여줍니다.
- 테넌트 범위 리포지토리 읽기 및 쓰기는 테넌트 경계를 넘을 수 없습니다.
- 캐시 적중은 테넌트별로 격리됩니다.
- Lock/rate-limit 주요 예에는 테넌트 접두사가 포함되며 해당 접두사로 키가 지정된 인메모리 상태를 사용합니다.
- 측정항목에는 테넌트 태그가 포함됩니다.
- README에는 `Used Bluetape4k features` 테이블과 before/after 설명이 포함되어 있습니다.
- 새 모듈에 대한 대상 Gradle 테스트가 통과되었습니다.

## 위험

- 실제 분산 잠금 의미 체계는 범위를 벗어납니다. 테넌트 안전 키잉을 시연하려면 키별 `ReentrantLock` 인스턴스를 사용하세요.
- Bucket4j는 이 모듈의 범위를 벗어납니다. 예제에서는 키 이름 지정에만 속도 제한 종속성을 추가하지 않도록 인메모리 고정 창 제한기를 사용합니다.
- H2 자동 증가 동작은 Exposed 1.3 API으로 확인해야 합니다.

## 리뷰 노트

- Claude 어드바이저 게이트 1: `.omx/artifacts/claude-issue-104-design-20260524152918.md`
- P0/P1 게이트 1의 결과: 이 초안에서 수정되었습니다.
- Claude 어드바이저 게이트 2: `.omx/artifacts/claude-issue-104-design-rerun-20260524153255.md`
- 게이트 2 판정: PASS, P0=0, P1=0.
