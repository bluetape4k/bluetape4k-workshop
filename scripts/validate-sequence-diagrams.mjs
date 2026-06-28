#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const diagramDir = path.join(root, "docs/images/readme-diagrams");
const legacySequenceSlugs = new Set([
  "aws-s3-spring-cloud-sequence-01.svg",
  "aws-storage-abstraction-sequence-01.svg",
  "docker-compose-demo-readme-sequence-01.svg",
  "docker-compose-plugin-demo-readme-sequence-01.svg",
  "exposed-dao-web-transaction-sequence-01.svg",
  "exposed-javers-audit-readme-sequence-01.svg",
  "exposed-mvc-jdbc-readme-sequence-01.svg",
  "exposed-mvc-virtualthread-readme-sequence-01.svg",
  "exposed-sql-webflux-coroutines-sequence-01.svg",
  "exposed-webflux-r2dbc-readme-sequence-01.svg",
  "gateway-api-gateway-readme-sequence-01.svg",
  "gatling-virtualthread-simulation-readme-sequence-01.svg",
  "graph-abuser-detection-readme-sequence-01.svg",
  "graph-social-network-readme-sequence-01.svg",
  "image-processing-advanced-workflow-readme-sequence-01.svg",
  "io-okio-examples-readme-sequence-01.svg",
  "json-jackson-examples-readme-sequence-01.svg",
  "kotlin-coroutines-readme-sequence-test-01.svg",
  "ktor-rest-coroutines-readme-sequence-01.svg",
  "leader-election-sequence.svg",
  "leader-leader-zookeeper-readme-election-sequence-01.svg",
  "messaging-kafka-readme-message-sequence-01.svg",
  "messaging-kafka-reply-readme-request-reply-sequence-01.svg",
  "kotlin-flow-extensions-race-fallback-readme-sequence-01.svg",
  "observability-micrometer-observation-readme-sequence-01.svg",
  "observability-micrometer-tracing-coroutines-readme-coroutine-sequence-01.svg",
  "observability-observability-advanced-readme-span-sequence-01.svg",
  "observability-observability-basic-readme-trace-sequence-01.svg",
  "quarkus-hibernate-reactive-panache-sequence-01.svg",
  "quarkus-rest-coroutine-sequence-01.svg",
  "ratelimit-bucker4j-bluetape4k-webflux-readme-filter-sequence-01.svg",
  "ratelimit-bucket4j-caffeine-web-readme-request-sequence-01.svg",
  "ratelimit-bucket4j-redis-readme-request-sequence-01.svg",
  "ratelimit-readme-selection-sequence-01.svg",
  "redis-cluster-demo-readme-number-sequence-01.svg",
  "redis-distributed-lock-readme-fenced-sequence-01.svg",
  "shared-readme-test-sequence-01.svg",
  "spring-boot-application-event-demo-sequence-01.svg",
  "spring-boot-application-event-demo-sequence-02.svg",
  "spring-boot-cache-caffeine-readme-cache-sequence-01.svg",
  "spring-boot-cache-redis-readme-cache-sequence-01.svg",
  "spring-boot-cache-resilience-readme-state-sequence-01.svg",
  "spring-boot-cbor-mvc-sequence-01.svg",
  "spring-boot-chaos-monkey-readme-assault-sequence-01.svg",
  "spring-boot-idempotency-sequence-01.svg",
  "spring-boot-idgenerator-readme-sequence-01.svg",
  "spring-boot-stomp-websocket-readme-sequence-01.svg",
  "spring-boot-webflux-coroutines-readme-sequence-01.svg",
  "spring-data-elasticsearch-readme-sequence-01.svg",
  "spring-data-elasticsearch-webflux-readme-sequence-01.svg",
  "spring-data-mongodb-coroutines-readme-sequence-01.svg",
  "spring-data-mongodb-transactions-readme-sequence-01.svg",
  "spring-data-r2dbc-examples-readme-sequence-01.svg",
  "spring-data-r2dbc-webflux-exposed-readme-sequence-01.svg",
  "spring-data-r2dbc-webflux-readme-sequence-01.svg",
  "spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-sequence-01.svg",
  "spring-modulith-events-deep-dive-readme-sequence-01.svg",
  "spring-modulith-jpa-demo-readme-sequence-01.svg",
  "spring-security-readme-sequence-01.svg",
  "virtualthreads-spring-mvc-tomcat-readme-sequence-01.svg",
  "virtualthreads-spring-webflux-readme-sequence-01.svg",
]);
const files = fs
  .readdirSync(diagramDir)
  .filter((name) => name.endsWith(".svg") && name.includes("sequence"))
  .sort()
  .map((name) => path.join(diagramDir, name));

function firstNumberList(value) {
  return [...String(value).matchAll(/[-+]?\d*\.?\d+/g)].map((match) => Number(match[0]));
}

