#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const output = path.join(root, "docs/images/readme-diagrams");
const selected = process.argv[2];
fs.mkdirSync(output, { recursive: true });

const diagrams = new Map([
  ["event-sourced-promotion-voucher-architecture-01.svg", architecture()],
  ["event-sourced-promotion-voucher-command-projection-sequence-01.svg", commandProjectionSequence()],
  ["event-sourced-promotion-voucher-rebuild-state-01.svg", rebuildState()],
]);

for (const [name, content] of diagrams) {
  if (selected && selected !== name) continue;
  const svg = path.join(output, name);
  const png = svg.replace(/\.svg$/, ".png");
  fs.writeFileSync(svg, content);
  const localCairo = path.join(os.homedir(), ".local/bin/cairosvg");
  execFileSync(fs.existsSync(localCairo) ? localCairo : "cairosvg", [svg, "-o", png, "-s", "2"]);
  console.log(`generated ${path.relative(root, svg)} and ${path.relative(root, png)}`);
}

function marker(name, color) {
  return `<marker id="arrow-${name}" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none"/></marker>`;
}

function defs() {
  return `<defs>
  <filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.14"/></filter>
  <style>
    .bg{fill:#F8FAFC}.frame{fill:#FFFFFF;stroke:#CBD5E1;stroke-width:2}.layer{stroke-width:2}.blueLayer{fill:#EFF6FF;stroke:#A8C7EA}.greenLayer{fill:#F1F8F1;stroke:#B8D7AE}.amberLayer{fill:#FFF8EA;stroke:#DFC69A}.purpleLayer{fill:#F8F3FF;stroke:#D7C3EF}
    .title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.body,.small,.footer,.msg,.legend{font-family:"Comic Mono";fill:#3E4C59}.subtitle{font-size:18px}.body{font-size:16px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.layerTitle{font-family:"Architects Daughter";font-size:27px;fill:#344154}.cardTitle,.participant,.state{font-family:"Architects Daughter";font-size:23px;fill:#16202A}
    .card{fill:#FFFFFF;stroke:#9FB0BC;stroke-width:2}.blueCard{fill:#EFF6FF;stroke:#4F86C6}.greenCard{fill:#F1F8F1;stroke:#6E8F4F}.amberCard{fill:#FFF8EA;stroke:#9B7D54}.redCard{fill:#FFF0F0;stroke:#B86868}.purpleCard{fill:#F8F3FF;stroke:#8065A8}
    .connector{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.blueEdge{stroke:#4F86C6;marker-end:url(#arrow-blue)}.greenEdge{stroke:#6E8F4F;marker-end:url(#arrow-green)}.amberEdge{stroke:#9B7D54;marker-end:url(#arrow-amber)}.redEdge{stroke:#B86868;marker-end:url(#arrow-red)}.purpleEdge{stroke:#8065A8;marker-end:url(#arrow-purple)}.tealEdge{stroke:#2D948C;marker-end:url(#arrow-teal)}.dashed{stroke-dasharray:9 7}
    .lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.label{fill:#FFFFFF;stroke:#D7E0E4;stroke-width:1.5}.badge{font-family:"Comic Mono";font-size:13px;font-weight:700}
  </style>
  ${marker("blue", "#4F86C6")}${marker("green", "#6E8F4F")}${marker("amber", "#9B7D54")}${marker("red", "#B86868")}${marker("purple", "#8065A8")}${marker("teal", "#2D948C")}
  </defs>`;
}

function card(id, x, y, width, height, title, lines, css = "card") {
  const body = lines
    .map((line, index) => `<text class="body" x="${x + width / 2}" y="${y + 62 + index * 23}" text-anchor="middle">${line}</text>`)
    .join("");
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${css}" x="${x}" y="${y}" width="${width}" height="${height}" rx="16"/><text class="cardTitle" x="${x + width / 2}" y="${y + 35}" text-anchor="middle">${title}</text>${body}</g>`;
}

function edge(id, css, d) {
  return `<path data-connector="${id}" class="connector ${css}" d="${d}"/>`;
}

function architecture() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1240" viewBox="0 0 1800 1240" role="img" aria-labelledby="title desc"><title id="title">Event-Sourced Promotion Voucher Architecture</title><desc id="desc">Command authority, append-only event store, bounded projection workers, rebuild generations, and public read paths.</desc>${defs()}<rect class="bg" width="1800" height="1240"/><text class="title" x="900" y="62" text-anchor="middle">Event-Sourced Voucher Campaign - Responsibility Map</text><text class="subtitle" x="900" y="98" text-anchor="middle">PostgreSQL is the event authority; projections are replaceable, fenced, and observable.</text>
  <rect class="layer blueLayer" x="70" y="135" width="1660" height="190" rx="22"/><text class="layerTitle" x="105" y="178">HTTP and application boundaries</text>
  ${card("commands", 120, 215, 350, 82, "Command APIs", ["expected version + receipt"], "blueCard")}
  ${card("queries", 725, 215, 350, 82, "Query and SSE", ["position headers + cursor"], "blueCard")}
  ${card("operators", 1330, 215, 350, 82, "Operator APIs", ["retry • rebuild • reconcile"], "amberCard")}
  <rect class="layer greenLayer" x="70" y="370" width="1660" height="390" rx="22"/><text class="layerTitle" x="105" y="413">Transactional workers and semantic persistence boundaries</text>
  ${card("command-service", 170, 500, 350, 115, "Command Service", ["aggregate replay", "atomic terminal receipt"], "greenCard")}
  ${card("projection-worker", 725, 500, 350, 115, "Projection Worker", ["lease + dedup", "read model + checkpoint"], "greenCard")}
  ${card("rebuild-worker", 1280, 500, 350, 115, "Rebuild Worker", ["candidate generation", "digest validation"], "purpleCard")}
  ${card("permit-gate", 170, 675, 1460, 68, "Hikari Permit Gate", ["foreground • projection • rebuild • independent readiness"], "amberCard")}
  <rect class="layer purpleLayer" x="70" y="815" width="1660" height="270" rx="22"/><text class="layerTitle" x="105" y="858">Single durable authority</text>
  ${card("postgres", 170, 925, 1460, 110, "PostgreSQL + HikariCP + JetBrains Exposed", ["event log • stream heads • receipts • snapshots • leases • checkpoints • poison records • generation pointer • audit"], "purpleCard")}
  ${edge("commands-service", "blueEdge", "M295 297 L295 500")}
  ${edge("queries-projection", "blueEdge", "M900 297 L900 500")}
  ${edge("operators-rebuild", "amberEdge", "M1505 297 L1505 500")}
  ${edge("command-permit", "greenEdge", "M345 615 L345 675")}
  ${edge("projection-permit", "greenEdge", "M900 615 L900 675")}
  ${edge("rebuild-permit", "purpleEdge", "M1455 615 L1455 675")}
  ${edge("permit-db", "amberEdge", "M900 743 L900 925")}
  <rect class="frame" x="170" y="1125" width="1460" height="64" rx="14"/><text class="legend" x="250" y="1164">Blue: caller path</text><text class="legend" x="680" y="1164">Green: authoritative transaction</text><text class="legend" x="1180" y="1164">Amber/Purple: bounded recovery</text></svg>`;
}

function participant(cx, title, role, bottom = 1040) {
  return `<g filter="url(#shadow)"><rect class="card" x="${cx - 135}" y="150" width="270" height="78" rx="12"/><text class="participant" x="${cx}" y="182" text-anchor="middle">${title}</text><text class="small" x="${cx}" y="207" text-anchor="middle">${role}</text></g><line class="lifeline" x1="${cx}" y1="228" x2="${cx}" y2="${bottom}"/>`;
}

function message(number, x1, x2, y, text, css, labelX) {
  const width = Math.max(250, text.length * 9 + 74);
  const x = labelX ?? Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - width / 2;
  const color = css.includes("green") ? "#6E8F4F" : css.includes("amber") ? "#9B7D54" : css.includes("red") ? "#B86868" : css.includes("purple") ? "#8065A8" : css.includes("teal") ? "#2D948C" : "#4F86C6";
  return `<rect class="label" x="${x}" y="${y - 44}" width="${width}" height="34" rx="17"/><circle cx="${x + 22}" cy="${y - 27}" r="13" fill="#FFFFFF" stroke="${color}" stroke-width="2"/><text class="badge" x="${x + 22}" y="${y - 22}" text-anchor="middle">${number}</text><text class="msg" x="${x + 44}" y="${y - 22}">${text}</text>${edge(`message-${number}`, css, `M${x1} ${y} L${x2} ${y}`)}`;
}

function commandProjectionSequence() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1130" viewBox="0 0 1800 1130" role="img" aria-labelledby="title desc"><title id="title">Command to Projection Consistency Sequence</title><desc id="desc">Expected-version append, terminal receipt, projection lease and checkpoint, position-aware query, and SSE cursor recovery.</desc>${defs()}<rect class="bg" width="1800" height="1130"/><rect class="frame" x="30" y="25" width="1740" height="1065" rx="22"/><text class="title" x="900" y="70" text-anchor="middle">Command Commit, Projection Catch-up, and Read Fence</text><text class="subtitle" x="900" y="104" text-anchor="middle">A committed command is authoritative before the replaceable read model catches up.</text>
  ${participant(180, "Client", "tenant-scoped caller")}
  ${participant(520, "Command API", "idempotent application boundary")}
  ${participant(860, "PostgreSQL", "event + receipt authority")}
  ${participant(1200, "Projection", "leased generation worker")}
  ${participant(1620, "Query / SSE", "position-aware adapter")}
  <rect class="activation" x="512" y="295" width="16" height="245" rx="6"/><rect class="activation" x="852" y="355" width="16" height="475" rx="6"/><rect class="activation" x="1192" y="595" width="16" height="245" rx="6"/><rect class="activation" x="1612" y="780" width="16" height="205" rx="6"/>
  ${message(1, 180, 520, 325, "command + expected revision + key", "blueEdge")}
  ${message(2, 520, 860, 390, "lock head; append events atomically", "greenEdge")}
  ${message(3, 520, 860, 455, "commit terminal receipt in transaction", "greenEdge")}
  ${message(4, 520, 180, 520, "201/200 + stream and projection positions", "tealEdge")}
  ${message(5, 1200, 860, 625, "lease-fenced batch read", "purpleEdge")}
  ${message(6, 1200, 860, 690, "dedup + read model + checkpoint", "greenEdge")}
  ${message(7, 180, 1620, 790, "GET with X-Min-Stream-Position", "blueEdge", 600)}
  ${message(8, 1620, 860, 855, "read active generation + checkpoint", "purpleEdge", 1180)}
  ${message(9, 1620, 180, 920, "200 fresh or 202 + Retry-After", "tealEdge", 570)}
  <rect class="frame" x="370" y="965" width="1260" height="74" rx="14" fill="none"/><text class="small" x="400" y="992">alt: projection lag returns 202 PROJECTION_PENDING; retry the GET, never repair lag by repeating a command</text><text class="small" x="400" y="1018">SSE: snapshot first, then opaque Last-Event-ID; terminal reset requires a fresh snapshot</text><text class="footer" x="900" y="1076" text-anchor="middle">Committed and terminal throughput are measured separately from conflicts, Hikari wait, stream-head wait, and append-fence wait.</text></svg>`;
}

