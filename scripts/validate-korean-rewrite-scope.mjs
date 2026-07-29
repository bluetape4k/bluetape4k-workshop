#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const DOC_EXTENSIONS = new Set([".md", ".mdx", ".adoc", ".asciidoc"]);
const PRUNED_PARTS = new Set([".git", ".gradle", ".omx", ".worktrees", "build"]);

const ISSUE_SCOPES = {
  "588": [
    "docs/governance/korean-rewrite-scope.md",
    "scripts/validate-korean-rewrite-scope.mjs",
  ],
  "589": [
    "docs/coverage-matrix.md",
    "docs/assets",
    "docs/governance",
    "docs/images/readme-diagrams",
    "docs/lessons",
    "docs/review",
    "docs/superpowers",
  ],
  "590": ["CHANGELOG.md", "WIP.md"],
  "591": ["commerce"],
  "592": ["exposed", "spring-data", "spring-modulith"],
  "593": ["spring-boot", "gateway", "spring-security", "virtualthreads"],
  "594": ["kotlin", "ktor", "vertx", "observability"],
  "595": ["messaging", "redis", "json", "io", "ratelimit"],
  "596": ["graph", "leader"],
  "597": ["aws", "image-processing", "spring-cloud"],
  "598": ["gatling", "docker", "shared", "build-logic"],
  "599": ["docs/manual/en", "docs/manual/ko", "docs/governance/manual-parity-audit.md"],
  "600": ["docs/governance/korean-comment-standard.md"],
};

function usage() {
  console.error(
    [
      "Usage:",
      "  node scripts/validate-korean-rewrite-scope.mjs inventory",
      "  node scripts/validate-korean-rewrite-scope.mjs manual-parity",
      "  node scripts/validate-korean-rewrite-scope.mjs changed --issue <number> [--base <ref>]",
    ].join("\n"),
  );
  process.exit(2);
}

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function isUnder(candidate, scope) {
  return candidate === scope || candidate.startsWith(`${scope.replace(/\/$/, "")}/`);
}

function isPruned(filePath) {
  return toPosix(filePath).split("/").some((part) => PRUNED_PARTS.has(part));
}

function isReadme(filePath) {
  return path.basename(filePath).startsWith("README");
}

function isOperatingDoc(filePath) {
  const base = path.basename(filePath);
  return base === "AGENTS.md" || base === "CLAUDE.md" || base === "SKILL.md";
}

function isManualPair(filePath) {
  const normalized = toPosix(filePath);
  return normalized.startsWith("docs/manual/en/") || normalized.startsWith("docs/manual/ko/");
}

function isPrimaryDoc(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  return (
    DOC_EXTENSIONS.has(ext) &&
    !isPruned(filePath) &&
    !isReadme(filePath) &&
    !isOperatingDoc(filePath) &&
    !isManualPair(filePath)
  );
}

function walk(dir, predicate, results = []) {
  if (!existsSync(dir) || isPruned(dir)) return results;
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (isPruned(full)) continue;
    const stat = statSync(full);
    if (stat.isDirectory()) {
      walk(full, predicate, results);
    } else if (stat.isFile() && predicate(full)) {
      results.push(toPosix(full));
    }
  }
  return results;
}

function filesForScopes(scopes, predicate) {
  const files = new Set();
  for (const scope of scopes) {
    if (!existsSync(scope)) continue;
    const stat = statSync(scope);
    if (stat.isFile()) {
      if (predicate(scope)) files.add(toPosix(scope));
      continue;
    }
    for (const file of walk(scope, predicate)) {
      files.add(file);
    }
  }
  return [...files].sort();
}

