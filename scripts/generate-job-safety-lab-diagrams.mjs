#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const out = path.join(root, "docs/images/readme-diagrams");
const wiki = process.env.BLUETAPE_WIKI_ROOT || path.join(os.homedir(), "work/bluetape4k/bluetape4k-wiki");
const postgres = icon("docs/icons/testcontainers/database/postgresql.svg");
const redis = icon("docs/icons/redis/redis-icon.svg");

fs.mkdirSync(out, { recursive: true });

const diagrams = new Map([
  ["leader-job-safety-lab-architecture-01.svg", architectureSvg()],
  ["leader-job-safety-lab-state-01.svg", stateSvg()],
  ["leader-job-safety-lab-takeover-sequence-01.svg", takeoverSequenceSvg()],
  ["leader-job-safety-lab-microservices-01.svg", microservicesSvg()],
]);

for (const [name, svg] of diagrams) {
  const target = path.join(out, name);
  fs.writeFileSync(target, svg);
  const png = target.replace(/\.svg$/, ".png");
  renderPng(target, png);
  console.log(`generated ${path.relative(root, target)} and ${path.relative(root, png)}`);
}

function renderPng(svgFile, pngFile) {
  const localCairosvg = path.join(os.homedir(), ".local/bin/cairosvg");
  const cairosvg = process.env.CAIROSVG_BIN || (fs.existsSync(localCairosvg) ? localCairosvg : "cairosvg");
  execFileSync(cairosvg, [svgFile, "-o", pngFile, "-s", "2"], { stdio: "pipe" });
}

function icon(relative) {
  return fs.readFileSync(path.join(wiki, relative)).toString("base64");
}

function defs() {
  return `<defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.13"/></filter>
    <style>
      .bg{fill:#F8FAFC}.frame{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:2}.layer{stroke-width:2}.blueLayer{fill:#EFF6FF;stroke:#A8C7EA}.greenLayer{fill:#F0F8F0;stroke:#B8D7AE}.amberLayer{fill:#FFF7E8;stroke:#DFC69A}.purpleLayer{fill:#F8F3FF;stroke:#D7C3EF}.title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.body,.small,.footer,.msg{font-family:"Comic Mono";fill:#3E4C59}.subtitle{font-size:18px}.layerTitle{font-family:"Architects Daughter";font-size:26px;fill:#344154}.cardTitle,.participant{font-family:"Architects Daughter";font-size:23px;fill:#16202A}.body{font-size:16px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.card{fill:#FFFFFF;stroke:#9FB0BC;stroke-width:2}.blueCard{fill:#EFF6FF;stroke:#4F86C6}.greenCard{fill:#F0F8F0;stroke:#6E8F4F}.amberCard{fill:#FFF7E8;stroke:#9B7D54}.redCard{fill:#FFF0F0;stroke:#B86868}.purpleCard{fill:#F8F3FF;stroke:#8065A8}.connector{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.blueEdge{stroke:#4F86C6;marker-end:url(#arrow-blue)}.greenEdge{stroke:#6E8F4F;marker-end:url(#arrow-green)}.amberEdge{stroke:#9B7D54;marker-end:url(#arrow-amber)}.redEdge{stroke:#B86868;marker-end:url(#arrow-red)}.purpleEdge{stroke:#8065A8;marker-end:url(#arrow-purple)}.tealEdge{stroke:#2D948C;marker-end:url(#arrow-teal)}.dashed{stroke-dasharray:10 8}.lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.label{fill:#FFFFFF;stroke:#D7E0E4;stroke-width:1.5}.badge{font-family:"Comic Mono";font-size:13px;font-weight:700}.legend{font-family:"Comic Mono";font-size:14px;fill:#475569}
    </style>
    ${marker("blue", "#4F86C6")}${marker("green", "#6E8F4F")}${marker("amber", "#9B7D54")}${marker("red", "#B86868")}${marker("purple", "#8065A8")}${marker("teal", "#2D948C")}
  </defs>`;
}

function marker(name, color) {
  return `<marker id="arrow-${name}" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none"/></marker>`;
}

function card(id, x, y, w, h, title, lines, css = "card") {
  const body = lines.map((line, i) => `<text class="body" x="${x + w / 2}" y="${y + 66 + i * 24}" text-anchor="middle">${line}</text>`).join("");
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${css}" x="${x}" y="${y}" width="${w}" height="${h}" rx="16"/><text class="cardTitle" x="${x + w / 2}" y="${y + 36}" text-anchor="middle">${title}</text>${body}</g>`;
}

function infraCard(id, x, y, w, h, title, body, iconData, source, css) {
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${css}" x="${x}" y="${y}" width="${w}" height="${h}" rx="16"/><image data-bluetape4k-icon="${id}" data-source="${source}" x="${x + 26}" y="${y + 24}" width="54" height="54" href="data:image/svg+xml;base64,${iconData}"/><text class="cardTitle" x="${x + 100}" y="${y + 43}">${title}</text><text class="body" x="${x + 100}" y="${y + 70}">${body}</text></g>`;
}

function edge(id, label, css, d) {
  return `<g class="edge" data-edge="${id}" data-label="${label}"><path data-connector="${id}" class="connector ${css}" d="${d}"/></g>`;
}

function architectureSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1180" viewBox="0 0 1800 1180" role="img" aria-labelledby="title desc">
  <title id="title">Leader Job Safety Lab Architecture</title><desc id="desc">Spring API, leader election, resource fencing, PostgreSQL authority, transactional outbox, and external reconciliation boundaries.</desc>${defs()}
  <rect class="bg" width="1800" height="1180"/><text id="title" class="title" x="900" y="62" text-anchor="middle">Leader Job Safety Lab - Safety Boundaries</text><text class="subtitle" x="900" y="96" text-anchor="middle">Leader election limits active runners; only an orderable fence checked by PostgreSQL blocks stale commits.</text>
  <rect class="layer blueLayer" x="70" y="130" width="1660" height="190" rx="22"/><text class="layerTitle" x="106" y="172">HTTP and operator boundary</text>
  ${card("safe-api", 150, 210, 390, 78, "Safe scenario API", ["authenticated SAFE comparisons"], "blueCard")}${card("security", 705, 210, 390, 78, "Spring Security", ["stateless HTTP Basic, deny by default"], "blueCard")}${card("operator-api", 1260, 210, 390, 78, "Operator API", ["reset, deliver, reconcile, unsafe"], "blueCard")}
  <rect class="layer greenLayer" x="70" y="375" width="1660" height="300" rx="22"/><text class="layerTitle" x="106" y="417">Execution and durable completion</text>
  ${card("coordinator", 125, 480, 320, 112, "JobRunCoordinator", ["leader first", "resource fence second"], "greenCard")}${card("fenced-execution", 520, 480, 360, 112, "Fenced execution", ["authority + fence check", "one Exposed transaction"], "greenCard")}${card("outbox-worker", 955, 480, 330, 112, "Outbox worker", ["short claim transaction", "network outside DB"], "amberCard")}${card("effect-provider", 1360, 480, 320, 112, "External provider", ["stable operation ID", "query before retry"], "amberCard")}
  <rect class="layer purpleLayer" x="70" y="730" width="1660" height="290" rx="22"/><text class="layerTitle" x="106" y="772">Infrastructure authority</text>
  ${infraCard("redis", 180, 840, 570, 112, "Redis + Lettuce", "opaque leader lease + monotonic Lua fence", redis, "docs/icons/redis/redis-icon.svg", "purpleCard")}${infraCard("postgresql", 1020, 840, 600, 112, "PostgreSQL + ExposedJdbcRepository", "topology, rollout, fence, checkpoint, outbox, receipt", postgres, "docs/icons/testcontainers/database/postgresql.svg", "greenCard")}
  ${edge("safe-api-to-coordinator", "safe scenario execution", "blueEdge", "M 345 288 L 345 480")}${edge("operator-to-outbox", "bounded operator work", "blueEdge", "M 1455 288 L 1455 350 Q 1455 366 1439 366 L 1140 366 Q 1120 366 1120 386 L 1120 480")}${edge("coordinator-to-execution", "fenced mutation", "greenEdge", "M 445 536 L 520 536")}${edge("execution-to-outbox", "atomic outbox row", "greenEdge", "M 880 536 L 955 536")}${edge("outbox-to-provider", "idempotent external effect", "amberEdge", "M 1285 536 L 1360 536")}${edge("coordinator-to-redis", "leader + Lua fencing", "purpleEdge", "M 285 592 L 285 704 Q 285 720 301 720 L 465 720 Q 485 720 485 740 L 485 840")}${edge("execution-to-postgresql", "authoritative conditional commit", "greenEdge", "M 700 592 L 700 764 Q 700 780 716 780 L 1130 780 Q 1150 780 1150 800 L 1150 840")}${edge("outbox-to-postgresql", "claim + receipt", "greenEdge", "M 1120 592 L 1120 700 Q 1120 716 1136 716 L 1240 716 Q 1260 716 1260 736 L 1260 840")}
  <rect class="frame" x="160" y="1060" width="1480" height="64" rx="16"/><text class="legend" x="220" y="1100">Blue: request / operator boundary</text><text class="legend" x="690" y="1100">Green: PostgreSQL-authoritative commit</text><text class="legend" x="1230" y="1100">Amber: external effect</text>
  <text class="footer" x="900" y="1155" text-anchor="middle">The Redis leader token is opaque and never becomes a fencing token.</text></svg>`;
}

function stateSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1050" viewBox="0 0 1800 1050" role="img" aria-labelledby="title desc"><title id="title">Job Execution State Diagram</title><desc id="desc">Safe execution states with contention, authority rejection, external reconciliation, completion, and failure exits.</desc>${defs()}<rect class="bg" width="1800" height="1050"/><text class="title" x="900" y="66" text-anchor="middle">Job Execution State Diagram</text><text class="subtitle" x="900" y="100" text-anchor="middle">Every exit is explicit: contention skips, stale authority rejects, ambiguous effects reconcile, and only durable receipts complete.</text>
  ${card("requested", 90, 190, 250, 84, "REQUESTED", ["stable operation ID"], "blueCard")}${card("leader-acquired", 425, 190, 250, 84, "LEADER_ACQUIRED", ["opaque owner only"], "purpleCard")}${card("fence-acquired", 760, 190, 250, 84, "FENCE_ACQUIRED", ["orderable generation"], "purpleCard")}${card("running", 1095, 190, 250, 84, "RUNNING", ["bounded work"], "greenCard")}${card("committed", 1430, 190, 250, 84, "COMMITTED", ["DB + outbox atomic"], "greenCard")}
  ${card("effect-pending", 1430, 430, 250, 84, "EFFECT_PENDING", ["provider outcome unknown"], "amberCard")}${card("reconcile", 1000, 430, 360, 84, "RECONCILIATION_REQUIRED", ["query original operation"], "amberCard")}${card("completed", 1430, 690, 250, 84, "COMPLETED", ["durable receipt"], "greenCard")}
  ${card("skipped", 90, 690, 250, 84, "SKIPPED", ["leader / fence contended"], "amberCard")}${card("rejected", 550, 690, 250, 84, "REJECTED", ["stale fence / authority"], "redCard")}${card("failed", 940, 850, 250, 84, "FAILED", ["backend / domain failure"], "redCard")}
  ${edge("requested-leader", "leader acquired", "blueEdge", "M 340 232 L 425 232")}${edge("leader-fence", "fence acquired", "purpleEdge", "M 675 232 L 760 232")}${edge("fence-running", "start work", "purpleEdge", "M 1010 232 L 1095 232")}${edge("running-committed", "conditional commit accepted", "greenEdge", "M 1345 232 L 1430 232")}${edge("committed-effect", "outbox delivery", "amberEdge", "M 1555 274 L 1555 430")}${edge("effect-reconcile", "UNKNOWN", "amberEdge", "M 1430 472 L 1360 472")}${edge("reconcile-completed", "query confirms original operation", "greenEdge", "M 1180 514 L 1180 600 Q 1180 620 1200 620 L 1535 620 Q 1555 620 1555 640 L 1555 690")}${edge("effect-completed", "confirmed receipt", "greenEdge", "M 1630 514 L 1630 640 Q 1630 660 1610 660 Q 1590 660 1590 680 L 1590 690")}${edge("requested-skipped", "leader contended", "amberEdge", "M 215 274 L 215 690")}${edge("fence-skipped", "fence contended", "amberEdge", "M 835 274 L 835 324 Q 835 340 819 340 L 396 340 Q 380 340 380 356 L 380 716 Q 380 732 364 732 L 340 732")}${edge("running-rejected", "stale authority or fence", "redEdge", "M 1120 274 L 1120 350 Q 1120 370 1100 370 L 990 370 Q 970 370 970 390 L 970 544 Q 970 560 954 560 L 691 560 Q 675 560 675 576 L 675 690")}${edge("requested-failed", "unrecoverable execution failure", "redEdge", "M 90 250 L 66 250 Q 50 250 50 266 L 50 876 Q 50 892 66 892 L 940 892")}
  <text class="footer" x="900" y="1005" text-anchor="middle">COMMITTED is not COMPLETED: a non-fenceable effect closes only after a durable receipt.</text></svg>`;
}

