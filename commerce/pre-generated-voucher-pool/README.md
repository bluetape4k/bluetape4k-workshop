# Pre-generated Voucher Pool

[한국어](README.ko.md) | English

This Spring Boot MVC workshop shows how to import or generate encrypted voucher inventory before demand arrives, then reserve, allocate, reveal, redeem, release, revoke, and reconcile it without spending one entry twice. PostgreSQL is the sole correctness authority. Redis admission, Bloom signals, and leader scheduling are advisory: losing them can reduce throughput, but cannot authorize a state transition.

> Workshop boundary: the application binds to loopback and uses local headers instead of production IAM, OAuth, or CSRF infrastructure. Operator routes and mounted key material are for an isolated workstation only. Do not expose them on a non-loopback interface.

The module uses Java 25 virtual threads, a fixed 16-connection Hikari pool, and database permits split into 11 foreground, 1 worker, and 3 SSE lanes. Voucher plaintext appears only in the import request and the first committed reveal response. Snapshots, metrics, diagnostics, audit rows, and application logs remain code-free.

## Architecture

![Pre-generated voucher pool architecture](../../docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-architecture-01.png)

The main path is Browser or `curl` -> Tomcat MVC -> idempotency owner -> bounded database permit -> application service -> PostgreSQL. Entries are encrypted with an envelope key before persistence. Redis can reject excess admission early and a leader can reduce duplicate worker triggers, but the PostgreSQL transaction, row locks, revisions, and uniqueness constraints decide every reservation and terminal result.

## Contention and recovery

![Pre-generated voucher contention and recovery sequence](../../docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-sequence-01.png)

Concurrent allocators select eligible entries with bounded locking and converge on distinct winners. A command owns its idempotency key until it stores a safe response descriptor, releases a retryable owner, or retains a terminal tombstone. Connection acquisition timeout and permit saturation map to retryable `503 BACKEND_TIMEOUT`; an eligible row with no lockable candidate maps to retryable `503 POOL_BUSY`. Both release the retryable owner and write no terminal descriptor.

The sequence also covers a lost one-time reveal response. Replaying the reveal never returns the code again. The customer must explicitly confirm the loss, after which the server revokes the exposed entry and creates at most one replacement reservation under the same entitlement root. Reconciliation repairs derived pool-depth and user-limit counters from PostgreSQL rows; it never invents a winner.

## Prerequisites and configuration

- JDK 25
- PostgreSQL 16+ with an empty `voucher_pool` database
- Redis only when the advisory path is enabled
- A permission-restricted absolute key-file path owned by the current process user
- `curl`; `jq` is useful for the walkthrough

There are no default operator credentials or key materials. `VOUCHER_POOL_OPERATOR_SECRET`, `VOUCHER_POOL_OPERATOR_GUARD`, and `VOUCHER_POOL_KEY_FILE` are mandatory outside the test profile. The mounted JSON file is limited to 64 KiB, must be a canonical non-symlink regular file readable only by its owner, and contains `stableDedup`, `commandTombstone`, rotating `VERIFICATION`, `USER_IDENTITY`, `REDIS_SIGNAL`, and `AUDIT` rings, plus a `kek` ring. Generate independent high-entropy values; never copy test fixtures.

| Concern | Default | Override or invariant |
|---|---:|---|
| HTTP | `127.0.0.1:8080` | `VOUCHER_POOL_SERVER_ADDRESS`, `VOUCHER_POOL_SERVER_PORT` |
| Management | `127.0.0.1:8081` | `VOUCHER_POOL_MANAGEMENT_PORT` |
| PostgreSQL | `jdbc:postgresql://localhost:5432/voucher_pool` | `VOUCHER_POOL_DATABASE_URL`, `VOUCHER_POOL_DATABASE_USERNAME`, `VOUCHER_POOL_DATABASE_PASSWORD` |
| Hikari | max 16, min idle 4, acquisition 2s | Do not increase the pool as the first saturation response |
| Foreground lane | 11 permits, wait 200ms, transaction/lock 5s | Customer and operator commands; observed stress gate remains 250ms |
| Worker lane | 1 permit, wait 1s, transaction/lock 10s | Revoke, expiry, reconciliation, retention |
| SSE lane | 3 permits, wait 1s, transaction/lock 5s | Snapshot and cursor polling |
| Redis | disabled, command timeout 500ms | `VOUCHER_POOL_REDIS_ENABLED`, `VOUCHER_POOL_REDIS_URI` |
| SSE | 32 total, 8 per scope, queue 64, write 5s | Bounded reset and polling fallback |
| Operator boundary | no credential defaults | `VOUCHER_POOL_OPERATOR_SECRET`, `VOUCHER_POOL_OPERATOR_GUARD` |
| Key material | no default and no CLI key | `VOUCHER_POOL_KEY_FILE` absolute mounted-secret path |