function readMessagePaths(svg) {
  const paths = [];
  const pathRe = /<path\b([^>]*)\/>/g;
  let match;
  while ((match = pathRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    if (!/(call|return)/.test(className)) continue;
    const d = (attrs.match(/\bd="([^"]+)"/) || [])[1] || "";
    const nums = firstNumberList(d);
    if (nums.length < 4) continue;
    const points = [];
    for (let index = 0; index + 1 < nums.length; index += 2) {
      points.push({ x: nums[index], y: nums[index + 1] });
    }
    paths.push({ y: nums[1], className, points });
  }
  return paths;
}

function readMessageLabels(svg) {
  const labels = [];
  const rectRe = /<rect\b([^>]*\bclass="message-label"[^>]*)\/>/g;
  let match;
  while ((match = rectRe.exec(svg))) {
    const attrs = match[1];
    const x = Number((attrs.match(/\bx="([^"]+)"/) || [])[1]);
    const y = Number((attrs.match(/\by="([^"]+)"/) || [])[1]);
    const width = Number((attrs.match(/\bwidth="([^"]+)"/) || [])[1]);
    const height = Number((attrs.match(/\bheight="([^"]+)"/) || [])[1]);
    if ([x, y, width, height].every(Number.isFinite)) labels.push({ x, y, width, height });
  }
  return labels;
}

function readActorBoxes(svg) {
  const boxes = [];
  const rectRe = /<rect\b([^>]*\bclass="box"[^>]*)\/>/g;
  let match;
  while ((match = rectRe.exec(svg))) {
    const attrs = match[1];
    const x = Number((attrs.match(/\bx="([^"]+)"/) || [])[1]);
    const y = Number((attrs.match(/\by="([^"]+)"/) || [])[1]);
    const width = Number((attrs.match(/\bwidth="([^"]+)"/) || [])[1]);
    const height = Number((attrs.match(/\bheight="([^"]+)"/) || [])[1]);
    if ([x, y, width, height].every(Number.isFinite)) boxes.push({ x, y, width, height });
  }
  return boxes;
}

function labelIntersectsPath(label, points) {
  const labelTop = label.y;
  const labelBottom = label.y + label.height;
  const labelLeft = label.x;
  const labelRight = label.x + label.width;
  const padding = 2;

  for (let index = 0; index + 1 < points.length; index += 1) {
    const start = points[index];
    const end = points[index + 1];
    const minX = Math.min(start.x, end.x);
    const maxX = Math.max(start.x, end.x);
    const minY = Math.min(start.y, end.y);
    const maxY = Math.max(start.y, end.y);
    const horizontal = Math.abs(start.y - end.y) < 1;
    const vertical = Math.abs(start.x - end.x) < 1;

    if (horizontal && start.y >= labelTop - padding && start.y <= labelBottom + padding && maxX >= labelLeft && minX <= labelRight) {
      return true;
    }
    if (vertical && start.x >= labelLeft - padding && start.x <= labelRight + padding && maxY >= labelTop && minY <= labelBottom) {
      return true;
    }
  }

  return false;
}

function readMessageTexts(svg) {
  const messages = [];
  const textRe = /<text\b([^>]*)>([\s\S]*?)<\/text>/g;
  let match;
  while ((match = textRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    if (!/\bmessage\b/.test(className)) continue;
    const text = match[2].replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim();
    if (text) messages.push(text);
  }
  return messages;
}

const failures = [];
let legacySkipped = 0;

for (const file of files) {
  const svg = fs.readFileSync(file, "utf8");
  const rel = path.relative(root, file).replaceAll(path.sep, "/");
  if (legacySequenceSlugs.has(path.basename(file))) {
    legacySkipped += 1;
    continue;
  }

  if (!svg.includes("Architects Daughter") || !svg.includes("Comic Mono") || /(Inter|Arial|Helvetica)/.test(svg)) {
    failures.push({ file: rel, failure: "font signature" });
  }
  if (/[가-힣]/.test(svg)) {
    failures.push({ file: rel, failure: "non-English diagram label" });
  }

  const paths = readMessagePaths(svg);
  const labels = readMessageLabels(svg);
  const actorBoxes = readActorBoxes(svg);
  const messageTexts = readMessageTexts(svg);
  const viewBox = firstNumberList((svg.match(/\bviewBox="([^"]+)"/) || [])[1] || "");
  const canvasWidth = viewBox.length >= 4 ? viewBox[2] : Number((svg.match(/\bwidth="([^"]+)"/) || [])[1]);
  if (paths.length !== labels.length) {
    failures.push({ file: rel, failure: `message path/label count ${paths.length}/${labels.length}` });
    continue;
  }
  actorBoxes.forEach((box, index) => {
    if (box.x < 24 || Number.isFinite(canvasWidth) && box.x + box.width > canvasWidth - 24) {
      failures.push({ file: rel, failure: `participant box outside frame at actor ${index + 1}` });
    }
  });
  const fallbackLabel = /^(?:\d+\.\s*)?(?:Actor\s+\d+|source\s+to\s+target|.+\s+to\s+target|source\s+to\s+.+|undefined|null)$/i;
  if (messageTexts.some((text) => /^\d+\.?$/.test(text) || fallbackLabel.test(text))) {
    failures.push({ file: rel, failure: "empty, numeric-only, or fallback message label" });
  }

  paths.forEach((messagePath, index) => {
    const label = labels[index];
    const gap = label.y - messagePath.y;
    if (gap < 10) {
      failures.push({ file: rel, failure: `label overlaps call line at message ${index + 1}`, gap });
    }
    if (labelIntersectsPath(label, messagePath.points)) {
      failures.push({ file: rel, failure: `label intersects connector segment at message ${index + 1}` });
    }
  });
}

if (failures.length > 0) {
  console.error(JSON.stringify({ checked: files.length, legacySkipped, failures: failures.length, sample: failures.slice(0, 40) }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ checked: files.length, legacySkipped, failures: 0 }, null, 2));
