# Issue #555 Kafka Broker-Path Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` for task-by-task execution. Steps use checkbox syntax for tracking.

**Goal:** Add a nightly Testcontainers proof that a real Kafka broker TCP path interruption preserves the Meter outbox row and recovers delivery after the path is restored.

**Architecture:** A Toxiproxy container fronts a Kafka custom listener. The Kafka advertised listener points back to the proxy mapped port, so the host-JVM Spring Kafka clients remain on the proxy path after metadata refresh. The existing failure switch remains the deterministic test seam.

**Tech Stack:** Kotlin, Spring Boot, Kafka client, Testcontainers Kafka 2.0.5, Testcontainers Toxiproxy 2.0.5, PostgreSQL Testcontainers, JUnit 5, Awaitility.

---

### Task 1: Declare the proxy dependency and fixture topology

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `commerce/usage-billing-microservices-composition-tests/build.gradle.kts`
- Modify: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/fixture/UsageBillingMicroserviceFixture.kt`

- [ ] Add an unversioned `org.testcontainers:testcontainers-toxiproxy` alias, resolved by the existing Testcontainers BOM.
- [ ] Add the alias to the composition test runtime.
- [ ] Add an opt-in proxy mode that starts a shared `Network`, starts Toxiproxy, creates `kafka:19092 -> proxy:8666`, and registers Kafka with a custom listener whose advertised supplier returns the proxy host/mapped port.
- [ ] Feed every Spring context the proxy bootstrap endpoint only in proxy mode; retain `kafka.bootstrapServers` otherwise.
- [ ] Close contexts first, then containers, then the network. Remove both toxic directions in a safe cleanup path.

### Task 2: Prove the failure before implementation and then recovery

**Files:**

- Create: `commerce/usage-billing-microservices-composition-tests/src/test/kotlin/io/bluetape4k/workshop/commerce/usagebilling/composition/BrokerPathRecoveryIntegrationTest.kt`

- [ ] Write a tagged integration test that creates the fixture in broker-path mode, activates a price, cuts the proxy, publishes Meter outbox work, and expects `retryWait == 1` plus one backlog row.
- [ ] Run the exact test before proxy behavior exists; expected result is a compile failure because broker-path fixture APIs do not exist.
- [ ] Implement only the fixture APIs required by the test: create with proxy, cut path, restore path.
- [ ] Re-run the exact test. It must observe retry state during the cut, remove both toxics, retry the same row, and await the same usage price evidence after recovery.

### Task 3: Register and document the nightly-only behavior

**Files:**

- Modify: `.github/workflows/nightly.yml`
- Modify: `commerce/usage-billing-microservices-composition-tests/README.md`
- Modify: `commerce/usage-billing-microservices-composition-tests/README.ko.md`

- [ ] Keep `test` excluding `integration`; retain the current nightly `integrationTest` invocation and add a targeted result-file assertion for `BrokerPathRecoveryIntegrationTest`.
- [ ] Document the two complementary failure lanes, the proxy/advertised-listener invariant, and the explicit single-broker non-claim.
- [ ] Add equivalent Korean documentation.

### Task 4: Verify the changed behavior and repository guards

- [ ] Run the exact new integration test with `cleanIntegrationTest --no-build-cache`.
- [ ] Run the full composition `integrationTest`, its `test`, and `koverXmlReport` sequentially.
- [ ] Run `detektTest`, README validator, `actionlint`, `git diff --check`, and a narrow nightly YAML/result-path review.
- [ ] Verify that the diff has no direct broker bootstrap property in proxy mode and that documentation never calls this cluster failover.

## Risks and recovery

| Risk | Signal | Mitigation / rerun point |
| --- | --- | --- |
| metadata bypasses proxy | cut does not yield `RETRY_WAIT` | verify the Kafka custom advertised listener supplier and proxy bootstrap property before retrying tests |
| toxic cleanup leaks between tests | later integration test cannot publish | remove upstream/downstream toxics in `finally`; create fresh fixture per test |
| host port differs under CI | context cannot start or connect | derive endpoint only from `toxiproxy.host` and `getMappedPort`, never a fixed host port |
| test timing masks a fault | publish succeeds during a cut | assert retry state and backlog before restoration; do not use longer waits as a substitute |
