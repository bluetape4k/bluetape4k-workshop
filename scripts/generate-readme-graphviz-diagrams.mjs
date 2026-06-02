#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
const skippedDirs = new Set([".git", ".gradle", "build", ".worktrees", ".omx", ".omc", "docs"]);

function walkReadmes(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkReadmes(file, out);
    } else if (entry.name === "README.md") {
      out.push(path.dirname(file));
    }
  }
  return out;
}

function read(file) {
  return fs.readFileSync(file, "utf8");
}

function titleOf(dir) {
  const text = read(path.join(dir, "README.md"));
  return text.match(/^#\s+(.+?)\s*$/m)?.[1] || path.basename(dir).replace(/[-_]/g, " ");
}

function slugOf(rel) {
  return rel
    .replace(/^\.$/, "root-readme")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();
}

function domainOf(rel) {
  if (rel === ".") return "Workshop";
  if (rel.startsWith("spring-security/")) return "Security";
  if (rel.startsWith("spring-data/")) return "Spring Data";
  if (rel.startsWith("spring-boot/")) return "Spring Boot";
  if (rel.startsWith("spring-modulith/")) return "Modulith";
  if (rel.startsWith("spring-cloud/")) return "Spring Cloud";
  return rel.split("/")[0]
    .replace(/[-_]/g, " ")
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function wrapLabel(text, width = 22) {
  const words = text.replace(/["\\]/g, "").split(/\s+/).filter(Boolean);
  const lines = [];
  let line = "";
  for (const word of words) {
    const next = line ? `${line} ${word}` : word;
    if (next.length > width && line) {
      lines.push(line);
      line = word;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines.slice(0, 3).join("\\n");
}

function dotFor({ title, rel }) {
  const domain = domainOf(rel);
  const moduleLabel = wrapLabel(title, 24);
  const sourceText = rel === "."
    ? "repository root README"
    : rel.replace(/src\/main\/kotlin\/io\/bluetape4k\/workshop\//, "").replace(/[/_-]+/g, " ");
  const packageLabel = wrapLabel(sourceText, 24);
  return `digraph G {
  graph [
    rankdir=LR,
    bgcolor="white",
    pad="0.35",
    nodesep="0.55",
    ranksep="0.75",
    splines=ortho,
    outputorder=edgesfirst
  ];
  node [
    shape=box,
    style="rounded,filled",
    penwidth=1.4,
    color="#88A3B5",
    fillcolor="#F6FAFF",
    fontname="Helvetica",
    fontsize=14,
    margin="0.14,0.10"
  ];
  edge [
    color="#637383",
    arrowsize=0.8,
    penwidth=1.5,
    fontname="Helvetica",
    fontsize=11
  ];

  reader [label="Developer\\nReader", fillcolor="#FFF8E7"];
  readme [label="${moduleLabel}\\nREADME", fillcolor="#EEF7FF"];
  module [label="${domain}\\nExample Module", fillcolor="#F1F8E9"];
  source [label="${packageLabel}\\nsource + tests", fillcolor="#F5F0FF"];
  bt [label="bluetape4k\\nhelpers / APIs", fillcolor="#EAF7F0"];
  runtime [label="Runtime or\\nTest Backend", fillcolor="#FFF1F1"];

  reader -> readme [label="opens"];
  readme -> module [label="explains"];
  module -> source [label="maps to"];
  source -> bt [label="uses"];
  source -> runtime [label="exercises"];
  bt -> runtime [label="adapts"];
}
`;
}

function run(command, args) {
  const result = spawnSync(command, args, { cwd: root, encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed\n${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

fs.mkdirSync(outDir, { recursive: true });

const dirs = walkReadmes(root)
  .filter((dir) => dir !== root)
  .sort();

let generated = 0;
for (const dir of dirs) {
  const rel = path.relative(root, dir).replaceAll(path.sep, "/");
  const slug = slugOf(rel);
  const base = path.join(outDir, `${slug}-readme-architecture-01`);
  const dot = dotFor({ title: titleOf(dir), rel });
  fs.writeFileSync(`${base}.dot`, dot);
  fs.writeFileSync(`${base}.plain`, run("dot", ["-Tplain", `${base}.dot`]));
  run("dot", ["-Tsvg", `${base}.dot`, "-o", `${base}.svg`]);
  run("dot", ["-Tpng", `${base}.dot`, "-o", `${base}.png`]);
  generated++;
}

console.log(`graphviz_readme_diagrams=${generated}`);
