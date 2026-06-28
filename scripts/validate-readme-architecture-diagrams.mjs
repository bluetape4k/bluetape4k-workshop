#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const diagramDir = path.join(root, "docs/images/readme-diagrams");
const legacyArchitectureSlugs = new Set([
  "aws-readme-architecture-01.svg",
  "aws-s3-spring-cloud-readme-architecture-01.svg",
  "docker-compose-demo-readme-architecture-01.svg",
  "docker-compose-plugin-demo-readme-architecture-01.svg",
  "exposed-javers-audit-readme-architecture-01.svg",
  "exposed-mvc-jdbc-readme-architecture-01.svg",
  "exposed-mvc-virtualthread-readme-architecture-01.svg",
  "exposed-readme-architecture-01.svg",
  "exposed-webflux-r2dbc-readme-architecture-01.svg",
  "gateway-api-gateway-readme-architecture-01.svg",
  "gateway-customers-readme-architecture-01.svg",
  "gateway-orders-readme-architecture-01.svg",
  "gateway-readme-architecture-01.svg",
  "gatling-virtualthread-simulation-readme-architecture-01.svg",
  "graph-abuser-detection-readme-architecture-01.svg",
  "graph-knowledge-graph-readme-architecture-01.svg",
  "graph-recommendation-readme-architecture-01.svg",
  "graph-social-network-readme-architecture-01.svg",
  "image-processing-advanced-workflow-readme-architecture-01.svg",
  "io-okio-examples-readme-architecture-01.svg",
  "json-jackson-examples-readme-architecture-01.svg",
  "json-jsonview-examples-readme-architecture-01.svg",
  "kotlin-coroutines-readme-architecture-01.svg",
  "kotlin-design-patterns-readme-architecture-01.svg",
  "kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-abstractfactory-readme-architecture-01.svg",
  "kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-builder-readme-architecture-01.svg",
  "kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-lazyloading-readme-architecture-01.svg",
  "kotlin-design-patterns-src-main-kotlin-io-bluetape4k-workshop-kotlin-designpatterns-singleton-readme-architecture-01.svg",
  "kotlin-flow-extensions-parallel-enrichment-readme-architecture-01.svg",
  "kotlin-flow-extensions-race-fallback-readme-architecture-01.svg",
  "kotlin-flow-extensions-subject-bridge-readme-architecture-01.svg",
  "kotlin-text-processing-readme-architecture-01.svg",
  "ktor-rest-coroutines-readme-architecture-01.svg",
  "leader-leader-election-readme-architecture-01.svg",
  "leader-leader-zookeeper-readme-architecture-01.svg",
  "messaging-kafka-readme-architecture-01.svg",
  "messaging-kafka-reply-readme-architecture-01.svg",
  "messaging-transactional-outbox-readme-architecture-01.svg",
  "observability-micrometer-observation-readme-architecture-01.svg",
  "observability-micrometer-tracing-coroutines-readme-architecture-01.svg",
  "observability-observability-advanced-readme-architecture-01.svg",
  "observability-observability-basic-readme-architecture-01.svg",
  "ratelimit-bucker4j-bluetape4k-webflux-readme-architecture-01.svg",
  "ratelimit-bucket4j-advanced-readme-architecture-01.svg",
  "ratelimit-bucket4j-caffeine-web-readme-architecture-01.svg",
  "ratelimit-bucket4j-redis-readme-architecture-01.svg",
  "redis-cluster-demo-readme-architecture-01.svg",
  "redis-distributed-lock-readme-architecture-01.svg",
  "redis-redisson-examples-readme-architecture-01.svg",
  "shared-readme-architecture-01.svg",
  "spring-boot-application-event-demo-readme-architecture-01.svg",
  "spring-boot-cache-benchmark-readme-architecture-01.svg",
  "spring-boot-cache-caffeine-readme-architecture-01.svg",
  "spring-boot-cache-redis-readme-architecture-01.svg",
  "spring-boot-cache-resilience-readme-architecture-01.svg",
  "spring-boot-cbor-mvc-readme-architecture-01.svg",
  "spring-boot-chaos-monkey-readme-architecture-01.svg",
  "spring-boot-idempotency-readme-architecture-01.svg",
  "spring-boot-idgenerator-readme-architecture-01.svg",
  "spring-boot-multi-tenant-data-isolation-readme-architecture-01.svg",
  "spring-boot-problem-readme-architecture-01.svg",
  "spring-boot-protobuf-mvc-readme-architecture-01.svg",
  "spring-boot-resilience4j-coroutines-readme-architecture-01.svg",
  "spring-boot-stomp-websocket-readme-architecture-01.svg",
  "spring-boot-webflux-coroutines-readme-architecture-01.svg",
  "spring-boot-webflux-websocket-readme-architecture-01.svg",
  "spring-cloud-gateway-example-readme-architecture-01.svg",
  "spring-data-elasticsearch-readme-architecture-01.svg",
  "spring-data-elasticsearch-webflux-readme-architecture-01.svg",
  "spring-data-jpa-querydsl-readme-architecture-01.svg",
  "spring-data-mongodb-coroutines-readme-architecture-01.svg",
  "spring-data-mongodb-transactions-readme-architecture-01.svg",
  "spring-data-r2dbc-coroutines-readme-architecture-01.svg",
  "spring-data-r2dbc-examples-readme-architecture-01.svg",
  "spring-data-r2dbc-webflux-exposed-readme-architecture-01.svg",
  "spring-data-r2dbc-webflux-readme-architecture-01.svg",
  "spring-data-redis-examples-readme-architecture-01.svg",
  "spring-data-redis-examples-src-main-kotlin-io-bluetape4k-workshop-redis-stream-readme-architecture-01.svg",
  "spring-modulith-events-deep-dive-readme-architecture-01.svg",
  "spring-modulith-jpa-demo-readme-architecture-01.svg",
  "spring-security-mvc-hello-readme-architecture-01.svg",
  "spring-security-readme-architecture-01.svg",
  "spring-security-webflux-hello-security-readme-architecture-01.svg",
  "spring-security-webflux-jwt-readme-architecture-01.svg",
  "vertx-coroutines-readme-architecture-01.svg",
  "vertx-readme-architecture-01.svg",
  "vertx-vertx-sqlclient-readme-architecture-01.svg",
  "vertx-vertx-webclient-readme-architecture-01.svg",
  "virtualthreads-rules-readme-architecture-01.svg",
  "virtualthreads-rules-src-test-kotlin-io-bluetape4k-workshop-virtualthread-part2-readme-architecture-01.svg",
  "virtualthreads-spring-mvc-tomcat-readme-architecture-01.svg",
  "virtualthreads-spring-webflux-readme-architecture-01.svg",
]);
const failures = [];
const tolerance = 0.2;
const clearance = 8;
let legacySkipped = 0;

function fail(file, message) {
  failures.push(`${path.relative(root, file)}: ${message}`);
}

function read(file) {
  return fs.readFileSync(file, "utf8");
}

function approx(a, b) {
  return Math.abs(a - b) <= tolerance;
}

function parseAttrs(text) {
  const attrs = {};
  for (const match of text.matchAll(/([A-Za-z_:][-A-Za-z0-9_:.]*)="([^"]*)"/g)) {
    attrs[match[1]] = match[2];
  }
  return attrs;
}

function parsePoints(d) {
  const matches = [...d.matchAll(/[ML]\s+(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)/g)];
  return matches.map((match) => ({ x: Number(match[1]), y: Number(match[2]) }));
}

function onBoundary(point, rect) {
  const withinX = point.x >= rect.x - tolerance && point.x <= rect.x + rect.w + tolerance;
  const withinY = point.y >= rect.y - tolerance && point.y <= rect.y + rect.h + tolerance;
  if (!withinX || !withinY) return null;
  if (approx(point.x, rect.x)) return "left";
  if (approx(point.x, rect.x + rect.w)) return "right";
  if (approx(point.y, rect.y)) return "top";
  if (approx(point.y, rect.y + rect.h)) return "bottom";
  return null;
}

function segmentDirection(a, b) {
  if (approx(a.x, b.x) && !approx(a.y, b.y)) return "vertical";
  if (approx(a.y, b.y) && !approx(a.x, b.x)) return "horizontal";
  if (approx(a.x, b.x) && approx(a.y, b.y)) return "zero";
  return "diagonal";
}

function segmentIntersectsRectInterior(a, b, rect, pad = 0) {
  const minX = rect.x - pad;
  const maxX = rect.x + rect.w + pad;
  const minY = rect.y - pad;
  const maxY = rect.y + rect.h + pad;
  const dir = segmentDirection(a, b);
  if (dir === "horizontal") {
    if (a.y <= minY + tolerance || a.y >= maxY - tolerance) return false;
    const start = Math.min(a.x, b.x);
    const end = Math.max(a.x, b.x);
    return end > minX + tolerance && start < maxX - tolerance;
  }
  if (dir === "vertical") {
    if (a.x <= minX + tolerance || a.x >= maxX - tolerance) return false;
    const start = Math.min(a.y, b.y);
    const end = Math.max(a.y, b.y);
    return end > minY + tolerance && start < maxY - tolerance;
  }
  return false;
}

