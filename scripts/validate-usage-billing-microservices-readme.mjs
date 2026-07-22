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
  "usage-billing-microservices-state-01",
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
  [".github/workflows/Examples.yml", ":commerce-usage-billing-microservices-composition-tests:integrationTest"],
  [".github/workflows/nightly.yml", ":commerce-usage-billing-microservices-composition-tests:integrationTest"],
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
