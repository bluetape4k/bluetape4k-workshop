#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const ALLOWED_FIELDS = [
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
];
const PHASES = [
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
];
const args = parseArgs(process.argv.slice(2));

if (args.help || args.paths.length === 0) {
  console.log("Usage: node scripts/validate-kafka-recovery-conformance.mjs [--path <kind>] <evidence.jsonl> [...]");
  console.log("Kinds: broker-leader, broker-coordinator, transport");
  process.exit(args.help ? 0 : 2);
}

const failures = [];
const reports = args.paths.map((file) => validateFile(file, args));
if (failures.length > 0) {
  console.error(JSON.stringify({
    status: "FAIL",
    files: reports,
    failures,
  }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ status: "PASS", files: reports }, null, 2));

function validateFile(file, options) {
  const absolute = path.resolve(process.cwd(), file);
  if (!fs.existsSync(absolute)) {
    failures.push(`${file}: evidence file is missing`);
    return { file, rows: 0 };
  }
  const rows = fs.readFileSync(absolute, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.trim().length > 0)
    .map((line, index) => parseRow(file, index + 1, line));
  const validRows = rows.filter(Boolean);
  if (validRows.length === 0) return { file, rows: 0 };

  const runIds = new Set(validRows.map((row) => row.runId));
  if (runIds.size !== 1) failures.push(`${file}: runId must be stable for one evidence stream`);

  const streams = new Map();
  for (const row of validRows) {
    const stream = streams.get(row.scenario) ?? [];
    stream.push(row);
    streams.set(row.scenario, stream);
  }

  if (options.path === "transport") {
    failures.push(`${file}: transport evidence must be supplied through KafkaRecoveryConformanceFixture; 18-field broker evidence cannot claim it`);
    return { file, rows: validRows.length, runId: [...runIds][0], scenarios: [...streams.keys()] };
  }

  const selectedScenarios = options.path === "broker-leader"
    ? ["data-leader-failover"]
    : options.path === "broker-coordinator"
      ? ["group-coordinator-failover"]
      : [...streams.keys()];
  const scenarioReports = [];
  for (const scenario of selectedScenarios) {
    const stream = streams.get(scenario);
    if (!stream) {
      failures.push(`${file}: requested scenario stream is missing`);
      continue;
    }
    scenarioReports.push(validateScenario(file, stream, options));
  }
  if (!options.path) {
    for (const scenario of streams.keys()) {
      if (!["data-leader-failover", "group-coordinator-failover"].includes(scenario)) {
        failures.push(`${file}: unsupported scenario=${scenario}`);
      }
    }
  }

  return {
    file,
    rows: validRows.length,
    runId: [...runIds][0],
    scenarios: scenarioReports,
  };
}

function validateScenario(file, rows, options) {
  const phases = rows.map((row) => row.phase);
  if (phases.join("|") !== PHASES.join("|")) {
    failures.push(`${file}: phase order must be ${PHASES.join(" -> ")} for scenario=${rows[0].scenario}`);
  }
  const terminal = rows.at(-1);
  if (terminal.status !== "PASS") failures.push(`${file}: terminal status must be PASS for scenario=${terminal.scenario}`);
  if (terminal.nodeCount !== 3) failures.push(`${file}: terminal nodeCount must be 3 for scenario=${terminal.scenario}`);
  if (!Array.isArray(terminal.isr) || terminal.isr.length !== 3) {
    failures.push(`${file}: terminal ISR must contain three brokers for scenario=${terminal.scenario}`);
  }
  for (const row of rows) {
    if (row.rawDeliveryCount !== null && row.appliedCount !== null && row.rawDeliveryCount < row.appliedCount) {
      failures.push(`${file}: rawDeliveryCount cannot be lower than appliedCount at phase=${row.phase}`);
    }
    if (row.conflictCount !== null && row.conflictCount !== 0) {
      failures.push(`${file}: conflictCount must remain zero at phase=${row.phase}`);
    }
  }

  if (options.path === "broker-leader" || terminal.scenario === "data-leader-failover") {
    validateLeaderScenario(file, rows, terminal);
  } else if (options.path === "broker-coordinator" || terminal.scenario === "group-coordinator-failover") {
    validateCoordinatorScenario(file, rows, terminal);
  } else {
    failures.push(`${file}: unsupported scenario=${terminal.scenario}`);
  }

  return {
    scenario: terminal.scenario,
    rows: rows.length,
    terminal: {
      appliedCount: terminal.appliedCount,
      rawDeliveryCount: terminal.rawDeliveryCount,
      conflictCount: terminal.conflictCount,
      isrCount: terminal.isr.length,
    },
  };
}

