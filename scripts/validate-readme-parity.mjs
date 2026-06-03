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

function headingLevels(markdown) {
  return (markdown.match(/^#{1,6} /gm) ?? []).map((heading) => heading.match(/^#+/)[0].length);
}

function codeFenceCount(markdown) {
  return (markdown.match(/```/g) ?? []).length;
}

function imageTargets(markdown) {
  return [...markdown.matchAll(/!\[[^\]]*]\(([^)]+)\)/g)].map((match) => match[1].trim());
}

const forbiddenKoPatterns = [
  /원문 상세 항목/,
  /영어 README에는 다음 상세 항목/,
  /한국어 요약은/,
  /영어 README의 같은 모듈 설명/,
  /영문 README/,
];

const failures = [];

for (const readme of walk(root)) {
  const koReadme = path.join(path.dirname(readme), "README.ko.md");
  if (!fs.existsSync(koReadme)) continue;

  const rel = path.relative(root, readme).replaceAll(path.sep, "/");
  const koRel = path.relative(root, koReadme).replaceAll(path.sep, "/");
  const english = fs.readFileSync(readme, "utf8");
  const korean = fs.readFileSync(koReadme, "utf8");
  const fileFailures = [];

  if (!/^\[한국어]\(README\.ko\.md\) \| English$/m.test(english)) {
    fileFailures.push("english language switch");
  }
  if (!/^\[English]\(README\.md\) \| 한국어$/m.test(korean)) {
    fileFailures.push("korean language switch");
  }

  const englishHeadingLevels = headingLevels(english);
  const koreanHeadingLevels = headingLevels(korean);
  if (JSON.stringify(englishHeadingLevels) !== JSON.stringify(koreanHeadingLevels)) {
    fileFailures.push(`heading levels ${englishHeadingLevels.length}/${koreanHeadingLevels.length}`);
  }

  const englishFenceCount = codeFenceCount(english);
  const koreanFenceCount = codeFenceCount(korean);
  if (englishFenceCount !== koreanFenceCount) {
    fileFailures.push(`code fences ${englishFenceCount}/${koreanFenceCount}`);
  }

  const englishImages = imageTargets(english);
  const koreanImages = imageTargets(korean);
  if (JSON.stringify(englishImages) !== JSON.stringify(koreanImages)) {
    fileFailures.push(`image targets ${englishImages.length}/${koreanImages.length}`);
  }

  for (const pattern of forbiddenKoPatterns) {
    if (pattern.test(korean)) {
      fileFailures.push(`forbidden Korean summary phrase: ${pattern}`);
    }
  }

  if (fileFailures.length > 0) {
    failures.push({ file: rel, koFile: koRel, failures: fileFailures });
  }
}

if (failures.length > 0) {
  console.error(JSON.stringify({ failures: failures.length, sample: failures.slice(0, 50) }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({ failures: 0 }, null, 2));
