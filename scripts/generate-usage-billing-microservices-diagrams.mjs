#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outputDirectory = path.join(root, "docs/images/readme-diagrams");
const prefix = "usage-billing-microservices";
const wikiRoot = process.env.BLUETAPE_WIKI_ROOT ?? path.resolve(root, "../../../bluetape4k-wiki");

const escapeXml = (value) => value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
const iconDataUri = (relativePath) => {
  const source = path.join(wikiRoot, relativePath);
  if (!fs.existsSync(source)) throw new Error(`missing required Bluetape diagram icon: ${source}`);
  return `data:image/svg+xml;base64,${fs.readFileSync(source).toString("base64")}`;
};

const kafkaIcon = iconDataUri("docs/icons/testcontainers/mq/apache-kafka.svg");
const postgresIcon = iconDataUri("docs/icons/testcontainers/database/postgresql.svg");

function svgDocument({ kind, title, description, width = 1700, height = 1020, body }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc" data-diagram-kind="${kind}">
  <title id="title">${escapeXml(title)}</title>
  <desc id="desc">${escapeXml(description)}</desc>
  <defs>
    <marker id="arrow" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L12,6 L0,12 Z" fill="#356a91" stroke="#356a91"/></marker>
    <marker id="amberArrow" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L12,6 L0,12 Z" fill="#a67331" stroke="#a67331"/></marker>
    <marker id="greenArrow" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L12,6 L0,12 Z" fill="#4f7b59" stroke="#4f7b59"/></marker>
    <style>
      .title{font:700 36px 'Architects Daughter',sans-serif;fill:#20384b}.subtitle{font:18px 'Comic Mono',monospace;fill:#536d80}.heading{font:700 23px 'Architects Daughter',sans-serif;fill:#20384b}.label{font:700 18px 'Comic Mono',monospace;fill:#20384b}.whiteLabel{font:700 18px 'Comic Mono',monospace;fill:#fff}.whiteSmall{font:14px 'Comic Mono',monospace;fill:#dce8f0}.copy{font:16px 'Comic Mono',monospace;fill:#405a6e}.small{font:14px 'Comic Mono',monospace;fill:#536d80}.flow{fill:none;stroke:#356a91;stroke-width:4;stroke-linecap:round;stroke-linejoin:round;marker-end:url(#arrow)}.recovery{fill:none;stroke:#a67331;stroke-width:4;stroke-linecap:round;stroke-linejoin:round;marker-end:url(#amberArrow)}.local{fill:none;stroke:#4f7b59;stroke-width:4;stroke-linecap:round;stroke-linejoin:round;marker-end:url(#greenArrow)}.lifeline{stroke:#92a7b7;stroke-width:2;stroke-dasharray:8 8}.activation{fill:#c5dfec;stroke:#356a91;stroke-width:2}.frame{stroke:#6f9fbe;stroke-width:2}.frameLabel{font:700 15px 'Comic Mono',monospace;fill:#20384b}.labelPill{fill:#f8fbfd;stroke:#6f9fbe;stroke-width:1.5}.num{font:700 13px 'Comic Mono',monospace;fill:#20384b}.message{font:13px 'Comic Mono',monospace;fill:#405a6e}
    </style>
  </defs>
  <rect width="${width}" height="${height}" fill="#f8fbfd"/>
  ${body}
</svg>\n`;
}

function architectureDiagram() {
  const services = [
    [120, "Meter", "price authority", "PriceActivated", "meter-db + outbox"],
    [430, "Usage", "receipt authority", "UsageAccepted", "usage-db + inbox/outbox"],
    [740, "Billing", "rating authority", "ChargeRated", "billing-db + inbox/outbox"],
    [1050, "Invoice", "immutable documents", "InvoiceIssued", "invoice-db + inbox"],
    [1360, "Query", "projection + audit", "quarantine", "query-db + quarantine"],
  ];
  const serviceCards = services.map(([x, name, authority, event]) => `<g>
    <rect class="card" x="${x}" y="240" width="220" height="155" rx="18" fill="#fff" stroke="#6f9fbe" stroke-width="2"/>
    <text x="${x + 22}" y="278" class="heading">${name}</text>
    <text x="${x + 22}" y="314" class="copy">${authority}</text>
    <text x="${x + 22}" y="348" class="small">${event}</text>
  </g>`).join("\n");
  const databaseCards = services.map(([x, , , , database]) => {
    const [primary, secondary] = database.split(" + ");
    return `<g>
    <rect class="card" x="${x}" y="720" width="220" height="94" rx="16" fill="#fff" stroke="#83aa89" stroke-width="2"/>
    <image href="${postgresIcon}" x="${x + 18}" y="740" width="42" height="42"/>
    <text x="${x + 70}" y="762" class="small">${escapeXml(primary)} +</text>
    <text x="${x + 70}" y="790" class="small">${escapeXml(secondary)}</text>
  </g>`;
  }).join("\n");
  const localPaths = services.map(([x]) => `<path class="local" d="M${x + 110} 720 V${660}"/>`).join("\n");
  const producerPaths = [
    "M230 395 V455 Q230 470 245 470 H290 Q305 470 305 485 V520",
    "M540 395 V455 Q540 470 555 470 H600 Q615 470 615 485 V520",
    "M850 395 V455 Q850 470 865 470 H910 Q925 470 925 485 V520",
    "M1160 395 V455 Q1160 470 1175 470 H1220 Q1235 470 1235 485 V520",
  ].map((d) => `<path class="flow" d="${d}"/>`).join("\n");
  const consumerPaths = [
    "M380 610 V650 Q380 665 395 665 H540 Q555 665 555 650 V610",
    "M690 610 V650 Q690 665 705 665 H850 Q865 665 865 650 V610",
    "M1000 610 V650 Q1000 665 1015 665 H1160 Q1175 665 1175 650 V610",
    "M1310 610 V650 Q1310 665 1325 665 H1470 Q1485 665 1485 650 V610",
  ].map((d) => `<path class="flow" d="${d}"/>`).join("\n");
  return svgDocument({
    kind: "architecture",
    title: "Usage billing microservice ownership",
    description: "Five Spring Boot services own separate PostgreSQL authorities and exchange replayable facts through Kafka.",
    height: 920,
    body: `<text x="70" y="72" class="title">Event-sourced usage billing: ownership before transport</text>
  <text x="70" y="108" class="subtitle">A local Exposed transaction is the correctness boundary; Kafka is the replayable transport boundary.</text>
  <rect x="70" y="155" width="1560" height="300" rx="28" fill="#eaf3f9" stroke="#b9d0df" stroke-width="2"/>
  <text x="100" y="202" class="heading">Spring Boot service authorities</text>
  ${serviceCards}
  <rect x="70" y="520" width="1560" height="90" rx="22" fill="#394f63"/>
  <image href="${kafkaIcon}" x="105" y="535" width="58" height="58"/>
  <text x="185" y="557" class="whiteLabel">Kafka — at-least-once facts</text>
  <text x="185" y="585" class="whiteSmall">meter.events.v1 -> usage.events.v1 -> billing.events.v1 -> invoice.events.v1</text>
  ${producerPaths}
  ${consumerPaths}
  <rect x="70" y="675" width="1560" height="170" rx="28" fill="#edf6ed" stroke="#c1d7c1" stroke-width="2"/>
  <text x="100" y="712" class="heading">Local PostgreSQL + Exposed persistence</text>
  ${databaseCards}
  ${localPaths}
  <path d="M1080 395 V495" fill="none" stroke="#356a91" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#arrow)"/>
  <text x="1260" y="420" class="small">Query consumes every topic</text>
  <text x="70" y="885" class="small">Blue arrows = Kafka publication/consumption. Green arrows = one service's local authority. No cross-database transaction exists.</text>`,
  });
}

