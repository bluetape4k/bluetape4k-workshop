#!/usr/bin/env bash
# smoke-validate.sh — Domain-group targeted test runner for bluetape4k-workshop
#
# Usage:
#   ./scripts/smoke-validate.sh <group>
#   ./scripts/smoke-validate.sh all-smoke     # all no-Testcontainers modules
#   ./scripts/smoke-validate.sh compile       # compile-only (no tests)
#   ./scripts/smoke-validate.sh stale-check   # Gradle project count + README link check
#   ./scripts/smoke-validate.sh diagram-qa    # changed README diagram QA evidence
#   ./scripts/smoke-validate.sh assertion-governance
#   ./scripts/smoke-validate.sh high-contention-contract
#   HIGH_CONTENTION_RUN_ID=<unique-id> ./scripts/smoke-validate.sh high-contention-ci
#
# Groups: data-access  spring-boot  serialization  messaging  commerce  optimization  operations  async  observability  aws  redis  assertion-governance
# Each group runs with --continue so a single failure does not abort the rest.

set -euo pipefail

# Keep root-project script paths free of Detekt and optional native-image metadata;
# standalone build-logic is kept separate below.
GRADLEW="./gradlew -x detekt -x collectReachabilityMetadata"
MAX_WORKERS="${MAX_WORKERS:-2}"

run() {
  echo "▶ $*"
  eval "$*"
}

# GitHub 호스티드 Ubuntu runner는 ripgrep를 보장하지 않으므로 파일·디렉터리
# 검색은 POSIX/GNU grep으로 수행해 stale-check 계약을 이식 가능하게 유지한다.
contains_pattern() {
  local pattern="$1"
  shift
  local path
  for path in "$@"; do
    if [ -d "$path" ]; then
      grep -REq -- "$pattern" "$path" || return 1
    else
      grep -Eq -- "$pattern" "$path" || return 1
    fi
  done
}

contains_disabled_bluetape_s3_switch() {
  awk '
    function indent(line) {
      match(line, /^[[:space:]]*/)
      return RLENGTH
    }
    {
      level = indent($0)
      line = $0
      sub(/^[[:space:]]*/, "", line)

      if (line ~ /^bluetape4k:[[:space:]]*$/) {
        bluetape_level = level
        aws_level = -1
        s3_level = -1
        next
      }
      if (bluetape_level >= 0 && level <= bluetape_level && line !~ /^bluetape4k:/) {
        bluetape_level = -1
        aws_level = -1
        s3_level = -1
      }
      if (bluetape_level >= 0 && line ~ /^aws:[[:space:]]*$/ && level > bluetape_level) {
        aws_level = level
        s3_level = -1
        next
      }
      if (aws_level >= 0 && level <= aws_level && line !~ /^aws:/) {
        aws_level = -1
        s3_level = -1
      }
      if (aws_level >= 0 && line ~ /^s3:[[:space:]]*$/ && level > aws_level) {
        s3_level = level
        next
      }
      if (s3_level >= 0 && level <= s3_level && line !~ /^s3:/) {
        s3_level = -1
      }
      if (s3_level >= 0 && line ~ /^enabled:[[:space:]]*false[[:space:]]*$/ && level > s3_level) {
        found = 1
      }
    }
    END { exit(found ? 0 : 1) }
  ' "$1"
}

contains_markdown_ref() {
  local needle="$1"
  local path
  while IFS= read -r -d '' path; do
    if grep -Iq -- "$needle" "$path"; then
      return 0
    fi
  done < <(
    find . \
      -type f \
      -name '*.md' \
      ! -path '*/.worktrees/*' \
      ! -path './docs/lessons/*' \
      ! -path './docs/superpowers/*' \
      -print0
  )
  return 1
}

extract_image_links() {
  local readme="$1"
  grep -oE '!\[[^]]*\]\([^ ")]+' "$readme" | sed -E 's/^!\[[^]]*\]\(//'
}

