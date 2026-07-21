#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleRoot = path.join(root, "leader/job-safety-lab");
const diagramRoot = path.join(root, "docs/images/readme-diagrams");
const readmes = ["README.md", "README.ko.md"].map((name) => ({
  name,
  text: fs.readFileSync(path.join(moduleRoot, name), "utf8"),
}));

const scenarios = [
  "CROSS_JOB_COLLISION",
  "LEASE_OVERRUN",
  "DYNAMIC_TENANT",
  "REGION_PARTITION",
  "MIXED_VERSION_ROLLOUT",
  "NON_FENCEABLE_EFFECT",
];
const states = [
  "REQUESTED",
  "LEADER_ACQUIRED",
  "FENCE_ACQUIRED",
  "RUNNING",
  "COMMITTED",
  "EFFECT_PENDING",
  "RECONCILIATION_REQUIRED",
  "COMPLETED",
  "SKIPPED",
  "REJECTED",
  "FAILED",
];
const guarantees = ["mutual exclusion", "failover", "replay safety", "fencing", "durable completion"];
const diagrams = [
  "leader-job-safety-lab-architecture-01",
  "leader-job-safety-lab-state-01",
  "leader-job-safety-lab-takeover-sequence-01",
  "leader-job-safety-lab-microservices-01",
];
const commands = [
  "./gradlew :leader-job-safety-lab:bootRun",
  "./gradlew :leader-job-safety-lab:test",
  "./gradlew :leader-job-safety-lab:integrationTest --max-workers=1",
  "/api/job-safety/scenarios/LEASE_OVERRUN/run",
  "/api/job-safety/effects/deliver",
  "/api/job-safety/effects/reconcile",
  "/api/job-safety/scenarios/LEASE_OVERRUN/reset",
  "/api/job-safety/unsafe/scenarios/LEASE_OVERRUN/run",
];
const links = [
  "../tenant-scheduler",
  "../backend-comparison",
  "bluetape4k.github.io/pull/249",
  "bluetape4k-workshop/issues/548",
  "bluetape4k-projects/issues/1068",
];

const failures = [];
const requireAll = (readme, group, values) => {
  for (const value of values) {
    if (!readme.text.includes(value)) failures.push(`${readme.name}: missing ${group} '${value}'`);
  }
};

for (const readme of readmes) {
  requireAll(readme, "scenario", scenarios);
  requireAll(readme, "state", states);
  requireAll(readme, "guarantee", guarantees);
  requireAll(readme, "command", commands);
  requireAll(readme, "link", links);
  requireAll(readme, "operating boundary", [
    "Java 25",
    "Spring Boot",
    "ExposedJdbcRepository",
    "PostgreSQL",
    "Redis",
    "transactional outbox",
    "opaque",
    "operator",
    "namespace epoch",
    "minimum writer version",
  ]);

  for (const diagram of diagrams) {
    const pngReference = `../../docs/images/readme-diagrams/${diagram}.png`;
    if (!readme.text.includes(pngReference)) failures.push(`${readme.name}: missing diagram reference '${pngReference}'`);
  }
}

for (const diagram of diagrams) {
  for (const extension of ["svg", "png"]) {
    const asset = path.join(diagramRoot, `${diagram}.${extension}`);
    if (!fs.existsSync(asset) || fs.statSync(asset).size === 0) failures.push(`missing or empty asset: ${path.relative(root, asset)}`);
  }
}

for (const group of [scenarios, states, guarantees, commands, links, diagrams]) {
  for (const value of group) {
    const counts = readmes.map(({ text }) => text.split(value).length - 1);
    if (counts[0] !== counts[1]) failures.push(`locale parity mismatch for '${value}': EN=${counts[0]} KO=${counts[1]}`);
  }
}

if (failures.length > 0) {
  console.error(`job-safety-lab README validation failed (${failures.length})`);
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(`job-safety-lab README validation passed: ${readmes.length} locales, ${scenarios.length} scenarios, ${states.length} states, ${diagrams.length * 2} assets`);
