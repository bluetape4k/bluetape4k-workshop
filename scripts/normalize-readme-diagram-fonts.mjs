#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const ignoredDirs = new Set([
  ".git",
  ".gradle",
  ".omc",
  ".omx",
  "build",
  "node_modules",
]);

function walk(dir, predicate) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!ignoredDirs.has(entry.name)) {
        out.push(...walk(full, predicate));
      }
    } else if (entry.isFile() && predicate(full)) {
      out.push(full);
    }
  }
  return out;
}

function readmeFiles() {
  return walk(root, (file) => /^README(\..+)?\.md$/.test(path.basename(file)));
}

function referencedDiagramPngs() {
  const refs = new Map();
  for (const readme of readmeFiles()) {
    const markdown = fs.readFileSync(readme, "utf8");
    const imagePattern = /!\[[^\]]*]\(([^)]+\.png)\)/g;
    for (const match of markdown.matchAll(imagePattern)) {
      const png = path.normalize(path.join(path.dirname(readme), match[1]));
      if (!png.includes(`${path.sep}readme-diagrams${path.sep}`)) {
        continue;
      }
      if (!refs.has(png)) {
        refs.set(png, new Set());
      }
      refs.get(png).add(path.relative(root, readme));
    }
  }
  return refs;
}

function normalizeCssFontFamilies(svg) {
  return svg.replace(/font-family\s*:\s*([^;}]+)/g, (full, value) => {
    const lower = full.toLowerCase();
    if (lower.includes("architects daughter")) {
      return 'font-family:"Architects Daughter"';
    }
    return 'font-family:"Comic Mono"';
  });
}

function normalizeAttributeFontFamilies(svg) {
  return svg.replace(/font-family=(["'])(.*?)\1/g, (_full, quote, value) => {
    const lower = value.toLowerCase();
    const family = lower.includes("architects daughter")
      ? "Architects Daughter"
      : "Comic Mono";
    return `font-family=${quote}${family}${quote}`;
  });
}

function normalizeSvg(svg) {
  return normalizeAttributeFontFamilies(normalizeCssFontFamilies(svg));
}

function fontStatus(svgFile) {
  const svg = fs.readFileSync(svgFile, "utf8").replace(/<!--[^]*?-->/g, "");
  const declarations = [];
  for (const match of svg.matchAll(/font-family\s*:\s*([^;}]+)/g)) {
    declarations.push(match[1]);
  }
  for (const match of svg.matchAll(/font-family=(["'])(.*?)\1/g)) {
    declarations.push(match[2]);
  }
  const joined = declarations.join("\n");
  const hasArchitects = joined.includes("Architects Daughter");
  const hasComicMono = joined.includes("Comic Mono");
  const forbidden = /(Comic Sans|Arial|Helvetica|Inter|sans-serif|cursive|Courier New|Times,serif|Georgia,serif|monospace)/.test(joined);
  return { hasArchitects, hasComicMono, forbidden };
}

function renderPng(svgFile, pngFile) {
  execFileSync("rsvg-convert", ["--keep-aspect-ratio", "-f", "png", "-o", pngFile, svgFile], {
    stdio: "pipe",
  });
}

const refs = referencedDiagramPngs();
const touched = [];
const failures = [];

for (const [pngFile, readmes] of refs) {
  const svgFile = pngFile.replace(/\.png$/, ".svg");
  if (!fs.existsSync(pngFile)) {
    failures.push({ png: path.relative(root, pngFile), reason: "missing png", readmes: [...readmes] });
    continue;
  }
  if (!fs.existsSync(svgFile)) {
    failures.push({ png: path.relative(root, pngFile), reason: "missing svg", readmes: [...readmes] });
    continue;
  }

  const before = fs.readFileSync(svgFile, "utf8");
  const after = normalizeSvg(before);
  const beforeStatus = fontStatus(svgFile);
  if (before !== after || beforeStatus.forbidden || !beforeStatus.hasComicMono) {
    fs.writeFileSync(svgFile, after);
    renderPng(svgFile, pngFile);
    touched.push(path.relative(root, pngFile));
  }
}

let compliant = 0;
const bad = [];
for (const [pngFile, readmes] of refs) {
  const svgFile = pngFile.replace(/\.png$/, ".svg");
  if (!fs.existsSync(pngFile) || !fs.existsSync(svgFile)) {
    bad.push({ png: path.relative(root, pngFile), reason: "missing pair", readmes: [...readmes] });
    continue;
  }
  const status = fontStatus(svgFile);
  if (status.hasArchitects && status.hasComicMono && !status.forbidden) {
    compliant += 1;
  } else {
    bad.push({
      png: path.relative(root, pngFile),
      reason: `hasArchitects=${status.hasArchitects} hasComicMono=${status.hasComicMono} forbidden=${status.forbidden}`,
      readmes: [...readmes],
    });
  }
}

const result = {
  readmeDiagramPngRefs: refs.size,
  touched: touched.length,
  compliant,
  failures: failures.length + bad.length,
  missingOrRenderFailures: failures,
  bad,
};

console.log(JSON.stringify(result, null, 2));
if (result.failures > 0) {
  process.exit(1);
}
