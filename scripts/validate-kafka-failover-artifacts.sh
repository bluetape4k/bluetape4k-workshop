#!/usr/bin/env bash

# CI 업로드를 위해 Kafka failover 증거를 제한된 범위로 검증하고 스테이징합니다.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: validate-kafka-failover-artifacts.sh [options]

Options:
  --module PATH    module path relative to the repository
  --run-id ID      run directory name under build/reports/kafka-failover
  --staging PATH   new directory receiving sanitized artifacts
  -h, --help       show this help

The command fails closed on missing reports, unsafe paths, malformed evidence,
raw/canary content, or an existing staging directory. It never prints matched
artifact content.
EOF
}

module="messaging/kafka-multi-broker-failover"
run_id=""
staging=""

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --module)
      [[ "$#" -ge 2 ]] || { echo "--module requires a value." >&2; exit 2; }
      module="$2"
      shift 2
      ;;
    --run-id)
      [[ "$#" -ge 2 ]] || { echo "--run-id requires a value." >&2; exit 2; }
      run_id="$2"
      shift 2
      ;;
    --staging)
      [[ "$#" -ge 2 ]] || { echo "--staging requires a value." >&2; exit 2; }
      staging="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$module" in
  ""|/*|..|../*|*/../*|*\\..\\*) echo "module path must stay inside the repository." >&2; exit 2 ;;
esac
if [[ -z "$run_id" ]]; then
  echo "--run-id is required." >&2
  exit 2
fi
if [[ ! "$run_id" =~ ^[A-Za-z0-9._-]{1,128}$ || "$run_id" == "." || "$run_id" == ".." ]]; then
  echo "run-id must be a bounded identifier." >&2
  exit 2
fi

module_root="$module"
artifact_root="$module_root/build/reports/kafka-failover"
run_dir="$artifact_root/$run_id"
evidence="$run_dir/evidence.jsonl"
performance="$run_dir/performance.jsonl"
test_results="$module_root/build/test-results/test"
test_reports="$module_root/build/reports/tests/test"
staging="${staging:-$artifact_root/sanitized}"
expected_digest="sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9"
expected_topic="kafka-failover-reference"

[[ -d "$module_root" ]] || { echo "module directory is missing." >&2; exit 1; }
[[ -d "$run_dir" && ! -L "$run_dir" ]] || { echo "run artifact directory is missing or unsafe." >&2; exit 1; }
[[ -s "$evidence" && ! -L "$evidence" ]] || { echo "evidence.jsonl is missing or empty." >&2; exit 1; }
[[ -s "$performance" && ! -L "$performance" ]] || { echo "performance.jsonl is missing or empty." >&2; exit 1; }
[[ "$(find "$run_dir" -maxdepth 1 -type f -name 'broker-*.log' -print | wc -l | tr -d ' ')" -ge 3 ]] || {
  echo "three broker summaries are required." >&2
  exit 1
}
[[ -d "$test_results" && ! -L "$test_results" ]] || { echo "JUnit result directory is missing or unsafe." >&2; exit 1; }
[[ -d "$test_reports" && ! -L "$test_reports" ]] || { echo "JUnit HTML report directory is missing or unsafe." >&2; exit 1; }
[[ -n "$(find "$test_results" -type f -name '*.xml' -print -quit)" ]] || { echo "JUnit XML is missing." >&2; exit 1; }
[[ -n "$(find "$test_reports" -type f -name '*.html' -print -quit)" ]] || { echo "JUnit HTML is missing." >&2; exit 1; }

case "$staging" in
  ""|.|..|/|../*|*/../*|*\\..\\*) echo "staging path is unsafe." >&2; exit 2 ;;
esac
if [[ "$staging" == "$run_dir" || "$staging" == "$artifact_root" || "$staging" == "$module_root" ]]; then
  echo "staging path must be separate from source artifacts." >&2
  exit 2
fi
if [[ "$staging" == "$run_dir"/* || "$staging" == "$test_results"/* || "$staging" == "$test_reports"/* ]]; then
  echo "staging path must not be nested inside source artifacts." >&2
  exit 2
fi
if [[ -e "$staging" || -L "$staging" ]]; then
  echo "staging directory already exists; remove it explicitly before retrying." >&2
  exit 1
fi

for root_dir in "$run_dir" "$test_results" "$test_reports"; do
  if [[ -n "$(find "$root_dir" -type l -print -quit)" ]]; then
    echo "artifact trees must not contain symbolic links." >&2
    exit 1
  fi
done

python3 - "$evidence" "$performance" "$run_id" "$expected_digest" "$expected_topic" "$run_dir" "$test_results" "$test_reports" <<'PY'
import hashlib
import html
import os
import re
import sys
from urllib.parse import unquote
import json

evidence_path, performance_path, run_id, expected_digest, expected_topic, *roots = sys.argv[1:]
fields = [
    "runId",
    "scenario",
    "phase",
    "image",
    "imageDigest",
    "topic",
    "partition",
    "nodeCount",
    "leader",
    "replicas",
    "isr",
    "coordinator",
    "assignmentCount",
    "rawDeliveryCount",
    "appliedCount",
    "conflictCount",
    "retryCount",
    "status",
]
phases = [
    "startup",
    "topic-ready",
    "assignment-ready",
    "prefix-acked",
    "fault-injected",
    "recovery",
    "suffix-acked",
    "replacement-ready",
    "isr-restored",
    "terminal",
]
scenarios = {"data-leader-failover", "group-coordinator-failover"}
errors = []

performance_fields = [
    "runId",
    "scenario",
    "phase",
    "elapsedMs",
    "deadlineRemainingMs",
    "adminRoundTripCount",
    "ackCount",
    "pollCount",
    "retryCount",
    "cleanupMs",
    "maxBufferedRecords",
    "maxBufferedBytes",
]

try:
    with open(evidence_path, encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle if line.strip()]
except (OSError, UnicodeError, json.JSONDecodeError):
    rows = []
    errors.append("evidence.jsonl could not be parsed")

last_phase = {}
terminal = {}
for index, row in enumerate(rows, start=1):
    if not isinstance(row, dict) or list(row) != fields:
        errors.append("evidence field order is invalid at line %d" % index)
        continue
    scenario = row.get("scenario")
    phase = row.get("phase")
    if row.get("runId") != run_id:
        errors.append("evidence runId mismatch at line %d" % index)
    if not isinstance(scenario, str) or scenario not in scenarios:
        errors.append("evidence scenario is not allowlisted at line %d" % index)
        continue
    if row.get("image") != "apache/kafka" or row.get("imageDigest") != expected_digest:
        errors.append("evidence image identity mismatch at line %d" % index)
    if row.get("topic") != expected_topic:
        errors.append("evidence topic mismatch at line %d" % index)
    if not isinstance(phase, str) or phase not in phases:
        errors.append("evidence phase is not allowlisted at line %d" % index)
        continue
    previous = last_phase.get(scenario, -1)
    current = phases.index(phase)
    if current <= previous:
        errors.append("evidence phase is not strictly increasing at line %d" % index)
    last_phase[scenario] = current
    if phase == "terminal":
        if row.get("status") not in {"PASS", "FAIL"}:
            errors.append("terminal evidence status is invalid at line %d" % index)
        terminal[scenario] = True
    elif not isinstance(row.get("status"), str) or not row.get("status"):
        errors.append("evidence status is empty at line %d" % index)

if not rows:
    errors.append("evidence.jsonl has no rows")
if set(last_phase) != scenarios:
    errors.append("evidence scenarios are incomplete")
for scenario in last_phase:
    if not terminal.get(scenario):
        errors.append("scenario has no terminal evidence")

try:
    with open(performance_path, encoding="utf-8") as handle:
        performance_rows = [json.loads(line) for line in handle if line.strip()]
except (OSError, UnicodeError, json.JSONDecodeError):
    performance_rows = []
    errors.append("performance.jsonl could not be parsed")

performance_scenarios = set()
for index, row in enumerate(performance_rows, start=1):
    if not isinstance(row, dict) or list(row) != performance_fields:
        errors.append("performance field order is invalid at line %d" % index)
        continue
    scenario = row.get("scenario")
    if not isinstance(scenario, str):
        errors.append("performance scenario is not a string at line %d" % index)
        continue
    performance_scenarios.add(scenario)
    if row.get("runId") != run_id:
        errors.append("performance runId mismatch at line %d" % index)
    if scenario not in scenarios:
        errors.append("performance scenario is not allowlisted at line %d" % index)
    if row.get("phase") != "terminal":
        errors.append("performance phase must be terminal at line %d" % index)
    for field in performance_fields[3:]:
        value = row.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            errors.append("performance counter is invalid for %s at line %d" % (field, index))
if not performance_rows:
    errors.append("performance.jsonl has no rows")
if performance_scenarios != scenarios:
    errors.append("performance scenarios are incomplete")

broker_paths = sorted(
    os.path.join(os.path.dirname(performance_path), name)
    for name in os.listdir(os.path.dirname(performance_path))
    if name.startswith("broker-") and name.endswith(".log")
)
summary_fields = ["runId", "nodeId", "alias", "image", "imageDigest", "running"]
if len(broker_paths) < 3:
    errors.append("three broker summaries are required")
for broker_path in broker_paths:
    try:
        with open(broker_path, encoding="utf-8") as handle:
            lines = [line.strip() for line in handle if line.strip()]
    except (OSError, UnicodeError):
        lines = []
    if len(lines) != 1:
        errors.append("broker summary must contain one line")
        continue
    tokens = lines[0].split()
    if any("=" not in token for token in tokens):
        errors.append("broker summary contains an unstructured token")
        continue
    if [token.split("=", 1)[0] for token in tokens if "=" in token] != summary_fields:
        errors.append("broker summary field order is invalid")
        continue
    values = dict(token.split("=", 1) for token in tokens)
    if values.get("runId") != run_id or values.get("image") != "apache/kafka" or values.get("imageDigest") != expected_digest:
        errors.append("broker summary identity mismatch")

canaries = [
    ("payload", re.compile(r"payload", re.I)),
    ("event-id", re.compile(r"event\s*id|eventid", re.I)),
    ("bootstrap", re.compile(r"bootstrap[.]servers", re.I)),
    ("endpoint", re.compile(r"127(?:[.]|%2e)0(?:[.]|%2e)0(?:[.]|%2e)1(?::|%3a)|localhost(?::|%3a)[0-9]+", re.I)),
    ("credential", re.compile(r"(?:credential|password|secret)\s*[:=]\s*[\"']?[^<\s\"']+", re.I)),
    ("owner-token", re.compile(r"owner[\s_-]*token", re.I)),
    ("environment", re.compile(r"kafka_[a-z0-9_]+", re.I)),
    ("exception", re.compile(r"(?:[A-Za-z0-9_.]+Exception\b|[A-Za-z0-9_.]+Error\b|stacktrace|caused\s+by|suppressed\s+exception)", re.I)),
    ("raw-log", re.compile(r"raw[\s_-]*log", re.I)),
]

def normalized(data):
    text = data.decode("utf-8", "replace")
    for _ in range(3):
        decoded = unquote(text)
        if decoded == text:
            break
        text = decoded
    text = html.unescape(text)
    text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match.group(1), 16)), text)
    text = re.sub(r"\\x([0-9a-fA-F]{2})", lambda match: chr(int(match.group(1), 16)), text)
    return text.lower()

scanned = 0
for root in roots:
    for directory, _, names in os.walk(root, followlinks=False):
        for name in names:
            file_path = os.path.join(directory, name)
            relative = os.path.relpath(file_path, root)
            lowered_name = unquote(relative).lower()
            if re.search(r"(^|[/_.-])raw[-_. ]?log(?:$|[.])", lowered_name) or lowered_name.endswith(".tmp"):
                errors.append("unsafe artifact filename in %s" % root)
            try:
                with open(file_path, "rb") as handle:
                    data = handle.read()
            except OSError:
                errors.append("artifact file could not be read in %s" % root)
                continue
            scanned += 1
            text = normalized(data)
            labels = [label for label, pattern in canaries if pattern.search(text)]
            if labels:
                digest = hashlib.sha256(data).hexdigest()
                errors.append("canary in %s sha256=%s labels=%s" % (root, digest, ",".join(labels)))

if errors:
    for error in errors:
        print("ERROR: " + error, file=sys.stderr)
    sys.exit(1)
print("artifact scan passed: rows=%d scenarios=%d files=%d" % (len(rows), len(last_phase), scanned))
PY

staging_parent="$(dirname "$staging")"
mkdir -p "$staging_parent"
temporary_staging="$(mktemp -d "${staging}.tmp.XXXXXX")"
cleanup_staging() {
  if [[ -n "${temporary_staging:-}" && -d "$temporary_staging" ]]; then
    rm -rf "$temporary_staging"
  fi
}
trap cleanup_staging EXIT

mkdir -p "$temporary_staging/kafka-failover" "$temporary_staging/test-results" "$temporary_staging/test-reports"
cp -R "$run_dir/." "$temporary_staging/kafka-failover/"
cp -R "$test_results/." "$temporary_staging/test-results/"
cp -R "$test_reports/." "$temporary_staging/test-reports/"
mv "$temporary_staging" "$staging"
temporary_staging=""
trap - EXIT

file_count="$(find "$staging" -type f | wc -l | tr -d ' ')"
echo "Kafka failover artifact sanitization passed: runId=$run_id files=$file_count staging=$staging"
