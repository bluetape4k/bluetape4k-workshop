#!/usr/bin/env node

import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const out = path.join(root, "docs/images/readme-diagrams");
const selected = process.argv[2];
fs.mkdirSync(out, { recursive: true });

const diagrams = new Map([
  ["usage-metering-billing-architecture-01.svg", architecture()],
  ["usage-metering-billing-state-01.svg", state()],
  ["usage-metering-billing-ingestion-sequence-01.svg", ingestionSequence()],
  ["usage-metering-billing-close-reconciliation-01.svg", closeSequence()],
]);

for (const [name, svg] of diagrams) {
  if (selected && selected !== name) continue;
  const target = path.join(out, name);
  fs.writeFileSync(target, svg);
  const png = target.replace(/\.svg$/, ".png");
  const local = path.join(os.homedir(), ".local/bin/cairosvg");
  execFileSync(fs.existsSync(local) ? local : "cairosvg", [target, "-o", png, "-s", "2"]);
  console.log(`generated ${path.relative(root, target)} and ${path.relative(root, png)}`);
}

function defs() {
  return `<defs>
  <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.13"/></filter>
  <style>
    .bg{fill:#F8FAFC}.frame{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:2}.layer{stroke-width:2}.blueLayer{fill:#EFF6FF;stroke:#A8C7EA}.greenLayer{fill:#F0F8F0;stroke:#B8D7AE}.amberLayer{fill:#FFF7E8;stroke:#DFC69A}.purpleLayer{fill:#F8F3FF;stroke:#D7C3EF}.title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.body,.small,.footer,.msg,.legend{font-family:"Comic Mono";fill:#3E4C59}.subtitle{font-size:18px}.layerTitle{font-family:"Architects Daughter";font-size:27px;fill:#344154}.cardTitle,.participant{font-family:"Architects Daughter";font-size:23px;fill:#16202A}.body{font-size:16px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.card{fill:#FFFFFF;stroke:#9FB0BC;stroke-width:2}.blueCard{fill:#EFF6FF;stroke:#4F86C6}.greenCard{fill:#F0F8F0;stroke:#6E8F4F}.amberCard{fill:#FFF7E8;stroke:#9B7D54}.redCard{fill:#FFF0F0;stroke:#B86868}.purpleCard{fill:#F8F3FF;stroke:#8065A8}.connector{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.blueEdge{stroke:#4F86C6;marker-end:url(#arrow-blue)}.greenEdge{stroke:#6E8F4F;marker-end:url(#arrow-green)}.amberEdge{stroke:#9B7D54;marker-end:url(#arrow-amber)}.redEdge{stroke:#B86868;marker-end:url(#arrow-red)}.purpleEdge{stroke:#8065A8;marker-end:url(#arrow-purple)}.tealEdge{stroke:#2D948C;marker-end:url(#arrow-teal)}.dashed{stroke-dasharray:9 7}.lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.label{fill:#FFFFFF;stroke:#D7E0E4;stroke-width:1.5}.badge{font-family:"Comic Mono";font-size:13px;font-weight:700}.state{font-family:"Architects Daughter";font-size:22px}
  </style>
  ${marker("blue", "#4F86C6")}${marker("green", "#6E8F4F")}${marker("amber", "#9B7D54")}${marker("red", "#B86868")}${marker("purple", "#8065A8")}${marker("teal", "#2D948C")}
  </defs>`;
}

function marker(name, color) {
  return `<marker id="arrow-${name}" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none"/></marker>`;
}

function card(id, x, y, w, h, title, lines, css = "card") {
  const body = lines.map((line, i) => `<text class="body" x="${x + w / 2}" y="${y + 63 + i * 23}" text-anchor="middle">${line}</text>`).join("");
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${css}" x="${x}" y="${y}" width="${w}" height="${h}" rx="16"/><text class="cardTitle" x="${x + w / 2}" y="${y + 35}" text-anchor="middle">${title}</text>${body}</g>`;
}

function edge(id, label, css, d) {
  return `<g class="edge" data-edge="${id}" data-label="${label}"><path data-connector="${id}" class="connector ${css}" d="${d}"/></g>`;
}