case "${1:-help}" in

  compile)
    run "$GRADLEW build -x test --parallel --continue"
    ;;

  all-smoke)
    # Docker-free Testcontainers Spring bridge contract boundary.
    run "$GRADLEW \
      :shared:test \
      --tests '*PropertyExportingServerDynamicPropertyRegistryTest' \
      --tests '*PropertyExportingServerDynamicPropertyRegistryContextTest' \
      --tests '*RedisTestSupportBridgeContractTest' \
      --rerun-tasks --no-build-cache --no-daemon --max-workers=$MAX_WORKERS --console=plain"
    run "$GRADLEW \
      :jackson-examples:test \
      :jsonview-examples:test \
      :exposed-javers-approval-workflow:test \
      :image-processing-advanced-workflow:test \
      :image-processing-profile-image-moderation:test \
      :image-processing-ocr-api:test \
      :image-processing-barcode-api:test \
      :aws-cloudwatch-imds-observability:test \
      :aws-eventbridge-scheduler:test \
      :aws-kinesis-coroutines:test \
      :aws-s3-vectors-access-grants:test \
      :aws-bedrock-converse:test \
      :aws-settings-boundary:test \
      :kotlin-flow-extensions-event-aggregation:test \
      :kotlin-flow-extensions-metrics-sampling:test \
      :kotlin-flow-extensions-search-pipeline:test \
      :kotlin-text-processing:test \
      :leader-backend-comparison-lab:test \
      :leader-k8s-lease-micrometer:test \
      :leader-job-safety-lab:test \
      :leader-tenant-scheduler:test \
      :okio-examples:test \
      :graph-event-lineage:test \
      :graph-abuser-detection:test \
      :graph-io-pipeline:test \
      :graph-recommendation:test \
      :graph-knowledge-graph:test \
      :graph-social-network:test \
      :kotlin-design-patterns:test \
      :micrometer-observation:test \
      :operations-job-console-core:test \
      :spring-boot-application-event-demo:test \
      :spring-boot-cache-caffeine:test \
      :spring-boot-multi-tenant-data-isolation:test \
      :spring-boot-cbor-mvc:test \
      :spring-boot-chaos-monkey:test \
      :spring-boot-problem:test \
      :spring-boot-protobuf-mvc:test \
      :spring-boot-resilience4j-coroutines:test \
      :spring-boot-stomp-websocket:test \
      :spring-boot-text-moderation-api:test \
      :spring-boot-webflux-coroutines:test \
      :spring-boot-webflux-websocket:test \
      :spring-data-r2dbc-examples:test \
      :spring-data-r2dbc-webflux-exposed:test \
      :spring-modulith-events-deep-dive:test \
      :spring-modulith-module-boundaries:test \
      :spring-security-mvc-hello:test \
      :spring-security-webflux-hello-security:test \
      :spring-security-webflux-jwt:test \
      :ktor-rest-coroutines:test \
      :vertx-coroutines:test \
      :virtualthreads-rules:test \
      --continue"
    ;;

  data-access)
    # Smoke (in-memory / H2)
    run "$GRADLEW \
      :exposed-javers-approval-workflow:test \
      :spring-data-r2dbc-examples:test \
      :spring-data-r2dbc-webflux-exposed:test \
      --continue"
    ;;

  data-access-full)
    # Testcontainers required
    run "$GRADLEW \
      :shared:test \
      --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain"
    run "$GRADLEW \
      :spring-data-redis-examples:test \
      --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain"
    run "$GRADLEW \
      :exposed-javers-audit:test \
      :exposed-javers-persistence-audit:test \
      :exposed-mvc-jdbc:test \
      :exposed-mvc-virtualthread:test \
      :exposed-webflux-r2dbc:test \
      :ktor-exposed-rest:test \
      :spring-data-r2dbc-coroutines:test \
      :spring-data-jpa-querydsl:test \
      :spring-data-mongodb-coroutines:test \
      :spring-data-mongodb-transactions:test \
      :spring-data-elasticsearch:test \
      :spring-data-elasticsearch-webflux:test \
      --continue --max-workers=1"
    ;;

  spring-boot)
    run "$GRADLEW \
      :spring-boot-application-event-demo:test \
      :spring-boot-cache-caffeine:test \
      :spring-boot-multi-tenant-data-isolation:test \
      :spring-boot-problem:test \
      :spring-boot-resilience4j-coroutines:test \
      :spring-boot-webflux-coroutines:test \
      :spring-boot-webflux-websocket:test \
      :spring-boot-chaos-monkey:test \
      :spring-boot-cbor-mvc:test \
      :spring-boot-protobuf-mvc:test \
      :spring-boot-stomp-websocket:test \
      :spring-boot-text-moderation-api:test \
      :spring-modulith-events-deep-dive:test \
      :spring-modulith-module-boundaries:test \
      :spring-security-mvc-hello:test \
      :spring-security-webflux-hello-security:test \
      :spring-security-webflux-jwt:test \
      --continue"
    ;;

  serialization)
    run "$GRADLEW \
      :jackson-examples:test \
      :jsonview-examples:test \
      :okio-examples:test \
      --continue"
    ;;

  messaging)
    # Testcontainers: Kafka
    run "$GRADLEW \
      :messaging-kafka:test \
      :messaging-kafka-reply:test \
      :messaging-kafka-outbox-fallback:test \
      --continue --max-workers=1"
    ;;

  commerce)
    # The event-sourced module's test task is container-free; the remaining tasks use Testcontainers.
    run "$GRADLEW \
      :commerce-shared:test \
      :commerce-event-sourced-promotion-voucher-campaign:test \
      :commerce-order-lifecycle-fulfillment:test \
      :commerce-reservation-control-plane:test \
      :commerce-promotion-voucher-campaign:test \
      :commerce-pre-generated-voucher-pool:test \
      :commerce-concert-ticket-flash-sale:test \
      :commerce-usage-metering-billing-ledger:integrationTest \
      :commerce-usage-metering-billing-event-sourcing:integrationTest \
      :commerce-usage-metering-billing-event-sourcing:stressTest \
      :commerce-usage-billing-meter-service:test \
      :commerce-usage-billing-usage-service:test \
      :commerce-usage-billing-billing-service:test \
      :commerce-usage-billing-invoice-service:test \
      :commerce-usage-billing-query-service:test \
      :commerce-usage-billing-microservices-composition-tests:test \
      :commerce-usage-billing-microservices-composition-tests:integrationTest \
      :commerce-usage-billing-microservices-composition-tests:koverXmlReport \
      --continue --max-workers=1"
    ;;

  operations)
    # Java 25; PostgreSQL/Redis integration tests run sequentially.
    run "$GRADLEW \
      :operations-job-console-core:test \
      :operations-job-console-core:integrationTest \
      :operations-job-console-spring:test \
      :operations-job-console-spring:integrationTest \
      :operations-job-console-ktor:test \
      :operations-job-console-ktor:integrationTest \
      --continue --max-workers=1"
    ;;

  optimization)
    # Java 25; optimization examples use deterministic fakes and PostgreSQL fixtures.
    run "$GRADLEW \
      :optimization-planning-contracts:test \
      :optimization-field-service-dispatch:test \
      :optimization-last-mile-routing:test \
      :optimization-warehouse-allocation:test \
      :optimization-shift-coverage:test \
      :optimization-clinic-appointment-solver:test \
      --continue --max-workers=1"
    ;;

  leader-full)
    # Java 25; the default path is container-free and integration is serialized.
    run "$GRADLEW \
      :leader-job-safety-lab:test \
      :leader-job-safety-lab:integrationTest \
      --continue --max-workers=1"
    ;;

  async)
    run "$GRADLEW \
      :kotlin-coroutines:test \
      :kotlin-design-patterns:test \
      :kotlin-flow-extensions-event-aggregation:test \
      :kotlin-flow-extensions-metrics-sampling:test \
      :kotlin-flow-extensions-search-pipeline:test \
      :vertx-coroutines:test \
      :vertx-vertx-sqlclient:test \
      :vertx-vertx-webclient:test \
      --continue"
    ;;

  assertion-governance)
    run "python3 .github/scripts/test_check_assertion_governance.py -v"
    run "python3 .github/scripts/check-assertion-governance.py"
    ;;

  observability)
    run "$GRADLEW \
      :micrometer-observation:test \
      :aws-cloudwatch-imds-observability:test \
      :aws-eventbridge-scheduler:test \
      :aws-kinesis-coroutines:test \
      :aws-sqs-sns-coroutines:test \
      :aws-s3-vectors-access-grants:test \
      :micrometer-tracing-coroutines:test \
      :virtualthreads-rules:test \
      :virtualthreads-spring-mvc-tomcat:test \
      :virtualthreads-spring-webflux:test \
      --continue --max-workers=1"
    ;;

  aws)
    run "$GRADLEW \
      :aws-cloudwatch-imds-observability:test \
      :aws-eventbridge-scheduler:test \
      :aws-ktor-dynamodb:test \
      :aws-kinesis-coroutines:test \
      :aws-s3-spring-cloud:test \
      :aws-sqs-sns-coroutines:test \
      :aws-storage-abstraction:test \
      :aws-s3-vectors-access-grants:test \
      :aws-bedrock-converse:test \
      :aws-settings-boundary:test \
      --continue --max-workers=1"
    ;;

  redis)
    run "$GRADLEW \
      :redis-cluster-demo:test \
      :redis-redisson-examples:test \
      :bucker4j-bluetape4k-webflux:test \
      :bucket4j-redis:test \
      --continue --max-workers=1"
    ;;

  high-contention-contract)
    run "node --test scripts/high-contention/validate-contract.test.mjs scripts/high-contention/validate-run.test.mjs scripts/high-contention/select-upload.test.mjs"
    run "node scripts/validate-high-contention-readme.mjs"
    # build-logic is a standalone Gradle build without a Detekt task.
    run "./gradlew -p build-logic test"
    # Colima exposes a host socket path that its remote Docker engine cannot bind-mount for Ryuk.
    # Keep the configured Docker context and disable only Ryuk for these contract runs.
    # Keep the container-backed modules in separate JVMs so Testcontainers state cannot leak between them.
    run "TESTCONTAINERS_RYUK_DISABLED=true $GRADLEW \
      :operations-job-console-core:test \
      --no-daemon \
      --max-workers=1"
    run "TESTCONTAINERS_RYUK_DISABLED=true $GRADLEW \
      :commerce-concert-ticket-flash-sale:test \
      --no-daemon \
      --max-workers=1"
    ;;

  high-contention-ci)
    if [ -z "${HIGH_CONTENTION_RUN_ID:-}" ]; then
      echo "HIGH_CONTENTION_RUN_ID is required and must be unique."
      exit 2
    fi
    if [[ ! "$HIGH_CONTENTION_RUN_ID" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] ||
      [[ "$HIGH_CONTENTION_RUN_ID" == "." || "$HIGH_CONTENTION_RUN_ID" == ".." ]]; then
      echo "HIGH_CONTENTION_RUN_ID must be a bounded identifier."
      exit 2
    fi
    high_contention_run_id="$HIGH_CONTENTION_RUN_ID"
    unset HIGH_CONTENTION_RUN_ID
    echo "▶ $GRADLEW highContentionCi -PhighContentionRunId=$high_contention_run_id --max-workers=1"
    ./gradlew -x detekt highContentionCi \
      "-PhighContentionRunId=$high_contention_run_id" \
      --max-workers=1
    ;;

  stale-check)
    echo "=== Gradle project count ==="
    count=$(./gradlew -x detekt projects --console=plain 2>/dev/null | grep -Ec "Project ':" || true)
    expected="${EXPECTED_GRADLE_PROJECTS:-}"
    if [ -n "$expected" ]; then
      echo "Active modules: $count (expected: $expected)"
      [ "$count" -eq "$expected" ] || echo "WARNING: Gradle project count drifted."
    else
      echo "Active modules: $count (expected: current Gradle project graph)"
    fi

    echo ""
    echo "=== Stale module refs in READMEs ==="
    stale=0
    for m in async-logging kotlin/workshop reactive/mutiny gatling/gradle-plugin-demo mapping/mapstruct; do
      if contains_markdown_ref "$m"; then
        echo "STALE REF: $m"
        stale=$((stale + 1))
      fi
    done
    if [ "$stale" -eq 0 ]; then
      echo "No stale refs found."
    else
      echo "ERROR: $stale stale ref(s) found."
      exit 1
    fi

    echo ""
    echo "=== Required workshop module registration ==="
    missing_modules=0
    for module in image-processing/advanced-workflow image-processing/ocr-api image-processing/profile-image-moderation image-processing/barcode-api graph/social-network commerce/shared aws/kinesis-coroutines aws/bedrock-converse aws/settings-boundary leader/backend-comparison-lab optimization/field-service-dispatch optimization/last-mile-routing optimization/warehouse-allocation optimization/shift-coverage optimization/clinic-appointment-solver messaging/kafka-multi-broker-failover; do
      for required_file in build.gradle.kts README.md README.ko.md; do
        if [ ! -f "$module/$required_file" ]; then
          echo "MISSING: $module/$required_file"
          missing_modules=$((missing_modules + 1))
        fi
      done
    done
    if [ "$missing_modules" -eq 0 ]; then
      echo "Required workshop modules are registered."
    else
      echo "ERROR: $missing_modules required module file(s) missing."
      exit 1
    fi

    echo ""
    echo "=== Tenant scheduled-policy example guard ==="
    scheduled_policy_config="leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyConfiguration.kt"
    scheduled_policy_fixture="leader/tenant-scheduler/src/main/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled/TenantScheduledPolicyFixture.kt"
    scheduled_policy_yaml="leader/tenant-scheduler/src/main/resources/application-scheduled-policy.yml"
    scheduled_policy_build="leader/tenant-scheduler/build.gradle.kts"
    scheduled_policy_tests="leader/tenant-scheduler/src/test/kotlin/io/bluetape4k/workshop/leader/tenantscheduler/scheduled"
    scheduled_policy_readme="leader/tenant-scheduler/README.md"
    scheduled_policy_readme_ko="leader/tenant-scheduler/README.ko.md"
    if contains_pattern '@Profile\("scheduled-policy"\)' "$scheduled_policy_config" && \
       contains_pattern '@EnableAspectJAutoProxy\(proxyTargetClass = true\)' "$scheduled_policy_config" && \
       contains_pattern '@Scheduled\(fixedDelay = 5_000, initialDelay = 60_000\)' "$scheduled_policy_fixture" && \
       contains_pattern 'libs\.bluetape4k\.leader\.spring\.boot' "$scheduled_policy_build" && \
       contains_pattern 'libs\.bluetape4k\.leader\.micrometer' "$scheduled_policy_build" && \
       contains_pattern 'selector: "tenantScheduledPolicyFixture#reconcile"' "$scheduled_policy_yaml" && \
       contains_pattern 'name: "tenant-scheduler:reconcile"' "$scheduled_policy_yaml" && \
       contains_pattern 'min-lease-time: 5s' "$scheduled_policy_yaml" && \
       contains_pattern 'failure-mode: SKIP' "$scheduled_policy_yaml" && \
       contains_pattern 'redacted-value: redacted-lock' "$scheduled_policy_yaml" && \
       contains_pattern 'strict: true' "$scheduled_policy_yaml" && \
       contains_pattern 'allow-method-invocation: false' "$scheduled_policy_yaml" && \
       contains_pattern 'TenantScheduledPolicyContextTest' "$scheduled_policy_tests" && \
       contains_pattern 'TenantScheduledPolicyLifecycleTest' "$scheduled_policy_tests" && \
       contains_pattern 'TenantScheduledPolicyDefaultProfileTest' "$scheduled_policy_tests" && \
       contains_pattern '--spring.profiles.active=scheduled-policy' "$scheduled_policy_readme" && \
       contains_pattern '--spring.profiles.active=scheduled-policy' "$scheduled_policy_readme_ko" && \
       contains_pattern 'libs\.bluetape4k\.leader\.spring\.boot' "$scheduled_policy_readme" && \
       contains_pattern 'libs\.bluetape4k\.leader\.micrometer' "$scheduled_policy_readme" && \
       contains_pattern 'libs\.bluetape4k\.leader\.spring\.boot' "$scheduled_policy_readme_ko" && \
       contains_pattern 'libs\.bluetape4k\.leader\.micrometer' "$scheduled_policy_readme_ko" && \
       contains_pattern 'Started TenantSchedulerLabAppKt' "$scheduled_policy_readme" && \
       contains_pattern 'Started TenantSchedulerLabAppKt' "$scheduled_policy_readme_ko" && \
       contains_pattern 'tenant-scheduler callback completed invocationCount=' "$scheduled_policy_readme" && \
       contains_pattern 'tenant-scheduler callback completed invocationCount=' "$scheduled_policy_readme_ko" && \
       contains_pattern 'bluetape4k\.leader\.scheduling\.enabled=true' "$scheduled_policy_readme" && \
       contains_pattern 'bluetape4k\.leader\.scheduling\.enabled=true' "$scheduled_policy_readme_ko" && \
       contains_pattern 'ScheduledTaskHolder' "$scheduled_policy_readme" && \
       contains_pattern 'ScheduledTaskHolder' "$scheduled_policy_readme_ko" && \
       contains_pattern 'FAIL_OPEN_RUN' "$scheduled_policy_readme" && \
       contains_pattern 'FAIL_OPEN_RUN' "$scheduled_policy_readme_ko" && \
       contains_pattern 'leader\.aop\.acquire' "$scheduled_policy_readme" && \
       contains_pattern 'leader\.aop\.acquire' "$scheduled_policy_readme_ko" && \
       contains_pattern 'leader-tenant-scheduler:bootRun' README.md && \
       contains_pattern 'leader-tenant-scheduler:bootRun' README.ko.md; then
      echo "Tenant scheduled-policy example and README contract are registered."
    else
      echo "ERROR: tenant scheduled-policy example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Leader diagnostics example guard ==="
    diagnostics_config="leader/backend-comparison-lab/src/main/resources/application.yml"
    diagnostics_tests="leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability"
    diagnostics_app="leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/BackendComparisonLabApp.kt"
    if contains_pattern '^    leaderBackendDiagnostics:$' "$diagnostics_config" && \
       contains_pattern '^      tracing:$' "$diagnostics_config" && \
       contains_pattern '^      backend-health:$' "$diagnostics_config" && \
       contains_pattern '^      state-provider-bean: workshopLeaderElector$' "$diagnostics_config" && \
       contains_pattern '@Import\(BackendComparisonLabApp::class\)' "$diagnostics_tests/LeaderBackendDiagnosticsContextTest.kt" && \
       contains_pattern 'LeaderBackendDiagnosticsContextTest' "$diagnostics_tests" && \
       contains_pattern 'LeaderBackendDiagnosticsConfiguration' "$diagnostics_app"; then
      echo "Leader diagnostics endpoint and context coverage are registered."
    else
      echo "ERROR: leader diagnostics endpoint/context coverage is missing."
      exit 1
    fi

    echo ""
    echo "=== AWS AppConfig settings-boundary example guard ==="
    appconfig_tests="aws/settings-boundary/src/test/kotlin/io/bluetape4k/workshop/aws/settings/AppConfigDataSpringIntegrationTest.kt"
    appconfig_readme="aws/settings-boundary/README.md"
    appconfig_readme_ko="aws/settings-boundary/README.ko.md"
    appconfig_lesson="docs/lessons/2026-09-01-issue-870-appconfig-runtime-reload.md"
    if contains_pattern 'aws-app-config:' "$appconfig_readme" && \
       contains_pattern 'aws-app-config:' "$appconfig_readme_ko" && \
       contains_pattern 'bootRun --args=' "$appconfig_readme" && \
       contains_pattern 'bootRun --args=' "$appconfig_readme_ko" && \
       contains_pattern 'Environment#getProperty' "$appconfig_readme" && \
       contains_pattern 'Environment#getProperty' "$appconfig_readme_ko" && \
       contains_pattern 'JSON format' "$appconfig_tests" && \
       contains_pattern 'default profile' "$appconfig_tests" && \
       contains_pattern 'test timeout' "$appconfig_tests" && \
       [ -f "$appconfig_lesson" ]; then
      echo "AWS AppConfig ConfigData/runtime-reload example and lesson are registered."
    else
      echo "ERROR: AWS AppConfig settings-boundary example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS S3 Resource pattern example guard ==="
    s3_pattern_main="aws/s3-spring-cloud/src/main/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Sample.kt"
    s3_pattern_test="aws/s3-spring-cloud/src/test/kotlin/io/bluetape4k/workshop/aws/s3/SpringCloudAwsS3Test.kt"
    s3_pattern_config="aws/s3-spring-cloud/src/main/resources/application.yml"
    s3_pattern_readme="aws/s3-spring-cloud/README.md"
    s3_pattern_readme_ko="aws/s3-spring-cloud/README.ko.md"
    s3_pattern_lesson="docs/lessons/2026-09-01-issue-871-s3-resource-pattern-resolver.md"
    if contains_disabled_bluetape_s3_switch "$s3_pattern_config"; then
      echo "ERROR: AWS S3 Resource pattern example still disables the global bluetape4k S3 auto-configuration."
      exit 1
    fi
    if contains_pattern 's3ResourcePatternResolver' "$s3_pattern_main" "$s3_pattern_test" "$s3_pattern_readme" "$s3_pattern_readme_ko" && \
       contains_pattern 'config/\*\*/\*\.yml' "$s3_pattern_main" "$s3_pattern_test" "$s3_pattern_readme" "$s3_pattern_readme_ko" && \
       contains_pattern 'autoconfigure:' "$s3_pattern_config" && \
       contains_pattern 'S3TransferAutoConfiguration' "$s3_pattern_config" && \
       contains_pattern 'PAGINATION_FIXTURE_COUNT' "$s3_pattern_test" && \
       [ -f "$s3_pattern_lesson" ]; then
      echo "AWS S3 Resource pattern example and lesson are registered."
    else
      echo "ERROR: AWS S3 Resource pattern example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS SNS PublishBatch example guard ==="
    sns_batch_service="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/OrderNotificationMessagingService.kt"
    sns_batch_models="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/OrderNotificationModels.kt"
    sns_batch_tests="aws/sqs-sns-coroutines/src/test/kotlin/io/bluetape4k/workshop/aws/sqssns"
    sns_batch_readme="aws/sqs-sns-coroutines/README.md"
    sns_batch_readme_ko="aws/sqs-sns-coroutines/README.ko.md"
    sns_batch_lesson="docs/lessons/2026-09-04-issue-873-sns-publish-batch.md"
    if contains_pattern 'suspend fun publishBatch' "$sns_batch_service" && \
       contains_pattern 'SnsPublishBatchRequest' "$sns_batch_service" && \
       contains_pattern 'SnsPublishBatchEntry' "$sns_batch_service" && \
       contains_pattern 'BatchPublishState' "$sns_batch_models" && \
       contains_pattern 'PublishBatch' "$sns_batch_tests" && \
       contains_pattern 'PublishBatch' "$sns_batch_readme" "$sns_batch_readme_ko" README.md README.ko.md && \
       contains_pattern 'unresolvedEntryIds' "$sns_batch_service" "$sns_batch_models" "$sns_batch_readme" "$sns_batch_readme_ko" && \
       [ -f "$sns_batch_lesson" ]; then
      echo "AWS SNS PublishBatch example and lesson are registered."
    else
      echo "ERROR: AWS SNS PublishBatch example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS SQS Observation listener example guard ==="
    sqs_observation_config="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/SqsObservationExampleConfiguration.kt"
    sqs_observation_local="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/LocalAwsMessagingConfig.kt"
    sqs_observation_tests="aws/sqs-sns-coroutines/src/test/kotlin/io/bluetape4k/workshop/aws/sqssns/SqsObservationExampleTest.kt"
    sqs_observation_readme="aws/sqs-sns-coroutines/README.md"
    sqs_observation_readme_ko="aws/sqs-sns-coroutines/README.ko.md"
    sqs_observation_lesson="docs/lessons/2026-09-04-issue-874-sqs-observation.md"
    if contains_pattern 'SqsObservationExampleConfiguration' "$sqs_observation_config" "$sqs_observation_tests" "$sqs_observation_readme" "$sqs_observation_readme_ko" && \
       contains_pattern '@SqsListener' "$sqs_observation_config" && \
       contains_pattern 'messageVisibilityHeartbeatIntervalSeconds' "$sqs_observation_config" && \
       contains_pattern 'ObservationRegistry.NOOP' "$sqs_observation_tests" && \
       contains_pattern 'CancellationException' "$sqs_observation_config" "$sqs_observation_tests" && \
       contains_pattern 'visibilityChanges' "$sqs_observation_local" "$sqs_observation_tests" && \
       contains_pattern 'observation.enabled' "$sqs_observation_readme" "$sqs_observation_readme_ko" && \
       [ -f "$sqs_observation_lesson" ]; then
      echo "AWS SQS Observation listener example and lesson are registered."
    else
      echo "ERROR: AWS SQS Observation listener example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS DynamoDB Streams Flow example guard ==="
    dynamodb_streams_build="aws/ktor-dynamodb/build.gradle.kts"
    dynamodb_streams_config="aws/ktor-dynamodb/src/main/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/DynamoDbStreamsWorkshopConfig.kt"
    dynamodb_streams_service="aws/ktor-dynamodb/src/main/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/DynamoDbStreamsOrderSessionService.kt"
    dynamodb_streams_tests="aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/DynamoDbStreamsOrderSessionServiceTest.kt"
    dynamodb_streams_emulator_test="aws/ktor-dynamodb/src/test/kotlin/io/bluetape4k/workshop/aws/ktordynamodb/OrderSessionDynamoDbEmulatorTest.kt"
    dynamodb_streams_readme="aws/ktor-dynamodb/README.md"
    dynamodb_streams_readme_ko="aws/ktor-dynamodb/README.ko.md"
    dynamodb_streams_lesson="docs/lessons/2026-09-04-issue-875-dynamodb-streams.md"
    if contains_pattern 'aws\.kotlin\.dynamodbstreams' "$dynamodb_streams_build" && \
       contains_pattern 'DynamoDbStreamsWorkshopConfig' "$dynamodb_streams_config" "$dynamodb_streams_service" && \
       contains_pattern 'shardRecordFlow' "$dynamodb_streams_service" && \
       contains_pattern 'DynamoDbStreamsStartingPosition' "$dynamodb_streams_config" "$dynamodb_streams_service" "$dynamodb_streams_emulator_test" && \
       contains_pattern 'checkpointStore\.save' "$dynamodb_streams_service" && \
       contains_pattern 'duplicate' "$dynamodb_streams_service" "$dynamodb_streams_tests" "$dynamodb_streams_emulator_test" && \
       contains_pattern 'streams/consume' "$dynamodb_streams_readme" "$dynamodb_streams_readme_ko" "$dynamodb_streams_emulator_test" && \
       contains_pattern 'DynamoDB Streams' "$dynamodb_streams_readme" "$dynamodb_streams_readme_ko" && \
       [ -f "$dynamodb_streams_lesson" ]; then
      echo "AWS DynamoDB Streams Flow example and lesson are registered."
    else
      echo "ERROR: AWS DynamoDB Streams Flow example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS Spring Modulith SNS/SQS externalization example guard ==="
    modulith_build="aws/sqs-sns-coroutines/build.gradle.kts"
    modulith_config="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/ModulithExternalizationExample.kt"
    modulith_models="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/OrderNotificationModels.kt"
    modulith_local="aws/sqs-sns-coroutines/src/main/kotlin/io/bluetape4k/workshop/aws/sqssns/LocalAwsMessagingConfig.kt"
    modulith_tests="aws/sqs-sns-coroutines/src/test/kotlin/io/bluetape4k/workshop/aws/sqssns/ModulithExternalizationExampleTest.kt"
    modulith_readme="aws/sqs-sns-coroutines/README.md"
    modulith_readme_ko="aws/sqs-sns-coroutines/README.ko.md"
    modulith_resources="aws/sqs-sns-coroutines/src/main/resources/application.yml"
    modulith_lesson="docs/lessons/2026-09-04-issue-876-modulith-externalization.md"
    if contains_pattern 'spring\.modulith\.events\.core' "$modulith_build" && \
       contains_pattern 'AwsModulithMessagingExampleConfiguration' "$modulith_config" "$modulith_tests" "$modulith_readme" "$modulith_readme_ko" && \
       contains_pattern 'EventExternalizationConfiguration' "$modulith_config" && \
       contains_pattern 'EventExternalizerModuleListener' "$modulith_config" && \
       contains_pattern 'AwsModulithSqsEventConsumer' "$modulith_config" && \
       contains_pattern 'ModulithOrderPlacedIntegrationEvent' "$modulith_models" "$modulith_config" "$modulith_tests" && \
       contains_pattern 'RETRY_REQUESTED' "$modulith_models" "$modulith_config" "$modulith_tests" && \
       contains_pattern 'visibilityChanges' "$modulith_local" "$modulith_tests" && \
       contains_pattern 'externalization' "$modulith_readme" "$modulith_readme_ko" && \
       contains_pattern 'modulith:' "$modulith_resources" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$modulith_build" "$modulith_config" "$modulith_tests" "$modulith_readme" "$modulith_readme_ko" "$modulith_resources" && \
       [ -f "$modulith_lesson" ]; then
      echo "AWS Spring Modulith SNS/SQS externalization example and lesson are registered."
    else
      echo "ERROR: AWS Spring Modulith SNS/SQS externalization example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Redisson RLocalCachedMap numeric update example guard ==="
    rlocal_map_examples="redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/collections/LocalCachedMapExamples.kt"
    rlocal_map_tests="redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/collections/LocalCachedMapTest.kt"
    rlocal_map_await_test="redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/collections/AwaitRedisTest.kt"
    rlocal_map_fixture="redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/AbstractRedissonTest.kt"
    rlocal_map_readme="redis/redisson-examples/README.md"
    rlocal_map_readme_ko="redis/redisson-examples/README.ko.md"
    rlocal_map_lesson="docs/lessons/2026-09-04-issue-878-rlocalcachedmap.md"
    if contains_pattern 'CompositeCodec' "$rlocal_map_examples" "$rlocal_map_tests" "$rlocal_map_readme" "$rlocal_map_readme_ko" && \
       contains_pattern 'RedissonCodecs\.Int' "$rlocal_map_examples" "$rlocal_map_tests" && \
       contains_pattern 'RedissonCodecs\.Double' "$rlocal_map_examples" "$rlocal_map_tests" && \
       contains_pattern 'addAndGetAsync' "$rlocal_map_examples" "$rlocal_map_tests" "$rlocal_map_readme" "$rlocal_map_readme_ko" && \
       contains_pattern 'withLocalCacheClearBarrier' "$rlocal_map_tests" && \
       contains_pattern 'SuspendedJobTester' "$rlocal_map_tests" && \
       contains_pattern 'awaitRedis' "$rlocal_map_fixture" "$rlocal_map_tests" "$rlocal_map_await_test" "$rlocal_map_readme" "$rlocal_map_readme_ko" && \
       contains_pattern 'CancellationException' "$rlocal_map_fixture" "$rlocal_map_await_test" && \
       contains_pattern ':redis-redisson-examples:test' README.md README.ko.md && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$rlocal_map_examples" "$rlocal_map_tests" "$rlocal_map_fixture" "$rlocal_map_readme" "$rlocal_map_readme_ko" && \
       [ -f "$rlocal_map_lesson" ]; then
      echo "Redisson RLocalCachedMap numeric update example and lesson are registered."
    else
      echo "ERROR: Redisson RLocalCachedMap numeric update example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== TenantContext carrier example guard ==="
    tenant_context_build="spring-boot/multi-tenant-data-isolation/build.gradle.kts"
    tenant_context_service="spring-boot/multi-tenant-data-isolation/src/main/kotlin/io/bluetape4k/workshop/multitenant/service/TenantContextCarrierService.kt"
    tenant_context_metrics="spring-boot/multi-tenant-data-isolation/src/main/kotlin/io/bluetape4k/workshop/multitenant/service/TenantMetrics.kt"
    tenant_context_tests="spring-boot/multi-tenant-data-isolation/src/test/kotlin/io/bluetape4k/workshop/multitenant/TenantContextCarrierExampleTest.kt"
    tenant_context_readme="spring-boot/multi-tenant-data-isolation/README.md"
    tenant_context_readme_ko="spring-boot/multi-tenant-data-isolation/README.ko.md"
    tenant_context_lesson="docs/lessons/2026-09-04-issue-877-tenant-context.md"
    if contains_pattern 'libs\.bluetape4k\.tenant' "$tenant_context_build" && \
       contains_pattern 'libs\.bluetape4k\.tenant\.reactor' "$tenant_context_build" && \
       contains_pattern 'TenantContextCarrierService' "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'withMvcTenant' "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'withVirtualThreadTenant' "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'withReactorTenant' "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'MissingTenantContextException' "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'tenant_fingerprint' "$tenant_context_metrics" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       contains_pattern 'TenantContextCarrierExampleTest' "$tenant_context_tests" && \
       contains_pattern 'spring-boot-multi-tenant-data-isolation:test' README.md README.ko.md && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$tenant_context_build" "$tenant_context_service" "$tenant_context_tests" "$tenant_context_readme" "$tenant_context_readme_ko" && \
       [ -f "$tenant_context_lesson" ]; then
      echo "TenantContext carrier example and lesson are registered."
    else
      echo "ERROR: TenantContext carrier example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== AWS S3 client-side encryption storage example guard ==="
    cse_build="aws/storage-abstraction/build.gradle.kts"
    cse_config="aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3Config.kt"
    cse_service="aws/storage-abstraction/src/main/kotlin/io/bluetape4k/workshop/storage/EncryptedS3StorageService.kt"
    cse_tests="aws/storage-abstraction/src/test/kotlin/io/bluetape4k/workshop/storage"
    cse_readme="aws/storage-abstraction/README.md"
    cse_readme_ko="aws/storage-abstraction/README.ko.md"
    cse_aes="aws/storage-abstraction/src/main/resources/application-s3-encrypted-aes.yml"
    cse_rsa="aws/storage-abstraction/src/main/resources/application-s3-encrypted-rsa.yml"
    cse_lesson="docs/lessons/2026-09-02-issue-872-s3-cse-transfer.md"
    if contains_pattern 'libs\.aws2\.s3\.transfer\.manager' "$cse_build" && \
       contains_pattern 'S3ClientSideEncryptionProviderTemplate' "$cse_config" "$cse_service" "$cse_tests" && \
       contains_pattern 'S3ClientSideEncryptionTransferTemplate' "$cse_config" "$cse_service" "$cse_tests" && \
       contains_pattern 'downloadEncryptedBytesBounded' "$cse_service" "$cse_tests" && \
       contains_pattern 's3-encrypted-aes' "$cse_aes" "$cse_readme" "$cse_readme_ko" "$cse_tests" && \
       contains_pattern 's3-encrypted-rsa' "$cse_rsa" "$cse_readme" "$cse_readme_ko" "$cse_tests" && \
       contains_pattern 'MAX_CIPHERTEXT_BYTES' "$cse_service" "$cse_tests" && \
       contains_pattern 'STAGING_KEY_PREFIX' "$cse_service" && \
       contains_pattern 'promoteStagingObject' "$cse_service" && \
       contains_pattern 'downloadEncryptedFile' "$cse_service" && \
       contains_pattern 'max-ciphertext-bytes' "$cse_readme" "$cse_readme_ko" && \
       contains_pattern 'per-call file' "$cse_readme" "$cse_readme_ko" && \
       contains_pattern 'successful file upload promotes staging' "$cse_tests" && \
       contains_pattern 'CancellationException' "$cse_service" "$cse_tests" && \
       [ -f "$cse_lesson" ]; then
      echo "AWS S3 client-side encryption transfer example and lesson are registered."
    else
      echo "ERROR: AWS S3 client-side encryption transfer example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Kafka producer callbackFlow example guard ==="
    kafka_flow_source="messaging/kafka-reply/src/main/kotlin/io/bluetape4k/workshop/kafka/flow/KafkaProducerFlow.kt"
    kafka_flow_tests="messaging/kafka-reply/src/test/kotlin/io/bluetape4k/workshop/kafka/flow/KafkaProducerFlowTest.kt"
    kafka_flow_readme="messaging/kafka-reply/README.md"
    kafka_flow_readme_ko="messaging/kafka-reply/README.ko.md"
    kafka_flow_lesson="docs/lessons/2026-09-04-issue-879-kafka-callbackflow.md"
    if contains_pattern 'class KafkaProducerFlow' "$kafka_flow_source" && \
       contains_pattern 'callbackFlow' "$kafka_flow_source" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'awaitClose' "$kafka_flow_source" && \
       contains_pattern 'maxInFlight' "$kafka_flow_source" "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'channelCapacity' "$kafka_flow_source" "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'flush' "$kafka_flow_source" "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'close' "$kafka_flow_source" "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'malformed callback' "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern '(late callback|늦게 도착한 callback)' "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       contains_pattern 'KafkaProducerFlowTest' "$kafka_flow_tests" && \
       contains_pattern ':messaging-kafka-reply:test' README.md README.ko.md && \
       contains_pattern '#879' docs/coverage-matrix.md docs/lessons/README.md && \
       contains_pattern '"issue_numbers": \[879\]' docs/ecosystem-reuse-train.json && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$kafka_flow_source" "$kafka_flow_tests" "$kafka_flow_readme" "$kafka_flow_readme_ko" && \
       [ -f "$kafka_flow_lesson" ]; then
      echo "Kafka producer callbackFlow example and lesson are registered."
    else
      echo "ERROR: Kafka producer callbackFlow example contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Ktor Exposed backend-selective health/readiness guard ==="
    ktor_selective_build="ktor/exposed-rest/build.gradle.kts"
    ktor_selective_catalog="gradle/libs.versions.toml"
    ktor_selective_app="ktor/exposed-rest/src/main/kotlin/io/bluetape4k/workshop/ktor/exposedrest/KtorExposedRestApplication.kt"
    ktor_selective_routes="ktor/exposed-rest/src/main/kotlin/io/bluetape4k/workshop/ktor/exposedrest/BookRoutes.kt"
    ktor_selective_tests="ktor/exposed-rest/src/test/kotlin/io/bluetape4k/workshop/ktor/exposedrest/KtorExposedSelectiveHealthTest.kt"
    ktor_selective_readme="ktor/exposed-rest/README.md"
    ktor_selective_readme_ko="ktor/exposed-rest/README.ko.md"
    ktor_selective_lesson="docs/lessons/2026-09-04-issue-880-ktor-selective-health.md"
    ktor_selective_review="docs/review/2026-09-04-issue-880-ktor-selective-health.md"
    if contains_pattern 'exposed-ktor-core.*bluetape4k-exposed-ktor-core' "$ktor_selective_catalog" && \
       contains_pattern 'exposed-ktor-jdbc.*bluetape4k-exposed-ktor-jdbc' "$ktor_selective_catalog" && \
       contains_pattern 'libs\.exposed\.ktor\.core' "$ktor_selective_build" && \
       contains_pattern 'libs\.exposed\.ktor\.jdbc' "$ktor_selective_build" && \
       ! contains_pattern 'libs\.exposed\.ktor\)' "$ktor_selective_build" && \
       ! contains_pattern 'libs\.exposed\.ktor\.(r2dbc|cache)' "$ktor_selective_build" && \
       contains_pattern 'bluetape4kExposedCoreErrors' "$ktor_selective_app" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'bluetape4kExposedJdbcErrors' "$ktor_selective_app" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'bluetape4kExposedHealthRoutes' "$ktor_selective_app" "$ktor_selective_tests" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'exposedKtorJdbcReadinessProbe' "$ktor_selective_app" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'exposedJdbcTransaction' "$ktor_selective_routes" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'TIMEOUT' "$ktor_selective_tests" "$ktor_selective_readme" "$ktor_selective_readme_ko" && \
       contains_pattern 'jdbc:postgresql://db\.internal' "$ktor_selective_tests" && \
       contains_pattern '2\.0\.0' "$ktor_selective_readme" "$ktor_selective_readme_ko" "$ktor_selective_lesson" "$ktor_selective_review" && \
       contains_pattern 'R2DBC' "$ktor_selective_readme" "$ktor_selective_readme_ko" "$ktor_selective_lesson" && \
       contains_pattern 'cache' "$ktor_selective_readme" "$ktor_selective_readme_ko" "$ktor_selective_lesson" && \
       contains_pattern ':ktor-exposed-rest:test' README.md README.ko.md && \
       contains_pattern '#880' docs/coverage-matrix.md docs/lessons/README.md && \
       contains_pattern '"issue_numbers": \[880\]' docs/ecosystem-reuse-train.json && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$ktor_selective_build" "$ktor_selective_app" "$ktor_selective_routes" "$ktor_selective_tests" "$ktor_selective_readme" "$ktor_selective_readme_ko" "$ktor_selective_lesson" "$ktor_selective_review" && \
       [ -f "$ktor_selective_lesson" ] && [ -f "$ktor_selective_review" ]; then
      echo "Ktor Exposed backend-selective health/readiness example and lesson are registered."
    else
      echo "ERROR: Ktor Exposed backend-selective health/readiness contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Exposed JDBC/R2DBC cursor pagination guard ==="
    cursor_jdbc_repo="exposed/mvc-jdbc/src/main/kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/author/repository/BookRepository.kt"
    cursor_jdbc_controller="exposed/mvc-jdbc/src/main/kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/author/controller/BookController.kt"
    cursor_jdbc_test="exposed/mvc-jdbc/src/test/kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/author/CursorPaginationRepositoryTest.kt"
    cursor_jdbc_api_test="exposed/mvc-jdbc/src/test/kotlin/io/bluetape4k/workshop/exposed/mvc/jdbc/author/AuthorControllerTest.kt"
    cursor_jdbc_readme="exposed/mvc-jdbc/README.md"
    cursor_jdbc_readme_ko="exposed/mvc-jdbc/README.ko.md"
    cursor_r2dbc_repo="exposed/webflux-r2dbc/src/main/kotlin/io/bluetape4k/workshop/exposed/webflux/r2dbc/author/repository/BookRepository.kt"
    cursor_r2dbc_controller="exposed/webflux-r2dbc/src/main/kotlin/io/bluetape4k/workshop/exposed/webflux/r2dbc/author/controller/BookController.kt"
    cursor_r2dbc_test="exposed/webflux-r2dbc/src/test/kotlin/io/bluetape4k/workshop/exposed/webflux/r2dbc/author/CursorPaginationRepositoryTest.kt"
    cursor_r2dbc_api_test="exposed/webflux-r2dbc/src/test/kotlin/io/bluetape4k/workshop/exposed/webflux/r2dbc/author/AuthorControllerTest.kt"
    cursor_r2dbc_readme="exposed/webflux-r2dbc/README.md"
    cursor_r2dbc_readme_ko="exposed/webflux-r2dbc/README.ko.md"
    cursor_lesson="docs/lessons/2026-09-04-issue-881-exposed-cursor-pagination.md"
    cursor_review="docs/review/2026-09-04-issue-881-exposed-cursor-pagination.md"
    if contains_pattern 'findCursorPage' "$cursor_jdbc_repo" "$cursor_jdbc_controller" "$cursor_jdbc_api_test" "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" && \
       contains_pattern 'LongR2dbcRepository' "$cursor_r2dbc_repo" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern 'findCursorPage' "$cursor_r2dbc_repo" "$cursor_r2dbc_controller" "$cursor_r2dbc_api_test" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern 'pageSize' "$cursor_jdbc_controller" "$cursor_r2dbc_controller" "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern 'nextCursor' "$cursor_jdbc_api_test" "$cursor_r2dbc_api_test" "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern 'sparse ID' "$cursor_jdbc_test" "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" "$cursor_r2dbc_test" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern '(cancellation|취소)' "$cursor_r2dbc_test" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern '2\.0\.0' "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" "$cursor_lesson" "$cursor_review" && \
       contains_pattern ':exposed-mvc-jdbc:test' README.md README.ko.md && \
       contains_pattern ':exposed-webflux-r2dbc:test' README.md README.ko.md && \
       contains_pattern '#881' docs/coverage-matrix.md docs/lessons/README.md && \
       contains_pattern 'CursorPaginationRepositoryTest' "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" && \
       contains_pattern 'exposed/mvc-jdbc' .github/workflows/Examples.yml && \
       contains_pattern 'exposed/webflux-r2dbc' .github/workflows/Examples.yml && \
       contains_pattern '"issue_numbers": \[881\]' docs/ecosystem-reuse-train.json && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$cursor_jdbc_repo" "$cursor_jdbc_controller" "$cursor_jdbc_test" "$cursor_jdbc_api_test" "$cursor_jdbc_readme" "$cursor_jdbc_readme_ko" "$cursor_r2dbc_repo" "$cursor_r2dbc_controller" "$cursor_r2dbc_test" "$cursor_r2dbc_api_test" "$cursor_r2dbc_readme" "$cursor_r2dbc_readme_ko" "$cursor_lesson" "$cursor_review" && \
       [ -f "$cursor_lesson" ] && [ -f "$cursor_review" ]; then
      echo "Exposed JDBC/R2DBC cursor pagination examples and lesson are registered."
    else
      echo "ERROR: Exposed JDBC/R2DBC cursor pagination contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Spring Data Exposed QBE/FluentQuery guard ==="
    qbe_catalog="gradle/libs.versions.toml"
    qbe_build="spring-data/r2dbc-webflux-exposed/build.gradle.kts"
    qbe_app="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/WebfluxR2dbcExposedApplication.kt"
    qbe_config="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/config/ExposedSpringDataR2dbcConfig.kt"
    qbe_repo="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/domain/repository/UserQueryByExampleRepository.kt"
    qbe_models="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/domain/model/UserQbeModels.kt"
    qbe_service="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/service/UserService.kt"
    qbe_controller="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/controller/UserController.kt"
    qbe_handler="spring-data/r2dbc-webflux-exposed/src/main/kotlin/io/bluetape4k/workshop/exposed/r2dbc/handler/UserHandler.kt"
    qbe_test="spring-data/r2dbc-webflux-exposed/src/test/kotlin/io/bluetape4k/workshop/exposed/r2dbc/domain/repository/UserQueryByExampleRepositoryTest.kt"
    qbe_api_test="spring-data/r2dbc-webflux-exposed/src/test/kotlin/io/bluetape4k/workshop/exposed/r2dbc/controller/UserControllerTest.kt"
    qbe_route_test="spring-data/r2dbc-webflux-exposed/src/test/kotlin/io/bluetape4k/workshop/exposed/r2dbc/handler/UserHandlerIT.kt"
    qbe_test_base="spring-data/r2dbc-webflux-exposed/src/test/kotlin/io/bluetape4k/workshop/exposed/r2dbc/AbstractWebfluxR2dbcExposedApplicationTest.kt"
    qbe_readme="spring-data/r2dbc-webflux-exposed/README.md"
    qbe_readme_ko="spring-data/r2dbc-webflux-exposed/README.ko.md"
    qbe_lesson="docs/lessons/2026-09-04-issue-882-spring-data-qbe.md"
    qbe_review="docs/review/2026-09-04-issue-882-spring-data-qbe.md"
    if contains_pattern 'exposed-spring-boot-r2dbc.*bluetape4k-exposed-spring-boot-r2dbc' "$qbe_catalog" && \
       contains_pattern 'libs\.exposed\.spring\.boot\.r2dbc' "$qbe_build" && \
       contains_pattern 'DataR2dbcRepositoriesAutoConfiguration' "$qbe_app" && \
       contains_pattern 'EnableExposedR2dbcRepositories' "$qbe_config" && \
       contains_pattern 'ExposedR2dbcQueryByExampleRepository' "$qbe_repo" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'UserQbeResponse' "$qbe_models" "$qbe_service" "$qbe_controller" && \
       contains_pattern 'ExampleMatcher' "$qbe_service" "$qbe_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'findBy' "$qbe_service" "$qbe_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'project' "$qbe_service" "$qbe_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'count' "$qbe_service" "$qbe_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'exists' "$qbe_service" "$qbe_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern 'cold' "$qbe_test" "$qbe_readme" "$qbe_readme_ko" "$qbe_lesson" && \
       contains_pattern 'Flow' "$qbe_test" "$qbe_readme" "$qbe_readme_ko" "$qbe_lesson" && \
       contains_pattern '(cancellation|취소)' "$qbe_test" "$qbe_readme" "$qbe_readme_ko" "$qbe_lesson" "$qbe_review" && \
       contains_pattern 'TransactionManager\.defaultDatabase' "$qbe_test_base" && \
       contains_pattern '/api/users/qbe' "$qbe_api_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern '/users/qbe' "$qbe_app" "$qbe_route_test" "$qbe_readme" "$qbe_readme_ko" && \
       contains_pattern '2\.0\.0' "$qbe_readme" "$qbe_readme_ko" "$qbe_lesson" "$qbe_review" && \
       contains_pattern ':spring-data-r2dbc-webflux-exposed:test' README.md README.ko.md && \
       contains_pattern '#882' docs/coverage-matrix.md docs/lessons/README.md && \
       contains_pattern 'spring-data/r2dbc-webflux-exposed' .github/workflows/Examples.yml && \
       contains_pattern '"issue_numbers": \[882\]' docs/ecosystem-reuse-train.json && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$qbe_build" "$qbe_app" "$qbe_config" "$qbe_repo" "$qbe_models" "$qbe_service" "$qbe_controller" "$qbe_handler" "$qbe_test" "$qbe_api_test" "$qbe_route_test" "$qbe_readme" "$qbe_readme_ko" "$qbe_lesson" "$qbe_review" && \
       [ -f "$qbe_lesson" ] && [ -f "$qbe_review" ]; then
      echo "Spring Data Exposed QBE/FluentQuery example and lesson are registered."
    else
      echo "ERROR: Spring Data Exposed QBE/FluentQuery contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Profile image privacy-safe derivative guard ==="
    privacy_build="image-processing/profile-image-moderation/build.gradle.kts"
    privacy_config="image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/config/ProfileImageModerationProperties.kt"
    privacy_processor="image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/service/ProfileImageProcessor.kt"
    privacy_service="image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/service/ProfileImageService.kt"
    privacy_model="image-processing/profile-image-moderation/src/main/kotlin/io/bluetape4k/workshop/imageprocessing/profile/model/ProfileImageModels.kt"
    privacy_tests="image-processing/profile-image-moderation/src/test/kotlin/io/bluetape4k/workshop/imageprocessing/profile/service"
    privacy_readme="image-processing/profile-image-moderation/README.md"
    privacy_readme_ko="image-processing/profile-image-moderation/README.ko.md"
    privacy_yaml="image-processing/profile-image-moderation/src/main/resources/application.yml"
    privacy_lesson="docs/lessons/2026-09-04-issue-885-privacy-derivative.md"
    privacy_review="docs/superpowers/specs/2026-09-04-issue-885-privacy-derivative-implementation-review.md"
    if contains_pattern 'libs\.bluetape4k\.images' "$privacy_build" && \
       contains_pattern 'ProfileImagePrivacyProperties' "$privacy_config" && \
       contains_pattern 'strip-metadata|remove-gps|max-metadata-bytes' "$privacy_yaml" "$privacy_readme" "$privacy_readme_ko" && \
       contains_pattern 'processPrivacySafe' "$privacy_processor" "$privacy_service" "$privacy_tests" "$privacy_readme" "$privacy_readme_ko" && \
       contains_pattern 'PrivacyDerivativePipeline|suspendPrivacyDerivative|PrivacyDerivativeOptions' "$privacy_processor" && \
       contains_pattern 'PrivacyMetadataCategory|PrivacyRedaction|privacy_safe_derivatives' "$privacy_tests" && \
       contains_pattern 'PrivacyDerivativePipeline' "$privacy_readme" "$privacy_readme_ko" && \
       contains_pattern 'PrivacyDerivativeReport' "$privacy_processor" "$privacy_model" "$privacy_readme" "$privacy_readme_ko" && \
       contains_pattern 'readImageMetadataReportStrict' "$privacy_processor" "$privacy_tests" && \
       contains_pattern 'redaction|Redaction' "$privacy_processor" "$privacy_tests" "$privacy_readme" "$privacy_readme_ko" && \
       contains_pattern ':image-processing-profile-image-moderation:test' README.md README.ko.md && \
       contains_pattern '#885' docs/coverage-matrix.md docs/lessons/README.md "$privacy_lesson" && \
       contains_pattern '"issue_numbers": \[883, 884, 885\]' docs/ecosystem-reuse-train.json && \
       contains_pattern 'image-processing/profile-image-moderation' .github/workflows/Examples.yml && \
       contains_pattern '2\.0\.0' "$privacy_readme" "$privacy_readme_ko" "$privacy_lesson" "$privacy_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$privacy_build" "$privacy_config" "$privacy_processor" "$privacy_service" "$privacy_model" "$privacy_tests" "$privacy_readme" "$privacy_readme_ko" "$privacy_yaml" "$privacy_lesson" "$privacy_review" && \
       [ -f "$privacy_lesson" ] && [ -f "$privacy_review" ]; then
      echo "Profile image privacy-safe derivative example and lesson are registered."
    else
      echo "ERROR: Profile image privacy-safe derivative contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Graph social weighted shortest-path guard ==="
    graph_social_service="graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkService.kt"
    graph_social_suspend_service="graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkSuspendService.kt"
    graph_social_tests="graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social"
    graph_social_readme="graph/social-network/README.md"
    graph_social_readme_ko="graph/social-network/README.ko.md"
    graph_social_lesson="docs/lessons/2026-09-05-issue-886-social-weighted-path.md"
    graph_social_review="docs/superpowers/specs/2026-09-05-issue-886-social-weighted-path-implementation-review.md"
    if contains_pattern 'findWeightedConnectionPath' "$graph_social_service" "$graph_social_suspend_service" "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" && \
       contains_pattern 'PathOptions' "$graph_social_service" "$graph_social_suspend_service" "$graph_social_tests" && \
       contains_pattern 'totalWeight' "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" && \
       contains_pattern 'MissingWeightPolicy' "$graph_social_service" "$graph_social_suspend_service" "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" && \
       contains_pattern 'maxVisited' "$graph_social_service" "$graph_social_suspend_service" "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" && \
       contains_pattern 'maxDepth' "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" && \
       contains_pattern 'deterministic|결정적' "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" "$graph_social_review" && \
       contains_pattern ':graph-social-network:test' README.md README.ko.md && \
       contains_pattern '#886' docs/coverage-matrix.md docs/lessons/README.md "$graph_social_lesson" && \
       contains_pattern 'graph/social-network' .github/workflows/Examples.yml && \
       contains_pattern '"issue_numbers": \[883, 884, 885, 886\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' "$graph_social_readme" "$graph_social_readme_ko" "$graph_social_lesson" "$graph_social_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$graph_social_service" "$graph_social_suspend_service" "$graph_social_tests" "$graph_social_readme" "$graph_social_readme_ko" "$graph_social_lesson" "$graph_social_review" && \
       [ -f "$graph_social_lesson" ] && [ -f "$graph_social_review" ]; then
      echo "Graph social weighted shortest-path example and lesson are registered."
    else
      echo "ERROR: Graph social weighted shortest-path contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Graph knowledge schema-drift planner guard ==="
    graph_knowledge_schema="graph/knowledge-graph/src/main/kotlin/io/bluetape4k/workshop/graph/knowledge/schema/KnowledgeGraphSchema.kt"
    graph_knowledge_service="graph/knowledge-graph/src/main/kotlin/io/bluetape4k/workshop/graph/knowledge/service/KnowledgeGraphService.kt"
    graph_knowledge_suspend_service="graph/knowledge-graph/src/main/kotlin/io/bluetape4k/workshop/graph/knowledge/service/KnowledgeGraphSuspendService.kt"
    graph_knowledge_tests="graph/knowledge-graph/src/test/kotlin/io/bluetape4k/workshop/graph/knowledge"
    graph_knowledge_readme="graph/knowledge-graph/README.md"
    graph_knowledge_readme_ko="graph/knowledge-graph/README.ko.md"
    graph_knowledge_lesson="docs/lessons/2026-09-05-issue-887-knowledge-schema-drift.md"
    graph_knowledge_review="docs/superpowers/specs/2026-09-05-issue-887-knowledge-schema-drift-implementation-review.md"
    if contains_pattern 'object KnowledgeGraphSchema' "$graph_knowledge_schema" && \
       contains_pattern 'desiredSchema' "$graph_knowledge_schema" "$graph_knowledge_service" "$graph_knowledge_suspend_service" "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" && \
       contains_pattern 'planSchema' "$graph_knowledge_service" "$graph_knowledge_suspend_service" "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" && \
       contains_pattern 'GraphSchemaPlanOptions' "$graph_knowledge_service" "$graph_knowledge_suspend_service" "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" && \
       contains_pattern 'GraphSchemaPlanAction|UNSUPPORTED' "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" && \
       contains_pattern 'deterministic|결정적' "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" "$graph_knowledge_review" && \
       contains_pattern 'before graph creation|graph 생성과|plan-first ordering' "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" "$graph_knowledge_review" && \
       contains_pattern ':graph-knowledge-graph:test' README.md README.ko.md && \
       contains_pattern '#887' docs/coverage-matrix.md docs/lessons/README.md "$graph_knowledge_lesson" && \
       contains_pattern 'graph/knowledge-graph' .github/workflows/Examples.yml && \
       contains_pattern '2\.0\.0' "$graph_knowledge_readme" "$graph_knowledge_readme_ko" "$graph_knowledge_lesson" "$graph_knowledge_review" && \
       contains_pattern '"issue_numbers": \[883, 884, 885, 886, 887\]' docs/ecosystem-reuse-train.json && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$graph_knowledge_schema" "$graph_knowledge_service" "$graph_knowledge_suspend_service" "$graph_knowledge_tests" "$graph_knowledge_readme" "$graph_knowledge_readme_ko" "$graph_knowledge_lesson" "$graph_knowledge_review" && \
       [ -f "$graph_knowledge_lesson" ] && [ -f "$graph_knowledge_review" ]; then
      echo "Graph knowledge schema-drift planner example and lesson are registered."
    else
      echo "ERROR: Graph knowledge schema-drift planner contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Graph abuser algorithm execution observation guard ==="
    graph_abuser_build="graph/abuser-detection/build.gradle.kts"
    graph_abuser_model="graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model"
    graph_abuser_service="graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionService.kt"
    graph_abuser_suspend_service="graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionSuspendService.kt"
    graph_abuser_tests="graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser"
    graph_abuser_readme="graph/abuser-detection/README.md"
    graph_abuser_readme_ko="graph/abuser-detection/README.ko.md"
    graph_abuser_lesson="docs/lessons/2026-09-05-issue-888-native-algorithm-observation.md"
    graph_abuser_review="docs/superpowers/specs/2026-09-05-issue-888-native-algorithm-observation-implementation-review.md"
    if contains_pattern 'rankSuspiciousUsersWithExecution' "$graph_abuser_service" "$graph_abuser_suspend_service" "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'AbuserAlgorithmExecution' "$graph_abuser_model" "$graph_abuser_service" "$graph_abuser_suspend_service" "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'GraphAlgorithmProviderPolicy' "$graph_abuser_service" "$graph_abuser_suspend_service" "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'NO_PROVIDER' "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'JVM_ONLY_POLICY' "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'NATIVE_ONLY' "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern 'CancellationException' "$graph_abuser_service" "$graph_abuser_suspend_service" "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" && \
       contains_pattern ':graph-abuser-detection:test' README.md README.ko.md && \
       contains_pattern '#888' docs/coverage-matrix.md docs/lessons/README.md "$graph_abuser_lesson" && \
       contains_pattern 'graph/abuser-detection' .github/workflows/Examples.yml && \
       contains_pattern '"issue_numbers": \[888\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' "$graph_abuser_build" "$graph_abuser_readme" "$graph_abuser_readme_ko" "$graph_abuser_lesson" "$graph_abuser_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$graph_abuser_build" "$graph_abuser_model" "$graph_abuser_service" "$graph_abuser_suspend_service" "$graph_abuser_tests" "$graph_abuser_readme" "$graph_abuser_readme_ko" "$graph_abuser_lesson" "$graph_abuser_review" && \
       [ -f "$graph_abuser_lesson" ] && [ -f "$graph_abuser_review" ]; then
      echo "Graph abuser algorithm execution observation example and lesson are registered."
    else
      echo "ERROR: Graph abuser algorithm execution observation contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Tokenizer dictionary preload readiness guard ==="
    tokenizer_readiness="kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/readiness/TokenizerDictionaryReadiness.kt"
    tokenizer_tests="kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/readiness/TokenizerDictionaryReadinessTest.kt"
    tokenizer_readme="kotlin/text-processing/README.md"
    tokenizer_readme_ko="kotlin/text-processing/README.ko.md"
    tokenizer_lesson="docs/lessons/2026-09-05-issue-889-dictionary-preload-readiness.md"
    tokenizer_review="docs/superpowers/specs/2026-09-05-issue-889-dictionary-preload-readiness-implementation-review.md"
    if contains_pattern 'TokenizerDictionaryReadiness' "$tokenizer_readiness" "$tokenizer_tests" "$tokenizer_readme" "$tokenizer_readme_ko" && \
       contains_pattern 'KoreanProcessor\.preload' "$tokenizer_readiness" "$tokenizer_readme" "$tokenizer_readme_ko" && \
       contains_pattern 'JapaneseProcessor\.preload' "$tokenizer_readiness" "$tokenizer_readme" "$tokenizer_readme_ko" && \
       contains_pattern 'NOT_READY' "$tokenizer_readiness" "$tokenizer_tests" "$tokenizer_readme" "$tokenizer_readme_ko" && \
       contains_pattern 'LOADING' "$tokenizer_readiness" "$tokenizer_tests" "$tokenizer_readme" "$tokenizer_readme_ko" && \
       contains_pattern 'CancellationException' "$tokenizer_readiness" "$tokenizer_tests" && \
       contains_pattern ':kotlin-text-processing:test' .github/workflows/Examples.yml scripts/smoke-validate.sh && \
       contains_pattern '#889' docs/coverage-matrix.md docs/lessons/README.md "$tokenizer_lesson" && \
       contains_pattern '"issue_numbers": \[889\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' "$tokenizer_readme" "$tokenizer_readme_ko" "$tokenizer_lesson" "$tokenizer_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$tokenizer_readiness" "$tokenizer_tests" "$tokenizer_readme" "$tokenizer_readme_ko" "$tokenizer_lesson" "$tokenizer_review" && \
       [ -f "$tokenizer_lesson" ] && [ -f "$tokenizer_review" ]; then
      echo "Tokenizer dictionary preload readiness example and lesson are registered."
    else
      echo "ERROR: Tokenizer dictionary preload readiness contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== Versioned text dictionary runtime guard ==="
    versioned_search="kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/search/VersionedMultilingualSearchIndex.kt"
    versioned_search_tests="kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/search/VersionedMultilingualSearchIndexTest.kt"
    versioned_detection="kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionService.kt"
    versioned_detection_tests="kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/detection/LanguageDetectionServiceTest.kt"
    versioned_moderation="spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/service/VersionedModerationDictionary.kt"
    versioned_service="spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/service/TextModerationService.kt"
    versioned_moderation_tests="spring-boot/text-moderation-api/src/test/kotlin/io/bluetape4k/workshop/textmoderation/service/VersionedModerationDictionaryTest.kt"
    versioned_service_tests="spring-boot/text-moderation-api/src/test/kotlin/io/bluetape4k/workshop/textmoderation/service/TextModerationServiceTest.kt"
    versioned_lesson="docs/lessons/2026-09-05-issue-890-versioned-dictionary-runtime.md"
    versioned_review="docs/superpowers/specs/2026-09-05-issue-890-versioned-dictionary-runtime-implementation-review.md"
    if contains_pattern 'VersionedDictionary' "$versioned_search" "$versioned_moderation" && \
       contains_pattern 'DictionarySnapshot' "$versioned_search" "$versioned_moderation" && \
       contains_pattern 'reload\(DictionarySnapshot' "$versioned_search" "$versioned_moderation" && \
       contains_pattern 'versions\.snapshot\(\)' "$versioned_search" "$versioned_moderation" && \
       contains_pattern 'historyCapacity' "$versioned_search" "$versioned_moderation" "$versioned_search_tests" "$versioned_moderation_tests" && \
       contains_pattern 'VersionedSearchResult' "$versioned_search" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md && \
       contains_pattern 'result\.version' "$versioned_search_tests" && \
       contains_pattern 'SnapshotKoreanNounTokenizer' "$versioned_search" && \
       contains_pattern 'koreanDictionary' "$versioned_search" "$versioned_search_tests" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md && \
       contains_pattern 'detectorLock' "$versioned_detection" "$versioned_service" && \
       contains_pattern '여러 동기 caller' "$versioned_detection_tests" && \
       contains_pattern 'toBoundedSnapshot|maxTotalDocumentCharacters|maxTotalKoreanNounCharacters' "$versioned_search" && \
       contains_pattern 'mutable source' "$versioned_search_tests" && \
       contains_pattern 'mutable words' "$versioned_moderation_tests" && \
       contains_pattern 'analyzeWithVersion' "$versioned_service" "$versioned_service_tests" spring-boot/text-moderation-api/README.md spring-boot/text-moderation-api/README.ko.md && \
       contains_pattern '@Autowired' "$versioned_service" && \
       contains_pattern 'bluetape4k\.text\.core' kotlin/text-processing/build.gradle.kts spring-boot/text-moderation-api/build.gradle.kts && \
       contains_pattern 'stale|Stale' "$versioned_search_tests" "$versioned_moderation_tests" "$versioned_lesson" "$versioned_review" && \
       contains_pattern 'previousRevision' "$versioned_search" "$versioned_moderation" "$versioned_moderation_tests" "$versioned_review" && \
       contains_pattern 'kotlin/text-processing' .github/workflows/Examples.yml && \
       contains_pattern 'spring-boot/text-moderation-api' .github/workflows/Examples.yml && \
       contains_pattern '#890' docs/coverage-matrix.md docs/lessons/README.md "$versioned_lesson" && \
       contains_pattern '"issue_numbers": \[890\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' kotlin/text-processing/README.md kotlin/text-processing/README.ko.md spring-boot/text-moderation-api/README.md spring-boot/text-moderation-api/README.ko.md "$versioned_lesson" "$versioned_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$versioned_search" "$versioned_search_tests" "$versioned_detection" "$versioned_detection_tests" "$versioned_moderation" "$versioned_service" "$versioned_moderation_tests" "$versioned_service_tests" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md spring-boot/text-moderation-api/README.md spring-boot/text-moderation-api/README.ko.md "$versioned_lesson" "$versioned_review" && \
       [ -f "$versioned_lesson" ] && [ -f "$versioned_review" ]; then
      echo "Versioned text dictionary runtime examples and lesson are registered."
    else
      echo "ERROR: Versioned text dictionary runtime contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== NFKC source-offset normalization guard ==="
    nfkc_filter="kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilter.kt"
    nfkc_filter_tests="kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/filter/AbuseWordFilterTest.kt"
    nfkc_redaction="kotlin/text-processing/src/main/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipeline.kt"
    nfkc_redaction_tests="kotlin/text-processing/src/test/kotlin/io/bluetape4k/workshop/text/redaction/SensitiveTextRedactionPipelineTest.kt"
    nfkc_properties="spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/config/TextModerationProperties.kt"
    nfkc_config="spring-boot/text-moderation-api/src/main/kotlin/io/bluetape4k/workshop/textmoderation/config/TextModerationConfig.kt"
    nfkc_context_tests="spring-boot/text-moderation-api/src/test/kotlin/io/bluetape4k/workshop/textmoderation/TextModerationContextTest.kt"
    nfkc_lesson="docs/lessons/2026-09-05-issue-891-nfkc-offset-normalization.md"
    nfkc_review="docs/superpowers/specs/2026-09-05-issue-891-nfkc-offset-normalization-implementation-review.md"
    if contains_pattern 'normalization: NormalizationForm = NormalizationForm\.NFC' "$nfkc_filter" "$nfkc_properties" && \
       contains_pattern 'keywordNormalization: NormalizationForm = NormalizationForm\.NFC' "$nfkc_redaction" && \
       contains_pattern 'policy\.keywordNormalization' "$nfkc_redaction" && \
       contains_pattern 'properties\.normalization' "$nfkc_config" && \
       contains_pattern 'NormalizationForm\.NFKC' "$nfkc_filter_tests" "$nfkc_redaction_tests" && \
       contains_pattern 'normalization=NFKC' "$nfkc_context_tests" && \
       contains_pattern '㈜' "$nfkc_filter_tests" "$nfkc_redaction_tests" "$nfkc_context_tests" kotlin/text-processing/README.ko.md spring-boot/text-moderation-api/README.ko.md && \
       contains_pattern 'U\+3231' kotlin/text-processing/README.md spring-boot/text-moderation-api/README.md && \
       contains_pattern '\\u3231' kotlin/text-processing/README.md spring-boot/text-moderation-api/README.md && \
       contains_pattern '1_025' "$nfkc_filter_tests" "$nfkc_redaction_tests" && \
       contains_pattern 'shouldNotContain raw' "$nfkc_filter_tests" "$nfkc_redaction_tests" && \
       contains_pattern '#891' docs/coverage-matrix.md docs/lessons/README.md "$nfkc_lesson" && \
       contains_pattern 'kotlin/text-processing' .github/workflows/Examples.yml && \
       contains_pattern 'spring-boot/text-moderation-api' .github/workflows/Examples.yml && \
       contains_pattern '"issue_numbers": \[891\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' kotlin/text-processing/README.md kotlin/text-processing/README.ko.md spring-boot/text-moderation-api/README.md spring-boot/text-moderation-api/README.ko.md "$nfkc_lesson" "$nfkc_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$nfkc_filter" "$nfkc_filter_tests" "$nfkc_redaction" "$nfkc_redaction_tests" "$nfkc_properties" "$nfkc_config" "$nfkc_context_tests" kotlin/text-processing/README.md kotlin/text-processing/README.ko.md spring-boot/text-moderation-api/README.md spring-boot/text-moderation-api/README.ko.md "$nfkc_lesson" "$nfkc_review" && \
       [ -f "$nfkc_lesson" ] && [ -f "$nfkc_review" ]; then
      echo "NFKC source-offset normalization examples and lesson are registered."
    else
      echo "ERROR: NFKC source-offset normalization contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== JaVers bounded history query guard ==="
    javers_order_build="exposed/javers-persistence-audit/build.gradle.kts"
    javers_order_service="exposed/javers-persistence-audit/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/OrderAuditService.kt"
    javers_order_repository="exposed/javers-persistence-audit/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/BoundedRedissonCdoSnapshotRepository.kt"
    javers_order_tests="exposed/javers-persistence-audit/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/persistence"
    javers_order_readme="exposed/javers-persistence-audit/README.md"
    javers_order_readme_ko="exposed/javers-persistence-audit/README.ko.md"
    javers_approval_service="exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/service/ProductPolicyApprovalService.kt"
    javers_approval_tests="exposed/javers-approval-workflow/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/approval"
    javers_approval_readme="exposed/javers-approval-workflow/README.md"
    javers_approval_readme_ko="exposed/javers-approval-workflow/README.ko.md"
    javers_history_lesson="docs/lessons/2026-09-05-issue-892-javers-history-limit.md"
    javers_history_review="docs/superpowers/specs/2026-09-05-issue-892-javers-history-limit-implementation-review.md"
    if contains_pattern '@JvmOverloads' "$javers_order_service" "$javers_approval_service" && \
       contains_pattern '\.limit\(limit\)' "$javers_order_service" "$javers_approval_service" && \
       contains_pattern 'limit in 1\.\.MAX_HISTORY_LIMIT' "$javers_order_service" "$javers_approval_service" && \
       contains_pattern 'range\(-queryParams\.limit\(\), -1\)' "$javers_order_repository" && \
       contains_pattern 'supportsBoundedExactInstanceHistory' "$javers_order_repository" && \
       contains_pattern 'delegate\.getStateHistory' "$javers_order_repository" && \
       contains_pattern 'decodeCount' "$javers_order_tests" && \
       contains_pattern 'unsupported query parameters delegate' "$javers_order_tests" && \
       contains_pattern 'newest-first' "$javers_order_readme" "$javers_order_readme_ko" "$javers_approval_readme" "$javers_approval_readme_ko" "$javers_history_lesson" && \
       contains_pattern 'authorization.*redaction' "$javers_order_readme" "$javers_order_readme_ko" "$javers_approval_readme" "$javers_approval_readme_ko" && \
       contains_pattern 'libs\.bluetape4k\.redisson' "$javers_order_build" && \
       contains_pattern ':exposed-javers-approval-workflow:test' .github/workflows/Examples.yml && \
       contains_pattern ':exposed-javers-persistence-audit:test' .github/workflows/Examples.yml && \
       contains_pattern '#892' docs/coverage-matrix.md docs/lessons/README.md "$javers_history_lesson" && \
       contains_pattern '"issue_numbers": \[892\]' docs/ecosystem-reuse-train.json && \
       contains_pattern '2\.0\.0' "$javers_order_readme" "$javers_order_readme_ko" "$javers_approval_readme" "$javers_approval_readme_ko" "$javers_history_lesson" "$javers_history_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$javers_order_build" "$javers_order_service" "$javers_order_repository" "$javers_order_tests" "$javers_order_readme" "$javers_order_readme_ko" "$javers_approval_service" "$javers_approval_tests" "$javers_approval_readme" "$javers_approval_readme_ko" "$javers_history_lesson" "$javers_history_review" && \
       [ -f "$javers_history_lesson" ] && [ -f "$javers_history_review" ]; then
      echo "JaVers bounded history query examples and lesson are registered."
    else
      echo "ERROR: JaVers bounded history query contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== JaVers Kafka snapshot projection guard ==="
    javers_kafka_pipeline="exposed/javers-persistence-audit/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/KafkaRedisOrderAuditPipeline.kt"
    javers_kafka_test="exposed/javers-persistence-audit/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/KafkaRedisOrderAuditPipelineTest.kt"
    javers_kafka_lesson="docs/lessons/2026-09-06-issue-893-javers-kafka-projection.md"
    javers_kafka_review="docs/superpowers/specs/2026-09-06-issue-893-javers-kafka-projection-implementation-review.md"
    if contains_pattern 'libs\.bluetape4k\.javers\.persistence\.kafka' "$javers_order_build" && \
       contains_pattern 'libs\.bluetape4k\.lettuce' "$javers_order_build" && \
       contains_pattern 'KafkaRedisOrderAuditPipeline' "$javers_kafka_pipeline" "$javers_kafka_test" && \
       contains_pattern 'KafkaCdoSnapshotProjector' "$javers_kafka_pipeline" "$javers_kafka_test" && \
       contains_pattern 'LettuceCdoSnapshotRepository' "$javers_kafka_pipeline" "$javers_kafka_test" && \
       contains_pattern 'validatedProjectionConfigs' "$javers_kafka_pipeline" && \
       contains_pattern 'pendingProjection = AtomicBoolean\(true\)' "$javers_kafka_pipeline" && \
       contains_pattern 'DEFAULT_MAX_IDLE_POLLS = 3' "$javers_kafka_pipeline" && \
       contains_pattern 'FailOnSecondProjectionRepository' "$javers_kafka_test" && \
       contains_pattern 'initial empty poll' "$javers_kafka_test" && \
       contains_pattern 'multi partition topic is rejected' "$javers_kafka_test" && \
       contains_pattern 'same group before offsets advance' "$javers_kafka_test" && \
       contains_pattern 'restart.*existing Kafka backlog' "$javers_kafka_test" && \
       contains_pattern 'single-partition' "$javers_order_readme" "$javers_order_readme_ko" "$javers_kafka_lesson" && \
       contains_pattern 'commit-unknown' "$javers_order_readme" "$javers_order_readme_ko" "$javers_kafka_lesson" && \
       contains_pattern '#290' "$javers_order_readme" "$javers_order_readme_ko" "$javers_kafka_lesson" && \
       contains_pattern '#893' docs/coverage-matrix.md docs/lessons/README.md "$javers_kafka_lesson" && \
       contains_pattern '"issue_numbers": \[893\]' docs/ecosystem-reuse-train.json && \
       contains_pattern ':exposed-javers-persistence-audit:test' .github/workflows/Examples.yml && \
       contains_pattern '2\.0\.0' "$javers_order_readme" "$javers_order_readme_ko" "$javers_kafka_lesson" "$javers_kafka_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$javers_order_build" "$javers_kafka_pipeline" "$javers_kafka_test" "$javers_order_readme" "$javers_order_readme_ko" "$javers_kafka_lesson" "$javers_kafka_review" && \
       [ -f "$javers_kafka_lesson" ] && [ -f "$javers_kafka_review" ]; then
      echo "JaVers Kafka snapshot projection example and lesson are registered."
    else
      echo "ERROR: JaVers Kafka snapshot projection contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== JaVers Redis head metadata fail-closed guard ==="
    javers_order_factory="exposed/javers-persistence-audit/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/RedisOrderAuditFactory.kt"
    javers_head_validator="exposed/javers-persistence-audit/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/RedisOrderAuditHeadValidator.kt"
    javers_head_redisson_test="exposed/javers-persistence-audit/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/OrderAuditServiceTest.kt"
    javers_head_lettuce_test="exposed/javers-persistence-audit/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/persistence/KafkaRedisOrderAuditPipelineTest.kt"
    javers_head_lesson="docs/lessons/2026-09-06-issue-894-javers-redis-head-fail-closed.md"
    javers_head_review="docs/superpowers/specs/2026-09-06-issue-894-javers-redis-head-fail-closed-implementation-review.md"
    if contains_pattern 'requireConsistentOrderAuditHead' "$javers_head_validator" "$javers_order_factory" "$javers_kafka_pipeline" && \
       contains_pattern 'persisted Order snapshots exist without head metadata' "$javers_head_validator" "$javers_head_redisson_test" "$javers_head_lettuce_test" && \
       contains_pattern 'malformed Redisson commit id fails closed' "$javers_head_redisson_test" && \
       contains_pattern 'malformed Lettuce sequence fails closed' "$javers_head_lettuce_test" && \
       contains_pattern 'snapshots without.*head metadata' "$javers_head_redisson_test" "$javers_head_lettuce_test" && \
       contains_pattern 'startup/rebuild' "$javers_order_readme" "$javers_order_readme_ko" && \
       contains_pattern 'startup' "$javers_head_lesson" && \
       contains_pattern '#894' docs/coverage-matrix.md docs/lessons/README.md "$javers_head_lesson" && \
       contains_pattern '"issue_numbers": \[894\]' docs/ecosystem-reuse-train.json && \
       contains_pattern ':exposed-javers-persistence-audit:test' .github/workflows/Examples.yml && \
       contains_pattern '2\.0\.0' "$javers_order_readme" "$javers_order_readme_ko" "$javers_head_lesson" "$javers_head_review" && \
       ! contains_pattern '2\.1\.0(-SNAPSHOT)?' "$javers_order_build" "$javers_order_factory" "$javers_kafka_pipeline" "$javers_head_validator" "$javers_head_redisson_test" "$javers_head_lettuce_test" "$javers_order_readme" "$javers_order_readme_ko" "$javers_head_lesson" "$javers_head_review" && \
       [ -f "$javers_head_lesson" ] && [ -f "$javers_head_review" ]; then
      echo "JaVers Redis head metadata fail-closed example and lesson are registered."
    else
      echo "ERROR: JaVers Redis head metadata fail-closed contract is missing or stale."
      exit 1
    fi

    echo ""
    echo "=== README broken image links ==="
    broken=0
    while IFS= read -r readme; do
      dir=$(dirname "$readme")
      while IFS= read -r link; do
        [[ "$link" =~ ^http ]] && continue
        if [[ ! -f "$dir/$link" ]]; then
          echo "BROKEN: $readme → $link"
          broken=$((broken + 1))
        fi
      done < <(extract_image_links "$readme" 2>/dev/null || true)
    done < <(fd README.md . --exclude .worktrees --exclude build)
    if [ "$broken" -eq 0 ]; then
      echo "No broken image links found."
    else
      echo "ERROR: $broken broken link(s) found."
      exit 1
    fi
    ;;

  diagram-qa)
    run "node scripts/validate-readme-diagram-qa.mjs"
    run "node scripts/validate-usage-billing-microservices-readme.mjs"
    ;;

  help|*)
    echo "Usage: $0 <group>"
    echo ""
    echo "Groups:"
    echo "  compile          Compile all modules (no tests)"
    echo "  all-smoke        All no-Testcontainers modules"
    echo "  data-access      Data Access (in-memory)"
    echo "  data-access-full Data Access (Testcontainers)"
    echo "  spring-boot      Spring Boot Operations (smoke)"
    echo "  serialization    Jackson / JSON / Okio"
    echo "  messaging        Kafka (Testcontainers)"
    echo "  commerce         Commerce lifecycles (PostgreSQL Testcontainers)"
    echo "  optimization     Planning contracts + Field Service + Last-mile routing + Warehouse Allocation + Shift Coverage + Clinic Appointment Solver (Java 25, deterministic + PostgreSQL fixtures)"
    echo "  operations       Job console core + Spring MVC/Ktor (PostgreSQL/Redis Testcontainers)"
    echo "  leader-full      Job safety lab default + PostgreSQL/Redis integration tests"
    echo "  async            Coroutines / Vert.x"
    echo "  observability    Micrometer / Virtual Threads"
    echo "  aws              AWS local-first examples"
    echo "  redis            Redis / Redisson / Rate Limit"
    echo "  high-contention-contract  Contract and producer tests (not part of all-smoke)"
    echo "  high-contention-ci        Full CI matrix; requires a unique HIGH_CONTENTION_RUN_ID"
    echo "  stale-check      Gradle project count + README link check"
    echo "  diagram-qa       Changed README diagram QA evidence"
    ;;
esac