function validateEndpointAngle(file, edgeName, side, first, second, endPoint = false) {
  const dir = segmentDirection(first, second);
  const expected = side === "left" || side === "right" ? "horizontal" : "vertical";
  if (dir !== expected) {
    const label = endPoint ? "last" : "first";
    fail(file, `${edgeName} ${label} segment is ${dir}; expected ${expected} at ${side} boundary`);
  }
}

function validateFile(file) {
  const svg = read(file);
  const base = file.replace(/\.svg$/, "");
  if (legacyArchitectureSlugs.has(path.basename(file))) {
    legacySkipped += 1;
    return;
  }

  if (!fs.existsSync(`${base}.png`)) {
    fail(file, `missing rendered PNG ${path.basename(base)}.png`);
  }

  if (!svg.includes("Architects Daughter") || !svg.includes("Comic Mono")) {
    fail(file, "missing required font roles");
  }
  if (/\b(Inter|Arial|Helvetica)\b/.test(svg)) {
    fail(file, "contains forbidden UI font family");
  }
  if (/class="edge-label\b|class="edge-label-bg\b/.test(svg)) {
    fail(file, "contains visible edge label elements");
  }

  const rects = new Map();
  for (const group of svg.matchAll(/<g class="node" data-node="([^"]+)"[\s\S]*?<rect\s+([^>]+)>/g)) {
    const attrs = parseAttrs(group[2]);
    rects.set(group[1], {
      x: Number(attrs.x),
      y: Number(attrs.y),
      w: Number(attrs.width),
      h: Number(attrs.height),
    });
  }
  if (rects.size === 0) {
    fail(file, "no node rectangles found");
  }

  const edgePattern = /<g class="edge" data-edge="([^"]+)" data-label="([^"]*)">\s*<path\s+([^>]+)>/g;
  for (const edge of svg.matchAll(edgePattern)) {
    const edgeName = edge[1];
    const label = edge[2];
    const attrs = parseAttrs(edge[3]);
    const points = parsePoints(attrs.d || "");
    const [from, to] = edgeName.split("-&gt;");
    const source = rects.get(from);
    const target = rects.get(to);
    if (!label.trim()) fail(file, `${edgeName} has empty semantic label`);
    if (!source || !target) {
      fail(file, `${edgeName} references missing source or target node`);
      continue;
    }
    if (points.length < 2) {
      fail(file, `${edgeName} has too few route points`);
      continue;
    }

    for (let i = 1; i < points.length; i += 1) {
      const dir = segmentDirection(points[i - 1], points[i]);
      if (dir === "zero") fail(file, `${edgeName} has zero-length segment ${i}`);
      if (dir === "diagonal") fail(file, `${edgeName} has diagonal segment ${i}`);
    }

    const startSide = onBoundary(points[0], source);
    const endSide = onBoundary(points.at(-1), target);
    if (!startSide) fail(file, `${edgeName} start point is not on source boundary`);
    if (!endSide) fail(file, `${edgeName} end point is not on target boundary`);
    if (startSide && points[1]) validateEndpointAngle(file, edgeName, startSide, points[0], points[1]);
    if (endSide && points.length > 1) {
      validateEndpointAngle(file, edgeName, endSide, points.at(-1), points.at(-2), true);
    }

    for (let i = 1; i < points.length; i += 1) {
      const a = points[i - 1];
      const b = points[i];
      for (const [nodeId, rect] of rects) {
        if (nodeId === from || nodeId === to) continue;
        if (segmentIntersectsRectInterior(a, b, rect, 0)) {
          fail(file, `${edgeName} segment ${i} crosses ${nodeId} interior`);
        }
        if (segmentIntersectsRectInterior(a, b, rect, clearance)) {
          fail(file, `${edgeName} segment ${i} violates ${clearance}px clearance around ${nodeId}`);
        }
      }
    }
  }
}

const files = fs.readdirSync(diagramDir)
  .filter((name) => name.endsWith("-readme-architecture-01.svg"))
  .map((name) => path.join(diagramDir, name))
  .sort();

for (const file of files) validateFile(file);

if (failures.length > 0) {
  console.error(failures.join("\n"));
  process.exit(1);
}

console.log(JSON.stringify({ checked: files.length, legacySkipped, failures: 0 }, null, 2));