function participant(cx, title, role) {
  return `<g filter="url(#shadow)"><rect class="card" x="${cx - 140}" y="150" width="280" height="78" rx="12"/><text class="participant" x="${cx}" y="182" text-anchor="middle">${title}</text><text class="small" x="${cx}" y="207" text-anchor="middle">${role}</text></g><line class="lifeline" x1="${cx}" y1="228" x2="${cx}" y2="930"/>`;
}

function message(n, x1, x2, y, label, css) {
  const width = Math.max(260, label.length * 9 + 70); const x = Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - width / 2;
  return `<rect class="label" x="${x}" y="${y - 44}" width="${width}" height="34" rx="17"/><circle cx="${x + 22}" cy="${y - 27}" r="13" fill="#FFFFFF" stroke="#4F86C6" stroke-width="2"/><text class="badge" x="${x + 22}" y="${y - 22}" text-anchor="middle">${n}</text><text class="msg" x="${x + 44}" y="${y - 22}">${label}</text>${edge(`sequence-${n}`, label, css, `M ${x1} ${y} L ${x2} ${y}`)}`;
}

function takeoverSequenceSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1020" viewBox="0 0 1800 1020" role="img" aria-labelledby="title desc"><title id="title">Fence Takeover Sequence</title><desc id="desc">Worker A pauses with fence 41, Worker B takes fence 42 and commits, then PostgreSQL rejects resumed fence 41.</desc>${defs()}<rect class="bg" width="1800" height="1020"/><rect class="frame" x="30" y="25" width="1740" height="960" rx="22"/><text class="title" x="900" y="70" text-anchor="middle">Lease Overrun: A41 Pauses, B42 Commits, A41 Rejects</text><text class="subtitle" x="900" y="104" text-anchor="middle">A lease says who may try now; PostgreSQL fencing decides whose write is still current.</text>
  ${participant(190, "Worker A", "stale generation")}${participant(550, "Redis Lua Lease", "monotonic counter")}${participant(910, "Worker B", "takeover generation")}${participant(1270, "Fenced Service", "Exposed transaction")}${participant(1630, "PostgreSQL", "durable authority")}
  <rect class="activation" x="182" y="300" width="16" height="510" rx="6"/><rect class="activation" x="902" y="470" width="16" height="240" rx="6"/><rect class="activation" x="1262" y="540" width="16" height="340" rx="6"/><rect class="activation" x="1622" y="600" width="16" height="270" rx="6"/>
  ${message(1, 190, 550, 330, "acquire resource fence", "purpleEdge")}${message(2, 550, 190, 400, "return fence 41; A pauses", "tealEdge")}${message(3, 910, 550, 500, "take over expired lease", "purpleEdge")}${message(4, 550, 910, 570, "increment and return fence 42", "tealEdge")}${message(5, 910, 1270, 640, "execute with fence 42", "greenEdge")}${message(6, 1270, 1630, 710, "accept fence 42 only when newer", "greenEdge")}${message(7, 1630, 1270, 770, "commit resource + checkpoint + outbox", "tealEdge")}${message(8, 190, 1270, 840, "resume and execute with fence 41", "redEdge")}${message(9, 1270, 1630, 900, "stale fence changes zero rows", "redEdge")}
  <text class="footer" x="900" y="960" text-anchor="middle">Result: B42 is durable; A41 becomes REJECTED(STALE_FENCE), even if A still believes it once held leadership.</text></svg>`;
}

function microservicesSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1100" viewBox="0 0 1800 1100" role="img" aria-labelledby="title desc"><title id="title">Modular Monolith to Microservices Extraction</title><desc id="desc">A modular monolith keeps ports explicit so scheduler, execution, effect delivery, and operator control can later split without changing safety contracts.</desc>${defs()}<rect class="bg" width="1800" height="1100"/><text class="title" x="900" y="65" text-anchor="middle">From One Spring Boot App to Safety-Preserving Services</text><text class="subtitle" x="900" y="100" text-anchor="middle">Split ownership only after the ports, stable IDs, fence checks, and durable handoffs are already explicit.</text>
  <rect class="layer greenLayer" x="70" y="145" width="1660" height="300" rx="22"/><text class="layerTitle" x="105" y="190">Phase 1 - modular monolith (this example)</text>${card("mono-api", 150, 255, 300, 105, "API / Security", ["safe + operator routes", "profile-gated unsafe"], "blueCard")}${card("mono-coord", 540, 255, 300, 105, "Coordination", ["leader port", "fencing lease port"], "purpleCard")}${card("mono-exec", 930, 255, 300, 105, "Execution", ["authority + fenced commit", "transactional outbox"], "greenCard")}${card("mono-effect", 1320, 255, 300, 105, "Effect delivery", ["idempotent provider port", "reconciliation"], "amberCard")}${edge("mono-api-coord", "in-process call", "blueEdge", "M 450 307 L 540 307")}${edge("mono-coord-exec", "fenced mutation", "purpleEdge", "M 840 307 L 930 307")}${edge("mono-exec-effect", "outbox handoff", "greenEdge", "M 1230 307 L 1320 307")}
  <rect class="layer purpleLayer" x="70" y="515" width="1660" height="380" rx="22"/><text class="layerTitle" x="105" y="560">Phase 2 - microservices with the same contracts</text>${card("scheduler-service", 115, 650, 300, 120, "Scheduler service", ["trigger + membership snapshot", "leader election only"], "blueCard")}${card("execution-service", 520, 650, 330, 120, "Execution service", ["resource fence", "PostgreSQL authority"], "greenCard")}${card("effect-service", 970, 650, 300, 120, "Effect worker", ["outbox claim", "query-before-retry"], "amberCard")}${card("operator-service", 1375, 650, 300, 120, "Operator control", ["reconcile / reset", "audit and metrics"], "blueCard")}
  ${infraCard("shared-redis", 260, 810, 500, 88, "Redis", "leader + resource fencing", redis, "docs/icons/redis/redis-icon.svg", "purpleCard")}${infraCard("authority-db", 1040, 810, 530, 88, "PostgreSQL", "authority + outbox + receipts", postgres, "docs/icons/testcontainers/database/postgresql.svg", "greenCard")}
  ${edge("scheduler-execution", "versioned command + operation ID", "blueEdge", "M 415 710 L 520 710")}${edge("execution-effects", "transactional outbox", "greenEdge", "M 850 710 L 970 710")}${edge("operator-effects", "bounded reconcile command", "blueEdge", "M 1375 710 L 1270 710")}${edge("scheduler-redis", "leader lease", "purpleEdge", "M 265 770 L 265 780 Q 265 790 275 790 L 350 790 Q 360 790 360 800 L 360 810")}${edge("execution-redis", "fencing lease", "purpleEdge", "M 650 770 L 650 810")}${edge("execution-db", "conditional commit", "greenEdge", "M 760 770 L 760 930 Q 760 950 780 950 L 1250 950 Q 1270 950 1270 930 L 1270 898")}${edge("effects-db", "outbox and receipt", "greenEdge", "M 1120 770 L 1120 810")}${edge("operator-db", "read-only diagnosis", "blueEdge dashed", "M 1525 770 L 1525 810")}
  <rect class="frame" x="130" y="960" width="1540" height="72" rx="16"/><text class="body" x="900" y="991" text-anchor="middle">Non-negotiable extraction rule: the execution service owns the PostgreSQL fence check and commits checkpoint + execution + outbox atomically.</text><text class="small" x="900" y="1018" text-anchor="middle">Do not replace the durable outbox with a best-effort synchronous call or treat broker order as a fencing token.</text><text class="footer" x="900" y="1070" text-anchor="middle">Scale services independently; preserve operation ID, namespace epoch, membership revision, region epoch, contract version, and fencing token end to end.</text></svg>`;
}
