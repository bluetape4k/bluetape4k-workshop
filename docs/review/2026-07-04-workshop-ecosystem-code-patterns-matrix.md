# Workshop Ecosystem Code Patterns Matrix

Date: 2026-07-04
Coordination branch: `feat/workshop-ecosystem-code-patterns`

This matrix tracks every registered Gradle project. A row reaches terminal
state only when disposition is `patched`, `no-op`, or `follow-up` with P0/P1=0
review evidence.

| Project | Directory | Candidate patterns | Disposition | Ecosystem reuse evidence | Stability/security verdict | Validation evidence | Reviewer/date |
|---|---|---|---|---|---|---|---|
| `:shared` | `shared` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-cloudwatch-imds-observability` | `aws/cloudwatch-imds-observability` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-eventbridge-scheduler` | `aws/eventbridge-scheduler` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-ktor-dynamodb` | `aws/ktor-dynamodb` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-s3-spring-cloud` | `aws/s3-spring-cloud` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-s3-vectors-access-grants` | `aws/s3-vectors-access-grants` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-sqs-sns-coroutines` | `aws/sqs-sns-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:aws-storage-abstraction` | `aws/storage-abstraction` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-javers-approval-workflow` | `exposed/javers-approval-workflow` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-javers-audit` | `exposed/javers-audit` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-javers-persistence-audit` | `exposed/javers-persistence-audit` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-mvc-jdbc` | `exposed/mvc-jdbc` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-mvc-virtualthread` | `exposed/mvc-virtualthread` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:exposed-webflux-r2dbc` | `exposed/webflux-r2dbc` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:api-gateway` | `gateway/api-gateway` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:customers` | `gateway/customers` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:orders` | `gateway/orders` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:gatling-virtualthread-simulation` | `gatling/virtualthread-simulation` | PR #401 / head `26edb11f504f58eca9b73311cf3ffd65471b01fb` | PASS / P0=0 P1=0 | `compileKotlin compileTestKotlin cleanTest test`; `compileGatlingKotlin`; GitHub checks all SUCCESS | used `requireInRange`, ProblemDetail bad-request mapping, existing `runSuspendIO`, `httpGet`, bluetape4k assertions | complete | Codex / 2026-07-04 |
| `:graph-abuser-detection` | `graph/abuser-detection` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:graph-event-lineage` | `graph/event-lineage` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:graph-io-pipeline` | `graph/io-pipeline` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:graph-knowledge-graph` | `graph/knowledge-graph` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:graph-recommendation` | `graph/recommendation` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:graph-social-network` | `graph/social-network` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:image-processing-advanced-workflow` | `image-processing/advanced-workflow` | PR #403 / head `e1124c6f` | PASS / P0=0 P1=0 | adopted bluetape4k validation helpers in properties, resolver, derivative processor, and Testcontainers fixtures; README locale pair updated | predicate-policy `require` calls documented as stable-message exceptions; no remaining production `!!` | Local compile/test pass; GitHub checks all SUCCESS; review artifact `docs/review/2026-07-04-image-processing-advanced-workflow-ecosystem-review.md` | Codex / 2026-07-04 |
| `:image-processing-ocr-api` | `image-processing/ocr-api` | PR #404 / head `60a78191` | PASS / P0=0 P1=0 | adopted bluetape4k validation helpers in OCR properties, decoded image bounds, controller upload size, and tests; README locale pair updated | parser/content-type/language predicates documented as stable-message/security exceptions; no `!!` or raw JUnit exception assertions | Local compile/test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-image-processing-ocr-api-ecosystem-review.md` | Codex / 2026-07-04 |
| `:okio-examples` | `io/okio-examples` | PR #402 / head `3c46bfb2` | PASS / P0=0 P1=0 | adopted `requirePositiveNumber`, `requireInRange`, Kluent/bluetape4k assertion style, and coroutine test coverage; README locale pair updated | documented blocking-adapter `runBlocking` and bounded test wait exceptions; no new synchronization or container risk | Local compile/test pass; GitHub checks all SUCCESS; review artifact `docs/review/2026-07-04-okio-examples-ecosystem-review.md` | Codex / 2026-07-04 |
| `:jackson-examples` | `json/jackson-examples` | PR #405 / head `1a4ed486` | PASS / P0=0 P1=0 | reused `AbstractJacksonTest.defaultMapper`, removed raw `jacksonObjectMapper`, and added serializable contract to touched cyclic fixture | mutable Jackson fixtures documented as annotation-binding examples; no new deserialization trust boundary | Local compile/test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-jackson-examples-ecosystem-review.md` | Codex / 2026-07-04 |
| `:jsonview-examples` | `json/jsonview-examples` | PR #406 / head `4c964bdc` | PASS / P0=0 P1=0 | made `ArticleDTO` serializable, switched same-type fixtures to named arguments, and updated README locale pair | nullable DTO fields preserved for JsonView projection semantics; unknown-ID behavior unchanged | Local compile/test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-jsonview-examples-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-coroutines` | `kotlin/coroutines` | PR #407 / head `32c77146` | PASS / P0=0 P1=0 | replaced active timed examples with coroutine `delay`, used `CompletableFuture.delayedExecutor`, and guarded scope `Job` with `requireNotNull` | disabled blocking comparison remains intentionally blocking without `Thread.sleep`; `runTest` remains in pure kotlinx examples | Local compile/test pass; GitHub checks all SUCCESS; review artifact `docs/review/2026-07-04-kotlin-coroutines-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-design-patterns` | `kotlin/design-patterns` | PR #409 / head `0300d910` | PASS / P0=0 P1=0 | replaced active lazy-loading `Thread.sleep` with explicit `LockSupport.parkNanos`; README locale pair updated | heavy-construction delay remains intentional; no input or security boundary changed | Local test pass; GitHub compile checks all SUCCESS; review artifact `docs/review/2026-07-04-kotlin-design-patterns-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-event-aggregation` | `kotlin/flow-extensions-event-aggregation` | PR #408 / head `b271ea3f` | PASS / P0=0 P1=0 | `OrderAuditEntry` sequence validation now uses `requirePositiveNumber`; invalid sequence regression covered | predicate token normalization and same-order projection guards documented as intentional exceptions; audit output unchanged | Local test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-flow-event-aggregation-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-metrics-sampling` | `kotlin/flow-extensions-metrics-sampling` | PR #410 / head `72d08263` | PASS / P0=0 P1=0 | centralized finite/positive-finite guards; threshold validation reuses `requirePositiveNumber` while sample values remain any finite number | cross-sample name/unit invariants remain explicit; Flow behavior unchanged | Local test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-flow-metrics-sampling-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-parallel-enrichment` | `kotlin/flow-extensions-parallel-enrichment` | PR #412 / head `e95d1504` | PASS / P0=0 P1=0 | adopted direct `bluetape4k-core` usage and `requirePositiveNumber` for public `parallelism` validation | Flow parallel operator narrative unchanged; invalid rail count now fails before rail creation | Local test pass; GitHub checks all SUCCESS; review artifact `docs/review/2026-07-04-flow-parallel-enrichment-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-race-fallback` | `kotlin/flow-extensions-race-fallback` | PR #413 / head `6fab8d95` | PASS / P0=0 P1=0 | adopted direct `bluetape4k-core` usage, `requireZeroOrPositiveNumber` for delay, and `requireNotEmpty` for source sets | wrapper remains thin over race/concat/eager/merge/materialize operators; invalid source contracts now fail explicitly | Local test pass after rebase; GitHub checks all SUCCESS; review artifact `docs/review/2026-07-04-flow-race-fallback-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-search-pipeline` | `kotlin/flow-extensions-search-pipeline` | PR #411 / head `e6a742b8` | PASS / P0=0 P1=0 | debounce validation uses bluetape4k `requireGt`/`requireLt`; feature-flag validation uses `requireInRange` on invalid count | redacted logging, session-close sharing, and stale-search cancellation unchanged | Local test pass; GitHub example and compile checks all SUCCESS; review artifact `docs/review/2026-07-04-flow-search-pipeline-ecosystem-review.md` | Codex / 2026-07-04 |
| `:kotlin-flow-extensions-subject-bridge` | `kotlin/flow-extensions-subject-bridge` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:kotlin-text-processing` | `kotlin/text-processing` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:ktor-exposed-rest` | `ktor/exposed-rest` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:ktor-rest-coroutines` | `ktor/rest-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:leader-backend-comparison-lab` | `leader/backend-comparison-lab` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:leader-k8s-lease-micrometer` | `leader/k8s-lease-micrometer` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:leader-leader-election` | `leader/leader-election` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:leader-leader-zookeeper` | `leader/leader-zookeeper` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:leader-tenant-scheduler` | `leader/tenant-scheduler` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:messaging-kafka` | `messaging/kafka` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:messaging-kafka-outbox-fallback` | `messaging/kafka-outbox-fallback` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:messaging-kafka-reply` | `messaging/kafka-reply` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:messaging-transactional-outbox` | `messaging/transactional-outbox` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:micrometer-observation` | `observability/micrometer-observation` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:micrometer-tracing-coroutines` | `observability/micrometer-tracing-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:observability-advanced` | `observability/observability-advanced` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:observability-basic` | `observability/observability-basic` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:bucker4j-bluetape4k-webflux` | `ratelimit/bucker4j-bluetape4k-webflux` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:bucket4j-advanced` | `ratelimit/bucket4j-advanced` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:bucket4j-caffeine-web` | `ratelimit/bucket4j-caffeine-web` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:bucket4j-redis` | `ratelimit/bucket4j-redis` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:redis-cluster-demo` | `redis/cluster-demo` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:redis-distributed-lock` | `redis/distributed-lock` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:redis-redisson-examples` | `redis/redisson-examples` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-application-event-demo` | `spring-boot/application-event-demo` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-cache-benchmark` | `spring-boot/cache-benchmark` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-cache-caffeine` | `spring-boot/cache-caffeine` | request-path slow-load simulation; blank cache-key validation; test-only sleep | patched | PR #399 adopts `requireNotBlank()` and bluetape4k assertion helpers; preserves documented 500 ms cache-fill simulation | PASS, P0/P1=0 in `docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md` | Local compile/test pass; GitHub checks all SUCCESS; head `96604c33dc42cbd80b654679ac27159f5f9e2c63` | Codex / 2026-07-04 |
| `:spring-boot-cache-redis` | `spring-boot/cache-redis` | PR #400 / head `782cceab89a2f3457dcbbbbc26b707d21e95d7e1` | PASS / P0=0 P1=0 | `compileKotlin compileTestKotlin cleanTest test --no-build-cache --max-workers=1`; GitHub checks all SUCCESS | used `requireNotBlank`, `assertFailsWith`, existing `RedisServer.Launcher.redis`, `RedisBinarySerializers.LZ4Kryo`; documented bounded Redis propagation waits | complete | Codex / 2026-07-04 |
| `:spring-boot-cache-resilience` | `spring-boot/cache-resilience` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-cbor-mvc` | `spring-boot/cbor-mvc` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-chaos-monkey` | `spring-boot/chaos-monkey` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-idempotency` | `spring-boot/idempotency` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-idgenerator` | `spring-boot/idgenerator` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-multi-tenant-data-isolation` | `spring-boot/multi-tenant-data-isolation` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-problem` | `spring-boot/problem` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-protobuf-mvc` | `spring-boot/protobuf-mvc` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-resilience4j-coroutines` | `spring-boot/resilience4j-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-stomp-websocket` | `spring-boot/stomp-websocket` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-text-moderation-api` | `spring-boot/text-moderation-api` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-webflux-coroutines` | `spring-boot/webflux-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-boot-webflux-websocket` | `spring-boot/webflux-websocket` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-elasticsearch` | `spring-data/elasticsearch` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-elasticsearch-webflux` | `spring-data/elasticsearch-webflux` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-jpa-querydsl` | `spring-data/jpa-querydsl` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-mongodb-coroutines` | `spring-data/mongodb-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-mongodb-transactions` | `spring-data/mongodb-transactions` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-r2dbc-coroutines` | `spring-data/r2dbc-coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-r2dbc-examples` | `spring-data/r2dbc-examples` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-r2dbc-webflux` | `spring-data/r2dbc-webflux` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-r2dbc-webflux-exposed` | `spring-data/r2dbc-webflux-exposed` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-data-redis-examples` | `spring-data/redis-examples` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-modulith-ddd-order-audit` | `spring-modulith/ddd-order-audit` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-modulith-events-deep-dive` | `spring-modulith/events-deep-dive` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-modulith-jpa-demo` | `spring-modulith/jpa-demo` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-modulith-module-boundaries` | `spring-modulith/module-boundaries` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-security-mvc-hello` | `spring-security/mvc/hello` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-security-webflux-hello-security` | `spring-security/webflux/hello-security` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:spring-security-webflux-jwt` | `spring-security/webflux/jwt` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:vertx-coroutines` | `vertx/coroutines` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:vertx-vertx-sqlclient` | `vertx/vertx-sqlclient` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:vertx-vertx-webclient` | `vertx/vertx-webclient` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:virtualthreads-rules` | `virtualthreads/rules` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:virtualthreads-spring-mvc-tomcat` | `virtualthreads/spring-mvc-tomcat` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
| `:virtualthreads-spring-webflux` | `virtualthreads/spring-webflux` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |
