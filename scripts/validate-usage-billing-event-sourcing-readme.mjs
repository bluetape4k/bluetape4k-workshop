#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleRoot = path.join(root, "commerce/usage-metering-billing-event-sourcing");
const failures = [];
const docs = ["README.md", "README.ko.md"].map((name) => ({
  name,
  text: fs.readFileSync(path.join(moduleRoot, name), "utf8"),
}));
const diagrams = [
  "usage-billing-event-sourcing-architecture-01",
  "usage-billing-event-sourcing-aggregate-state-01",
  "usage-billing-event-sourcing-command-sequence-01",
  "usage-billing-event-sourcing-replay-sequence-01",
  "usage-billing-event-sourcing-projection-state-01",
  "usage-billing-event-sourcing-rebuild-01",
  "usage-billing-event-sourcing-correction-01",
  "usage-billing-event-sourcing-microservices-01",
];

for (const doc of docs) {
  for (const diagram of diagrams) {
    const png = `../../docs/images/readme-diagrams/${diagram}.png`;
    const svg = `../../docs/images/readme-diagrams/${diagram}.svg`;
    if (!doc.text.includes(png)) failures.push(`${doc.name}: missing PNG embed ${png}`);
    if (!doc.text.includes(svg)) failures.push(`${doc.name}: missing SVG source link ${svg}`);
  }
  for (const required of [
    "Java 25",
    "Spring Boot",
    "ExposedJdbcRepository",
    "PostgreSQL",
    "globalPosition",
    "previousHash",
    "EventCodecRegistry",
    "Idempotency-Key",
    "BUILDING",
    "ACTIVE",
    "X-Wait-For-Position",
    "10,000",
    "#555",
    "#1070",
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
  ["README.md", "commerce-usage-metering-billing-event-sourcing"],
  ["README.ko.md", "commerce-usage-metering-billing-event-sourcing"],
  ["commerce/README.md", "usage-metering-billing-event-sourcing"],
  ["commerce/README.ko.md", "usage-metering-billing-event-sourcing"],
  ["scripts/smoke-validate.sh", ":commerce-usage-metering-billing-event-sourcing:stressTest"],
  [".github/workflows/Examples.yml", ":commerce-usage-metering-billing-event-sourcing:integrationTest"],
  [".github/workflows/nightly.yml", ":commerce-usage-metering-billing-event-sourcing:stressTest"],
  ["docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md", "112 projects"],
];
for (const [file, needle] of repositorySurfaces) {
  const text = fs.readFileSync(path.join(root, file), "utf8");
  if (!text.includes(needle)) failures.push(`${file}: missing ${needle}`);
}

for (const workflow of [".github/workflows/Examples.yml", ".github/workflows/nightly.yml"]) {
  const text = fs.readFileSync(path.join(root, workflow), "utf8");
  for (const needle of [
    ":commerce-usage-metering-billing-event-sourcing:koverXmlReport",
    "commerce/usage-metering-billing-event-sourcing/build/test-results/integrationTest/*.xml",
    "commerce/usage-metering-billing-event-sourcing/build/reports/kover/report.xml",
  ]) {
    if (!text.includes(needle)) failures.push(`${workflow}: missing ${needle}`);
  }
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(
  `event-sourced billing README validation passed: locales=${docs.length}, diagrams=${diagrams.length}, headings=${englishHeadings.length}`,
);

function headings(markdown) {
  return markdown.split("\n").filter((line) => /^## /.test(line));
}
