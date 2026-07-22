#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleRoot = path.join(root, "commerce/usage-metering-billing-ledger");
const failures = [];
const docs = ["README.md", "README.ko.md"].map((name) => ({
  name,
  text: fs.readFileSync(path.join(moduleRoot, name), "utf8"),
}));
const diagrams = [
  "usage-metering-billing-architecture-01",
  "usage-metering-billing-state-01",
  "usage-metering-billing-ingestion-sequence-01",
  "usage-metering-billing-close-reconciliation-01",
];

for (const doc of docs) {
  for (const diagram of diagrams) {
    const embed = `../../docs/images/readme-diagrams/${diagram}.png`;
    if (!doc.text.includes(embed)) failures.push(`${doc.name}: missing ${embed}`);
  }
  for (const required of [
    "Java 25",
    "ExposedJdbcRepository",
    "PostgreSQL",
    "receivedAt",
    "occurredAt",
    "REQUIRES_NEW",
    "FAILED_VALIDATION",
    "DEBIT_ADJUSTMENT",
    "Microservice",
  ]) {
    if (!doc.text.includes(required)) failures.push(`${doc.name}: missing contract ${required}`);
  }
}

for (const diagram of diagrams) {
  for (const extension of ["svg", "png"]) {
    const target = path.join(root, `docs/images/readme-diagrams/${diagram}.${extension}`);
    if (!fs.existsSync(target) || fs.statSync(target).size === 0) failures.push(`missing diagram ${target}`);
  }
}

const englishHeadings = headings(docs[0].text);
const koreanHeadings = headings(docs[1].text);
if (englishHeadings.length !== koreanHeadings.length) {
  failures.push(`heading parity: English=${englishHeadings.length}, Korean=${koreanHeadings.length}`);
}

const repositorySurfaces = [
  ["README.md", "commerce-usage-metering-billing-ledger"],
  ["README.ko.md", "commerce-usage-metering-billing-ledger"],
  ["scripts/smoke-validate.sh", ":commerce-usage-metering-billing-ledger:integrationTest"],
  [".github/workflows/Examples.yml", ":commerce-usage-metering-billing-ledger:integrationTest"],
  [".github/workflows/nightly.yml", ":commerce-usage-metering-billing-ledger:integrationTest"],
  ["docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md", "112 projects"],
];
for (const [file, needle] of repositorySurfaces) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  if (!text.includes(needle)) failures.push(`${file}: missing ${needle}`);
}

for (const workflow of [".github/workflows/Examples.yml", ".github/workflows/nightly.yml"]) {
  const text = fs.readFileSync(path.join(root, workflow), "utf8");
  if (!text.includes(":commerce-usage-metering-billing-ledger:koverXmlReport")) {
    failures.push(`${workflow}: missing metering Kover task`);
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`usage metering README validation passed: locales=${docs.length}, diagrams=${diagrams.length}, headings=${englishHeadings.length}`);

function headings(markdown) {
  return markdown.split("\n").filter((line) => /^## /.test(line));
}
