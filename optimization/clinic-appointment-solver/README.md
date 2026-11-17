# Clinic Appointment Solver

[한국어](README.ko.md) | English

This Spring Boot reference application embeds `ai.timefold.solver:timefold-solver-core`
to optimize a synthetic clinic appointment schedule. It models providers, rooms, equipment, operating windows, requested slots, and confirmed appointments pinned by Timefold.

## Why this example exists

Clinic scheduling is a resource-allocation problem: a requested slot is useful
only when a qualified provider, room, and equipment can all be available at the
same time. This example lets a team inspect how those business rules compete
before introducing patient data or an external planning platform.

The Solver returns a deterministic proposal and score; the application does
not confirm an appointment or write a booking. Synthetic fixtures, fixed
termination, and a stable difficulty comparator are deliberate design choices
so a reader can compare the same hard/soft constraints repeatedly. PHI, EHR,
waitlists, expiry, authentication, and Timefold Platform integration remain
explicit follow-up boundaries.

The `test` command in **Run and verify** is the test-backed proof. Its
`ConstraintVerifier` cases exercise each hard and soft rule without Docker or
external credentials; the HTTP `bootRun` path is a manual inspection of the
read-only proposal, not a production scheduling claim.

## Boundary

- The `demo` profile runs locally with a deterministic in-memory fixture. It does not contact Timefold Platform, require a tenant/API key, or send a webhook.
- The Solver returns a read-only proposal with `HardSoftScore`, assignments, and closed reason codes. It never commits an appointment, writes a database row, or publishes an external command.
- Hard constraints cover provider qualification/availability, clinic and requested windows, provider/room/equipment compatibility, overlapping resources, and complete assignment. Soft constraints prefer the requested provider and slot and reduce same-day provider load concentration.
- The fixture is synthetic and contains no PHI, EHR, patient name, diagnosis, insurance, or clinical advice.
- PostgreSQL/CAS, slot hold and expiry, waitlist transitions, browser authentication, and Timefold Platform integration remain follow-up scope for Issue #528.

## Run and verify

```bash
./gradlew :optimization-clinic-appointment-solver:test --max-workers=1 --console=plain
./gradlew :optimization-clinic-appointment-solver:bootRun
curl -s http://127.0.0.1:8080/api/clinic-appointments/demo
```

The Solver uses a fixed step-count termination and stable entity difficulty comparator so the same fixture produces the same sorted proposal and score. `ConstraintVerifier` tests cover each hard/soft rule without Docker or external credentials.

The module uses the root `bluetape4k-dependencies` BOM. The Timefold alias is versionless; the BOM selects the resolved Timefold version. No individual Timefold BOM or Bluetape module version is pinned.