```bash
export VOUCHER_POOL_DATABASE_URL=jdbc:postgresql://localhost:5432/voucher_pool
export VOUCHER_POOL_DATABASE_USERNAME=vouchers
export VOUCHER_POOL_DATABASE_PASSWORD=vouchers
export VOUCHER_POOL_OPERATOR_SECRET='replace-with-at-least-32-random-bytes'
export VOUCHER_POOL_OPERATOR_GUARD='replace-with-an-independent-random-guard'
export VOUCHER_POOL_KEY_FILE=/absolute/path/to/voucher-pool-keys.json
chmod 600 "$VOUCHER_POOL_KEY_FILE"
./gradlew :commerce-pre-generated-voucher-pool:bootRun
```

Enable Redis only to exercise advisory admission and leader scheduling:

```bash
export VOUCHER_POOL_REDIS_ENABLED=true
export VOUCHER_POOL_REDIS_URI=redis://127.0.0.1:6379
./gradlew :commerce-pre-generated-voucher-pool:bootRun
```

## Import and generation

Operator requests require loopback transport, an allowed `Host`, same-origin `Origin` or `X-Workshop-Origin`, `X-Workshop-Tenant`, `X-Workshop-Operator-Secret`, and `X-Workshop-Guard`. Mutations also require `Idempotency-Key` and an explicit creation or revision precondition.

| Method | Operator route | Purpose |
|---|---|---|
| `POST` | `/operator/api/v1/campaigns` | Create a campaign |
| `POST` | `/operator/api/v1/campaigns/{campaignId}/policy` | Change policy under `If-Match` |
| `POST` | `/operator/api/v1/campaigns/{campaignId}/activate`, `/pause`, `/resume` | Move campaign lifecycle |
| `POST` | `/operator/api/v1/campaigns/{campaignId}/revoke-preview`, `/revoke` | Preview and confirm campaign revocation |
| `POST` | `/operator/api/v1/batches/import`, `/operator/api/v1/batches/generate` | Create an import or generation batch |
| `POST` | `/operator/api/v1/batches/{batchId}/import-chunks`, `/generate-chunks` | Append a replay-safe ordered chunk |
| `POST` | `/operator/api/v1/batches/{batchId}/activate`, `/pause`, `/resume` | Move batch lifecycle |
| `POST` | `/operator/api/v1/batches/{batchId}/revoke-preview`, `/revoke` | Preview and confirm batch revocation |
| `POST` | `/operator/api/v1/reconciliation/run` | Run one bounded reconciliation |
| `GET` | `/operator/api/v1/batches/{batchId}`, `/pool-depth`, `/reservations/stuck` | Read authoritative operator state |
| `GET` | `/operator/api/v1/diagnostics/{requestId}`, `/snapshots`, `/events` | Read bounded diagnostics, snapshots, and SSE |

Create and activate a campaign, then create an import batch with one first chunk. Reuse the response `ETag` exactly, including quotes.

