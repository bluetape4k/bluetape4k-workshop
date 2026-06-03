#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const sequenceDir = path.join(root, "docs/images/readme-diagrams");

function esc(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function unesc(value) {
  return String(value)
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&#39;", "'");
}

function stripTags(value) {
  return unesc(String(value).replace(/<[^>]+>/g, "").replace(/\s+/g, " ").trim());
}

function textLines(text, maxChars = 34) {
  const words = String(text).replace(/\s+/g, " ").trim().split(" ").filter(Boolean);
  const lines = [];
  let line = "";
  for (const word of words) {
    if (word.length > maxChars) {
      if (line) lines.push(line);
      for (let i = 0; i < word.length; i += maxChars) lines.push(word.slice(i, i + maxChars));
      line = "";
      continue;
    }
    const next = line ? `${line} ${word}` : word;
    if (next.length > maxChars && line) {
      lines.push(line);
      line = word;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines.length ? lines : [""];
}

function textBlock(text, x, y, className, maxChars, lineHeight = 15) {
  return textLines(text, maxChars)
    .map((line, index, lines) => {
      const yy = y + (index - (lines.length - 1) / 2) * lineHeight;
      return `<text class="${className}" x="${x}" y="${yy}" text-anchor="middle" dominant-baseline="middle">${esc(line)}</text>`;
    })
    .join("\n");
}

function actorText(label, x, y) {
  return textBlock(label, x, y, "actor", 17, 14);
}

function readTexts(svg) {
  const texts = [];
  const textRe = /<text\b([^>]*)>([\s\S]*?)<\/text>/g;
  let match;
  while ((match = textRe.exec(svg))) {
    const attrs = match[1];
    const x = Number((attrs.match(/\bx="([^"]+)"/) || [])[1]);
    const y = Number((attrs.match(/\by="([^"]+)"/) || [])[1]);
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    const text = stripTags(match[2]);
    if (!Number.isFinite(x) || !Number.isFinite(y) || !text) continue;
    texts.push({ x, y, className, text });
  }
  const groupRe = /<g\b[^>]*transform="translate\(([-+]?\d*\.?\d+),\s*([-+]?\d*\.?\d+)\)"[^>]*>([\s\S]*?)<\/g>/g;
  while ((match = groupRe.exec(svg))) {
    const dx = Number(match[1]);
    const dy = Number(match[2]);
    const inner = match[3];
    let innerMatch;
    const innerTextRe = /<text\b([^>]*)>([\s\S]*?)<\/text>/g;
    while ((innerMatch = innerTextRe.exec(inner))) {
      const attrs = innerMatch[1];
      const x = Number((attrs.match(/\bx="([^"]+)"/) || [])[1]);
      const y = Number((attrs.match(/\by="([^"]+)"/) || [])[1]);
      const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
      const text = stripTags(innerMatch[2]);
      if (!Number.isFinite(x) || !Number.isFinite(y) || !text) continue;
      texts.push({ x: x + dx, y: y + dy, className, text });
    }
  }
  return texts;
}

function readLifelines(svg) {
  const lines = [];
  const lineRe = /<line\b([^>]*)\/>/g;
  let match;
  while ((match = lineRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    const x = Number((attrs.match(/\bx1="([^"]+)"/) || [])[1]);
    const x2 = Number((attrs.match(/\bx2="([^"]+)"/) || [])[1]);
    const y1 = Number((attrs.match(/\by1="([^"]+)"/) || [])[1]);
    const y2 = Number((attrs.match(/\by2="([^"]+)"/) || [])[1]);
    const dashed = /\bstroke-dasharray=/.test(attrs);
    const vertical = Number.isFinite(x) && Number.isFinite(x2) && Math.abs(x - x2) < 2;
    if ((className.includes("lifeline") || (vertical && dashed && Math.abs(y2 - y1) > 120)) && Number.isFinite(x)) {
      lines.push({ x, y1, y2 });
    }
  }
  return lines.sort((a, b) => a.x - b.x);
}

function pathPoints(d) {
  const values = [...d.matchAll(/[-+]?\d*\.?\d+/g)].map((m) => Number(m[0]));
  if (values.length < 4) return null;
  return { x1: values[0], y1: values[1], x2: values[values.length - 2], y2: values[values.length - 1] };
}

function readPaths(svg) {
  const paths = [];
  const pathRe = /<path\b([^>]*)\/>/g;
  let match;
  while ((match = pathRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    if (!/(reqArrow|rspArrow|call|return|lineBlue|lineGreen|dashGray|\bline\b|dashed)/.test(className)) continue;
    const d = (attrs.match(/\bd="([^"]+)"/) || [])[1];
    if (!d) continue;
    const points = pathPoints(d);
    if (!points) continue;
    if (Math.abs(points.x2 - points.x1) < 18 && Math.abs(points.y2 - points.y1) < 18) continue;
    paths.push({ ...points, className, dashed: /rspArrow|return|dashGray|dashed/.test(className) });
  }
  const lineRe = /<line\b([^>]*)\/>/g;
  while ((match = lineRe.exec(svg))) {
    const attrs = match[1];
    const className = (attrs.match(/\bclass="([^"]+)"/) || [])[1] || "";
    if (!/(reqArrow|rspArrow|call|return|lineBlue|lineGreen|dashGray|\bline\b|dashed)/.test(className)) continue;
    const x1 = Number((attrs.match(/\bx1="([^"]+)"/) || [])[1]);
    const y1 = Number((attrs.match(/\by1="([^"]+)"/) || [])[1]);
    const x2 = Number((attrs.match(/\bx2="([^"]+)"/) || [])[1]);
    const y2 = Number((attrs.match(/\by2="([^"]+)"/) || [])[1]);
    if (![x1, y1, x2, y2].every(Number.isFinite)) continue;
    if (Math.abs(x2 - x1) < 18 && Math.abs(y2 - y1) < 18) continue;
    paths.push({ x1, y1, x2, y2, className, dashed: /rspArrow|return|dashGray|dashed/.test(className) });
  }
  return paths.sort((a, b) => a.y1 - b.y1 || a.x1 - b.x1);
}

function nearestActor(x, actors) {
  return actors.reduce((best, actor) => {
    const distance = Math.abs(actor.oldX - x);
    return !best || distance < best.distance ? { actor, distance } : best;
  }, null)?.actor;
}

function labelForPath(pathInfo, texts, used) {
  const minX = Math.min(pathInfo.x1, pathInfo.x2) - 90;
  const maxX = Math.max(pathInfo.x1, pathInfo.x2) + 90;
  const candidates = texts
    .map((text, index) => ({ ...text, index }))
    .filter((text) => !used.has(text.index))
    .filter((text) => !/(title|subtitle|actor|smallLabel)/.test(text.className))
    .filter((text) => !/^\d+\.?$/.test(text.text))
    .filter((text) => text.x >= minX && text.x <= maxX)
    .filter((text) => Math.abs(text.y - pathInfo.y1) <= 34 || (text.y > pathInfo.y1 && text.y - pathInfo.y1 <= 48));

  if (candidates.length === 0) return "";

  candidates.sort((a, b) => Math.abs(a.y - pathInfo.y1) - Math.abs(b.y - pathInfo.y1) || a.x - b.x);
  const base = candidates[0];
  const group = candidates
    .filter((text) => Math.abs(text.x - base.x) <= 12 && Math.abs(text.y - base.y) <= 18)
    .sort((a, b) => a.y - b.y);
  for (const text of group) used.add(text.index);
  return group.map((text) => text.text).join(" / ").replace(/\s+/g, " ").trim();
}

function inferActors(svg, texts, lifelines) {
  const headerY = Math.min(...lifelines.map((line) => line.y1).filter(Number.isFinite), 160);
  return lifelines.map((line, index) => {
    const candidates = texts
      .filter((text) => !/(title|subtitle)/.test(text.className))
      .filter((text) => Math.abs(text.x - line.x) < 86)
      .filter((text) => text.y >= 88 && text.y <= headerY + 10)
      .sort((a, b) => a.y - b.y);
    const label = candidates.map((text) => text.text).join(" ").trim() || `Actor ${index + 1}`;
    return { id: `a${index}`, oldX: line.x, label };
  });
}

function parseSequenceSvg(svg) {
  const texts = readTexts(svg);
  const title = texts.find((text) => text.className.includes("title"))?.text || "Sequence Diagram";
  const subtitle = texts.find((text) => text.className.includes("subtitle"))?.text || "Labels are placed below call lines";
  const lifelines = readLifelines(svg);
  const actors = inferActors(svg, texts, lifelines);
  const used = new Set();
  const messages = readPaths(svg).map((pathInfo, index) => {
    const from = nearestActor(pathInfo.x1, actors);
    const to = nearestActor(pathInfo.x2, actors);
    const label = labelForPath(pathInfo, texts, used) || `${from?.label || "source"} to ${to?.label || "target"}`;
    return {
      index: index + 1,
      from: from?.id || actors[0]?.id || "a0",
      to: to?.id || actors[actors.length - 1]?.id || "a1",
      label: label.replace(/^[①②③④⑤⑥⑦⑧⑨⑩]\s*/, ""),
      dashed: pathInfo.dashed,
    };
  });
  return { title, subtitle, actors, messages };
}

function renderSequence(model) {
  const actorCount = Math.max(model.actors.length, 2);
  const width = Math.max(1040, actorCount * 220 + 170);
  const actorY = 108;
  const eventStart = 205;
  const eventGap = 108;
  const height = Math.max(430, eventStart + model.messages.length * eventGap + 86);
  const laneLeft = 92;
  const laneRight = width - 92;
  const laneStep = (laneRight - laneLeft) / (actorCount - 1);
  const actors = model.actors.length ? model.actors : [{ id: "a0", label: "Source" }, { id: "a1", label: "Target" }];
  const xByActor = new Map(actors.map((actor, index) => [actor.id, laneLeft + laneStep * index]));
  const colors = ["#e8f3ff", "#eaf7ef", "#fff3d9", "#f1ecff", "#fdecef", "#e9f7f6"];

  let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${esc(model.title)}">
<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="116%"><feDropShadow dx="0" dy="4" stdDeviation="5" flood-color="#1f2937" flood-opacity="0.10"/></filter>
  <marker id="arrowBlue" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M1 1 L7 4 L1 7 Z" fill="#5b8def"/></marker>
  <marker id="arrowTeal" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth"><path d="M1 1 L7 4 L1 7 Z" fill="#45a7a1"/></marker>
  <style>
    .canvas{fill:#f7f9fc}
    .frame{fill:#ffffff;stroke:#d8e0ea;stroke-width:1.5}
    .title{font-family:"Architects Daughter";font-size:34px;fill:#102033}
    .subtitle{font-family:"Comic Mono";font-size:13px;fill:#536273}
    .actor{font-family:"Architects Daughter";font-size:17px;fill:#102033}
    .message{font-family:"Comic Mono";font-size:12px;fill:#263445;paint-order:stroke;stroke:#ffffff;stroke-width:4px;stroke-linejoin:round}
    .detail{font-family:"Comic Mono";font-size:11px;fill:#536273}
    .lifeline{stroke:#b8c4d3;stroke-width:1.4;stroke-dasharray:7 6}
    .call{stroke:#5b8def;stroke-width:2.15;fill:none;marker-end:url(#arrowBlue)}
    .return{stroke:#45a7a1;stroke-width:2.05;stroke-dasharray:7 5;fill:none;marker-end:url(#arrowTeal)}
    .box{stroke-width:1.8;filter:url(#shadow)}
  </style>
</defs>
<rect class="canvas" width="${width}" height="${height}"/>
<rect class="frame" x="24" y="20" width="${width - 48}" height="${height - 40}" rx="18"/>
<text class="title" x="48" y="58">${esc(model.title)}</text>
<text class="subtitle" x="50" y="84">${esc(model.subtitle.replace(/"/g, "'"))}</text>`;

  actors.forEach((actor, index) => {
    const x = xByActor.get(actor.id);
    svg += `\n<rect class="box" x="${x - 90}" y="${actorY}" width="180" height="58" rx="10" fill="${colors[index % colors.length]}" stroke="#7aa0c4"/>`;
    svg += `\n${actorText(actor.label, x, actorY + 29)}`;
    svg += `\n<line class="lifeline" x1="${x}" y1="${actorY + 58}" x2="${x}" y2="${height - 58}"/>`;
  });

  model.messages.forEach((message, messageIndex) => {
    const rowY = eventStart + messageIndex * eventGap;
    const fromX = xByActor.get(message.from);
    const toX = xByActor.get(message.to);
    if (!Number.isFinite(fromX) || !Number.isFinite(toX)) return;

    const arrowClass = message.dashed ? "return" : "call";
    const label = `${messageIndex + 1}. ${message.label.replace(/^\d+\.\s*/, "")}`;
    const sameActor = Math.abs(fromX - toX) < 10;
    const labelWidth = sameActor ? 330 : Math.min(Math.abs(toX - fromX) + 150, 560);
    const maxChars = Math.max(24, Math.floor(labelWidth / 8.8));
    const labelLines = textLines(label, maxChars);
    const labelHeight = labelLines.length * 15 + 16;
    const midX = sameActor ? Math.min(width - 190, fromX + 160) : (fromX + toX) / 2;
    const labelTop = sameActor ? rowY + 42 : rowY + 14;
    const labelCenterY = labelTop + labelHeight / 2;

    if (sameActor) {
      const loopX = Math.min(width - 72, fromX + 90);
      svg += `\n<path class="${arrowClass}" d="M${fromX} ${rowY} L${loopX} ${rowY} L${loopX} ${rowY + 28} L${fromX + 12} ${rowY + 28}"/>`;
    } else {
      svg += `\n<path class="${arrowClass}" d="M${fromX} ${rowY} L${toX} ${rowY}"/>`;
    }
    svg += `\n<rect class="message-label" x="${midX - labelWidth / 2}" y="${labelTop}" width="${labelWidth}" height="${labelHeight}" rx="7" fill="#ffffff" opacity="0.96" stroke="#dbe3ee"/>`;
    svg += `\n${textBlock(label, midX, labelCenterY, "message", maxChars, 15)}`;
  });

  svg += "\n</svg>\n";
  return svg;
}

function renderPng(svgFile, pngFile) {
  execFileSync("rsvg-convert", ["--keep-aspect-ratio", "-f", "png", "-o", pngFile, svgFile], { stdio: "pipe" });
}

const targets = fs
  .readdirSync(sequenceDir)
  .filter((name) => name.endsWith(".svg") && name.includes("sequence"))
  .sort()
  .map((name) => path.join(sequenceDir, name));

for (const svgFile of targets) {
  const original = fs.readFileSync(svgFile, "utf8");
  const model = parseSequenceSvg(original);
  if (model.actors.length < 2 || model.messages.length === 0) {
    console.error(`Cannot normalize ${path.relative(root, svgFile)}: actors=${model.actors.length}, messages=${model.messages.length}`);
    process.exitCode = 1;
    continue;
  }
  fs.writeFileSync(svgFile, renderSequence(model));
  renderPng(svgFile, svgFile.replace(/\.svg$/, ".png"));
}

console.log(JSON.stringify({ normalized: targets.length }, null, 2));