function rebuildState() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1100" viewBox="0 0 1800 1100" role="img" aria-labelledby="title desc"><title id="title">Projection Rebuild Generation State</title><desc id="desc">Fenced rebuild generation transitions, validation, active pointer swap, cancellation, failure, resume, and retained rollback generation.</desc>${defs()}<rect class="bg" width="1800" height="1100"/><text class="title" x="900" y="66" text-anchor="middle">Projection Rebuild - Candidate Before Pointer Swap</text><text class="subtitle" x="900" y="102" text-anchor="middle">The current ACTIVE generation remains readable until a candidate reaches its target and passes digest validation.</text>
  <rect class="layer blueLayer" x="80" y="145" width="1640" height="330" rx="22"/><text class="layerTitle" x="115" y="190">Normal activation path</text>
  ${card("building", 135, 280, 300, 105, "BUILDING", ["fenced replay", "fixed target position"], "blueCard")}
  ${card("validating", 575, 280, 300, 105, "VALIDATING", ["position reached", "canonical digest check"], "amberCard")}
  ${card("active", 1015, 280, 300, 105, "ACTIVE", ["CAS generation pointer", "serves query and SSE"], "greenCard")}
  ${card("retired", 1455, 280, 220, 105, "RETIRED", ["retained rollback", "no new writes"], "purpleCard")}
  ${edge("building-validating", "greenEdge", "M435 332 L575 332")}
  ${edge("validating-active", "greenEdge", "M875 332 L1015 332")}
  ${edge("active-retired", "purpleEdge", "M1315 332 L1455 332")}
  <rect class="layer amberLayer" x="80" y="535" width="1640" height="390" rx="22"/><text class="layerTitle" x="115" y="580">Cancellation, failure, and recovery</text>
  ${card("cancelling", 145, 690, 280, 105, "CANCELLING", ["increment cancellation revision", "reject stale worker"], "amberCard")}
  ${card("cancelled", 535, 690, 280, 105, "CANCELLED", ["checkpoint retained", "explicit resume only"], "redCard")}
  ${card("failed", 925, 690, 280, 105, "FAILED", ["poison or validation gap", "retryable resumes BUILDING"], "redCard")}
  ${card("still-active", 1315, 690, 300, 105, "CURRENT ACTIVE", ["pointer unchanged", "read availability preserved"], "greenCard")}
  ${edge("building-cancelling", "amberEdge", "M285 385 L285 690")}
  ${edge("cancelling-cancelled", "redEdge", "M425 742 L535 742")}
  ${edge("validating-failed", "redEdge", "M725 385 L725 510 Q725 525 740 525 L1050 525 Q1065 525 1065 540 L1065 690")}
  ${edge("cancelled-resume", "purpleEdge", "M675 795 L675 850 Q675 865 660 865 L115 865 Q100 865 100 850 L100 347 Q100 332 115 332 L135 332")}
  ${edge("failed-active-preserved", "greenEdge dashed", "M1205 742 L1315 742")}
  <rect class="frame" x="180" y="965" width="1440" height="70" rx="15"/><text class="body" x="900" y="994" text-anchor="middle">All mutation endpoints require X-Expected-Generation-Token; stale tokens return 412 and cannot move the active pointer.</text><text class="small" x="900" y="1022" text-anchor="middle">Rollback restores a retained generation pointer after verification; immutable event rows are never edited.</text></svg>`;
}