```bash
ORIGIN=http://127.0.0.1:8080
TENANT=voucher-pool-demo
CAMPAIGN_ID=018f3b8c-45a7-7cc1-8f1d-31b63140d001
BATCH_ID=018f3b8c-45a7-7cc1-8f1d-31b63140d101
MANIFEST=$(printf 'voucher-pool-import-v1' | shasum -a 256 | awk '{print $1}')
OPERATOR=(-H "Origin: $ORIGIN" -H "X-Workshop-Tenant: $TENANT" \
  -H "X-Workshop-Operator-Secret: $VOUCHER_POOL_OPERATOR_SECRET" \
  -H "X-Workshop-Guard: $VOUCHER_POOL_OPERATOR_GUARD")

curl -i -X POST "$ORIGIN/operator/api/v1/campaigns" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: campaign-create-0001' \
  -H 'If-None-Match: *' \
  -d "{\"campaignId\":\"$CAMPAIGN_ID\",\"startsAt\":\"2026-07-20T00:00:00Z\",\"endsAt\":\"2036-07-20T00:00:00Z\",\"perUserLimit\":1,\"reservationTtlSeconds\":300,\"allocationTtlSeconds\":1800,\"replacementAllowance\":1}"

curl -i -X POST "$ORIGIN/operator/api/v1/campaigns/$CAMPAIGN_ID/activate" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: campaign-activate-0001' \
  -H 'If-Match: "0"' -d '{}'

curl -i -X POST "$ORIGIN/operator/api/v1/batches/import" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: batch-import-0001' \
  -H 'If-None-Match: *' \
  -d "{\"batchId\":\"$BATCH_ID\",\"campaignId\":\"$CAMPAIGN_ID\",\"manifestDigest\":\"$MANIFEST\",\"expectedCount\":2,\"activatesAt\":\"2026-07-20T00:00:00Z\",\"codes\":[\"POOL-IMPORT-0001\"]}"

curl -i -X POST "$ORIGIN/operator/api/v1/batches/$BATCH_ID/import-chunks" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: batch-import-0002' \
  -H 'If-Match: "1"' \
  -d "{\"campaignId\":\"$CAMPAIGN_ID\",\"firstOrdinal\":1,\"manifestDigest\":\"$MANIFEST\",\"codes\":[\"POOL-IMPORT-0002\"]}"

curl -i -X POST "$ORIGIN/operator/api/v1/batches/$BATCH_ID/activate" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: batch-activate-0001' \
  -H 'If-Match: "2"' -d '{}'
```

For server-side generation, create `/operator/api/v1/batches/generate`, append ordered `/generate-chunks` with `firstOrdinal`, `manifestDigest`, and bounded `count`, then activate at the returned revision. Import and generation are replay-safe: the batch pins its source kind, manifest digest, expected count, next ordinal, accepted/rejected counts, and failure code. Resume from the authoritative batch snapshot rather than guessing a missing chunk.

## Customer workflow

Customer routes use `X-Workshop-Tenant` and `X-Workshop-Principal`; mutations also use `Idempotency-Key` and strong ETag preconditions. `ReserveVoucherRequest` is intentionally an empty closed JSON object. Safe `GET` responses never contain entry identity or voucher material.

| Method | Customer route | Purpose |
|---|---|---|
| `POST` | `/api/v1/campaigns/{campaignId}/reservations` | Reserve one eligible entry |
| `GET` | `/api/v1/reservations/{reservationId}` | Read an owner-scoped reservation |
| `POST` | `/api/v1/reservations/{reservationId}/allocate` | Convert an active reservation to an allocation |
| `POST` | `/api/v1/allocations/{allocationId}/code-reveals` | Deliver the code once |
| `GET` | `/api/v1/allocations/{allocationId}` | Read a code-free allocation snapshot |
| `POST` | `/api/v1/allocations/{allocationId}/replacements` | Confirm lost reveal and create a bounded replacement |
| `POST` | `/api/v1/allocations/{allocationId}/redeem` | Verify and redeem the code |
| `POST` | `/api/v1/allocations/{allocationId}/release` | Release an unredeemed allocation without recycling it |
| `GET` | `/api/v1/snapshots`, `/api/v1/events` | Read snapshot-first state and SSE updates |

```bash
USER_REF=customer-001
CUSTOMER=(-H "X-Workshop-Tenant: $TENANT" -H "X-Workshop-Principal: $USER_REF")

RESERVATION_HEADERS=$(mktemp)
RESERVATION_BODY=$(mktemp)
curl -sS -D "$RESERVATION_HEADERS" -o "$RESERVATION_BODY" \
  -X POST "$ORIGIN/api/v1/campaigns/$CAMPAIGN_ID/reservations" "${CUSTOMER[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: reserve-0001' \
  -H 'If-None-Match: *' -d '{}'
RESERVATION_ID=$(jq -r .reservationId "$RESERVATION_BODY")

ALLOCATION_BODY=$(mktemp)
curl -sS -o "$ALLOCATION_BODY" \
  -X POST "$ORIGIN/api/v1/reservations/$RESERVATION_ID/allocate" "${CUSTOMER[@]}" \
  -H 'Idempotency-Key: allocate-0001' -H 'If-Match: "0"'
ALLOCATION_ID=$(jq -r .allocationId "$ALLOCATION_BODY")

REVEAL_BODY=$(mktemp)
curl -sS -o "$REVEAL_BODY" \
  -X POST "$ORIGIN/api/v1/allocations/$ALLOCATION_ID/code-reveals" "${CUSTOMER[@]}" \
  -H 'Idempotency-Key: reveal-0001' -H 'If-Match: "0"'
VOUCHER_CODE=$(jq -r .code "$REVEAL_BODY")
REVISION=$(jq -r .revision "$REVEAL_BODY")

curl -i -X POST "$ORIGIN/api/v1/allocations/$ALLOCATION_ID/redeem" "${CUSTOMER[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: redeem-0001' \
  -H "If-Match: \"$REVISION\"" -d "{\"code\":\"$VOUCHER_CODE\"}"

rm -f "$RESERVATION_HEADERS" "$RESERVATION_BODY" "$ALLOCATION_BODY" "$REVEAL_BODY"
```

