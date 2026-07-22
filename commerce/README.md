# Commerce Examples

[한국어](README.ko.md) | English

This group contains end-to-end commerce workflows whose correctness depends on
multiple independently versioned aggregates, durable application events, and
operator-visible recovery paths.

## Modules

| Module | Focus | Infrastructure |
|--------|-------|----------------|
| [`order-lifecycle-fulfillment`](order-lifecycle-fulfillment/) | Independent order, payment, inventory, fulfillment, and refund lifecycles | PostgreSQL (Testcontainers) |
| [`reservation-control-plane`](reservation-control-plane/) | PostgreSQL-authoritative reservations, idempotent retries, waitlist offers, and expiry | PostgreSQL + Redis (Testcontainers) |
| [`promotion-voucher-campaign`](promotion-voucher-campaign/) | Campaign capacity, voucher allocation/redemption, review, SSE, and reconciliation | PostgreSQL + Redis (Testcontainers) |
| [`pre-generated-voucher-pool`](pre-generated-voucher-pool/) | PostgreSQL-authoritative pre-generated voucher reservation, one-time reveal/replacement, revoke, and reconciliation | PostgreSQL + Redis (Testcontainers) |
| [`concert-ticket-flash-sale`](concert-ticket-flash-sale/) | Waiting-room admission, USER/IP purchase guards, payment/refund recovery, and ticket-safe restock | PostgreSQL + Redis (Testcontainers) |
| [`usage-metering-billing-ledger`](usage-metering-billing-ledger/) | Idempotent usage, time-versioned pricing, restartable close, and immutable ledger/invoice | PostgreSQL (Testcontainers) |
| [`usage-metering-billing-event-sourcing`](usage-metering-billing-event-sourcing/) | Event append/replay/upcasting, snapshots, fenced projection rebuilds, correction, and reconciliation | PostgreSQL (Testcontainers) |

The modules use Java 25 virtual threads for blocking Spring MVC and Exposed JDBC
work. Database concurrency remains bounded by HikariCP; virtual threads increase
request concurrency, not PostgreSQL capacity.

## Run

```bash
./gradlew :commerce-order-lifecycle-fulfillment:test --max-workers=1
./gradlew :commerce-reservation-control-plane:test --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:integrationTest --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest --max-workers=1
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest --max-workers=1
./scripts/smoke-validate.sh commerce
```
