#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outputDirectory = path.join(root, "docs/images/readme-diagrams");
const prefix = "usage-billing-microservices";

const escapeXml = (value) => value
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;");

function stageDiagram({ slug, title, subtitle, stages, note }) {
  const cardWidth = 250;
  const cardHeight = 180;
  const gap = 45;
  const startX = (1600 - (stages.length * cardWidth + (stages.length - 1) * gap)) / 2;
  const cardY = 285;
  const connectors = stages.slice(0, -1).map((_, index) => {
    const fromX = startX + index * (cardWidth + gap) + cardWidth;
    const toX = startX + (index + 1) * (cardWidth + gap);
    return `<path class="flow" d="M${fromX} ${cardY + 90} H${toX - 14}"/>`;
  }).join("\n    ");
  const cards = stages.map((stage, index) => {
    const x = startX + index * (cardWidth + gap);
    const lines = stage.lines.map((line, lineIndex) =>
      `<text x="${x + 24}" y="${cardY + 90 + lineIndex * 28}" class="cardText">${escapeXml(line)}</text>`,
    ).join("\n      ");
    return `<g>
      <rect class="card" x="${x}" y="${cardY}" width="${cardWidth}" height="${cardHeight}" rx="20"/>
      <circle cx="${x + 34}" cy="${cardY + 36}" r="18" class="step"/>
      <text x="${x + 34}" y="${cardY + 43}" text-anchor="middle" class="stepText">${index + 1}</text>
      <text x="${x + 64}" y="${cardY + 44}" class="cardTitle">${escapeXml(stage.title)}</text>
      ${lines}
    </g>`;
  }).join("\n    ");

  return `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="920" viewBox="0 0 1600 920" role="img" aria-labelledby="title desc">
  <title id="title">${escapeXml(title)}</title>
  <desc id="desc">${escapeXml(subtitle)}</desc>
  <defs>
    <marker id="blueArrow" markerWidth="14" markerHeight="14" refX="12" refY="7" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L14,7 L0,14 Z" fill="#356a91" stroke="#356a91"/></marker>
    <style>
      .title{font:700 36px 'Architects Daughter',sans-serif;fill:#213447}.subtitle{font:18px 'Comic Mono',monospace;fill:#496273}.card{fill:#fff;stroke:#6f9fbe;stroke-width:3}.cardTitle{font:700 22px 'Architects Daughter',sans-serif;fill:#213447}.cardText{font:16px 'Comic Mono',monospace;fill:#385164}.step{fill:#356a91}.stepText{font:700 16px 'Comic Mono',monospace;fill:#fff}.flow{fill:none;stroke:#356a91;stroke-width:4;stroke-linecap:round;stroke-linejoin:round;marker-end:url(#blueArrow)}.note{font:17px 'Comic Mono',monospace;fill:#526675}.bandTitle{font:700 22px 'Architects Daughter',sans-serif;fill:#213447}
    </style>
  </defs>
  <rect width="1600" height="920" fill="#f8fbfd"/>
  <text x="70" y="78" class="title">${escapeXml(title)}</text>
  <text x="70" y="116" class="subtitle">${escapeXml(subtitle)}</text>
  <rect x="60" y="175" width="1480" height="420" rx="28" fill="#eaf3f9" stroke="#b9d0df" stroke-width="2"/>
  <text x="90" y="225" class="bandTitle">Decision path</text>
  <g>
    ${connectors}
    ${cards}
  </g>
  <rect x="120" y="660" width="1360" height="120" rx="22" fill="#edf6ed" stroke="#c1d7c1" stroke-width="2"/>
  <text x="160" y="710" class="bandTitle">Operational invariant</text>
  <text x="160" y="748" class="note">${escapeXml(note)}</text>
  <text x="70" y="870" class="note">PostgreSQL owns durable decisions. Kafka transports replayable facts between service boundaries.</text>
</svg>\n`;
}

const generated = [
  {
    slug: "delivery",
    title: "At-least-once delivery without double billing",
    subtitle: "A local commit survives relay delay; a durable inbox absorbs replay before applying another effect.",
    stages: [
      { title: "Local commit", lines: ["fact + outbox", "one transaction"] },
      { title: "Relay claim", lines: ["owner + lease", "fenced completion"] },
      { title: "Kafka", lines: ["publish may repeat", "offset is not truth"] },
      { title: "Durable inbox", lines: ["event ID + digest", "duplicate = success"] },
      { title: "Local effect", lines: ["apply once", "commit then offset"] },
    ],
    note: "A crash after broker acceptance can replay the event; it must not recreate the financial fact.",
  },
  {
    slug: "poison-recovery",
    title: "Poison isolation and audited redrive",
    subtitle: "Permanent contract failure is durable and inspectable while unrelated aggregates continue.",
    stages: [
      { title: "Decode", lines: ["validate envelope", "schema + digest"] },
      { title: "Quarantine", lines: ["store payload", "reason + tenant"] },
      { title: "Progress", lines: ["independent keys", "continue safely"] },
      { title: "Operator", lines: ["inspect snapshot", "request redrive"] },
      { title: "Replay", lines: ["preserve payload", "audit decision"] },
    ],
    note: "Redrive is not an edit surface: amounts, prices, event IDs, and the original payload stay immutable.",
  },
  {
    slug: "correction",
    title: "Financial correction appends a compensating fact",
    subtitle: "Billing owns the adjustment command and Invoice preserves the original immutable line.",
    stages: [
      { title: "ChargeRated", lines: ["original event ID", "immutable amount"] },
      { title: "Adjustment", lines: ["Billing authority", "idempotent command"] },
      { title: "AdjustmentPosted", lines: ["correctionOf", "durable outbox"] },
      { title: "Invoice", lines: ["append new line", "never overwrite"] },
      { title: "Query", lines: ["original + repair", "visible history"] },
    ],
    note: "The correction references the original charge; history remains explainable and replay-safe.",
  },
  {
    slug: "extraction",
    title: "Staged extraction with route-only rollback",
    subtitle: "Move authority only after black-box parity and drain evidence are stable at each boundary.",
    stages: [
      { title: "Ledger", lines: ["baseline truth", "capture parity"] },
      { title: "Meter + Usage", lines: ["price + receipt", "dual-check"] },
      { title: "Billing", lines: ["rated totals", "drain outbox"] },
      { title: "Invoice + Query", lines: ["materialize", "compare event IDs"] },
      { title: "Routing", lines: ["cut over", "rollback route only"] },
    ],
    note: "Never copy service databases backward or rewrite published history during rollback.",
  },
];

fs.mkdirSync(outputDirectory, { recursive: true });
const stateSource = path.join(outputDirectory, `${prefix}-state-01.svg`);
const stateTarget = path.join(outputDirectory, `${prefix}-outbox-inbox-state-01.svg`);
if (!fs.existsSync(stateSource)) throw new Error(`missing state source: ${stateSource}`);
fs.copyFileSync(stateSource, stateTarget);

for (const diagram of generated) {
  fs.writeFileSync(
    path.join(outputDirectory, `${prefix}-${diagram.slug}-01.svg`),
    stageDiagram(diagram),
  );
}

const slugs = ["architecture", "outbox-inbox-state", ...generated.map(({ slug }) => slug)];
for (const slug of slugs) {
  const source = path.join(outputDirectory, `${prefix}-${slug}-01.svg`);
  const target = path.join(outputDirectory, `${prefix}-${slug}-01.png`);
  const result = spawnSync("cairosvg", [source, "-o", target, "-s", "2"], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log(`generated usage billing diagrams: ${slugs.length}`);
