# Usage Billing Query Service

[English](README.md) | 한국어

`usage-billing-query-service`는 usage-billing 예제의 조회 경계입니다. 네 개의
public Kafka topic을 모두 소비해 로컬 projection을 만들고 tenant summary와
operator 복구 진단을 제공합니다. 금융 command를 소유하지 않으며 원본 event를
다시 쓰지 않습니다.

![Usage billing 서비스 경계](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.ko.svg)

## 책임

- `PriceActivated`, `UsageAccepted`, `UsageCorrected`, `ChargeRated`,
  `AdjustmentPosted`, `InvoiceIssued`, `InvoiceCorrectionIssued`를 decode합니다.
- payload digest를 검증한 뒤 schema version `1`, `2`만 허용합니다.
- event마다 durable inbox 결정, projection, checkpoint를 기록합니다.
- 영구적인 contract 오류는 quarantine하고 서로 무관한 valid event의 처리는
  계속합니다.
- immutable source envelope를 변경하지 않고 감사 가능한 redrive request를
  기록합니다.

로컬 PostgreSQL이 read model, inbox, checkpoint, quarantine event, redrive audit의
권위입니다. Kafka offset은 durable inbox 또는 quarantine 결정 이후에만
commit합니다.

## HTTP contract

애플리케이션은 stateless basic authentication과 tenant/role authority를
사용합니다.

| Method와 path | Authority | 결과 |
| --- | --- | --- |
| `GET /api/v1/tenants/{tenantId}/query/summary` | `TENANT_{tenantId}` | 대상 tenant의 applied event count와 checkpoint |
| `GET /api/v1/operator/query-recovery` | `OPERATOR` role | quarantine 및 복구 조회 |
| `POST /api/v1/operator/query-recovery/quarantine/{eventId}/redrive` | `OPERATOR` role 및 `X-Correlation-Id` | 감사 가능한 redrive request |
| `GET /actuator/health`, `GET /actuator/info` | public | health와 build 정보 |

`/actuator/metrics/**`와 `/api/v1/operator/**`는 `OPERATOR`가 필요합니다.
tenant endpoint는 인증 후 대상 tenant 권한을 검사하며, 그 외 request는
거부합니다. 서비스가 stateless이므로 CSRF는 비활성화되어 있습니다.

## projection과 복구

`QueryInboundEventDecoder`는 지원하지 않는 event type, schema version, 누락된
field, digest 불일치를 `PermanentQueryInboundException`으로 거부합니다.
`QueryInboxService`는 event ID로 deduplicate하고 로컬 projection을 적용합니다.
`QueryRecoveryService.redrive`는 actor와 correlation ID를 기록할 뿐 원본 event를
수정하거나 재생성하지 않습니다. operator는 별도의 replay 전에 보존된 source에서
immutable envelope를 가져와야 합니다.

관련 source: [`QueryInboundEventDecoder`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/messaging/QueryKafkaConsumer.kt),
[`QueryInboxService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/application/QueryInboxService.kt),
[`QueryRecoveryService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/application/QueryRecoveryService.kt),
[`QueryControllers`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/web/QueryControllers.kt).

## 검증 실행

```bash
./gradlew :commerce-usage-billing-query-service:test --max-workers=1
./gradlew :commerce-usage-billing-query-service:integrationTest --max-workers=1
```

기본 suite는 container 없이 decoder compatibility, inbox deduplication, metrics,
recovery audit, security, repository contract를 검증합니다. integration suite는
PostgreSQL로 projection/checkpoint durability와 quarantine persistence를
검증합니다.

## 관련 서비스

- [`usage-billing-meter-service`](../usage-billing-meter-service/),
  [`usage-billing-usage-service`](../usage-billing-usage-service/),
  [`usage-billing-billing-service`](../usage-billing-billing-service/),
  [`usage-billing-invoice-service`](../usage-billing-invoice-service/)가 여기서
  projection하는 topic을 발행합니다.
- [`usage-billing-microservices-composition-tests`](../usage-billing-microservices-composition-tests/)
  가 전체 경계에서 tenant isolation, poison event quarantine, schema evolution,
  operator redrive audit를 검증합니다.
