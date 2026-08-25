# Clinic Appointment Solver

This Spring Boot reference application embeds `ai.timefold.solver:timefold-solver-core`
to optimize a synthetic clinic appointment schedule. It models providers, rooms,
equipment, operating windows, requested slots, and confirmed appointments pinned by
Timefold.

## Boundary

- The `demo` profile runs locally with a deterministic in-memory fixture. It does not
  contact Timefold Platform, require a tenant/API key, or send a webhook.
- The Solver returns a read-only proposal with `HardSoftScore`, assignments, and closed
  reason codes. It never commits an appointment, writes a database row, or publishes an
  external command.
- Hard constraints cover provider qualification/availability, clinic and requested
  windows, provider/room/equipment compatibility, overlapping resources, and complete
  assignment. Soft constraints prefer the requested provider and slot and reduce same-day
  provider load concentration.
- The fixture is synthetic and contains no PHI, EHR, patient name, diagnosis, insurance,
  or clinical advice.
- PostgreSQL/CAS, slot hold and expiry, waitlist transitions, browser authentication, and
  Timefold Platform integration remain follow-up scope for Issue #528.

## Run and verify

```bash
./gradlew :optimization-clinic-appointment-solver:test --max-workers=1 --console=plain
./gradlew :optimization-clinic-appointment-solver:bootRun
curl -s http://127.0.0.1:8080/api/clinic-appointments/demo
```

The Solver uses a fixed step-count termination and stable entity difficulty comparator so
the same fixture produces the same sorted proposal and score. `ConstraintVerifier` tests
cover each hard/soft rule without Docker or external credentials.

The module uses the root `bluetape4k-dependencies` BOM. The Timefold alias is versionless;
the BOM selects the resolved Timefold version. No individual Timefold BOM or Bluetape
module version is pinned.
