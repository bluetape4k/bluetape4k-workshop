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
      :leader-backend-comparison-lab:test \
      :leader-k8s-lease-micrometer:test \
      :leader-job-safety-lab:test \
      :leader-tenant-scheduler:test \
      :okio-examples:test \
      :graph-event-lineage:test \
      :graph-io-pipeline:test \
      :graph-recommendation:test \
      :graph-knowledge-graph:test \
      :graph-social-network:test \
      :kotlin-design-patterns:test \
      :micrometer-observation:test \
      :operations-job-console-core:test \
      :spring-boot-application-event-demo:test \
      :spring-boot-cache-caffeine:test \
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
      :spring-data-redis-examples:test \
      --continue --max-workers=1"
    ;;

  spring-boot)
    run "$GRADLEW \
      :spring-boot-application-event-demo:test \
      :spring-boot-cache-caffeine:test \
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
    for module in image-processing/barcode-api commerce/shared aws/kinesis-coroutines aws/bedrock-converse aws/settings-boundary leader/backend-comparison-lab optimization/field-service-dispatch optimization/last-mile-routing optimization/warehouse-allocation optimization/shift-coverage optimization/clinic-appointment-solver messaging/kafka-multi-broker-failover; do
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
