#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const MODULE = "commerce/pre-generated-voucher-pool";
const moduleDir = path.resolve(process.cwd(), MODULE);
const REQUIRED_SECTIONS = [
  ["Architecture", "Architecture"],
  ["Contention and recovery", "경합과 복구"],
  ["Import and generation", "가져오기와 생성"],
  ["Customer workflow", "고객 워크플로"],
  ["Lost reveal replacement", "유실된 reveal 교체"],
  ["Error status retry and action catalog", "오류 상태 retry 및 조치 catalog"],
  ["Revoke preview confirm and progress", "revoke preview confirm과 진행 상태"],
  ["Reconciliation", "Reconciliation"],
  ["Redis outage", "Redis 장애"],
  ["Health and degraded state", "Health와 degraded 상태"],
  ["Alerts and diagnostics", "알림과 진단"],
  ["Retention and purge", "보존과 purge"],
  ["Migration and rollback", "Migration과 rollback"],
  ["Backup and restore", "Backup과 restore"],
  ["Unsupported scope", "지원하지 않는 범위"],
  ["Verify", "검증"],
];
const REQUIRED_ROUTES = [
  ["reservation create", "/api/v1/campaigns/{campaignId}/reservations", "/api/v1/campaigns/$CAMPAIGN_ID/reservations"],
  ["allocation create", "/api/v1/reservations/{reservationId}/allocate", "/api/v1/reservations/$RESERVATION_ID/allocate"],
  ["one-time reveal", "/api/v1/allocations/{allocationId}/code-reveals", "/api/v1/allocations/$ALLOCATION_ID/code-reveals"],
  ["lost reveal replacement", "/api/v1/allocations/{allocationId}/replacements", "/api/v1/allocations/$ALLOCATION_ID/replacements"],
  ["revoke preview", "/operator/api/v1/campaigns/{campaignId}/revoke-preview", "/operator/api/v1/batches/$BATCH_ID/revoke-preview"],
  ["revoke confirm", "/operator/api/v1/campaigns/{campaignId}/revoke", "/operator/api/v1/batches/$BATCH_ID/revoke"],
  ["reconciliation", "/operator/api/v1/reconciliation/run"],
];
const REQUIRED_COMMANDS = [
  "VOUCHER_POOL_DATABASE_URL",
  "VOUCHER_POOL_OPERATOR_SECRET",
  "VOUCHER_POOL_OPERATOR_GUARD",
  "/actuator/health/readiness",
  "./gradlew :commerce-pre-generated-voucher-pool:test --max-workers=1",
  ":commerce-pre-generated-voucher-pool:migrationCompatibilityTest",
  ":commerce-pre-generated-voucher-pool:stressTest",
  ":commerce-pre-generated-voucher-pool:koverXmlReport",
];
const STABLE_CODES = [
  "COMMAND_IN_PROGRESS",
  "IDEMPOTENCY_FINGERPRINT_CONFLICT",
  "REPLAY_WINDOW_EXPIRED",
  "POOL_BUSY",
  "POOL_EXHAUSTED",
  "USER_LIMIT_REACHED",
  "STALE_REVISION",
  "CAMPAIGN_NOT_ACTIVE",
  "CAMPAIGN_PAUSED",
  "CAMPAIGN_REVOKING",
  "CAMPAIGN_REVOKED",
  "BATCH_PAUSED",
  "BATCH_EXPIRING",
  "BATCH_REVOKED",
  "BATCH_EXPIRED",
  "BATCH_FAILED_RETRYABLE",
  "BATCH_FAILED_TERMINAL",
  "RESERVATION_EXPIRED",
  "ALLOCATION_EXPIRED",
  "WRONG_OWNER",
  "SCOPE_NOT_FOUND",
  "RATE_LIMITED",
  "BACKEND_TIMEOUT",
  "KEY_MATERIAL_UNAVAILABLE",
  "CIPHERTEXT_INVALID",
  "ALREADY_REVEALED",
];
const FORBIDDEN = [/raw.*code.*log/i, /GenericContainer/, /MockMvc/];

const documents = [
  { locale: "English", file: path.join(moduleDir, "README.md"), sectionIndex: 0 },
  { locale: "Korean", file: path.join(moduleDir, "README.ko.md"), sectionIndex: 1 },
];
const failures = [];
const contents = new Map();
const errorMatrixSignatures = new Map();
const curlBlockSignatures = new Map();

const tableCells = (line) => line.split("|").slice(1, -1).map((cell) => cell.trim());

function sectionTable(content, heading) {
  const lines = content.split("\n");
  const headingIndex = lines.findIndex((line) => line.trim() === `## ${heading}`);
  if (headingIndex < 0) return null;
  const headerIndex = lines.findIndex((line, index) => index > headingIndex && /^\|.*\|$/.test(line.trim()));
  if (headerIndex < 0 || !/^\|(?:\s*:?-+:?\s*\|)+$/.test(lines[headerIndex + 1]?.trim() ?? "")) return null;
  const rows = [];
  for (let index = headerIndex + 2; index < lines.length && /^\|.*\|$/.test(lines[index].trim()); index += 1) {
    rows.push(tableCells(lines[index]));
  }
  return { headers: tableCells(lines[headerIndex]), rows };
}

function headerIndex(headers, patterns) {
  return headers.findIndex((header) => patterns.some((pattern) => pattern.test(header)));
}

function bashCurlBlocks(content) {
  return [...content.matchAll(/```bash\n([\s\S]*?)```/g)]
    .map((match) => match[1].trim().replace(/[ \t]+$/gm, ""))
    .filter((block) => /\bcurl\b/.test(block));
}

function errorMatrixSignature(table) {
  return table.rows.map((row) => [row[0], row[1]]);
}