function architecture() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1160" viewBox="0 0 1800 1160" role="img" aria-labelledby="title desc"><title id="title">Usage Metering and Billing Architecture</title><desc id="desc">Spring Boot modular monolith boundaries and PostgreSQL billing authority.</desc>${defs()}<rect class="bg" width="1800" height="1160"/><text class="title" x="900" y="62" text-anchor="middle">Usage Metering and Billing Ledger - Authority Map</text><text class="subtitle" x="900" y="98" text-anchor="middle">Mutable workflows are restartable; money and issued invoices are append-only PostgreSQL facts.</text>
  <rect class="layer blueLayer" x="70" y="135" width="1660" height="180" rx="22"/><text class="layerTitle" x="105" y="178">HTTP, security, and operations</text>${card("tenant-api", 145, 215, 350, 76, "Tenant API", ["meter, price, usage, period"], "blueCard")}${card("receipt", 725, 215, 350, 76, "Command receipt", ["fingerprint, replay, takeover"], "purpleCard")}${card("operator-api", 1305, 215, 350, 76, "Operator API", ["close, adjustment, reconcile"], "blueCard")}
  <rect class="layer greenLayer" x="70" y="365" width="1660" height="360" rx="22"/><text class="layerTitle" x="105" y="408">Modular business boundaries</text>${card("ingest", 120, 490, 290, 112, "Ingestion", ["source uniqueness", "server receivedAt"], "greenCard")}${card("pricing", 455, 490, 290, 112, "Pricing", ["half-open timeline", "occurredAt selection"], "greenCard")}${card("close", 790, 490, 290, 112, "Billing close", ["keyset checkpoint", "fixed cutoff"], "greenCard")}${card("invoice", 1125, 490, 260, 112, "Invoice", ["immutable snapshot", "full provenance"], "greenCard")}${card("reconcile", 1430, 490, 250, 112, "Reconcile", ["read only scan", "stale-safe repair"], "amberCard")}
  <rect class="layer purpleLayer" x="70" y="780" width="1660" height="245" rx="22"/><text class="layerTitle" x="105" y="823">Single correctness authority</text>${card("postgres", 240, 875, 1320, 100, "PostgreSQL + JetBrains Exposed + ExposedJdbcRepository", ["unique constraints • row locks • conditional updates • append-only ledger and invoice"], "purpleCard")}
  ${edge("tenant-receipt", "idempotent command", "blueEdge", "M495 253 L725 253")}${edge("receipt-ingest", "short acquire transaction", "purpleEdge", "M900 291 L900 340 Q 900 350 890 350 L265 350 Q 250 350 250 365 L250 490")}${edge("operator-close", "bounded work", "blueEdge", "M1430 291 L1430 340 Q 1430 350 1420 350 L935 350 Q 920 350 920 365 L920 490")}${edge("ingest-pricing", "meter and usage", "greenEdge", "M410 546 L455 546")}${edge("pricing-close", "price by occurredAt", "greenEdge", "M745 546 L790 546")}${edge("close-invoice", "ready ledger snapshot", "greenEdge", "M1080 546 L1125 546")}${edge("invoice-reconcile", "provenance checks", "amberEdge", "M1385 546 L1430 546")}${edge("ingest-db", "append usage", "greenEdge", "M265 602 L265 740 Q 265 755 280 755 L500 755 Q 515 755 515 770 L515 875")}${edge("pricing-db", "serialize timeline", "greenEdge", "M600 602 L600 875")}${edge("close-db", "ledger + checkpoint atomically", "greenEdge", "M935 602 L935 875")}${edge("invoice-db", "append invoice + provenance", "greenEdge", "M1255 602 L1255 875")}${edge("reconcile-db", "bounded read", "amberEdge dashed", "M1555 602 L1555 740 Q 1555 755 1540 755 L1450 755 Q 1435 755 1435 770 L1435 875")}
  <rect class="frame" x="170" y="1060" width="1460" height="56" rx="14"/><text class="legend" x="250" y="1095">Blue: authenticated command</text><text class="legend" x="700" y="1095">Green: authoritative transaction</text><text class="legend" x="1240" y="1095">Amber: diagnosis / repair</text></svg>`;
}

function state() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1060" viewBox="0 0 1800 1060" role="img" aria-labelledby="title desc"><title id="title">Billing Period and Close Run State Diagram</title><desc id="desc">Billing period and close run one-way transitions, validation failure, retry, and finalization.</desc>${defs()}<rect class="bg" width="1800" height="1060"/><text class="title" x="900" y="66" text-anchor="middle">Billing Lifecycle - Restartable Workflow, Immutable Result</text><text class="subtitle" x="900" y="102" text-anchor="middle">A period freezes once; the close run may restart from its checkpoint but never rewrites financial history.</text>
  <rect class="layer blueLayer" x="80" y="150" width="1640" height="320" rx="22"/><text class="layerTitle" x="115" y="195">Billing period</text>${card("period-open", 170, 275, 320, 100, "OPEN", ["accept usage and adjustments"], "blueCard")}${card("period-closing", 740, 275, 320, 100, "CLOSING", ["cutoffReceivedAt is fixed"], "amberCard")}${card("period-finalized", 1310, 275, 320, 100, "FINALIZED", ["invoice and ledger immutable"], "greenCard")}${edge("period-open-closing", "start close after lateness", "amberEdge", "M490 325 L740 325")}${edge("period-closing-final", "invoice transaction commits", "greenEdge", "M1060 325 L1310 325")}
  <rect class="layer purpleLayer" x="80" y="530" width="1640" height="390" rx="22"/><text class="layerTitle" x="115" y="575">Close run</text>${card("run-running", 135, 685, 290, 105, "RUNNING", ["(occurredAt, usageId)", "checkpoint batches"], "purpleCard")}${card("run-failed", 555, 685, 290, 105, "FAILED_VALIDATION", ["stable pricing finding", "operator repairs gap"], "redCard")}${card("run-ready", 975, 685, 290, 105, "READY_TO_FINALIZE", ["all eligible usage priced", "ledger snapshot fixed"], "amberCard")}${card("run-final", 1395, 685, 290, 105, "FINALIZED", ["invoice provenance linked", "no mutable exit"], "greenCard")}${edge("run-running-failed", "price missing", "redEdge", "M425 738 L555 738")}${edge("run-running-ready", "end of keyset, zero findings", "greenEdge", "M425 705 L460 705 Q 475 705 475 650 Q 475 635 490 635 L1120 635 Q 1135 635 1135 650 L1135 685")}${edge("run-failed-running", "explicit repair and resume", "purpleEdge", "M700 790 L700 845 Q 700 860 685 860 L295 860 Q 280 860 280 845 L280 790")}${edge("run-ready-final", "atomic invoice finalization", "greenEdge", "M1265 738 L1395 738")}
  <rect class="frame" x="190" y="955" width="1420" height="62" rx="15"/><text class="body" x="900" y="993" text-anchor="middle">Late usage never reopens FINALIZED: it posts a positive DEBIT_ADJUSTMENT into the unique current OPEN period.</text></svg>`;
}