If a response is lost before its outcome is known, replay the identical method, route, headers, idempotency key, and body. `COMMAND_IN_PROGRESS` carries `Retry-After`; a completed descriptor replays without a second effect. Changing the payload under the same key returns `IDEMPOTENCY_FINGERPRINT_CONFLICT`.

## Lost reveal replacement

The reveal response is one-time delivery. A replay returns `200 ALREADY_REVEALED` without `code`, with `replacementAvailable` and a safe request ID. First inspect `GET /api/v1/allocations/{allocationId}` and the corresponding diagnostic. If the customer truly lost the committed response, explicitly confirm replacement with the latest ETag:

```bash
curl -i -X POST "$ORIGIN/api/v1/allocations/$ALLOCATION_ID/replacements" "${CUSTOMER[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: replace-lost-0001' \
  -H "If-Match: \"$REVISION\"" -d '{"confirmLostReveal":true}'
```

The old allocation becomes `REVOKED` with a lost-reveal reason and is never returned to inventory. The replacement is a new reservation with the same entitlement root and incremented `replacementOrdinal`. Campaign policy bounds the allowance; exhaustion requires operator review rather than another automatic code.

## Error status retry and action catalog

| Status | Stable code | Retry rule | Bounded caller or operator action |
|---:|---|---|---|
| 200 | `ALREADY_REVEALED` | Do not replay for the code | Refresh the allocation; use the explicit replacement flow only after confirmed response loss |
| 404 | `WRONG_OWNER`, `SCOPE_NOT_FOUND` | Do not probe another tenant or owner | Verify local identity and identifier; cross-scope resources intentionally look absent |
| 409 | `COMMAND_IN_PROGRESS` | Retry the same request after `Retry-After` | Preserve method, route, body, preconditions, and key |
| 409 | `IDEMPOTENCY_FINGERPRINT_CONFLICT` | Never retry the changed payload with this key | Use the original payload or a new key for a genuinely new command |
| 410 | `REPLAY_WINDOW_EXPIRED` | Do not recreate an old effect | Look up authoritative state and audit by safe request ID |
| 503 | `POOL_BUSY`, `BACKEND_TIMEOUT`, `BATCH_FAILED_RETRYABLE` | Bounded backoff with the same key | Restore permit/database capacity or repair the batch source, then retry |
| 409 | `POOL_EXHAUSTED`, `USER_LIMIT_REACHED` | Terminal for the current campaign/user state | Use a new eligible campaign or request operator review |
| 409 | `STALE_REVISION` | Do not reuse the stale precondition | Refresh the authoritative snapshot and submit a new command with a new key |
| 409 | `CAMPAIGN_NOT_ACTIVE`, `CAMPAIGN_PAUSED`, `BATCH_PAUSED`, `BATCH_EXPIRING` | Refresh state with bounded backoff | Wait for activation/resume or choose another active scope |
| 409 | `CAMPAIGN_REVOKING`, `CAMPAIGN_REVOKED`, `BATCH_REVOKED`, `BATCH_EXPIRED`, `BATCH_FAILED_TERMINAL` | Terminal for this scope | Select a new scope or complete operator recovery |
| 409 | `RESERVATION_EXPIRED`, `ALLOCATION_EXPIRED` | Do not mutate the expired object | Create a new reservation or use the documented recovery path |
| 429 | `RATE_LIMITED` | Retry after `Retry-After` | Reduce admission rate; PostgreSQL state was not changed by the rejection |
| 503 | `KEY_MATERIAL_UNAVAILABLE`, `CIPHERTEXT_INVALID` | Fail closed; do not substitute keys | Quarantine affected entries and restore the referenced retained key version |
| 400/500 | `INVALID_REQUEST`, `INTERNAL_ERROR` | Correct invalid input; investigate 500 before replay | Use the safe request ID and redacted diagnostic, then decide whether same-key replay is safe |

