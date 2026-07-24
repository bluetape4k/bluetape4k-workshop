#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleRoot = path.join(root, "commerce/event-sourced-promotion-voucher-campaign");
const failures = [];
const docs = ["README.md", "README.ko.md"].map((name) => ({
  name,
  text: read(path.join(moduleRoot, name)),
}));
const diagrams = [
  "event-sourced-promotion-voucher-architecture-01",
  "event-sourced-promotion-voucher-command-projection-sequence-01",
  "event-sourced-promotion-voucher-rebuild-state-01",
];

for (const doc of docs) {
  for (const diagram of diagrams) {
    requireText(doc.name, doc.text, `../../docs/images/readme-diagrams/${diagram}.png`);
  }
  for (const contract of [
    "Java 25",
    "Spring-managed HikariCP",
    "ExposedJdbcRepository",
    "X-Stream-Position",
    "X-Projection-Position",
    "X-Projection-Lag",
    "X-Min-Stream-Position",
    "202 PROJECTION_PENDING",
    "Last-Event-ID",
    "X-Expected-Generation-Token",
    "POISON_RETRY_BACKOFF",
    "REPLAY_KEY_UNAVAILABLE",
    "BUILDING",
    "VALIDATING",
    "ACTIVE",
    "RETIRED",
    "EXPLAIN (ANALYZE, BUFFERS)",
    "-PeventSourcedStress=true",
  ]) {
    requireText(doc.name, doc.text, contract);
  }
}

const englishHeadings = headings(docs[0].text);
const koreanHeadings = headings(docs[1].text);
if (englishHeadings.join("\n") !== koreanHeadings.join("\n")) {
  failures.push(`README heading parity differs:\nEN=${englishHeadings.join(",")}\nKO=${koreanHeadings.join(",")}`);
}

for (const diagram of diagrams) {
  for (const extension of ["svg", "png"]) {
    const asset = path.join(root, `docs/images/readme-diagrams/${diagram}.${extension}`);
    if (!fs.existsSync(asset) || fs.statSync(asset).size === 0) failures.push(`missing diagram ${asset}`);
  }
}

const sourceContracts = [
  {
    file: "EventSourcedCampaignQueryController.kt",
    needles: [
      'MIN_STREAM_POSITION_HEADER = "X-Min-Stream-Position"',
      'STREAM_POSITION_HEADER = "X-Stream-Position"',
      'PROJECTION_POSITION_HEADER = "X-Projection-Position"',
      'PROJECTION_LAG_HEADER = "X-Projection-Lag"',
      '@GetMapping("/campaigns/{campaignId}")',
      '"PROJECTION_PENDING"',
    ],
  },
  {
    file: "EventSourcedCampaignCommandController.kt",
    needles: [
      '@PostMapping("/campaigns")',
      '@PostMapping("/campaigns/{campaignId}/activate")',
      '"COMMAND_IN_PROGRESS"',
      '"REPLAY_KEY_UNAVAILABLE"',
    ],
  },
  {
    file: "EventSourcedVoucherCommandController.kt",
    needles: [
      '@PostMapping("/campaigns/{campaignId}/claims")',
      '@PostMapping("/claims/{claimId}/redeem")',
      '@PostMapping("/claims/{claimId}/release")',
    ],
  },
  {
    file: "EventSourcedRebuildController.kt",
    needles: [
      '@RequestMapping("/operator/api/v1/projections/{projection}/rebuilds")',
      '@PostMapping("/{generation}/cancel")',
      '@PostMapping("/{generation}/resume")',
      '"STALE_GENERATION_TOKEN"',
    ],
  },
  {
    file: "EventSourcedProjectionRecoveryController.kt",
    needles: [
      '@PostMapping("/poison-events/{eventId}/retry")',
      '@PostMapping("/reconciliation")',
      '"POISON_RETRY_BACKOFF"',
    ],
  },
  {
    file: "EventSourcedEventStream.kt",
    needles: [
      '@GetMapping("/api/v1/campaigns/{campaignId}/events"',
      '"Last-Event-ID"',
      '"reset"',
    ],
  },
];