function participant(cx, title, role, bottom = 990) {
  return `<g filter="url(#shadow)"><rect class="card" x="${cx - 140}" y="150" width="280" height="78" rx="12"/><text class="participant" x="${cx}" y="182" text-anchor="middle">${title}</text><text class="small" x="${cx}" y="207" text-anchor="middle">${role}</text></g><line class="lifeline" x1="${cx}" y1="228" x2="${cx}" y2="${bottom}"/>`;
}

function message(n, x1, x2, y, label, css, labelX) {
  const width = Math.max(260, label.length * 9 + 72);
  const x = labelX ?? (Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - width / 2);
  const color = css.includes("red") ? "#B86868" : css.includes("green") ? "#6E8F4F" : css.includes("amber") ? "#9B7D54" : css.includes("purple") ? "#8065A8" : "#4F86C6";
  return `<rect class="label labelPill" x="${x}" y="${y - 44}" width="${width}" height="34" rx="17"/><circle cx="${x + 22}" cy="${y - 27}" r="13" fill="#FFFFFF" stroke="${color}" stroke-width="2"/><text class="badge num" x="${x + 22}" y="${y - 22}" text-anchor="middle">${n}</text><text class="msg" x="${x + 44}" y="${y - 22}">${label}</text>${edge(`sequence-${n}`, label, css, `M${x1} ${y} L${x2} ${y}`)}`;
}