## Revoke preview confirm and progress

Revocation is a preview-confirm operation, never a blind bulk update. Pause the campaign or batch, call `POST /operator/api/v1/{scope}/{id}/revoke-preview` with `If-Match`, inspect counts and `affectedCount`, then send the returned short-lived `previewToken`, confirmed ID, current ETag, and a new idempotency key to `/revoke`. The worker returns progress state and revision; poll the scope snapshot and pool depth until terminal.

```bash
curl -i -X POST "$ORIGIN/operator/api/v1/batches/$BATCH_ID/revoke-preview" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'If-Match: "3"' -d '{}'

curl -i -X POST "$ORIGIN/operator/api/v1/batches/$BATCH_ID/revoke" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: batch-revoke-0001' \
  -H 'If-Match: "3"' \
  -d "{\"previewToken\":\"$PREVIEW_TOKEN\",\"confirmedBatchId\":\"$BATCH_ID\"}"
```

Expired or mismatched tokens fail closed. Revoke competes with redeem under PostgreSQL revision and row-lock order; exactly one terminal transition wins. The worker is bounded and restartable, so retry progress observation rather than issuing a second confirmation.

## Reconciliation

The leader-triggered worker repairs pool-depth projections, user-limit counters, expired reservations, and durable checkpoints in bounded chunks. If leader scheduling is unavailable, run the same path manually for one batch:

```bash
curl -i -X POST "$ORIGIN/operator/api/v1/reconciliation/run" "${OPERATOR[@]}" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: reconcile-0001' \
  -d "{\"batchId\":\"$BATCH_ID\"}"

curl -sS "$ORIGIN/operator/api/v1/pool-depth?batchId=$BATCH_ID" "${OPERATOR[@]}"
curl -sS "$ORIGIN/operator/api/v1/reservations/stuck?campaignId=$CAMPAIGN_ID&limit=50" "${OPERATOR[@]}"
```

Success means the checkpoint advances, the backlog age falls, counters match authoritative row counts, and duplicate effects remain zero. Manual reconciliation is not a license to run unbounded loops or increase the worker lane above one permit.

## Redis outage

Redis rate admission, Bloom hints, and leader election never become terminal authority. When Redis is unavailable, the health component becomes `DEGRADED`, admission falls back to local permits plus PostgreSQL, and a leader-triggered worker may require the bounded manual path. Customer mutation outcomes remain PostgreSQL outcomes.

Restore Redis and network reachability, then require three successful probes before treating admission as healthy. Do not restart merely to hide a degraded signal, and do not disable database preconditions. Verify that pool depth and user-limit counters did not drift during the outage.

## Health and degraded state

`GET http://127.0.0.1:8081/actuator/health/liveness` answers whether the process can continue. `GET http://127.0.0.1:8081/actuator/health/readiness` includes `voucherPoolReadiness` and blocks new work when the authoritative boundary cannot serve it. `DEGRADED` and `RECOVERING` map to HTTP 200 so an advisory Redis or leader fault does not evict a PostgreSQL-capable instance; `DOWN` and `OUT_OF_SERVICE` map to HTTP 503.

Prometheus exposure is limited to `health` and `prometheus`. Metric tags use bounded command, outcome, reason, backend, and state enums. Tenant, batch, allocation, request, user, voucher material, URL, and exception messages are forbidden as labels.

## Alerts and diagnostics

Every diagnostic begins with the safe `X-Request-Id` returned to the caller and an explicit tenant header. `GET /operator/api/v1/diagnostics/{requestId}` returns only method, path, status, and elapsed time. The following thresholds are warnings, not permission to bypass limits.