function countCommentLikeLines(filePath) {
  const text = readFileSync(filePath, "utf8");
  return text
    .split(/\r?\n/)
    .filter((line) => /^\s*\/\//.test(line) || /\/\*/.test(line) || /\*\//.test(line) || /^\s*\*\s/.test(line))
    .length;
}

function inventory() {
  const primaryDocs = walk(".", isPrimaryDoc);
  console.log(`primary_docs_total=${primaryDocs.length}`);

  for (const [issue, scopes] of Object.entries(ISSUE_SCOPES)) {
    if (["599", "600"].includes(issue)) continue;
    const docs = filesForScopes(scopes, isPrimaryDoc);
    console.log(`issue_${issue}_primary_docs=${docs.length}`);
  }

  for (const issue of ["591", "592", "593", "594", "595", "596", "597", "598"]) {
    const ktFiles = filesForScopes(ISSUE_SCOPES[issue], (file) => path.extname(file) === ".kt" && !isPruned(file));
    const commentLines = ktFiles.reduce((sum, file) => sum + countCommentLikeLines(file), 0);
    console.log(`issue_${issue}_kt_files=${ktFiles.length}`);
    console.log(`issue_${issue}_comment_like_lines=${commentLines}`);
  }
}

function manualParity() {
  const enRoot = "docs/manual/en";
  const koRoot = "docs/manual/ko";
  if (!existsSync(enRoot) && !existsSync(koRoot)) {
    console.log("manual_parity=PASS");
    console.log("manual_pair_count=0");
    console.log("reason=manual directories are absent in this repository snapshot");
    return;
  }

  const relativeDocs = (root) =>
    filesForScopes([root], (file) => DOC_EXTENSIONS.has(path.extname(file).toLowerCase()))
      .map((file) => toPosix(path.relative(root, file)))
      .sort();
  const en = new Set(relativeDocs(enRoot));
  const ko = new Set(relativeDocs(koRoot));
  const missingKo = [...en].filter((file) => !ko.has(file));
  const missingEn = [...ko].filter((file) => !en.has(file));
  console.log(`manual_pair_count=${Math.max(en.size, ko.size)}`);
  console.log(`missing_ko=${missingKo.length}`);
  console.log(`missing_en=${missingEn.length}`);
  if (missingKo.length || missingEn.length) {
    console.log("manual_parity=FAIL");
    for (const file of missingKo) console.log(`missing_ko_file=${file}`);
    for (const file of missingEn) console.log(`missing_en_file=${file}`);
    process.exit(1);
  }
  console.log("manual_parity=PASS");
}

function changed(issue, baseRef = "origin/develop") {
  if (!issue || !ISSUE_SCOPES[issue]) {
    console.error(`unknown or missing issue: ${issue ?? ""}`);
    process.exit(2);
  }
  const committedOutput = execFileSync("git", ["diff", "--name-only", `${baseRef}...HEAD`], {
    encoding: "utf8",
  }).trim();
  const workingOutput = execFileSync("git", ["status", "--porcelain=v1"], {
    encoding: "utf8",
  }).replace(/\r?\n$/, "");
  const changedFiles = new Set(committedOutput ? committedOutput.split(/\r?\n/).filter(Boolean) : []);
  for (const line of workingOutput ? workingOutput.split(/\r?\n/) : []) {
    const statusPath = line.slice(3);
    const renameMarker = " -> ";
    const file = statusPath.includes(renameMarker)
      ? statusPath.slice(statusPath.indexOf(renameMarker) + renameMarker.length)
      : statusPath;
    if (file) changedFiles.add(file);
  }
  const allowed = ISSUE_SCOPES[issue];
  const violations = [];
  for (const file of [...changedFiles].sort()) {
    if (isPruned(file) || isReadme(file) || isOperatingDoc(file) || (issue !== "599" && isManualPair(file))) {
      violations.push(`${file} (excluded surface)`);
      continue;
    }
    if (!allowed.some((scope) => isUnder(file, scope))) {
      violations.push(`${file} (outside issue #${issue} scope)`);
    }
  }
  console.log(`changed_files=${changedFiles.size}`);
  console.log(`issue=${issue}`);
  console.log(`base=${baseRef}`);
  if (violations.length) {
    console.log("scope_check=FAIL");
    for (const violation of violations) console.log(`violation=${violation}`);
    process.exit(1);
  }
  console.log("scope_check=PASS");
}

const [command, ...args] = process.argv.slice(2);
if (command === "inventory") {
  inventory();
} else if (command === "manual-parity") {
  manualParity();
} else if (command === "changed") {
  const issueIndex = args.indexOf("--issue");
  const baseIndex = args.indexOf("--base");
  changed(
    issueIndex >= 0 ? args[issueIndex + 1] : undefined,
    baseIndex >= 0 ? args[baseIndex + 1] : undefined,
  );
} else {
  usage();
}
