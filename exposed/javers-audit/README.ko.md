# exposed/javers-audit

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **exposed/javers-audit** 모듈을 실행 가능한 Exposed 데이터 접근 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 시퀀스 다이어그램

JetBrains Exposed JDBC와 H2 인메모리 데이터베이스에 통합한 JaVers 엔티티 변경 이력 감사 예제입니다.

## 아키텍처

![exposed/javers-audit Graphviz 아키텍처 다이어그램](../../docs/images/readme-diagrams/exposed-javers-audit-readme-architecture-01.png)

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 변경 추적 | 모든 `save` / `delete` 호출이 JaVers commit을 만들며, 엔티티 id로 전체 snapshot 이력을 조회할 수 있습니다. |
| Diff 계산 | `diff(old, new)`가 typed `ValueChange` 항목을 포함한 구조화된 `Diff`를 반환하므로 수동 audit table이 필요 없습니다. |
| 인메모리 저장소 | JaVers 내장 인메모리 repository를 사용하므로 테스트나 데모에 외부 인프라가 필요 없습니다. |
| Exposed persistence | Exposed `ProductTable`에 upsert/delete를 함께 수행해 audit 이력이 일반 JDBC 저장과 어떻게 공존하는지 보여줍니다. |

## Before / After - 수동 Audit Table vs JaVers

**Before (manual audit log table)**

```sql
CREATE TABLE product_audit_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT       NOT NULL,
    changed_at TIMESTAMP    NOT NULL,
    author     VARCHAR(100) NOT NULL,
    old_price  DECIMAL(19,4),
    new_price  DECIMAL(19,4),
    old_cat    VARCHAR(100),
    new_cat    VARCHAR(100)
);
```

컬럼이 변경될 때마다 audit table에 INSERT를 추가해야 합니다. 또한 이전 값과 새 값을 직접 비교하고, entity와 schema를 계속 동기화해야 합니다.

**After (JaVers)**

```kotlin
val javers = JaversBuilder.javers().build()
javers.commit("alice", product)           // snapshot stored automatically
val history = javers.findSnapshots(
    QueryBuilder.byInstanceId(productId, Product::class.java).build()
)
val diff = javers.compare(oldProduct, newProduct)
val priceChanges = diff.changesByType<ValueChange>()
    .filter { it.propertyName == "price" }
```

JaVers는 모든 속성을 자동으로 추적하고, 조회 가능한 snapshot tree를 생성하며, typed diff 접근을 제공합니다. 별도의 schema 유지보수는 필요 없습니다.

## 사용법

```kotlin
// Build JaVers with in-memory repository
val javers = JaversBuilder.javers().build()
val service = ProductAuditService(javers)

// Create and persist a product
val product = Product(id = 1L, name = "Widget", price = BigDecimal("9.99"), category = "Tools")
service.save("alice", product)

// Update price and persist
val updated = product.copy(price = BigDecimal("12.99"))
service.save("alice", updated)

// Query full history
val history = service.getHistory(1L)          // 2 snapshots: INITIAL + UPDATE

// Compute diff between two versions
val diff = service.diff(product, updated)
val changes = diff.changesByType<ValueChange>()  // [ValueChange: price 9.99 → 12.99]

// Latest snapshot
val latest = service.getLatestSnapshot(1L)

// Soft-delete with terminal snapshot
service.delete("alice", updated)
```

## 설정

외부 설정은 필요 없습니다. 원하는 `Javers` 인스턴스를 `ProductAuditService`에 전달하면 됩니다. 운영 환경에서는 인메모리 repository를 `bluetape4k-javers` 라이브러리의 JDBC 또는 Redis 기반 repository로 교체하세요.

## 의존성

```kotlin
implementation("io.github.bluetape4k.javers:javers-core")  // bluetape4k JaVers integration
implementation("org.jetbrains.exposed:exposed-core")
implementation("org.jetbrains.exposed:exposed-jdbc")
runtimeOnly("com.h2database:h2")
```