const webRoot = path.join(moduleRoot, "src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web");
for (const contract of sourceContracts) {
  const source = read(path.join(webRoot, contract.file));
  for (const needle of contract.needles) requireText(contract.file, source, needle);
}

const routes = [
  "POST /operator/api/v1/campaigns",
  "POST /operator/api/v1/campaigns/{campaignId}/activate",
  "POST /api/v1/campaigns/{campaignId}/claims",
  "POST /api/v1/claims/{claimId}/redeem",
  "POST /api/v1/claims/{claimId}/release",
  "GET /api/v1/campaigns/{campaignId}",
  "GET /api/v1/campaigns/{campaignId}/events",
  "POST /operator/api/v1/projections/{projection}/rebuilds",
];
for (const doc of docs) {
  for (const route of routes) requireText(doc.name, doc.text, route);
}

const build = read(path.join(moduleRoot, "build.gradle.kts"));
for (const contract of [
  "JavaLanguageVersion.of(25)",
  "jvmToolchain(25)",
  "alias(libs.plugins.kover)",
  "libs.exposed.spring.boot.jdbc",
  'runtimeOnly(libs.bluetape4k.virtualthread.jdk25)',
]) {
  requireText("module build.gradle.kts", build, contract);
}
if (/io\.github\.bluetape4k:[^"']+:[^"']+/.test(build)) {
  failures.push("module build.gradle.kts: explicit bluetape4k version is forbidden");
}
if (/platform\(libs\.bluetape4k\./.test(build)) {
  failures.push("module build.gradle.kts: individual bluetape4k BOM import is forbidden");
}

const repositorySurfaces = [
  ["settings.gradle.kts", 'includeModules("commerce", false, true)'],
  ["README.md", "commerce-event-sourced-promotion-voucher-campaign"],
  ["README.ko.md", "commerce-event-sourced-promotion-voucher-campaign"],
  ["commerce/README.md", "event-sourced-promotion-voucher-campaign"],
  ["commerce/README.ko.md", "event-sourced-promotion-voucher-campaign"],
  ["scripts/smoke-validate.sh", ":commerce-event-sourced-promotion-voucher-campaign:test"],
  [".github/workflows/Examples.yml", ":commerce-event-sourced-promotion-voucher-campaign:integrationTest"],
  [".github/workflows/nightly.yml", ":commerce-event-sourced-promotion-voucher-campaign:integrationTest"],
  ["docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md", "registers 119 projects"],
];
for (const [file, needle] of repositorySurfaces) requireText(file, read(path.join(root, file)), needle);

for (const workflow of [".github/workflows/Examples.yml", ".github/workflows/nightly.yml"]) {
  const text = read(path.join(root, workflow));
  requireText(workflow, text, ":commerce-event-sourced-promotion-voucher-campaign:koverXmlReport");
  requireText(workflow, text, "commerce/event-sourced-promotion-voucher-campaign/build/test-results/integrationTest/*.xml");
  requireText(workflow, text, "commerce/event-sourced-promotion-voucher-campaign/build/reports/kover/report.xml");
}

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log(
  `event-sourced voucher README validation passed: locales=${docs.length}, diagrams=${diagrams.length}, routes=${routes.length}, sourceFiles=${sourceContracts.length}, headings=${englishHeadings.length}`,
);

function read(file) {
  if (!fs.existsSync(file)) {
    failures.push(`missing file ${path.relative(root, file)}`);
    return "";
  }
  return fs.readFileSync(file, "utf8");
}

function requireText(label, text, needle) {
  if (!text.includes(needle)) failures.push(`${label}: missing ${needle}`);
}

function headings(markdown) {
  return markdown
    .split("\n")
    .filter((line) => /^## /.test(line))
    .map((line) => line.replace(/^## /, ""));
}
