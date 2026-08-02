# Usage Billing Query Service

[한국어](README.ko.md) | English

`usage-billing-query-service` is the read boundary for the usage-billing
reference. It consumes all four public Kafka topics, builds a local projection,
and exposes tenant summaries plus operator recovery diagnostics. It never owns
a financial command or rewrites an original event.

![Usage billing service boundaries](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.png)

[Architecture SVG source](../../docs/images/readme-diagrams/usage-billing-service-boundaries-01.svg)

## Responsibility

- decode `PriceActivated`, `UsageAccepted`, `UsageCorrected`, `ChargeRated`,
  `AdjustmentPosted`, `InvoiceIssued`, and `InvoiceCorrectionIssued`;
- accept schema versions `1` and `2` only after verifying the payload digest;
- record a durable inbox decision, projection, and checkpoint per event;
- quarantine permanent contract failures while allowing unrelated valid events
  to continue;
- record an auditable redrive request without mutating the immutable source
  envelope.

The local PostgreSQL database owns the read model, inbox, checkpoints,
quarantine events, and redrive audits. Kafka offsets are committed only after a
durable inbox or quarantine decision.

## HTTP contract

The application uses stateless basic authentication and tenant/role authorities.

| Method and path | Authority | Result |
| --- | --- | --- |
| `GET /api/v1/tenants/{tenantId}/query/summary` | `TENANT_{tenantId}` | applied event count and checkpoint for the target tenant |
| `GET /api/v1/operator/query-recovery` | `OPERATOR` role | quarantine and recovery view |
| `POST /api/v1/operator/query-recovery/quarantine/{eventId}/redrive` | `OPERATOR` role plus `X-Correlation-Id` | auditable redrive request |
| `GET /actuator/health`, `GET /actuator/info` | public | health and build information |

`/actuator/metrics/**` and `/api/v1/operator/**` require `OPERATOR`. Tenant
endpoints are authenticated and target-tenant authorization is enforced; other
requests are denied. CSRF is disabled because the service is stateless.

## Projection and recovery

`QueryInboundEventDecoder` rejects unsupported event types, schema versions,
missing fields, and digest mismatches as `PermanentQueryInboundException`.
`QueryInboxService` deduplicates by event ID and applies the local projection.
`QueryRecoveryService.redrive` records the actor and correlation ID; it does
not edit or regenerate the original event. An operator must retrieve the
immutable envelope from its retained source before a separate replay operation.

Relevant source: [`QueryInboundEventDecoder`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/messaging/QueryKafkaConsumer.kt),
[`QueryInboxService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/application/QueryInboxService.kt),
[`QueryRecoveryService`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/application/QueryRecoveryService.kt),
and [`QueryControllers`](src/main/kotlin/io/bluetape4k/workshop/commerce/usagebilling/query/web/QueryControllers.kt).

## Run the evidence

```bash
./gradlew :commerce-usage-billing-query-service:test --max-workers=1
./gradlew :commerce-usage-billing-query-service:integrationTest --max-workers=1
```

The default suite is container-free and covers decoder compatibility, inbox
deduplication, metrics, recovery audit, security, and repository contracts.
The integration suite uses PostgreSQL to prove projection/checkpoint durability
and quarantine persistence.

## Related services

- [`usage-billing-meter-service`](../usage-billing-meter-service/),
  [`usage-billing-usage-service`](../usage-billing-usage-service/),
  [`usage-billing-billing-service`](../usage-billing-billing-service/), and
  [`usage-billing-invoice-service`](../usage-billing-invoice-service/) publish
  the topics projected here.
- [`usage-billing-microservices-composition-tests`](../usage-billing-microservices-composition-tests/)
  proves tenant isolation, poison-event quarantine, schema evolution, and
  operator redrive audit across the complete boundary.