function validateLeaderScenario(file, rows, terminal) {
  if (terminal.scenario !== "data-leader-failover") {
    failures.push(`${file}: broker-leader path must use scenario=data-leader-failover`);
  }
  if (terminal.appliedCount !== 8) failures.push(`${file}: data-leader terminal appliedCount must be 8`);
  const assignment = rows.find((row) => row.phase === "assignment-ready");
  const recovery = rows.find((row) => row.phase === "recovery");
  if (!assignment || !recovery || assignment.leader == null || recovery.leader == null) {
    failures.push(`${file}: leader path needs assignment-ready and recovery leaders`);
  } else if (assignment.leader === recovery.leader) {
    failures.push(`${file}: broker leader must change after fault injection`);
  }
}

function validateCoordinatorScenario(file, rows, terminal) {
  if (terminal.scenario !== "group-coordinator-failover") {
    failures.push(`${file}: broker-coordinator path must use scenario=group-coordinator-failover`);
  }
  if (terminal.appliedCount !== 6) failures.push(`${file}: coordinator terminal appliedCount must be 6`);
  const assignment = rows.find((row) => row.phase === "assignment-ready");
  const recovery = rows.find((row) => row.phase === "recovery");
  if (!assignment || !recovery || assignment.coordinator == null || recovery.coordinator == null) {
    failures.push(`${file}: coordinator path needs assignment-ready and recovery coordinators`);
  } else if (assignment.coordinator === recovery.coordinator) {
    failures.push(`${file}: broker coordinator must change after fault injection`);
  }
  if (assignment?.leader != null && recovery?.leader != null && assignment.leader !== recovery.leader) {
    failures.push(`${file}: selected data leader must remain stable during coordinator failover`);
  }
}

function parseRow(file, lineNumber, line) {
  let row;
  try {
    row = JSON.parse(line);
  } catch {
    failures.push(`${file}:${lineNumber}: invalid JSON`);
    return null;
  }
  const keys = Object.keys(row).sort();
  const expected = [...ALLOWED_FIELDS].sort();
  if (keys.join("|") !== expected.join("|")) {
    failures.push(`${file}:${lineNumber}: exact 18-field schema required`);
    return null;
  }
  if (!PHASES.includes(row.phase)) failures.push(`${file}:${lineNumber}: unsupported phase=${row.phase}`);
  if (typeof row.runId !== "string" || row.runId.length === 0) failures.push(`${file}:${lineNumber}: runId is required`);
  if (typeof row.scenario !== "string" || row.scenario.length === 0) failures.push(`${file}:${lineNumber}: scenario is required`);
  return row;
}

function parseArgs(values) {
  const paths = [];
  let pathKind = null;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--help" || value === "-h") return { help: true, paths: [], path: null };
    if (value === "--path") {
      pathKind = values[++index];
      if (!["broker-leader", "broker-coordinator", "transport"].includes(pathKind)) throw new Error(`unsupported --path=${pathKind}`);
    } else if (value.startsWith("-")) {
      throw new Error(`unknown option=${value}`);
    } else {
      paths.push(value);
    }
  }
  return { help: false, paths, path: pathKind };
}
