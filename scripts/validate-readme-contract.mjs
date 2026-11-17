#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const excludedPathParts = new Set([
  ".git",
  ".gradle",
  ".omx",
  ".omc",
  ".worktrees",
  "build",
  "node_modules",
  "src",
]);

function trackedReadmes() {
  const paths = execFileSync("git", ["ls-files", "-z"], { cwd: root, encoding: "utf8" })
    .split("\0")
    .filter(Boolean)
    .filter((file) => {
      const parts = file.split("/");
      return (path.basename(file) === "README.md" || path.basename(file) === "README.ko.md") &&
        !parts.some((part) => excludedPathParts.has(part));
    });
  return paths.sort();
}

function readmeText(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), "utf8").replaceAll("\r\n", "\n");
}

function isModuleReadme(relativePath) {
  const dir = path.dirname(path.join(root, relativePath));
  return fs.existsSync(path.join(dir, "build.gradle.kts")) || fs.existsSync(path.join(dir, "build.gradle"));
}

function hasReaderContext(markdown, locale) {
  const paragraphs = markdown
    .split(/\n\s*\n/)
    .map((paragraph) => paragraph.trim())
    .filter((paragraph) => paragraph && !paragraph.startsWith("#") && !paragraph.startsWith("[") && !paragraph.startsWith("!"));
  if (paragraphs.length === 0) return false;

  const lead = paragraphs.slice(0, 4).join(" ").replace(/[`*_]/g, " ").replace(/\s+/g, " ").trim();
  return locale === "ko" ? lead.length >= 60 : lead.split(/\s+/).length >= 14;
}

function hasTechnicalBoundary(markdown, locale) {
  const englishBoundary = /\b(?:scope|boundary|limit(?:s|ation)?|constraint|unsupported|not intended|does not|doesn't|cannot|must not|without|only|default|local[- ]first|in[- ]memory|synthetic|demo[- ]only|test[- ]only|production|requires|bounded|unbounded|failure|safety|profile|no\s+(?:database|external|persistent|durable)|embedded|container[- ]backed|testcontainers|local (?:vert\.x )?server|mock|fixture|sample data)\b/i;
  const koreanBoundary = /(?:범위|경계|한계|제약|제한|미지원|지원하지|불가|제외|않|아니|없|기본|전용|로컬|인메모리|합성|데모|테스트|운영|필요|필수|최대|최소|실패|안전|profile|Testcontainers|container-backed|in-memory|fixture|sample data)/i;
  return (locale === "ko" ? koreanBoundary : englishBoundary).test(markdown);
}

const approachHeading = /architecture|flow|approach|strategy|design|sequence|workflow|pipeline|model|contract|scenario|example|pattern|module map|features|how to|runtime|responsibility|scope|boundary|authority|lifecycle|walkthrough|runbook|operations|key rules|selection|comparison|event|request|test map|evidence|what (?:this )?(?:module|example) (?:shows|demonstrates)|key concepts|main components|query flow/i;
const approachHeadingKo = /architecture|flow|approach|strategy|design|sequence|workflow|pipeline|model|contract|scenario|example|pattern|module map|features|how to|runtime|responsibility|scope|boundary|authority|lifecycle|walkthrough|runbook|operations|key rules|selection|comparison|event|request|test map|evidence|what (?:this )?(?:module|example) (?:shows|demonstrates)|key concepts|main components|query flow|아키텍처|구조|흐름|방식|전략|설계|순서|작업|파이프라인|모델|계약|시나리오|예제|패턴|모듈|기능|구성|실행|책임|범위|경계|수명주기|비교|증거|이 예제가 보여|이 모듈이 보여|핵심 개념|주요 구성 요소|query 흐름/i;

function hasSolutionDirection(markdown, locale) {
  const headings = markdown.match(/^#{2,4} .*$/gm) ?? [];
  const headingPattern = locale === "ko" ? approachHeadingKo : approachHeading;
  return headings.some((heading) => headingPattern.test(heading));
}

function hasDesignEvidence(markdown, locale) {
  const hasDiagram = /!\[[^\]]*\]\([^)]+\)|```mermaid/i.test(markdown);
  const designSection = /^#{2,4} .*?(?:design|architecture|sequence|model|schema|decision|contract|class|domain|pattern|structure|references|api|state|map|lifecycle|flow|scenario|boundary|scope|authority|walkthrough|evidence|what (?:this )?(?:module|example) (?:shows|demonstrates)|key concepts|main components|query flow|설계|아키텍처|순서|모델|스키마|결정|계약|클래스|도메인|패턴|구조|참고|API|상태|흐름|경계|범위|이 예제가 보여|이 모듈이 보여|핵심 개념|주요 구성 요소|query 흐름)/im.test(markdown);
  const hasImplementationAnchor = /`[A-Z][A-Za-z0-9_.<>:()/-]*`/.test(markdown);
  return hasDiagram || designSection || (hasImplementationAnchor && hasSolutionDirection(markdown, locale));
}

function hasTestEvidence(markdown, moduleDir) {
  if (moduleDir !== root && !fs.existsSync(path.join(moduleDir, "src", "test"))) return false;

  const hasTestSection = /^#{2,4} .*?(?:test|verif|evidence|smoke|run|검증|테스트|실행|증거)/im.test(markdown);
  const hasTestCommand = /(?:\.\/gradlew|gradle\s+)[^\n]*(?::[\w-]+)*(?:test|integrationTest|check|build)\b/i.test(markdown);
  const hasTestClaim = /(?:test(?:s|ing)?|검증|테스트).{0,160}(?:verify|cover|assert|exercise|check|prove|compare|확인|비교|검사|다룬다|검증)/i.test(markdown);
  const hasTestClass = /\b[A-Z][A-Za-z0-9_]*(?:Test|Tests|IT)\b/.test(markdown);

  return (hasTestSection || hasTestCommand) && (hasTestCommand || hasTestClaim || hasTestClass);
}

const readmes = trackedReadmes();
const englishReadmes = readmes.filter((file) => path.basename(file) === "README.md");
const koreanReadmes = new Set(readmes.filter((file) => path.basename(file) === "README.ko.md"));
const failures = [];
let bilingualPairs = 0;
let rootPairs = 0;
let modulePairs = 0;
let indexPairs = 0;
let standaloneReadmes = 0;

for (const englishFile of englishReadmes) {
  const relative = englishFile;
  const directory = path.dirname(relative);
  const koreanFile = path.join(directory, "README.ko.md").replaceAll(path.sep, "/");

  if (!koreanReadmes.has(koreanFile)) {
    standaloneReadmes += 1;
    if (relative !== "docs/lessons/README.md") failures.push({ file: relative, missing: ["Korean locale pair"] });
    continue;
  }

  bilingualPairs += 1;
  if (relative === "README.md") rootPairs += 1;
  else if (isModuleReadme(relative)) modulePairs += 1;
  else indexPairs += 1;

  if (relative !== "README.md" && !isModuleReadme(relative)) continue;

  const directoryPath = path.join(root, directory);
  const english = readmeText(englishFile);
  const korean = readmeText(koreanFile);
  const missing = [];

  for (const [locale, markdown] of [["en", english], ["ko", korean]]) {
    if (!hasReaderContext(markdown, locale)) missing.push(`${locale}: reader context`);
    if (!hasTechnicalBoundary(markdown, locale)) missing.push(`${locale}: technical boundary`);
    if (!hasSolutionDirection(markdown, locale)) missing.push(`${locale}: solution direction`);
    if (!hasDesignEvidence(markdown, locale)) missing.push(`${locale}: design evidence`);
    if (!hasTestEvidence(markdown, directoryPath)) missing.push(`${locale}: test-backed verification`);
  }

  if (missing.length > 0) failures.push({ file: relative, missing });
}

for (const koreanFile of koreanReadmes) {
  const englishFile = path.join(path.dirname(koreanFile), "README.md").replaceAll(path.sep, "/");
  if (!readmes.includes(englishFile)) failures.push({ file: koreanFile, missing: ["English locale pair"] });
}

const result = {
  scope: "git ls-files tracked README.md and README.ko.md; excludes .worktrees, src, build and generated paths",
  readmeFiles: readmes.length,
  englishReadmes: englishReadmes.length,
  koreanReadmes: koreanReadmes.size,
  bilingualPairs,
  rootPairs,
  modulePairs,
  indexPairs,
  standaloneReadmes,
  failures: failures.length,
  failureDetails: failures,
};

console.log(JSON.stringify(result, null, 2));
if (failures.length > 0) process.exit(1);
