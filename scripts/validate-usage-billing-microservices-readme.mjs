#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const module = "commerce/usage-billing-microservices-composition-tests";
const docs = ["README.md", "README.ko.md"].map((name) => ({
  name,
  text: fs.readFileSync(path.join(root, module, name), "utf8"),
}));
const diagrams = [
  "usage-billing-microservices-architecture-01",
  "usage-billing-microservices-outbox-inbox-state-01",
  "usage-billing-microservices-delivery-01",
  "usage-billing-microservices-poison-recovery-01",
  "usage-billing-microservices-correction-01",
  "usage-billing-microservices-extraction-01",
];
const failures = [];

for (const doc of docs) {
  for (const diagram of diagrams) {
    for (const extension of ["png", "svg"]) {
      const relative = `../../docs/images/readme-diagrams/${diagram}.${extension}`;
      if (!doc.text.includes(relative)) failures.push(`${doc.name}: missing ${relative}`);
      const asset = path.join(root, "docs/images/readme-diagrams", `${diagram}.${extension}`);
      if (!fs.existsSync(asset) || fs.statSync(asset).size === 0) failures.push(`missing asset ${asset}`);
    }
  }
  for (const required of [
    "Java 25",
    "Spring Boot",
    "ExposedJdbcRepository",
    "PostgreSQL",
    "Kafka",
    "outbox",
    "inbox",
    "exactly-once",
    "rollback",
  ]) {
    if (!doc.text.includes(required)) failures.push(`${doc.name}: missing contract ${required}`);
  }
}

for (const [file, required] of [
  ["README.md", "commerce-usage-billing-microservices"],
  ["README.ko.md", "commerce-usage-billing-microservices"],
  ["commerce/README.md", "usage-billing-microservices"],
  ["commerce/README.ko.md", "usage-billing-microservices"],
  ["scripts/smoke-validate.sh", ":commerce-usage-billing-microservices-composition-tests:integrationTest"],
  ["scripts/smoke-validate.sh", ":commerce-usage-billing-microservices-composition-tests:koverXmlReport"],
  [".github/workflows/Examples.yml", ":commerce-usage-billing-microservices-composition-tests:integrationTest"],
  [".github/workflows/Examples.yml", ":commerce-usage-billing-microservices-composition-tests:koverXmlReport"],
  [".github/workflows/Examples.yml", "commerce/usage-billing-microservices-composition-tests/build/reports/kover/report.xml"],
  [".github/workflows/nightly.yml", ":commerce-usage-billing-microservices-composition-tests:integrationTest"],
  [".github/workflows/nightly.yml", ":commerce-usage-billing-microservices-composition-tests:koverXmlReport"],
  [".github/workflows/nightly.yml", "commerce/usage-billing-microservices-composition-tests/build/reports/kover/report.xml"],
  ["scripts/generate-usage-billing-microservices-diagrams.mjs", "generated usage billing diagrams"],
  ["docs/lessons/2026-07-23-issue-555-usage-billing-microservices.md", "Local database가 correctness authority다"],
  ["docs/review/2026-07-22-issue-555-usage-billing-microservices-implementation-review.md", "여섯 관점 검토"],
]) {
  if (!fs.readFileSync(path.join(root, file), "utf8").includes(required)) {
    failures.push(`${file}: missing ${required}`);
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log(`usage billing microservices README validation passed: locales=${docs.length}, diagrams=${diagrams.length}`);
