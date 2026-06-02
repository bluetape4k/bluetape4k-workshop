#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
const ignoredDirs = new Set([".git", ".gradle", ".omc", ".omx", "build", "node_modules"]);

function walk(dir, predicate) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!ignoredDirs.has(entry.name)) out.push(...walk(full, predicate));
    } else if (entry.isFile() && predicate(full)) {
      out.push(full);
    }
  }
  return out;
}

function slugify(value) {
  return value
    .replace(root, "")
    .replace(/README(\..+)?\.md$/, "")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .toLowerCase();
}

function esc(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function diagramText(value) {
  return String(value)
    .replace(/예제/g, "Example")
    .replace(/정상 완료/g, "normal completion")
    .replace(/예외 발생/g, "exception")
    .replace(/성공/g, "success")
    .replace(/실패/g, "failure")
    .replace(/[—–]/g, "-")
    .replace(/→/g, "->")
    .replace(/←/g, "<-")
    .replace(/≥/g, ">=")
    .replace(/≤/g, "<=")
    .replace(/[^\x00-\x7F]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function textLines(text, maxChars = 28) {
  const clean = diagramText(text.replace(/<br\s*\/?>/gi, " / "));
  if (clean.length <= maxChars) return [clean];
  const words = clean.split(" ");
  const lines = [];
  let line = "";
  for (const word of words) {
    if (!line) line = word;
    else if ((line + " " + word).length <= maxChars) line += " " + word;
    else {
      lines.push(line);
      line = word;
    }
  }
  if (line) lines.push(line);
  return lines.slice(0, 3);
}

function textBlock(text, x, y, className, anchor = "middle", maxChars = 28, lineHeight = 17) {
  return textLines(text, maxChars)
    .map((line, index, lines) => {
      const yy = y + (index - (lines.length - 1) / 2) * lineHeight;
      return `<text class="${className}" x="${x}" y="${yy}" text-anchor="${anchor}" dominant-baseline="middle">${esc(line)}</text>`;
    })
    .join("\n");
}

function svgHeader(width, height, title, subtitle) {
  title = diagramText(title);
  subtitle = diagramText(subtitle);
  const titleSize = Math.max(24, Math.min(34, Math.floor((width - 100) / Math.max(title.length * 0.5, 1))));
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(title)}">
<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="4" stdDeviation="5" flood-color="#1f2937" flood-opacity="0.10"/></filter>
  <marker id="arrowBlue" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M1 1 L7 4 L1 7 Z" fill="#5b8def"/></marker>
  <marker id="arrowTeal" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M1 1 L7 4 L1 7 Z" fill="#45a7a1"/></marker>
  <style>
    .canvas{fill:#f7f9fc}
    .frame{fill:#ffffff;stroke:#d8e0ea;stroke-width:1.5}
    .title{font-family:"Architects Daughter";font-size:${titleSize}px;fill:#102033}
    .subtitle{font-family:"Comic Mono";font-size:13px;fill:#536273}
    .actor{font-family:"Architects Daughter";font-size:17px;fill:#102033}
    .detail{font-family:"Comic Mono";font-size:11px;fill:#536273}
    .message{font-family:"Comic Mono";font-size:12px;fill:#263445;paint-order:stroke;stroke:#ffffff;stroke-width:4px;stroke-linejoin:round}
    .branch{font-family:"Architects Daughter";font-size:15px;fill:#1f3b57}
    .lifeline{stroke:#b8c4d3;stroke-width:1.4;stroke-dasharray:7 6}
    .call{stroke:#5b8def;stroke-width:2.1;fill:none;marker-end:url(#arrowBlue)}
    .return{stroke:#45a7a1;stroke-width:2;stroke-dasharray:7 5;fill:none;marker-end:url(#arrowTeal)}
    .box{stroke-width:1.8;filter:url(#shadow)}
  </style>
</defs>
<rect class="canvas" width="${width}" height="${height}"/>
<rect class="frame" x="24" y="20" width="${width - 48}" height="${height - 40}" rx="18"/>
<text class="title" x="48" y="58">${esc(title)}</text>
<text class="subtitle" x="50" y="84">${esc(subtitle)}</text>`;
}

function parseSequence(source) {
  const actors = new Map();
  const events = [];
  const aliases = new Map();
  for (const raw of source.split(/\n/)) {
    const line = raw.trim();
    if (!line || line === "sequenceDiagram") continue;
    const participant = line.match(/^participant\s+([A-Za-z0-9_]+)(?:\s+as\s+(.+))?$/);
    if (participant) {
      const id = participant[1];
      const label = participant[2] || id;
      aliases.set(id, label);
      actors.set(id, label);
      continue;
    }
    const msg = line.match(/^([A-Za-z0-9_]+)\s*(-{1,2}>>)\s*([A-Za-z0-9_]+)\s*:\s*(.+)$/);
    if (msg) {
      const [, from, arrow, to, label] = msg;
      if (!actors.has(from)) actors.set(from, aliases.get(from) || from);
      if (!actors.has(to)) actors.set(to, aliases.get(to) || to);
      events.push({ type: "message", from, to, label, dashed: arrow.startsWith("--") });
      continue;
    }
    const note = line.match(/^Note\s+over\s+([A-Za-z0-9_]+)(?:\s*,\s*([A-Za-z0-9_]+))?\s*:\s*(.+)$/);
    if (note) {
      const [, from, to, label] = note;
      if (!actors.has(from)) actors.set(from, aliases.get(from) || from);
      if (to && !actors.has(to)) actors.set(to, aliases.get(to) || to);
      events.push({ type: "note", from, to: to || from, label });
      continue;
    }
    const branch = line.match(/^(alt|else|opt|loop)\s*(.*)$/);
    if (branch) {
      events.push({ type: "branch", kind: branch[1], label: branch[2] || branch[1] });
      continue;
    }
    if (line === "end") {
      events.push({ type: "branch", kind: "end", label: "end" });
    }
  }
  return { actors: [...actors.entries()].map(([id, label]) => ({ id, label })), events };
}

function renderSequence(source, title, subtitle) {
  const model = parseSequence(source);
  const actorCount = Math.max(model.actors.length, 2);
  const width = Math.max(1040, actorCount * 210 + 220);
  const top = 116;
  const actorY = top;
  const eventStart = 218;
  const eventGap = 54;
  const height = Math.max(420, eventStart + model.events.length * eventGap + 72);
  const laneLeft = 122;
  const laneRight = width - 122;
  const laneStep = (laneRight - laneLeft) / (actorCount - 1);
  const xByActor = new Map(model.actors.map((actor, index) => [actor.id, laneLeft + laneStep * index]));
  const colors = ["#e8f3ff", "#eaf7ef", "#fff3d9", "#f1ecff", "#fdecef", "#e9f7f6"];
  let svg = svgHeader(width, height, title, subtitle);

  model.actors.forEach((actor, index) => {
    const x = xByActor.get(actor.id);
    const fill = colors[index % colors.length];
    svg += `\n<rect class="box" x="${x - 76}" y="${actorY}" width="152" height="58" rx="10" fill="${fill}" stroke="#7aa0c4"/>`;
    svg += `\n${textBlock(actor.label, x, actorY + 29, "actor", "middle", 18, 16)}`;
    svg += `\n<line class="lifeline" x1="${x}" y1="${actorY + 58}" x2="${x}" y2="${height - 58}"/>`;
  });

  model.events.forEach((event, index) => {
    const y = eventStart + index * eventGap;
    if (event.type === "branch") {
      if (event.kind === "end") {
        svg += `\n<line x1="52" y1="${y}" x2="${width - 52}" y2="${y}" stroke="#d8e0ea" stroke-width="1.2" stroke-dasharray="5 5"/>`;
      } else {
        svg += `\n<rect x="50" y="${y - 18}" width="${width - 100}" height="34" rx="8" fill="#f8fafc" stroke="#cbd5e1" stroke-dasharray="6 4"/>`;
        svg += `\n<text class="branch" x="68" y="${y + 1}" dominant-baseline="middle">${esc(diagramText(`${event.kind}: ${event.label}`))}</text>`;
      }
      return;
    }
    if (event.type === "note") {
      const fromX = xByActor.get(event.from);
      const toX = xByActor.get(event.to);
      if (fromX === undefined || toX === undefined) return;
      const left = Math.min(fromX, toX) - 84;
      const right = Math.max(fromX, toX) + 84;
      svg += `\n<rect x="${left}" y="${y - 18}" width="${right - left}" height="34" rx="8" fill="#fff8dc" stroke="#e7c86b" stroke-dasharray="5 4"/>`;
      svg += `\n${textBlock(event.label, (left + right) / 2, y - 1, "message", "middle", Math.max(24, Math.floor((right - left) / 9)), 14)}`;
      return;
    }
    const fromX = xByActor.get(event.from);
    const toX = xByActor.get(event.to);
    if (fromX === undefined || toX === undefined) return;
    const labelY = y - 16;
    const arrowClass = event.dashed ? "return" : "call";
    const labelWidth = Math.min(Math.abs(toX - fromX) + 88, 420);
    const midX = (fromX + toX) / 2;
    svg += `\n<rect x="${midX - labelWidth / 2}" y="${labelY - 14}" width="${labelWidth}" height="24" rx="6" fill="#ffffff" opacity="0.92" stroke="#dbe3ee"/>`;
    svg += `\n${textBlock(`${index + 1}. ${event.label}`, midX, labelY - 1, "message", "middle", Math.max(18, Math.floor(labelWidth / 9)), 14)}`;
    svg += `\n<path class="${arrowClass}" d="M${fromX} ${y} L${toX} ${y}"/>`;
  });

  svg += "\n</svg>\n";
  return svg;
}

function parseFlow(source) {
  const nodes = new Map();
  const edges = [];
  const groups = new Map();
  let currentGroup = null;
  const addNode = (token) => {
    const node = parseNodeToken(token);
    if (!node) return null;
    nodes.set(node.id, node.label);
    if (currentGroup && !groups.has(node.id)) groups.set(node.id, currentGroup);
    return node.id;
  };
  for (const raw of source.split(/\n/)) {
    const line = raw.trim();
    if (!line || /^(flowchart|graph)\s+/.test(line) || line.startsWith("classDef") || line.startsWith("class ")) continue;
    const subgraph = line.match(/^subgraph\s+(?:[A-Za-z0-9_]+\s*)?(?:\[(.+)]|\"(.+)\"|(.+))$/);
    if (subgraph) {
      currentGroup = cleanLabel(subgraph[1] || subgraph[2] || subgraph[3] || "Group");
      continue;
    }
    if (line === "end") {
      currentGroup = null;
      continue;
    }
    const edge = line.match(/^(.+?)\s*(---|-->|==>)\s*(?:\|([^|]+)\|\s*)?(.+)$/);
    if (edge) {
      const from = addNode(edge[1].trim());
      const to = addNode(edge[4].trim());
      if (from && to) edges.push({ from, to, label: cleanLabel(edge[3] || "") });
      continue;
    }
    addNode(line);
  }
  return {
    nodes: [...nodes.entries()].map(([id, label]) => ({ id, label, group: groups.get(id) || "" })),
    edges,
  };
}

function cleanLabel(value) {
  return String(value || "")
    .replace(/^["']|["']$/g, "")
    .replace(/\\n/g, " / ")
    .replace(/\s+/g, " ")
    .trim();
}

function parseNodeToken(token) {
  const clean = token.trim().replace(/;$/, "");
  const match = clean.match(/^([A-Za-z0-9_]+)(?:\[(.+)]|\((.+)\)|\{(.+)})?$/);
  if (!match) return null;
  const label = cleanLabel(match[2] || match[3] || match[4] || match[1]);
  return { id: match[1], label };
}

function dotEsc(value) {
  return String(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

function flowLayoutWithGraphviz(model, direction) {
  const rankdir = direction === "LR" ? "LR" : "TB";
  const dot = [
    "digraph G {",
    `  graph [rankdir=${rankdir}, splines=ortho, nodesep=0.7, ranksep=0.8, margin=0.1];`,
    '  node [shape=box, fixedsize=false, width=2.2, height=0.9, margin=0.08, style="rounded,filled", color="#7aa0c4", fillcolor="#e8f3ff", fontname="Architects Daughter"];',
    '  edge [color="#5b8def", penwidth=1.8, arrowsize=0.7, fontname="Comic Mono", fontsize=10];',
    ...model.nodes.map((node) => `  "${dotEsc(node.id)}" [label="${dotEsc(node.label)}"];`),
    ...model.edges.map(({ from, to }) => `  "${dotEsc(from)}" -> "${dotEsc(to)}";`),
    "}",
  ].join("\n");
  const plain = execFileSync("dot", ["-Tplain"], { input: dot, encoding: "utf8" });
  return { dot, plain };
}

function parsePlainLayout(plain) {
  const nodes = new Map();
  const edges = [];
  let graphWidth = 8;
  let graphHeight = 5;
  for (const raw of plain.split(/\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const graph = line.match(/^graph\s+\S+\s+(\S+)\s+(\S+)/);
    if (graph) {
      graphWidth = Number(graph[1]);
      graphHeight = Number(graph[2]);
      continue;
    }
    const node = line.match(/^node\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)/);
    if (node) {
      nodes.set(node[1].replace(/^"|"$/g, ""), {
        x: Number(node[2]),
        y: Number(node[3]),
        width: Number(node[4]),
        height: Number(node[5]),
      });
      continue;
    }
    const edge = line.match(/^edge\s+(\S+)\s+(\S+)\s+(\d+)\s+(.+)$/);
    if (edge) {
      const count = Number(edge[3]);
      const values = edge[4].split(/\s+/).slice(0, count * 2).map(Number);
      const points = [];
      for (let i = 0; i < values.length; i += 2) points.push({ x: values[i], y: values[i + 1] });
      edges.push({
        from: edge[1].replace(/^"|"$/g, ""),
        to: edge[2].replace(/^"|"$/g, ""),
        points,
      });
    }
  }
  return { graphWidth, graphHeight, nodes, edges };
}

function renderFlow(source, title, subtitle) {
  const model = parseFlow(source);
  const direction = (source.trim().split(/\n/)[0] || "").includes(" LR") ? "LR" : "TB";
  const layout = parsePlainLayout(flowLayoutWithGraphviz(model, direction).plain);
  const scale = 116;
  const marginX = 80;
  const headerY = 132;
  const width = Math.max(940, Math.ceil(layout.graphWidth * scale + marginX * 2));
  const height = Math.max(460, Math.ceil(layout.graphHeight * scale + headerY + 74));
  const pos = new Map();
  let svg = svgHeader(width, height, title, subtitle);
  model.nodes.forEach((node, index) => {
    const placed = layout.nodes.get(node.id);
    if (!placed) return;
    pos.set(node.id, {
      cx: marginX + placed.x * scale,
      cy: headerY + (layout.graphHeight - placed.y) * scale,
      width: Math.max(210, placed.width * scale),
      height: Math.max(86, placed.height * scale),
    });
  });
  const labelByEdge = new Map(model.edges.map((edge) => [`${edge.from}->${edge.to}`, edge.label]));
  for (const edge of layout.edges) {
    const points = edge.points.map((point) => ({
      x: marginX + point.x * scale,
      y: headerY + (layout.graphHeight - point.y) * scale,
    }));
    if (points.length < 2) continue;
    const route = points.map((point, index) => `${index === 0 ? "M" : "L"}${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(" ");
    svg += `\n<path class="call" d="${route}"/>`;
    const label = labelByEdge.get(`${edge.from}->${edge.to}`);
    if (label) {
      const mid = points[Math.floor(points.length / 2)];
      const labelWidth = Math.min(190, Math.max(92, label.length * 7 + 34));
      svg += `\n<rect x="${mid.x - labelWidth / 2}" y="${mid.y - 32}" width="${labelWidth}" height="24" rx="6" fill="#ffffff" opacity="0.95" stroke="#dbe3ee"/>`;
      svg += `\n${textBlock(label, mid.x, mid.y - 20, "message", "middle", Math.max(16, Math.floor(labelWidth / 8)), 13)}`;
    }
  }
  const colors = ["#e8f3ff", "#eaf7ef", "#fff3d9", "#f1ecff", "#fdecef", "#e9f7f6"];
  model.nodes.forEach((node, index) => {
    const p = pos.get(node.id);
    if (!p) return;
    const fill = colors[index % colors.length];
    svg += `\n<rect class="box" x="${p.cx - p.width / 2}" y="${p.cy - p.height / 2}" width="${p.width}" height="${p.height}" rx="10" fill="${fill}" stroke="#7aa0c4"/>`;
    svg += `\n${textBlock(node.label, p.cx, p.cy - (node.group ? 10 : 0), "actor", "middle", 24, 16)}`;
    if (node.group) {
      svg += `\n${textBlock(node.group, p.cx, p.cy + 26, "detail", "middle", 30, 12)}`;
    }
  });
  svg += "\n</svg>\n";
  return svg;
}

function renderMermaid(source, title, subtitle) {
  const first = source.trim().split(/\n/)[0] || "";
  if (first === "sequenceDiagram") {
    return renderSequence(source, title, subtitle);
  }
  return renderFlow(source, title, subtitle);
}

function diagramType(source) {
  const first = source.trim().split(/\n/)[0] || "";
  if (first === "sequenceDiagram") return "sequence";
  if (/^(flowchart|graph)\s+/.test(first)) return "flow";
  return "diagram";
}

function titleFromReadme(readme) {
  const content = fs.readFileSync(readme, "utf8");
  const match = content.match(/^#\s+(.+)$/m);
  return match ? match[1].trim() : path.basename(path.dirname(readme));
}

function renderPng(svgFile, pngFile) {
  execFileSync("rsvg-convert", ["--keep-aspect-ratio", "-f", "png", "-o", pngFile, svgFile], {
    stdio: "pipe",
  });
}

fs.mkdirSync(outDir, { recursive: true });

let converted = 0;
const files = walk(root, (file) => /^README(\..+)?\.md$/.test(path.basename(file)));
for (const readme of files) {
  let content = fs.readFileSync(readme, "utf8");
  let blockIndex = 0;
  const next = content.replace(/```mermaid\n([\s\S]*?)\n```/g, (block, source) => {
    blockIndex += 1;
    const type = `readme-${diagramType(source)}`;
    const base = `${slugify(readme)}-${type}-${String(blockIndex).padStart(2, "0")}`;
    const svgFile = path.join(outDir, `${base}.svg`);
    const pngFile = path.join(outDir, `${base}.png`);
    const title = `${titleFromReadme(readme)} Diagram ${blockIndex}`;
    const first = source.trim().split(/\n/)[0] || "mermaid";
    const subtitle = `${first} rendered from the current README model; labels are separated from connector lanes`;
    const svg = renderMermaid(source, title, subtitle);
    fs.writeFileSync(svgFile, svg);
    renderPng(svgFile, pngFile);
    converted += 1;
    const rel = path.relative(path.dirname(readme), pngFile).replaceAll(path.sep, "/");
    return `![${title}](${rel})`;
  });
  if (next !== content) {
    fs.writeFileSync(readme, next);
  }
}

console.log(JSON.stringify({ converted }, null, 2));
