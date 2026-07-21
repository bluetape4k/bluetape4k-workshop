#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleDir = path.join(root, "commerce", "concert-ticket-flash-sale");
const sections = [
  "Prerequisites and Java 25",
  "Run, seed, and reset",
  "Join the waiting room",
  "Purchase and replay a lost response",
  "Reconcile timeout and late approval",
  "Cancel, refund, revoke, and restock",
  "Operator invariant and backlog checks",
  "State mapping: internal state to customer action",
  "Redis and PostgreSQL authority",
  "Production security boundary",
  "Microservice extraction guide",
];
const required = [
  "ExposedJdbcRepository", "bluetape4k-lettuce", "Java 25", "UNKNOWN", "NEVER_ISSUED",
  "REFUND_PENDING", "REFUND_QUARANTINED", "no-store", "loopback", "JWT/OAuth2",
  "transactional outbox", "#1065",
];
const documents = ["README.md", "README.ko.md"];
const failures = [];
const contents = documents.map(file => {
  const target = path.join(moduleDir, file);
  if (!fs.existsSync(target)) {
    failures.push(`missing ${path.relative(root, target)}`);
    return "";
  }
  return fs.readFileSync(target, "utf8");
});

for (const [index, content] of contents.entries()) {
  let cursor = -1;
  for (const section of sections) {
    const next = content.indexOf(`## ${section}`);
    if (next < 0) failures.push(`${documents[index]}: missing section ${section}`);
    if (next >= 0 && next <= cursor) failures.push(`${documents[index]}: section order failed at ${section}`);
    cursor = next;
  }
  for (const phrase of required) {
    if (!content.includes(phrase)) failures.push(`${documents[index]}: missing ${phrase}`);
  }
}

const imageTargets = content => [...content.matchAll(/!\[[^\]]*\]\(([^)]+)\)/g)].map(match => match[1]);
const englishImages = imageTargets(contents[0]);
const koreanImages = imageTargets(contents[1]);
if (JSON.stringify(englishImages) !== JSON.stringify(koreanImages)) failures.push("README image target/order mismatch");
if (englishImages.length !== 6) failures.push(`expected 6 diagrams, found ${englishImages.length}`);
for (const target of englishImages) {
  const png = path.resolve(moduleDir, target);
  if (!fs.existsSync(png)) failures.push(`missing image ${target}`);
  const svg = png.replace(/\.png$/, ".svg");
  if (!fs.existsSync(svg)) failures.push(`missing SVG pair ${path.relative(moduleDir, svg)}`);
}

const html = fs.readFileSync(path.join(moduleDir, "src/main/resources/static/index.html"), "utf8");
const script = fs.readFileSync(path.join(moduleDir, "src/main/resources/static/app.js"), "utf8");
const styles = fs.readFileSync(path.join(moduleDir, "src/main/resources/static/styles.css"), "utf8");
if (!html.includes('aria-live="polite"') || !html.includes("data-polling-fallback")) failures.push("missing accessible live/fallback contract");
if (!styles.includes("prefers-reduced-motion") || !styles.includes(":focus-visible")) failures.push("missing reduced-motion/focus contract");
if (script.includes("innerHTML") || script.includes("localStorage") || script.includes("document.cookie")) failures.push("browser script violates in-memory/safe DOM contract");

if (failures.length) {
  console.error("Ticket flash-sale runbook validation failed:");
  failures.forEach(failure => console.error(`- ${failure}`));
  process.exit(1);
}
console.log(`Ticket flash-sale runbook validation PASS: 2 locales, ${sections.length} ordered sections, 6 SVG/PNG pairs.`);