function sequenceDiagram({ slug, title, subtitle, participants, messages, frame }) {
  const width = 1700;
  const startX = 130;
  const spacing = 360;
  const participantX = participants.map((_, index) => startX + index * spacing);
  const boxes = participants.map((participant, index) => `<g>
    <rect x="${participantX[index] - 105}" y="150" width="210" height="72" rx="14" fill="#fff" stroke="#6f9fbe" stroke-width="2"/>
    <text x="${participantX[index]}" y="180" text-anchor="middle" class="label">${escapeXml(participant)}</text>
    <text x="${participantX[index]}" y="204" text-anchor="middle" class="small">${index === 2 ? "Kafka topic" : "durable boundary"}</text>
    <line x1="${participantX[index]}" y1="222" x2="${participantX[index]}" y2="900" class="lifeline"/>
  </g>`).join("\n");
  const messageSvg = messages.map((message, index) => {
    const y = frame.y + 92 + index * 85;
    const from = participantX[message.from];
    const to = participantX[message.to];
    const start = from + (to > from ? 10 : -10);
    const end = to + (to > from ? -10 : 10);
    const labelX = (from + to) / 2;
    const kind = message.kind === "recovery" ? "recovery" : "flow";
    const labelWidth = Math.min(330, Math.max(210, message.label.length * 7 + 48));
    const labelX0 = labelX - labelWidth / 2;
    return `<rect x="${from - 7}" y="${y - 22}" width="14" height="45" class="activation"/>
      <path class="${kind}" d="M${start} ${y} H${end}"/>
      <rect x="${labelX0}" y="${y - 50}" width="${labelWidth}" height="26" rx="13" class="labelPill"/>
      <text x="${labelX0 + 15}" y="${y - 33}" text-anchor="middle" class="num">${index + 1}</text>
      <text x="${labelX0 + 31}" y="${y - 33}" class="message">${escapeXml(message.label)}</text>`;
  }).join("\n");
  return svgDocument({
    kind: "sequence",
    title,
    description: subtitle,
    body: `<text x="70" y="72" class="title">${escapeXml(title)}</text>
  <text x="70" y="108" class="subtitle">${escapeXml(subtitle)}</text>
  <rect x="70" y="125" width="1560" height="810" rx="28" fill="#eef5f9" stroke="#b9d0df" stroke-width="2"/>
  ${boxes}
  <rect x="100" y="${frame.y}" width="1500" height="${frame.height}" rx="12" class="frame" fill="none"/>
  <path d="M100 ${frame.y} H245 V${frame.y + 32} H100" fill="#eaf3f9" stroke="#6f9fbe" stroke-width="2"/>
  <text x="118" y="${frame.y + 22}" class="frameLabel">${escapeXml(frame.label)}</text>
  ${messageSvg}
  <text x="110" y="910" class="small">Messages are numbered by durable decision order. A Kafka offset advances only after the receiver commits its local inbox or quarantine decision.</text>`,
  });
}

const diagrams = {
  architecture: architectureDiagram(),
  delivery: sequenceDiagram({
    slug: "delivery",
    title: "At-least-once delivery without double billing",
    subtitle: "A committed outbox survives relay delay; the receiver's durable inbox turns replay into a duplicate success.",
    participants: ["Meter", "Meter DB + outbox", "Kafka", "Usage", "Usage DB + inbox"],
    frame: { y: 270, height: 570, label: "alt  broker accepted before publisher completion" },
    messages: [
      { from: 0, to: 1, label: "commit PriceActivated + PENDING outbox" },
      { from: 1, to: 2, label: "claim lease; publish meter.events.v1" },
      { from: 2, to: 3, label: "deliver PriceActivated (may replay)" },
      { from: 3, to: 4, label: "validate; record eventId + digest" },
      { from: 4, to: 3, label: "APPLIED or DUPLICATE durable outcome", kind: "recovery" },
      { from: 3, to: 2, label: "commit offset after local decision", kind: "recovery" },
    ],
  }),
  "poison-recovery": sequenceDiagram({
    slug: "poison-recovery",
    title: "Poison isolation and audited redrive",
    subtitle: "Query makes a permanent schema or digest failure durable, while unrelated aggregates keep moving.",
    participants: ["Kafka ingress", "Query decoder", "Query DB", "Operator", "Retained source"],
    frame: { y: 270, height: 570, label: "alt  permanent contract failure" },
    messages: [
      { from: 0, to: 1, label: "deliver unsupported schema / bad digest" },
      { from: 1, to: 2, label: "store quarantine metadata + reason" },
      { from: 2, to: 0, label: "commit offset after quarantine", kind: "recovery" },
      { from: 3, to: 2, label: "inspect backlog; request redrive" },
      { from: 2, to: 4, label: "record auditable retained-source request", kind: "recovery" },
      { from: 4, to: 1, label: "external retrieval/republication (not implemented)", kind: "recovery" },
    ],
  }),
  correction: sequenceDiagram({
    slug: "correction",
    title: "Financial correction appends a compensating fact",
    subtitle: "Billing owns the idempotent adjustment; Invoice preserves the original line and appends its correction.",
    participants: ["Billing API", "Billing DB + outbox", "Kafka", "Invoice", "Invoice DB"],
    frame: { y: 270, height: 570, label: "opt  adjustmentEventId is first seen" },
    messages: [
      { from: 0, to: 1, label: "post negative adjustment(correctionOf)" },
      { from: 1, to: 2, label: "commit AdjustmentPosted + outbox" },
      { from: 2, to: 3, label: "deliver billing.events.v1" },
      { from: 3, to: 4, label: "append correction invoice line" },
      { from: 4, to: 3, label: "APPLIED / DUPLICATE result", kind: "recovery" },
      { from: 3, to: 2, label: "commit offset after append", kind: "recovery" },
    ],
  }),
  extraction: sequenceDiagram({
    slug: "extraction",
    title: "Staged extraction with route-only rollback",
    subtitle: "Move authority one boundary at a time, prove immutable event parity, then change only routing on rollback.",
    participants: ["Legacy ledger", "Meter + Usage", "Kafka", "Billing + Invoice", "Query"],
    frame: { y: 270, height: 570, label: "loop  one extracted authority boundary at a time" },
    messages: [
      { from: 0, to: 1, label: "compare price + receipt black-box parity" },
      { from: 1, to: 2, label: "publish immutable source-event IDs" },
      { from: 2, to: 3, label: "rate and materialize locally" },
      { from: 3, to: 4, label: "compare totals + event IDs" },
      { from: 4, to: 0, label: "report parity before route cutover", kind: "recovery" },
      { from: 0, to: 1, label: "rollback route only; retain history", kind: "recovery" },
    ],
  }),
};

fs.mkdirSync(outputDirectory, { recursive: true });
for (const [slug, svg] of Object.entries(diagrams)) {
  fs.writeFileSync(path.join(outputDirectory, `${prefix}-${slug}-01.svg`), svg);
}

const slugs = ["architecture", "outbox-inbox-state", "delivery", "poison-recovery", "correction", "extraction"];
for (const slug of slugs) {
  const source = path.join(outputDirectory, `${prefix}-${slug}-01.svg`);
  const target = path.join(outputDirectory, `${prefix}-${slug}-01.png`);
  const result = spawnSync("cairosvg", [source, "-o", target, "-s", "2"], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

console.log(`generated usage billing diagrams: ${slugs.length}`);
