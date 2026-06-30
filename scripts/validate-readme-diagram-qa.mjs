#!/usr/bin/env node

import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const root = process.cwd();
const diagramDir = path.join(root, "docs/images/readme-diagrams");
const skillAuditDir = process.env.DIAGRAM_QA_REFERENCE_AUDIT_DIR || "/Users/debop/.codex/skills/bluetape4k-diagram/references";
const localCairosvg = path.join(os.homedir(), ".local/bin/cairosvg");
const cairosvg = process.env.CAIROSVG_BIN || (fs.existsSync(localCairosvg) ? localCairosvg : "cairosvg");
const failures = [];
const rows = [];

function rel(file) {
  return path.relative(root, file).replaceAll(path.sep, "/");
}

function addRow(scope, gate, evidence, result = "PASS") {
  rows.push({ scope, gate, evidence, result });
}

function fail(scope, gate, evidence) {
  failures.push(`${scope}: ${gate}: ${evidence}`);
  addRow(scope, gate, evidence, "FAIL");
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    maxBuffer: 1024 * 1024 * 16,
    ...options,
  });
  return {
    status: result.status ?? 1,
    stdout: result.stdout.trim(),
    stderr: result.stderr.trim(),
  };
}

function runRequired(scope, gate, command, args) {
  const result = run(command, args);
  const evidence = [result.stdout, result.stderr].filter(Boolean).join("\n").trim() || `exit=${result.status}`;
  if (result.status !== 0) {
    fail(scope, gate, evidence);
  } else {
    addRow(scope, gate, summarizeEvidence(evidence));
  }
  return result;
}

function summarizeEvidence(evidence) {
  const trimmed = evidence.trim();
  if (!trimmed) return "exit=0";
  try {
    const parsed = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return Object.entries(parsed).map(([key, value]) => `${key}=${value}`).join(" ");
    }
  } catch {
    // Fall back to the last output line below.
  }
  return trimmed.split("\n").at(-1) || "exit=0";
}

function parseAttrs(text) {
  const attrs = {};
  for (const match of text.matchAll(/([A-Za-z_:][-A-Za-z0-9_:.]*)="([^"]*)"/g)) {
    attrs[match[1]] = match[2];
  }
  return attrs;
}

function pathTokens(d) {
  return d.match(/[A-Za-z]|-?\d+(?:\.\d+)?/g) || [];
}

function parsePathEndPoints(d) {
  const tokens = pathTokens(d);
  const points = [];
  let index = 0;
  let command = null;
  let current = { x: 0, y: 0 };

  const nextNumber = () => Number(tokens[index++]);
  while (index < tokens.length) {
    if (/^[A-Za-z]$/.test(tokens[index])) command = tokens[index++];
    if (!command) break;
    if (command === "M" || command === "L") {
      current = { x: nextNumber(), y: nextNumber(), command };
      points.push(current);
    } else if (command === "H") {
      current = { x: nextNumber(), y: current.y, command };
      points.push(current);
    } else if (command === "V") {
      current = { x: current.x, y: nextNumber(), command };
      points.push(current);
    } else if (command === "Q") {
      nextNumber();
      nextNumber();
      current = { x: nextNumber(), y: nextNumber(), command };
      points.push(current);
    } else {
      break;
    }
  }
  return points;
}

function segmentDirection(a, b) {
  if (Math.abs(a.x - b.x) < 0.2 && Math.abs(a.y - b.y) >= 0.2) return "vertical";
  if (Math.abs(a.y - b.y) < 0.2 && Math.abs(a.x - b.x) >= 0.2) return "horizontal";
  if (Math.abs(a.x - b.x) < 0.2 && Math.abs(a.y - b.y) < 0.2) return "zero";
  return "diagonal";
}

function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function changedSvgTargets() {
  const explicit = process.argv.slice(2).filter((arg) => arg.endsWith(".svg"));
  if (explicit.length > 0) {
    return explicit.map((file) => path.resolve(root, file));
  }

  const candidates = [
    ["merge-base", "origin/develop", "HEAD"],
    ["merge-base", "develop", "HEAD"],
  ];
  let base = null;
  for (const args of candidates) {
    try {
      base = execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
      if (base) break;
    } catch {
      // Try the next candidate.
    }
  }
  if (!base) {
    base = "HEAD~1";
  }

  const diff = run("git", ["diff", "--name-only", `${base}...HEAD`]);
  const files = diff.stdout
    .split("\n")
    .filter((name) => name.startsWith("docs/images/readme-diagrams/") && name.endsWith(".svg"))
    .map((name) => path.join(root, name));

  return [...new Set(files)].sort();
}

