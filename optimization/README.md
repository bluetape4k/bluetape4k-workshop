# Optimization Workshops

[한국어](README.ko.md) | English

The `optimization/` group contains application-owned planning and optimization
examples. Every module in this group uses the Java 25 toolchain so blocking
provider HTTP calls and JDBC work can run with the Bluetape JDK 25 virtual-thread
runtime.

| Module | Purpose | Infrastructure |
|---|---|---|
| [`planning-contracts`](planning-contracts/) | Provider-neutral planning submission, PostgreSQL inbox/outbox convergence, callback idempotency, and final aggregate-version revalidation | PostgreSQL + WireMock (Testcontainers) |
| [`field-service-dispatch`](field-service-dispatch/) | Synthetic Field Service dispatch with deterministic planning, proposal approval, worker-route CAS confirmation, and a redacted browser console | PostgreSQL (Testcontainers) |

Run the group validation with:

```bash
./scripts/smoke-validate.sh optimization
```

`bluetape4k-dependencies:1.4.0` remains the sole version authority for all
published Bluetape modules. Optimization examples do not import an individual
library BOM or pin a Bluetape module version.
