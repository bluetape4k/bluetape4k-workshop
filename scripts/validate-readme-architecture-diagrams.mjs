#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const diagramDir = path.join(root, "docs/images/readme-diagrams");
const failures = [];
const tolerance = 0.2;
const clearance = 8;

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
  for (const suffix of [".png", ".dot", ".plain", "-graphviz.svg", "-graphviz.png"]) {
    if (!fs.existsSync(`${base}${suffix}`)) {
      fail(file, `missing generated evidence pair ${path.basename(base)}${suffix}`);
    }
  }

  if (!svg.includes("sourceEvidence") || !svg.includes("layered-readme-architecture")) {
    fail(file, "missing architecture metadata");
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

console.log(`architecture_diagrams_validated=${files.length}`);
