#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const skippedDirs = new Set([".git", ".gradle", ".omx", ".omc", ".worktrees", "build", "buildSrc", "node_modules"]);

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(file, out);
    } else if (entry.name === "README.md") {
      out.push(file);
    }
  }
  return out;
}

const offenders = [];
for (const file of walk(root)) {
  const rel = path.relative(root, file).replaceAll(path.sep, "/");
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  const hits = [];
  lines.forEach((line, index) => {
    if (!/[가-힣]/.test(line)) return;
    if (/^\[한국어\]\(README\.ko\.md\) \| English\s*$/.test(line.trim())) return;
    hits.push({ line: index + 1, text: line });
  });
  if (hits.length > 0) offenders.push({ file: rel, count: hits.length, hits: hits.slice(0, 5) });
}

if (offenders.length > 0) {
  console.error(JSON.stringify({
    offenders: offenders.length,
    totalHits: offenders.reduce((sum, offender) => sum + offender.count, 0),
    sample: offenders.slice(0, 20),
  }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ offenders: 0, totalHits: 0 }, null, 2));