for (const document of documents) {
  if (!fs.existsSync(document.file)) {
    failures.push(`${document.locale}: missing ${path.relative(process.cwd(), document.file)}`);
    continue;
  }
  const content = fs.readFileSync(document.file, "utf8");
  contents.set(document.locale, content);

  for (const section of REQUIRED_SECTIONS) {
    const heading = section[document.sectionIndex];
    if (!content.includes(`## ${heading}`)) failures.push(`${document.locale}: missing section '${heading}'`);
  }
  for (const [label, ...routes] of REQUIRED_ROUTES) {
    if (!routes.some((route) => content.includes(route))) {
      failures.push(`${document.locale}: missing representative route ${label}`);
    }
  }
  for (const command of REQUIRED_COMMANDS) {
    if (!content.includes(command)) failures.push(`${document.locale}: missing command or configuration ${command}`);
  }
  for (const code of STABLE_CODES) {
    if (!content.includes(`\`${code}\``)) {
      failures.push(`${document.locale}: stable code ${code} is missing from the error catalog`);
    }
  }
  for (const forbidden of FORBIDDEN) {
    if (forbidden.test(content)) failures.push(`${document.locale}: forbidden runbook pattern ${forbidden}`);
  }

  const errorHeading = REQUIRED_SECTIONS[5][document.sectionIndex];
  const errorTable = sectionTable(content, errorHeading);
  if (errorTable === null) {
    failures.push(`${document.locale}: missing error status/retry/action table`);
  } else {
    const requiredColumns = [
      [/code/i, /코드/],
      [/status/i, /상태/],
      [/retry/i, /재시도/],
      [/action/i, /조치/],
    ];
    for (const patterns of requiredColumns) {
      if (headerIndex(errorTable.headers, patterns) < 0) {
        failures.push(`${document.locale}: error catalog is missing column ${patterns[0]}`);
      }
    }
    errorTable.rows.forEach((row, rowIndex) => {
      if (row.length !== requiredColumns.length || row.some((cell) => cell.length === 0)) {
        failures.push(`${document.locale}: error catalog row ${rowIndex + 1} must contain status, code, retry, and action`);
      }
    });
    errorMatrixSignatures.set(document.locale, errorMatrixSignature(errorTable));
  }

  const alertHeading = REQUIRED_SECTIONS[10][document.sectionIndex];
  const alertTable = sectionTable(content, alertHeading);
  if (alertTable === null || alertTable.rows.length === 0) {
    failures.push(`${document.locale}: missing alerts and diagnostics rows`);
  } else {
    const alertColumns = [
      [/threshold/i, /임계값/],
      [/safe diagnostic/i, /안전한 진단/],
      [/authoritative query/i, /권위.*query/i],
      [/bounded.*action/i, /bounded.*조치/i],
      [/recovery signal/i, /복구.*signal/i],
    ];
    const indexes = alertColumns.map((patterns) => headerIndex(alertTable.headers, patterns));
    indexes.forEach((index, column) => {
      if (index < 0) failures.push(`${document.locale}: alerts table is missing column ${alertColumns[column][0]}`);
    });
    alertTable.rows.forEach((row, rowIndex) => {
      indexes.forEach((index, column) => {
        if (index >= 0 && !row[index]) {
          failures.push(`${document.locale}: alert row ${rowIndex + 1} has empty ${alertColumns[column][0]} evidence`);
        }
      });
    });
  }

  curlBlockSignatures.set(document.locale, bashCurlBlocks(content));
}

const imageTargets = (content) => [...content.matchAll(/!\[[^\]]*\]\(([^)]+)\)/g)].map((match) => match[1]);
const englishImages = imageTargets(contents.get("English") ?? "");
const koreanImages = imageTargets(contents.get("Korean") ?? "");
if (JSON.stringify(englishImages) !== JSON.stringify(koreanImages)) {
  failures.push("README image targets differ between English and Korean");
}
for (const target of englishImages) {
  if (!fs.existsSync(path.resolve(moduleDir, target))) failures.push(`missing README image target ${target}`);
}
if (englishImages.length !== 2) failures.push(`expected 2 README diagrams, found ${englishImages.length}`);

const englishFenceCount = (contents.get("English")?.match(/^```/gm) ?? []).length;
const koreanFenceCount = (contents.get("Korean")?.match(/^```/gm) ?? []).length;
if (englishFenceCount !== koreanFenceCount || englishFenceCount % 2 !== 0) {
  failures.push(`code fence parity failed: English=${englishFenceCount}, Korean=${koreanFenceCount}`);
}
const englishCurlCount = (contents.get("English")?.match(/\bcurl\b/g) ?? []).length;
const koreanCurlCount = (contents.get("Korean")?.match(/\bcurl\b/g) ?? []).length;
if (englishCurlCount < 4 || englishCurlCount !== koreanCurlCount) {
  failures.push(`customer curl parity failed: English=${englishCurlCount}, Korean=${koreanCurlCount}`);
}
if (JSON.stringify(curlBlockSignatures.get("English")) !== JSON.stringify(curlBlockSignatures.get("Korean"))) {
  failures.push("bash curl blocks differ between English and Korean");
}
if (JSON.stringify(errorMatrixSignatures.get("English")) !== JSON.stringify(errorMatrixSignatures.get("Korean"))) {
  failures.push("error status/code row matrix differs between English and Korean");
}

if (failures.length > 0) {
  console.error("Voucher pool runbook validation failed:");
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(
  `Voucher pool runbook validation PASS: ${documents.length} locales, ${REQUIRED_SECTIONS.length} sections, ` +
    `${STABLE_CODES.length} stable codes, ${REQUIRED_ROUTES.length} routes, ${englishImages.length} diagrams.`,
);
