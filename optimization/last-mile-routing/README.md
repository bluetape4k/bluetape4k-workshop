# Last-Mile Routing

[한국어](README.ko.md) | English

This Spring Boot reference application demonstrates synthetic pickup and delivery routing with a fixed travel matrix. PostgreSQL remains authoritative for jobs, route proposals, carrier versions, callbacks, and committed stops. The default deterministic provider is offline and provider-neutral; it does not contact Timefold, OSRM, a map tile service, GPS, or a carrier API.

## Why this example exists

Last-mile planning has a concrete operational tension: pickup precedence,
capacity, time windows, and driver progress must be respected while travel data
or a carrier callback may be late. This example makes that tension observable
with a synthetic matrix and a proposal that must still pass approval-time
version checks.

The solution keeps PostgreSQL as the state authority and puts the provider
behind a normalized `RoutingProvider`. Canonical callback bytes, inbox/outbox
state, ETags, and explicit stale conflicts are design choices that make replay
and operator diagnosis safe; live GPS, geocoding, traffic, and carrier
credentials are outside the boundary.

The `cleanTest`, `test`, `build`, and optimization smoke commands in
**Verification** provide test-backed evidence for these constraints. They do
not measure production route quality or prove a live provider integration.

## Scope and boundary

- Pickup precedes delivery, vehicle capacity, time windows, required skills, and started-stop pins are hard constraints.
- Matrix misses and provider outages are explicit bounded failures; there is no silent network fallback.
- A normalized `RoutingProvider` seam keeps provider revisions separate from PostgreSQL job/carrier versions.
- Callback inbox/outbox state uses an event key and canonical payload digest so duplicate, conflicting, and stale results are observable without raw payload logging.
- The browser console projects synthetic polylines, depot/stop markers, ETA, capacity, window, skill, unassigned reasons, numeric score, revision diff, and started pins. It never renders addresses, customer data, secrets, or raw provider text.

## Run

The demo requires explicit PostgreSQL settings; no credentials or database defaults are embedded in `application.yml`.

```bash
export LAST_MILE_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/last_mile'
export LAST_MILE_DATABASE_USERNAME='last_mile'
export LAST_MILE_DATABASE_PASSWORD='change-me-locally'
./gradlew :optimization-last-mile-routing:bootRun
open http://127.0.0.1:8080/last-mile-routing/
```

The loopback API exposes `GET /api/last-mile-routing/plans/{planId}` plus replan, approval, normalized provider callback, canonical event, and driver reconnect `POST` endpoints. Responses are revisioned with `ETag`; stale approval and callback digest conflicts are explicit HTTP conflicts.

The demo is synthetic and loopback-only; its HTTP surface is not production authentication or CSRF protection. Actual Timefold/OSRM credentials, live GPS, geocoding, traffic, carrier contracts, tenant APIs, and production migrations are intentionally out of scope.

## Verification

```bash
./gradlew :optimization-last-mile-routing:cleanTest \
  :optimization-last-mile-routing:test \
  --no-build-cache --max-workers=1
./gradlew :optimization-last-mile-routing:build --max-workers=1
./scripts/smoke-validate.sh optimization
```

The module consumes the `bluetape4k-dependencies` BOM through the root build and does not pin individual Bluetape versions or depend on
`:optimization-planning-contracts` implementation classes.