function ingestionSequence() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1080" viewBox="0 0 1800 1080" role="img" aria-labelledby="title desc"><title id="title">Idempotent Usage Ingestion Sequence</title><desc id="desc">Tenant validation, short receipt transaction, domain commit, terminal CAS, replay, and conflict branches.</desc>${defs()}<rect class="bg" width="1800" height="1080"/><rect class="frame" x="30" y="25" width="1740" height="1015" rx="22"/><text class="title" x="900" y="70" text-anchor="middle">Usage Ingestion - Two Independent Duplicate Guards</text><text class="subtitle" x="900" y="104" text-anchor="middle">The HTTP idempotency key replays a response; the source event key rejects producer duplicates independently.</text>${participant(190,"Client","tenant-scoped caller")}${participant(550,"Tenant API","Spring Security")}${participant(910,"Receipt Service","REQUIRES_NEW")}${participant(1270,"Ingestion","Exposed transaction")}${participant(1630,"PostgreSQL","durable authority")}<rect class="activation" x="542" y="300" width="16" height="600" rx="6"/><rect class="activation" x="902" y="365" width="16" height="200" rx="6"/><rect class="activation" x="1262" y="575" width="16" height="205" rx="6"/><rect class="activation" x="1622" y="430" width="16" height="410" rx="6"/>${message(1,190,550,330,"POST usage + Idempotency-Key","blueEdge")}${message(2,550,910,395,"digest key and canonical request","purpleEdge")}${message(3,910,1630,460,"insert owner or inspect receipt","purpleEdge",1280)}${message(4,910,550,525,"Acquired / Replay / Conflict","tealEdge")}${message(5,550,1270,605,"tenant-safe ingest command","greenEdge")}${message(6,1270,1630,670,"unique source event + server receivedAt","greenEdge")}${message(7,1630,1270,735,"committed usage ID","tealEdge")}${message(8,550,910,800,"terminal CAS by owner token","purpleEdge")}${message(9,550,190,875,"201 or exact stored replay","tealEdge")}<rect class="frame" x="650" y="925" width="1000" height="72" rx="14" fill="none"/><text class="small" x="680" y="950">alt: same key + different fingerprint returns 409 conflict</text><text class="small" x="680" y="976">else: active lease returns 409 + Retry-After; expired lease permits owner-token takeover</text><text class="footer" x="900" y="1024" text-anchor="middle">Raw keys and bodies are never persisted, logged, or used as metric tags.</text></svg>`;
}

function closeSequence() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1160" viewBox="0 0 1800 1160" role="img" aria-labelledby="title desc"><title id="title">Restartable Billing Close and Reconciliation Sequence</title><desc id="desc">Fixed cutoff, keyset batches, price lookup, atomic ledger checkpoint, restart, invoice, late adjustment, and reconciliation.</desc>${defs()}<rect class="bg" width="1800" height="1160"/><rect class="frame" x="30" y="25" width="1740" height="1095" rx="22"/><text class="title" x="900" y="70" text-anchor="middle">Close, Restart, Finalize, and Reconcile</text><text class="subtitle" x="900" y="104" text-anchor="middle">Each batch commits ledger rows with its checkpoint; a crash repeats work, not money.</text>${participant(190,"Operator / Scheduler","same application use case",1060)}${participant(550,"Close Service","bounded keyset worker",1060)}${participant(910,"Pricing","occurredAt timeline",1060)}${participant(1270,"Invoice / Adjust","append-only results",1060)}${participant(1630,"PostgreSQL","single authority",1060)}<rect class="activation" x="542" y="305" width="16" height="610" rx="6"/><rect class="activation" x="902" y="485" width="16" height="180" rx="6"/><rect class="activation" x="1262" y="790" width="16" height="210" rx="6"/><rect class="activation" x="1622" y="355" width="16" height="680" rx="6"/>${message(1,190,550,335,"start close after allowed lateness","blueEdge")}${message(2,550,1630,400,"OPEN to CLOSING; freeze cutoff","greenEdge")}${message(3,550,1630,465,"read next (occurredAt, usageId) batch","greenEdge")}${message(4,550,910,530,"select half-open price version","purpleEdge")}${message(5,910,550,595,"unit price or validation gap","tealEdge")}${message(6,550,1630,665,"append CHARGE + checkpoint atomically","greenEdge")}${message(7,190,550,735,"restart calls process-next again","amberEdge")}${message(8,550,1270,810,"READY_TO_FINALIZE snapshot","greenEdge")}${message(9,1270,1630,875,"invoice + lines + provenance + FINALIZED","greenEdge")}${message(10,1270,1630,940,"late debit / credit append only","amberEdge")}${message(11,190,1630,1010,"read-only reconcile; digest-gated repair","blueEdge dashed")}<text class="footer" x="900" y="1092" text-anchor="middle">Microservice extraction preserves ownership: ingest, pricing, billing, and reconciliation get separate databases plus versioned events and dedup receipts.</text></svg>`;
}
