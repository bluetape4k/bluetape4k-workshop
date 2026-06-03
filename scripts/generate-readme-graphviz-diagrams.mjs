#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
const skippedDirs = new Set([".git", ".gradle", "build", ".worktrees", ".omx", ".omc", "docs"]);
const titleFont = "Architects Daughter";
const detailFont = "Comic Mono";
const pxPerInch = 96;
const canvasPad = 28;
const framePad = 10;
const arrowMarker = "M 1 1 L 7 4 L 1 7 Z";

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

function humanizeRel(rel) {
  const sourceText = rel === "."
    ? "repository root"
    : rel.replace(/src\/main\/kotlin\/io\/bluetape4k\/workshop\//, "");
  return sourceText
    .replace(/[/_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function imageTitleOf(dir, rel) {
  const title = titleOf(dir);
  return /[^\x00-\x7F]/.test(title) ? humanizeRel(rel) : title;
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

function wrapLines(text, width = 30, limit = 3) {
  return wrapLabel(text, width).split("\\n").filter(Boolean).slice(0, limit);
}

function detailItems(items, width = 30, limit = 4) {
  return uniqueLimit(items, limit)
    .flatMap((item) => wrapLines(item, width, 2))
    .slice(0, limit);
}

function node(id, title, details, fill) {
  const lines = [title, ...detailItems(details, 32, 4)];
  return {
    id,
    title,
    details: lines.slice(1),
    label: lines.join("\\n"),
    fill,
  };
}

function escapeXml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function listFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      listFiles(file, out);
    } else {
      out.push(file);
    }
  }
  return out;
}

function classNameOf(file) {
  return path.basename(file).replace(/\.(kt|kts|yml|yaml|properties|sql|http)$/i, "");
}

function uniqueLimit(items, limit) {
  return [...new Set(items.filter(Boolean))].slice(0, limit);
}

function readMaybe(file) {
  try {
    return fs.readFileSync(file, "utf8");
  } catch {
    return "";
  }
}

function classifySource(file) {
  const name = classNameOf(file);
  const rel = path.relative(root, file).replaceAll(path.sep, "/");
  const text = readMaybe(file);
  const lower = `${rel}\n${text}`.toLowerCase();
  if (/springbootapplication|application\.kt|app\.kt|demoapplication|fun\s+main/.test(lower)) return "entry";
  if (/restcontroller|controller|handler|router|route|webfilter|filter|listener|consumer|producer|scheduler|gateway/.test(lower)) return "api";
  if (/service|manager|usecase|component|lock|warmer|aggregator|poller/.test(lower)) return "service";
  if (/repository|dao|table|entity|r2dbc|jdbc|exposed|redis|kafka|mongodb|elasticsearch|s3|client|store|container|testcontainer|redisson/.test(lower)) return "infra";
  if (/domain|dto|model|message|event|result|inventory|user|order|customer/.test(lower)) return "domain";
  return name.endsWith("Test") || rel.includes("/src/test/") ? "test" : "service";
}

function runtimeOf(files, buildText) {
  const corpus = `${files.map((file) => `${path.relative(root, file)}\n${readMaybe(file)}`).join("\n")}\n${buildText}`.toLowerCase();
  const runtimes = [];
  if (/postgres|r2dbc|jdbc|exposed|querydsl|jpa|h2/.test(corpus)) runtimes.push("SQL / R2DBC");
  if (/redis|redisson|lettuce/.test(corpus)) runtimes.push("Redis");
  if (/kafka/.test(corpus)) runtimes.push("Kafka");
  if (/mongodb|mongo/.test(corpus)) runtimes.push("MongoDB");
  if (/elastic/.test(corpus)) runtimes.push("Elasticsearch");
  if (/localstack|s3|aws/.test(corpus)) runtimes.push("AWS / S3");
  if (/zookeeper|curator/.test(corpus)) runtimes.push("ZooKeeper");
  if (/wiremock/.test(corpus)) runtimes.push("WireMock");
  if (/testcontainers/.test(corpus)) runtimes.push("Testcontainers");
  return uniqueLimit(runtimes, 3);
}

function bluetape4kDeps(buildText) {
  return uniqueLimit([...buildText.matchAll(/bluetape4k[-.:]([A-Za-z0-9_-]+)/g)]
    .map((match) => match[1].replace(/[-_]+/g, " ")), 3);
}

function frameworkDeps(buildText) {
  return uniqueLimit([
    ...[...buildText.matchAll(/libs\.spring\.([A-Za-z0-9_.-]+)/g)].map((match) => `Spring ${match[1].replace(/[._-]+/g, " ")}`),
    ...[...buildText.matchAll(/libs\.kafka\.([A-Za-z0-9_.-]+)/g)].map((match) => `Kafka ${match[1].replace(/[._-]+/g, " ")}`),
    ...[...buildText.matchAll(/libs\.testcontainers\.([A-Za-z0-9_.-]+)/g)].map((match) => `Testcontainers ${match[1].replace(/[._-]+/g, " ")}`),
  ], 4);
}

function childModules(dir) {
  return fs.readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !skippedDirs.has(entry.name))
    .map((entry) => path.join(dir, entry.name))
    .filter((child) => fs.existsSync(path.join(child, "build.gradle.kts")) || fs.existsSync(path.join(child, "README.md")))
    .map((child) => path.basename(child).replace(/[-_]+/g, " "))
    .slice(0, 4);
}

function architectureModel(dir, rel) {
  const files = listFiles(dir);
  const ktFiles = files.filter((file) => file.endsWith(".kt"));
  const mainKt = ktFiles.filter((file) => file.includes("/src/main/"));
  const testKt = ktFiles.filter((file) => file.includes("/src/test/"));
  const buildFile = path.join(dir, "build.gradle.kts");
  const buildText = readMaybe(buildFile);
  const grouped = new Map();
  for (const file of mainKt) {
    const group = classifySource(file);
    if (!grouped.has(group)) grouped.set(group, []);
    grouped.get(group).push(classNameOf(file));
  }

  const children = childModules(dir);
  const runtimes = runtimeOf(files, buildText);
  const btDeps = bluetape4kDeps(buildText);
  const fwDeps = frameworkDeps(buildText);
  const title = imageTitleOf(dir, rel);
  const module = humanizeRel(rel);
  const sourceEvidence = uniqueLimit([
    ...mainKt.map((file) => path.relative(root, file).replaceAll(path.sep, "/")),
    ...testKt.map((file) => path.relative(root, file).replaceAll(path.sep, "/")),
    fs.existsSync(buildFile) ? path.relative(root, buildFile).replaceAll(path.sep, "/") : "",
  ], 8);

  const entryDetails = mainKt.length > 0
    ? uniqueLimit([
      ...uniqueLimit(grouped.get("entry") || [], 3),
      ...testKt.map(classNameOf).filter((name) => /Test|IT|Spec/.test(name)).slice(0, 2),
      `${module} tests`,
    ], 4)
    : [title];

  const nodes = [
    node("entry", mainKt.length > 0 ? "Entry & Verification" : "Example Family", entryDetails, "#FFF8E7"),
  ];

  if ((grouped.get("api") || []).length > 0) {
    nodes.push(node("api", "API & Adapters", grouped.get("api"), "#EEF7FF"));
  } else if (children.length > 0) {
    nodes.push(node("api", "Child Examples", children, "#EEF7FF"));
  }

  const serviceItems = uniqueLimit([...(grouped.get("service") || []), ...(grouped.get("domain") || [])], 4);
  if (serviceItems.length > 0) {
    nodes.push(node("service", "Service & Domain", serviceItems, "#F1F8E9"));
  }

  const infraItems = uniqueLimit(grouped.get("infra") || [], 4);
  if (infraItems.length > 0) {
    nodes.push(node("infra", "Repository & Infra", infraItems, "#F5F0FF"));
  } else if (fwDeps.length > 0) {
    nodes.push(node("infra", "Framework Layer", fwDeps, "#F5F0FF"));
  }

  if (btDeps.length > 0) {
    nodes.push(node("bt", "bluetape4k APIs", btDeps, "#EAF7F0"));
  }

  nodes.push(node("runtime", "Runtime", runtimes.length ? runtimes : ["In-memory / JVM"], "#FFF1F1"));

  const ids = nodes.map((node) => node.id);
  const edges = [];
  for (let i = 0; i < ids.length - 1; i++) {
    if (ids[i] !== "bt" && ids[i + 1] !== "bt") {
      edges.push([ids[i], ids[i + 1], edgeLabel(ids[i], ids[i + 1], i)]);
    }
  }
  if (ids.includes("bt") && ids.includes("runtime")) {
    const source = ids.includes("infra") ? "infra" : ids.includes("service") ? "service" : "api";
    if (source) edges.push([source, "bt", "extends"]);
    edges.push(["bt", "runtime", "adapts"]);
  }

  return { nodes, edges, sourceEvidence };
}

function edgeLabel(from, to, index) {
  if (from === "entry" && to === "api") return "invokes";
  if (to === "service") return "delegates";
  if (to === "infra") return "persists / publishes";
  if (to === "bt") return "extends";
  if (to === "runtime") return index === 0 ? "runs on" : "backs";
  return index === 0 ? "calls" : "uses";
}

function dotFor({ model }) {
  return `digraph G {
  graph [
    rankdir=LR,
    bgcolor="white",
    pad="0.35",
    nodesep="0.70",
    ranksep="0.95",
    splines=ortho,
    outputorder=edgesfirst
  ];
  node [
    shape=box,
    style="rounded,filled",
    penwidth=1.4,
    color="#88A3B5",
    fillcolor="#F6FAFF",
    fontname="${titleFont}",
    fontsize=14,
    margin="0.20,0.16"
  ];
  edge [
    color="#637383",
    arrowsize=0.8,
    penwidth=1.5,
    fontname="${detailFont}",
    fontsize=11
  ];

${model.nodes.map((node) => `  ${node.id} [label="${node.label}", fillcolor="${node.fill}"];`).join("\n")}

${model.edges.map(([from, to, label]) => `  ${from} -> ${to} [label="${label}"];`).join("\n")}
}
`;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    env: { ...process.env, ...options.env },
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed\n${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

function fontFilesFor(family) {
  const output = run("fc-list", [family, "file", "family"]);
  return output
    .split(/\r?\n/)
    .filter((line) => line.toLowerCase().includes(family.toLowerCase()))
    .map((line) => line.split(":")[0])
    .filter(Boolean);
}

function fontDirsForRequiredFonts() {
  const fontFiles = [
    ...fontFilesFor(titleFont),
    ...fontFilesFor(detailFont),
  ];
  const missing = [];
  if (!fontFiles.some((file) => file.toLowerCase().includes("architect"))) missing.push(titleFont);
  if (!fontFiles.some((file) => file.toLowerCase().includes("comicmono"))) missing.push(detailFont);
  if (missing.length > 0) {
    throw new Error(`Required diagram fonts not found by fc-list: ${missing.join(", ")}`);
  }
  return [...new Set(fontFiles.map((file) => path.dirname(file)))];
}

function fontconfigEnv() {
  const dirs = fontDirsForRequiredFonts();
  const cacheDir = path.join(root, ".omx/cache/readme-diagram-fontconfig");
  fs.mkdirSync(cacheDir, { recursive: true });
  const config = `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
${dirs.map((dir) => `  <dir>${dir}</dir>`).join("\n")}
  <cachedir>${cacheDir}</cachedir>
  <match target="pattern">
    <test qual="any" name="family"><string>${titleFont}</string></test>
    <edit name="family" mode="assign" binding="strong"><string>${titleFont}</string></edit>
  </match>
  <match target="pattern">
    <test qual="any" name="family"><string>${detailFont}</string></test>
    <edit name="family" mode="assign" binding="strong"><string>${detailFont}</string></edit>
  </match>
</fontconfig>
`;
  const file = path.join(cacheDir, "fonts.conf");
  fs.writeFileSync(file, config);

  const env = { FONTCONFIG_FILE: file };
  const titleMatch = run("fc-match", [titleFont], { env });
  const detailMatch = run("fc-match", [detailFont], { env });
  if (!titleMatch.includes("ArchitectsDaughter") && !titleMatch.includes("Architects Daughter")) {
    throw new Error(`${titleFont} resolved to fallback: ${titleMatch.trim()}`);
  }
  if (!detailMatch.includes("ComicMono") && !detailMatch.includes("Comic Mono")) {
    throw new Error(`${detailFont} resolved to fallback: ${detailMatch.trim()}`);
  }
  return env;
}

function parsePlain(plain) {
  const graph = { width: 0, height: 0 };
  const nodes = [];
  const edges = [];
  const logicalLines = [];

  for (const line of plain.trim().split(/\r?\n/)) {
    if (logicalLines.length > 0 && logicalLines.at(-1).endsWith("\\")) {
      logicalLines[logicalLines.length - 1] = `${logicalLines.at(-1).slice(0, -1)}${line}`;
    } else {
      logicalLines.push(line);
    }
  }

  for (const rawLine of logicalLines) {
    const parts = rawLine.match(/"[^"]*"|\S+/g)?.map((part) => part.replace(/^"|"$/g, "")) || [];
    if (parts[0] === "graph") {
      graph.width = Number(parts[2]);
      graph.height = Number(parts[3]);
    } else if (parts[0] === "node") {
      nodes.push({
        id: parts[1],
        cx: Number(parts[2]),
        cy: Number(parts[3]),
        width: Number(parts[4]),
        height: Number(parts[5]),
        label: parts[6],
        stroke: parts.at(-2),
        fill: parts.at(-1),
      });
    } else if (parts[0] === "edge") {
      const pointCount = Number(parts[3]);
      const points = [];
      for (let i = 0; i < pointCount; i++) {
        points.push({ x: Number(parts[4 + i * 2]), y: Number(parts[5 + i * 2]) });
      }
      const afterPoints = 4 + pointCount * 2;
      const maybeLabel = parts[afterPoints];
      const hasLabel = Number.isNaN(Number(maybeLabel));
      edges.push({
        from: parts[1],
        to: parts[2],
        points,
        label: hasLabel ? maybeLabel : "",
        labelX: hasLabel ? Number(parts[afterPoints + 1]) : undefined,
        labelY: hasLabel ? Number(parts[afterPoints + 2]) : undefined,
        color: parts.at(-1),
      });
    }
  }

  return { graph, nodes, edges };
}

function svgFromPlain(plain, model) {
  const { graph, nodes, edges } = parsePlain(plain);
  const width = Math.ceil(graph.width * pxPerInch + canvasPad * 2);
  const height = Math.ceil(graph.height * pxPerInch + canvasPad * 2);
  const x = (value) => canvasPad + value * pxPerInch;
  const y = (value) => canvasPad + (graph.height - value) * pxPerInch;
  const attrs = (items) => Object.entries(items).map(([key, value]) => `${key}="${escapeXml(value)}"`).join(" ");
  const textLines = (label) => label.split("\\n");

  const edgeSvg = edges.map((edge) => {
    const points = edge.points.map((point) => `${x(point.x).toFixed(1)},${y(point.y).toFixed(1)}`).join(" ");
    const label = edge.label
      ? `<text class="edge-label" x="${x(edge.labelX).toFixed(1)}" y="${(y(edge.labelY) - 7).toFixed(1)}" text-anchor="middle">${escapeXml(edge.label)}</text>`
      : "";
    return `<polyline class="connector" points="${points}" marker-end="url(#arrow)"/>${label}`;
  }).join("\n    ");

  const nodeSvg = nodes.map((node) => {
    const left = x(node.cx - node.width / 2);
    const top = y(node.cy + node.height / 2);
    const w = node.width * pxPerInch;
    const h = node.height * pxPerInch;
    const lines = textLines(node.label);
    const titleLineHeight = 22;
    const detailLineHeight = 16;
    const blockHeight = titleLineHeight + Math.max(0, lines.length - 1) * detailLineHeight;
    const startY = top + h / 2 - blockHeight / 2 + 17;
    const tspans = lines.map((line, index) => {
      const className = index === 0 ? "node-title" : "node-detail";
      const yPos = index === 0
        ? startY
        : startY + titleLineHeight + (index - 1) * detailLineHeight;
      return `<tspan class="${className}" x="${(left + w / 2).toFixed(1)}" y="${yPos.toFixed(1)}">${escapeXml(line)}</tspan>`;
    }).join("");
    return `<g class="node" data-node="${escapeXml(node.id)}">
      <rect ${attrs({
        x: left.toFixed(1),
        y: top.toFixed(1),
        width: w.toFixed(1),
        height: h.toFixed(1),
        rx: 15,
        fill: node.fill,
        stroke: node.stroke,
      })}/>
      <text text-anchor="middle">${tspans}</text>
    </g>`;
  }).join("\n    ");

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="Source-derived README architecture diagram">
  <metadata>${escapeXml(JSON.stringify({ sourceEvidence: model.sourceEvidence }))}</metadata>
  <defs>
    <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
      <path d="${arrowMarker}" fill="#637383"/>
    </marker>
    <style>
      .canvas { fill: #F8FBFD; }
      .frame { fill: #FFFFFF; stroke: #D5E0E8; stroke-width: 1.2; }
      .node rect { stroke-width: 1.6; }
      .node-title { font-family: "${titleFont}"; font-size: 18px; fill: #102033; }
      .node-detail { font-family: "${detailFont}"; font-size: 12px; fill: #465160; }
      .edge-label { font-family: "${detailFont}"; font-size: 13px; fill: #102033; paint-order: stroke; stroke: #FFFFFF; stroke-width: 4px; stroke-linejoin: round; }
      .connector { fill: none; stroke: #637383; stroke-width: 2; stroke-linecap: square; stroke-linejoin: round; }
    </style>
  </defs>
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="frame" x="${framePad}" y="${framePad}" width="${width - framePad * 2}" height="${height - framePad * 2}" rx="18"/>
  <g>
    ${edgeSvg}
    ${nodeSvg}
  </g>
</svg>
`;
}

fs.mkdirSync(outDir, { recursive: true });
const renderEnv = fontconfigEnv();

const dirs = walkReadmes(root)
  .filter((dir) => dir !== root)
  .sort();

let generated = 0;
for (const dir of dirs) {
  const rel = path.relative(root, dir).replaceAll(path.sep, "/");
  const slug = slugOf(rel);
  const base = path.join(outDir, `${slug}-readme-architecture-01`);
  const model = architectureModel(dir, rel);
  if (model.sourceEvidence.length === 0) {
    throw new Error(`No source evidence found for ${rel}`);
  }
  const dot = dotFor({ model });
  fs.writeFileSync(`${base}.dot`, dot);
  const plain = run("dot", ["-Tplain", `${base}.dot`], { env: renderEnv });
  fs.writeFileSync(`${base}.plain`, plain);
  run("dot", ["-Tsvg", `${base}.dot`, "-o", `${base}-graphviz.svg`], { env: renderEnv });
  run("dot", ["-Tpng", `${base}.dot`, "-o", `${base}-graphviz.png`], { env: renderEnv });
  fs.writeFileSync(`${base}.svg`, svgFromPlain(plain, model));
  run("rsvg-convert", [`${base}.svg`, "-o", `${base}.png`], { env: renderEnv });
  generated++;
}

console.log(`graphviz_readme_diagrams=${generated}`);
