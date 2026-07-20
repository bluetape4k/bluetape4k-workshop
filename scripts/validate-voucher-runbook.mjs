#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const moduleDir = path.join(root, "commerce", "promotion-voucher-campaign");
const documents = [
  {
    locale: "English",
    file: path.join(moduleDir, "README.md"),
    headings: [
      "What This Teaches",
      "Architecture",
      "Sequence Diagram",
      "Prerequisites",
      "Startup And Configuration",
      "Seed And Reset",
      "Customer Curl Walkthrough",
      "Lost-Response Idempotent Retry",
      "Allocation And Redemption Review",
      "Reconciliation",
      "Redis And PostgreSQL Outages",
      "SSE Reconnect And Polling Fallback",
      "Browser Walkthrough",
      "Stable Error And Retry Catalog",
      "Operator Runbook",
      "Scenario Cookbook",
      "Unsupported Scope",
      "Troubleshooting",
      "Verify",
    ],
    phrases: [
      "5 minutes",
      "10 minutes",
      "2 scheduling cycles",
      "60s acquisition budget",
      "cleanup leak",
      "Backup, Restore, And Key Retention",
      "signal",
      "query or command",
      "warning threshold",
      "decision",
      "action",
      "recovery check",
    ],
  },
  {
    locale: "Korean",
    file: path.join(moduleDir, "README.ko.md"),
    headings: [
      "학습 목표",
      "Architecture",
      "Sequence Diagram",
      "사전 준비",
      "시작과 설정",
      "Seed와 Reset",
      "Customer curl walkthrough",
      "응답 유실 멱등 재시도",
      "Allocation과 Redemption Review",
      "Reconciliation",
      "Redis와 PostgreSQL 장애",
      "SSE 재연결과 Polling fallback",
      "Browser walkthrough",
      "안정적인 Error와 Retry catalog",
      "Operator Runbook",
      "Scenario Cookbook",
      "지원하지 않는 범위",
      "Troubleshooting",
      "검증",
    ],
    phrases: [
      "5분",
      "10분",
      "2 scheduling cycle",
      "60초 acquisition budget",
      "cleanup leak",
      "Backup, Restore, Key Retention",
      "Signal",
      "Query 또는 command",
      "Warning threshold",
      "판단",
      "조치",
      "복구 확인",
    ],
  },
];

const stableCodes = [
  "INVALID_REQUEST",
  "OPERATOR_ACCESS_DENIED",
  "RESOURCE_NOT_FOUND",
  "CAMPAIGN_NOT_FOUND",
  "CLAIM_NOT_FOUND",
  "REVIEW_NOT_FOUND",
  "COMMAND_IN_PROGRESS",
  "IDEMPOTENCY_FINGERPRINT_CONFLICT",
  "CAMPAIGN_ALREADY_EXISTS",
  "CAMPAIGN_PAUSED",
  "CAMPAIGN_NOT_ACTIVE",
  "CAMPAIGN_NOT_STARTED",
  "CAMPAIGN_ENDED",
  "CAPACITY_EXHAUSTED",
  "PER_USER_LIMIT_REACHED",
  "INVALID_CODE",
  "CLAIM_EXPIRED",
  "CLAIM_REVOKED",
  "ALREADY_REDEEMED",
  "CONCURRENT_MODIFICATION",
  "CODE_ALREADY_ACKNOWLEDGED",
  "RECONCILIATION_IN_PROGRESS",
  "STALE_REVISION",
  "RATE_LIMITED",
  "DATABASE_BULKHEAD_REJECTED",
  "AUTHORITATIVE_BACKEND_UNAVAILABLE",
  "IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE",
  "SSE_CAPACITY_REJECTED",
  "SERVICE_SHUTTING_DOWN",
  "INTERNAL_ERROR",
];

const scenarios = [
  "allocation-review",
  "redemption-review",
  "redis-outage",
  "bloom-false-positive",
  "delayed-duplicate-out-of-order",
  "pause-allocation-race",
  "redeem-revoke-race",
];
const subsystems = ["PostgreSQL", "Redis", "Leader", "Worker", "SSE", "Keys"];
const failures = [];
const contents = new Map();

for (const document of documents) {
  if (!fs.existsSync(document.file)) {
    failures.push(`${document.locale}: missing ${path.relative(root, document.file)}`);
    continue;
  }
  const content = fs.readFileSync(document.file, "utf8");
  contents.set(document.locale, content);

  for (const heading of document.headings) {
    if (!content.includes(`## ${heading}`)) {
      failures.push(`${document.locale}: missing section '${heading}'`);
    }
  }
  for (const phrase of document.phrases) {
    if (!content.toLowerCase().includes(phrase.toLowerCase())) {
      failures.push(`${document.locale}: missing runbook contract '${phrase}'`);
    }
  }
  for (const code of stableCodes) {
    if (!content.includes(`\`${code}\``)) {
      failures.push(`${document.locale}: missing stable code ${code}`);
    }
  }
  for (const scenario of scenarios) {
    if (!content.includes(`\`${scenario}\``)) {
      failures.push(`${document.locale}: missing scenario ${scenario}`);
    }
  }
  for (const subsystem of subsystems) {
    if (!new RegExp(`^\\| ${subsystem} \\|`, "m").test(content)) {
      failures.push(`${document.locale}: missing runbook row ${subsystem}`);
    }
  }
  if (!/loopback/i.test(content) || !/IAM/.test(content) || !/OAuth/.test(content) || !/CSRF/.test(content)) {
    failures.push(`${document.locale}: missing loopback/IAM/OAuth/CSRF workshop warning`);
  }
}

const imageTargets = (content) => [...content.matchAll(/!\[[^\]]*\]\(([^)]+)\)/g)].map((match) => match[1]);
const englishImages = imageTargets(contents.get("English") ?? "");
const koreanImages = imageTargets(contents.get("Korean") ?? "");
if (JSON.stringify(englishImages) !== JSON.stringify(koreanImages)) {
  failures.push("README image targets differ between English and Korean");
}
for (const target of englishImages) {
  if (!fs.existsSync(path.resolve(moduleDir, target))) {
    failures.push(`missing README image target ${target}`);
  }
}
if (englishImages.length !== 2) {
  failures.push(`expected 2 README diagrams, found ${englishImages.length}`);
}

const englishFenceCount = (contents.get("English")?.match(/^```/gm) ?? []).length;
const koreanFenceCount = (contents.get("Korean")?.match(/^```/gm) ?? []).length;
if (englishFenceCount !== koreanFenceCount || englishFenceCount % 2 !== 0) {
  failures.push(`code fence parity failed: English=${englishFenceCount}, Korean=${koreanFenceCount}`);
}

if (failures.length > 0) {
  console.error("Voucher runbook validation failed:");
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log(
  `Voucher runbook validation PASS: ${documents.length} locales, ${stableCodes.length} stable codes, ` +
    `${scenarios.length} scenarios, ${subsystems.length} subsystem rows, ${englishImages.length} diagrams.`,
);
