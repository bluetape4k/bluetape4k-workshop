#!/usr/bin/env bash
# smoke-validate.sh — Domain-group targeted test runner for bluetape4k-workshop
#
# Usage:
#   ./scripts/smoke-validate.sh <group>
#   ./scripts/smoke-validate.sh all-smoke     # all no-Testcontainers modules
#   ./scripts/smoke-validate.sh compile       # compile-only (no tests)
#   ./scripts/smoke-validate.sh stale-check   # Gradle project count + README link check
#   ./scripts/smoke-validate.sh diagram-qa    # changed README diagram QA evidence
#
# Groups: data-access  spring-boot  serialization  messaging  async  observability  aws  redis
# Each group runs with --continue so a single failure does not abort the rest.

set -euo pipefail

GRADLEW="./gradlew"
MAX_WORKERS="${MAX_WORKERS:-2}"

run() {
  echo "▶ $*"
  eval "$*"
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
      :aws-cloudwatch-imds-observability:test \
      :aws-eventbridge-scheduler:test \
      :aws-s3-vectors-access-grants:test \
      :kotlin-flow-extensions-event-aggregation:test \
      :kotlin-flow-extensions-metrics-sampling:test \
      :kotlin-flow-extensions-search-pipeline:test \
      :leader-backend-comparison-lab:test \
      :leader-k8s-lease-micrometer:test \
      :leader-tenant-scheduler:test \
      :okio-examples:test \
      :graph-event-lineage:test \
      :graph-io-pipeline:test \
      :kotlin-design-patterns:test \
      :micrometer-observation:test \
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

  observability)
    run "$GRADLEW \
      :micrometer-observation:test \
      :aws-cloudwatch-imds-observability:test \
      :aws-eventbridge-scheduler:test \
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
      :aws-s3-spring-cloud:test \
      :aws-sqs-sns-coroutines:test \
      :aws-s3-vectors-access-grants:test \
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

  stale-check)
    echo "=== Gradle project count ==="
    count=$("$GRADLEW" projects --console=plain 2>/dev/null | grep -Ec "Project ':" || true)
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
      if rg -l "$m" --include="*.md" . 2>/dev/null | grep -qv "\.worktrees\|docs/lessons\|docs/superpowers"; then
        echo "STALE REF: $m"
        stale=$((stale + 1))
      fi
    done
    [ "$stale" -eq 0 ] && echo "No stale refs found." || echo "WARNING: $stale stale ref(s) found."

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
      done < <(rg '!\[.*\]\(([^ ")\t]+)' "$readme" -o -r '$1' 2>/dev/null || true)
    done < <(fd README.md . --exclude .worktrees --exclude build)
    [ "$broken" -eq 0 ] && echo "No broken image links found." || echo "WARNING: $broken broken link(s) found."
    ;;

  diagram-qa)
    run "node scripts/validate-readme-diagram-qa.mjs"
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
    echo "  async            Coroutines / Vert.x"
    echo "  observability    Micrometer / Virtual Threads"
    echo "  aws              AWS local-first examples"
    echo "  redis            Redis / Redisson / Rate Limit"
    echo "  stale-check      Gradle project count + README link check"
    echo "  diagram-qa       Changed README diagram QA evidence"
    ;;
esac