| Alert | Threshold | Safe diagnostic | Authoritative query | Bounded action | Recovery signal |
|---|---|---|---|---|---|
| PostgreSQL/Hikari | pending `> 0` for 10s | safe request ID and readiness | `pg_stat_activity`; `SELECT state,count(*) FROM voucher_pool_entries GROUP BY state` | stop traffic amplification, repair blocked/slow transactions, keep max pool at 16 | readiness `UP`, pending 0, state sum matches imported count |
| Worker checkpoint | no progress for 30s | tenant-scoped batch snapshot | worker checkpoint row and oldest claim age | restore the failing dependency or run one bounded reconciliation | checkpoint advances and oldest age decreases |
| Redis | degraded for 30s | readiness component and bounded backend metrics | `redis-cli -u "$VOUCHER_POOL_REDIS_URI" PING` | repair Redis/network while keeping PostgreSQL authority enabled | three successful probes and no counter drift |
| Eligible pool depth | below 10% for 60s | safe request ID and batch scope | `GET /operator/api/v1/pool-depth`; authoritative entry-state aggregation | activate a verified batch or pause new demand; never recycle exposed entries | eligible depth rises above threshold and totals remain invariant |
| SSE reset | 10 resets/min | subscriber/reset metrics and safe snapshot | cursor retention query | move slow clients to polling and inspect queue/write pressure | reset rate falls and permits return after disconnect |
| Quarantine | at least 1 row | tenant-scoped quarantine count | referenced key-version coverage query | stop purge for affected rows, restore keys, inspect ciphertext integrity | quarantine resolved by verified recovery; no invalid ciphertext remains |
| Purge lag | oldest eligible row exceeds 24h | retention backlog count and oldest age by stage | legal-hold and quarantine joins | run one dependency-ordered bounded purge after backup verification | oldest age and count decrease without reference violations |
| Restore smoke | any failure, immediately | restore manifest coverage and smoke result | migration checksum; replay, counter, audit/cursor, worker, and reveal results | keep traffic stopped, restore the missing database/key unit, rerun one smoke suite | complete smoke passes before readiness is enabled |

## Retention and purge

Default retention is 24 hours for full idempotency descriptors, 7 days for terminal inbox and claims, 30 days for terminal entries and reservation history, and 90 days for audit. Stable dedup and command tombstones survive until explicit irreversible tenant deletion and the end of backup retention. Legal holds and quarantine pause deletion.

Purge runs in dependency order with a bounded limit and durable cursor: descriptors -> terminal inbox/claims -> terminal reservations/entries -> audit. It reports count and oldest age per stage. A purge must not delete an entry, key reference, tombstone, or audit row that is still needed for replay, verification, legal hold, quarantine, or a retained backup.

## Migration and rollback

Startup applies checksummed `V001__voucher_pool.sql` under a PostgreSQL advisory lock. A checksum mismatch fails closed. Rehearse clean, warm, and previous-schema migration with the packaged compatibility fixture before deployment.

Rollback is restore-based, not an in-place schema downgrade. Stop application traffic, retain the failed database, restore the last compatible database plus the exact referenced key inventory, start one node, validate migration checksum and key coverage, then execute the restore smoke. Only re-enable readiness after replay and state invariants pass.

## Backup and restore

Treat the database and referenced key inventory as one recovery unit. The backup manifest covers KEK, verification, user-identity, audit, stable-dedup, command-tombstone, and signature versions referenced by live rows, retained tombstones, audit, and backup history. A new current key does not authorize retirement of an old referenced version.

Restore validates every manifest key before importing data, then checks live ciphertext coverage, pool and user counters, idempotent replay, audit/cursor continuity, stale worker takeover, and one-time reveal behavior. After descriptor purge, replaying the same old key must return `410 REPLAY_WINDOW_EXPIRED` without a new effect. A failed restore smoke keeps readiness closed.

## Unsupported scope

- Redis is not a correctness authority, queue, or substitute database.
- The local header guard is not production authentication or authorization.
- The browser console is an operator learning surface, not an internet-facing administration product.
- Cross-region active-active allocation, multi-database consensus, and external secret-manager adapters are not implemented.
- Redeemed vouchers are not reversed or recycled. Exposed, revoked, expired, quarantined, and lost-reveal entries never return to the eligible pool.
- Retention APIs are internal bounded worker services; there is no unguarded HTTP purge or restore endpoint.

## Verify

Run container-backed tasks sequentially. Stress latency and throughput are report-only; correctness, resource bounds, lane waits, deadlines, pending drain, progress, and leak checks are hard gates.

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:migrationCompatibilityTest --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:stressTest -PvoucherPoolStressRun=local --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:koverXmlReport --max-workers=1
node scripts/validate-voucher-pool-runbook.mjs
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
EXPECTED_GRADLE_PROJECTS=105 ./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh commerce
```

This consumer module imports only the repository-wide `bluetape4k-dependencies` platform. It does not publish a BOM or pin individual Bluetape module versions.
