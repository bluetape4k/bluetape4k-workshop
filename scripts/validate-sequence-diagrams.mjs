#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const diagramDir = path.join(root, "docs/images/readme-diagrams");
const legacySequenceSlugs = new Set([
  "kotlin-flow-extensions-race-fallback-readme-sequence-01.svg",
  "observability-micrometer-observation-readme-sequence-01.svg",
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
  const rectRe = /<rect\b([^>]*)\/>/g;
  let match;
  while ((match = rectRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    if (!/\b(label|labelPill|pill)\b/.test(className) || /\bmessage-label\b/.test(className)) continue;
    const x = Number((attrs.match(/\bx="([^"]+)"/) || [])[1]);
    const y = Number((attrs.match(/\by="([^"]+)"/) || [])[1]);
    const width = Number((attrs.match(/\bwidth="([^"]+)"/) || [])[1]);
    const height = Number((attrs.match(/\bheight="([^"]+)"/) || [])[1]);
    if ([x, y, width, height].every(Number.isFinite)) labels.push({ x, y, width, height });
  }
  return labels;
}

function readLegacyMessageLabels(svg) {
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
const documentedExceptionSlugs = [];

for (const file of files) {
  const svg = fs.readFileSync(file, "utf8");
  const rel = path.relative(root, file).replaceAll(path.sep, "/");
  if (legacySequenceSlugs.has(path.basename(file))) {
    legacySkipped += 1;
    documentedExceptionSlugs.push(path.basename(file));
    continue;
  }

  if (!svg.includes("Architects Daughter") || !svg.includes("Comic Mono") || /(Inter|Arial|Helvetica)/.test(svg)) {
    failures.push({ file: rel, failure: "font signature" });
  }
  if (/[가-힣]/.test(svg)) {
    failures.push({ file: rel, failure: "non-English diagram label" });
  }

  const paths = readMessagePaths(svg);
  const visibleLabels = readMessageLabels(svg);
  const labels = visibleLabels.length > 0 ? visibleLabels : readLegacyMessageLabels(svg);
  const actorBoxes = readActorBoxes(svg);
  const messageTexts = readMessageTexts(svg);
  const viewBox = firstNumberList((svg.match(/\bviewBox="([^"]+)"/) || [])[1] || "");
  const canvasWidth = viewBox.length >= 4 ? viewBox[2] : Number((svg.match(/\bwidth="([^"]+)"/) || [])[1]);
  if (paths.length > 0 && labels.length < 2) {
    failures.push({ file: rel, failure: `visible message label count ${labels.length}` });
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

  labels.forEach((label, labelIndex) => {
    const bottomGapToNearestLine = Math.min(
      ...paths
        .filter((messagePath) => labelIntersectsPath({ ...label, height: label.height + 12 }, messagePath.points))
        .map((messagePath) => messagePath.y - (label.y + label.height)),
    );
    if (Number.isFinite(bottomGapToNearestLine) && bottomGapToNearestLine < 6) {
      failures.push({ file: rel, failure: `visible label overlaps or touches call line at label ${labelIndex + 1}`, gap: bottomGapToNearestLine });
    }
    paths.forEach((messagePath, pathIndex) => {
      if (labelIntersectsPath(label, messagePath.points)) {
        failures.push({ file: rel, failure: `visible label intersects connector segment at label ${labelIndex + 1}, path ${pathIndex + 1}` });
      }
    });
  });
}

function resultPayload() {
  return {
    checked: files.length,
    validated: files.length - legacySkipped,
    legacySkipped,
    documentedExceptions: legacySkipped,
    exceptionSlugs: documentedExceptionSlugs,
  };
}

if (failures.length > 0) {
  console.error(JSON.stringify({ ...resultPayload(), failures: failures.length, sample: failures.slice(0, 40) }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ ...resultPayload(), failures: 0 }, null, 2));