function classStyles(svg) {
  const styles = new Map();
  for (const match of svg.matchAll(/\.([A-Za-z][A-Za-z0-9_-]*)\s*\{([^}]*)}/g)) {
    const body = match[2];
    const stroke = (body.match(/stroke\s*:\s*(#[0-9a-fA-F]{3,8})/) || [])[1];
    const marker = (body.match(/marker-end\s*:\s*url\(#([^)]+)\)/) || [])[1];
    const dash = (body.match(/stroke-dasharray\s*:\s*([^;}]*)/) || [])[1];
    styles.set(match[1], { stroke, marker, dash });
  }
  return styles;
}

function markerDefs(svg) {
  const markers = new Map();
  for (const marker of svg.matchAll(/<marker\b([^>]*)>([\s\S]*?)<\/marker>/g)) {
    const attrs = parseAttrs(marker[1]);
    const pathMatch = marker[2].match(/<path\b([^>]*)\/?>/);
    if (!attrs.id || !pathMatch) continue;
    const pathAttrs = parseAttrs(pathMatch[1]);
    markers.set(attrs.id, pathAttrs);
  }
  return markers;
}

function auditMarkers(file, svg) {
  const scope = rel(file);
  const styles = classStyles(svg);
  const markers = markerDefs(svg);
  let checked = 0;
  let markerFailures = 0;
  const pathRe = /<path\b([^>]*)\/?>/g;
  let match;
  while ((match = pathRe.exec(svg))) {
    const attrs = parseAttrs(match[1]);
    const classes = (attrs.class || "").split(/\s+/).filter(Boolean);
    let stroke = attrs.stroke;
    let marker = (attrs["marker-end"] || "").match(/url\(#([^)]+)\)/)?.[1];
    let dash = attrs["stroke-dasharray"];
    for (const className of classes) {
      const style = styles.get(className);
      if (!style) continue;
      stroke ||= style.stroke;
      marker ||= style.marker;
      dash ||= style.dash;
    }
    if (!marker) continue;
    checked += 1;
    const markerPath = markers.get(marker);
    if (!markerPath) {
      markerFailures += 1;
      fail(scope, "marker audit", `${marker} is referenced but not defined`);
      continue;
    }
    const markerFill = markerPath.fill;
    const markerStroke = markerPath.stroke;
    const markerDash = markerPath["stroke-dasharray"] || "";
    const markerStyle = markerPath.style || "";
    if (!stroke || markerFill !== stroke || markerStroke !== stroke) {
      markerFailures += 1;
      fail(scope, "marker audit", `${marker} color fill=${markerFill} stroke=${markerStroke} expected=${stroke}`);
    }
    if (dash && dash !== "none" && markerDash !== "none" && !markerStyle.includes("stroke-dasharray:none")) {
      markerFailures += 1;
      fail(scope, "marker audit", `${marker} can inherit dashed arrowhead stroke`);
    }
    if ((markerFill || "").includes("context-stroke") || (markerStroke || "").includes("context-stroke")) {
      markerFailures += 1;
      fail(scope, "marker audit", `${marker} uses context-stroke`);
    }
  }
  if (checked === 0 && /marker-end\s*:|marker-end=/.test(svg)) {
    fail(scope, "marker audit", "marker-end exists but no marker paths were checked");
  } else if (markerFailures === 0) {
    addRow(scope, "marker audit", `markers_checked=${checked} marker_failures=0`);
  }
}

function auditConnectorGeometry(file, svg) {
  const scope = rel(file);
  const pathMatches = [...svg.matchAll(/<path\b([^>]*\bdata-connector="([^"]+)"[^>]*)\/?>/g)];
  if (pathMatches.length === 0) {
    if (/class="edge"|data-edge=/.test(svg)) fail(scope, "fallback connector audit", "edge groups exist but data-connector paths=0");
    return;
  }

  let diagonalSegments = 0;
  let roundedBentConnectors = 0;
  let bentConnectors = 0;
  let sharpBentFailures = 0;
  let terminalFailures = 0;
  const terminalLengths = [];

  for (const pathMatch of pathMatches) {
    const attrs = parseAttrs(pathMatch[1]);
    const connector = attrs["data-connector"];
    const d = attrs.d || "";
    const points = parsePathEndPoints(d);
    const directions = [];
    for (let i = 1; i < points.length; i += 1) {
      if (points[i].command === "Q") continue;
      const dir = segmentDirection(points[i - 1], points[i]);
      directions.push(dir);
      if (dir === "diagonal") diagonalSegments += 1;
    }
    const turns = directions.filter((dir, i) => i > 0 && dir !== "zero" && directions[i - 1] !== "zero" && dir !== directions[i - 1]).length;
    const hasQ = /\bQ\b/.test(d);
    if (turns > 0) {
      bentConnectors += 1;
      if (hasQ) roundedBentConnectors += 1;
      if (!hasQ) {
        sharpBentFailures += 1;
        fail(scope, "fallback rounded-corner audit", `${connector} has ${turns} turn(s) without Q bends`);
      }
    }
    if (hasQ && points.length >= 2 && /marker-end\s*:|marker-end=|class="[^"]*(?:solid|dashed|call|return)/.test(pathMatch[1])) {
      const finalLength = distance(points.at(-2), points.at(-1));
      terminalLengths.push(`${connector}:${finalLength.toFixed(1)}`);
      if (finalLength < 16) {
        terminalFailures += 1;
        fail(scope, "terminal segment audit", `${connector} final_segment_px=${finalLength.toFixed(1)} minimum=16`);
      }
    }
  }

  if (diagonalSegments > 0) fail(scope, "fallback diagonal audit", `diagonal_segments=${diagonalSegments}`);
  if (sharpBentFailures === 0 && diagonalSegments === 0 && terminalFailures === 0) {
    addRow(
      scope,
      "fallback connector audit",
      `connectors=${pathMatches.length} bent=${bentConnectors} rounded_bent=${roundedBentConnectors} sharp_bent_failures=0 terminal_segments=[${terminalLengths.join(",") || "none"}]`,
    );
  }
}

function auditSequenceShape(file, svg) {
  const scope = rel(file);
  if (!path.basename(file).includes("sequence")) return;
  const labels = [...svg.matchAll(/<rect\b[^>]*\bclass="[^"]*labelPill[^"]*"[^>]*>/g)].length;
  const numbers = [...svg.matchAll(/<text\b[^>]*\bclass="[^"]*\bnum\b[^"]*"[^>]*>(\d+)<\/text>/g)].map((match) => Number(match[1]));
  const altBodies = [...svg.matchAll(/<rect\b([^>]*\bclass="[^"]*\balt\b[^"]*"[^>]*)\/?>/g)];
  let altFillFailures = 0;
  for (const alt of altBodies) {
    const attrs = parseAttrs(alt[1]);
    if (attrs.fill && attrs.fill !== "none") altFillFailures += 1;
  }
  const monotonic = numbers.every((value, index) => value === index + 1);
  if (labels === 0 || numbers.length === 0 || !monotonic || altFillFailures > 0) {
    fail(scope, "fallback sequence audit", `labels=${labels} numbers=${numbers.join(",")} monotonic=${monotonic} alt_fill_failures=${altFillFailures}`);
  } else {
    addRow(scope, "fallback sequence audit", `labels=${labels} numbers=${numbers.length} monotonic=true alt_fill_failures=0`);
  }
}

function runSkillAudits(file, svg) {
  const scope = rel(file);
  const isConnectorHeavy = /data-connector=|class="edge"|marker-end\s*:|marker-end=/.test(svg);
  if (!isConnectorHeavy) return;

  const audits = [
    ["geometry", "diagram-geometry-audit.py"],
    ["endpoint", "diagram-endpoint-audit.py"],
    ["connector", "diagram-connector-audit.py"],
    ["mixed-corner", "diagram-mixed-corner-audit.py"],
  ];
  for (const [gate, script] of audits) {
    const scriptPath = path.join(skillAuditDir, script);
    if (!fs.existsSync(scriptPath)) {
      addRow(scope, `${gate} reference audit`, `${scriptPath} missing`, "UNAVAILABLE");
      continue;
    }
    const result = run("python3", [scriptPath, rel(file)]);
    const evidence = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    if (result.status !== 0) {
      fail(scope, `${gate} reference audit`, evidence || `exit=${result.status}`);
      continue;
    }
    const weakConnector = gate === "connector" && /connectors=0/.test(evidence);
    const weakMixed = gate === "mixed-corner" && /paths=0/.test(evidence) && /\bQ\b/.test(svg);
    const weakCards = gate === "connector" && path.basename(file).includes("architecture") && /cards=0/.test(evidence);
    const resultLabel = weakConnector || weakMixed || weakCards ? "WEAK" : "PASS";
    addRow(scope, `${gate} reference audit`, evidence.split("\n").at(-1) || "exit=0", resultLabel);
  }
}

const targets = changedSvgTargets();
if (targets.length === 0) {
  console.log("diagram QA wrapper: PASS targets=0");
  process.exit(0);
}

for (const file of targets) {
  const scope = rel(file);
  if (!file.startsWith(diagramDir) || !fs.existsSync(file)) {
    fail(scope, "target scope", "target SVG is missing or outside docs/images/readme-diagrams");
    continue;
  }
  const svg = fs.readFileSync(file, "utf8");
  const png = file.replace(/\.svg$/, ".png");
  runRequired(scope, "SVG XML parse", "xmllint", ["--noout", rel(file)]);
  if (!fs.existsSync(cairosvg)) {
    fail(scope, "PNG render", `${cairosvg} missing`);
  } else {
    runRequired(scope, "PNG render", cairosvg, [rel(file), "-o", rel(png), "-s", "2"]);
  }
  if (!fs.existsSync(png)) {
    fail(scope, "PNG pair", `${rel(png)} missing`);
  } else {
    const stat = fs.statSync(png);
    addRow(scope, "PNG pair", `${rel(png)} bytes=${stat.size}`);
  }
  auditMarkers(file, svg);
  runSkillAudits(file, svg);
  auditConnectorGeometry(file, svg);
  auditSequenceShape(file, svg);
}

if (targets.some((file) => path.basename(file).includes("architecture"))) {
  runRequired("diagram-set", "architecture validator", "node", ["scripts/validate-readme-architecture-diagrams.mjs"]);
}
if (targets.some((file) => path.basename(file).includes("sequence"))) {
  runRequired("diagram-set", "sequence validator", "node", ["scripts/validate-sequence-diagrams.mjs"]);
  const sequenceStyle = path.join(skillAuditDir, "diagram-sequence-style-audit.py");
  if (fs.existsSync(sequenceStyle)) {
    const sequenceTargets = targets.filter((file) => path.basename(file).includes("sequence")).map(rel);
    const result = run("python3", [sequenceStyle, ...sequenceTargets]);
    const evidence = [result.stdout, result.stderr].filter(Boolean).join("\n").trim() || `exit=${result.status}`;
    if (result.status !== 0) fail("diagram-set", "sequence style reference audit", evidence);
    else addRow("diagram-set", "sequence style reference audit", evidence.split("\n").at(-1) || "exit=0");
  }
}

const weakRows = rows.filter((row) => row.result === "WEAK" || row.result === "UNAVAILABLE");
const fallbackRows = rows.filter((row) => row.gate.startsWith("fallback "));
if (weakRows.length > 0 && fallbackRows.length === 0) {
  fail("diagram-set", "weak audit fallback", `weak_rows=${weakRows.length} fallback_rows=0`);
}

console.log(`diagram QA wrapper: ${failures.length === 0 ? "PASS" : "FAIL"} targets=${targets.length} weak_reference_rows=${weakRows.length}`);
console.log("| Scope | Gate | Result | Evidence |");
console.log("|---|---|---|---|");
for (const row of rows) {
  const evidence = row.evidence.replaceAll("|", "\\|").replace(/\s+/g, " ").trim();
  console.log(`| ${row.scope} | ${row.gate} | ${row.result} | ${evidence} |`);
}

if (failures.length > 0) {
  console.error("\nFailures:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}
