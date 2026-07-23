#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outputDirectory = path.join(root, "docs/images/readme-diagrams");
const prefix = "usage-billing-microservices";
const wikiRoot = process.env.BLUETAPE_WIKI_ROOT ?? path.resolve(root, "../../../bluetape4k-wiki");

const colors = {
  ink: "#243447",
  copy: "#526579",
  border: "#C9D5DF",
  canvas: "#F7F9FB",
  white: "#FFFDFC",
  blue: "#4F86C6",
  blueSoft: "#EAF2FB",
  green: "#6E8F4F",
  greenSoft: "#EEF5E9",
  purple: "#8065A8",
  purpleSoft: "#F1EDF7",
  amber: "#B57E2A",
  amberSoft: "#FBF2E3",
  teal: "#2D948C",
  tealSoft: "#E8F5F3",
  red: "#B75B5B",
  redSoft: "#F9ECEC",
  gray: "#8FA1B2",
  graySoft: "#EEF2F5",
};

const escapeXml = (value) =>
  String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");

const iconDataUri = (relativePath) => {
  const source = path.join(wikiRoot, relativePath);
  if (!fs.existsSync(source)) throw new Error(`missing required Bluetape diagram icon: ${source}`);
  return `data:image/svg+xml;base64,${fs.readFileSync(source).toString("base64")}`;
};

const kafkaIcon = iconDataUri("docs/icons/testcontainers/mq/apache-kafka.svg");
const postgresIcon = iconDataUri("docs/icons/testcontainers/database/postgresql.svg");

function svgDocument({ kind, title, description, width = 2400, height = 1350, body }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc" data-diagram-kind="${kind}">
  <title id="title">${escapeXml(title)}</title>
  <desc id="desc">${escapeXml(description)}</desc>
  <defs>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="5" stdDeviation="8" flood-color="#243447" flood-opacity=".10"/>
    </filter>
    <style>
      .canvas{fill:${colors.canvas}}
      .outer{fill:${colors.white};stroke:${colors.border};stroke-width:3}
      .title{font:700 48px 'Architects Daughter',sans-serif;fill:${colors.ink}}
      .subtitle{font:22px 'Comic Mono',monospace;fill:${colors.copy}}
      .regionTitle{font:700 30px 'Architects Daughter',sans-serif;fill:${colors.ink}}
      .cardTitle{font:700 28px 'Architects Daughter',sans-serif;fill:${colors.ink}}
      .participant{font:700 25px 'Architects Daughter',sans-serif;fill:${colors.ink}}
      .body{font:19px 'Comic Mono',monospace;fill:${colors.copy}}
      .small{font:17px 'Comic Mono',monospace;fill:${colors.copy}}
      .micro{font:15px 'Comic Mono',monospace;fill:${colors.copy}}
      .footer{font:19px 'Comic Mono',monospace;fill:${colors.copy}}
      .card{fill:${colors.white};stroke-width:3}
      .lifeline{stroke:${colors.gray};stroke-width:2;stroke-dasharray:10 10}
      .activation{fill:${colors.blueSoft};stroke:${colors.blue};stroke-width:2}
      .edgePath{fill:none;stroke-width:4;stroke-linecap:round;stroke-linejoin:round}
      .labelPill{fill:${colors.white};stroke-width:2}
      .num{font:700 16px 'Comic Mono',monospace}
      .message{font:17px 'Comic Mono',monospace;fill:${colors.ink}}
      .frame{fill:none;stroke-width:2.5}
      .frameLabel{font:700 19px 'Architects Daughter',sans-serif}
      .legend{font:17px 'Comic Mono',monospace;fill:${colors.copy}}
    </style>
  </defs>
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="outer" x="28" y="24" width="${width - 56}" height="${height - 48}" rx="28"/>
  ${body}
</svg>
`;
}

function textLines({ x, y, lines, className = "body", lineHeight = 28, anchor = "start" }) {
  return lines
    .map(
      (line, index) =>
        `<text x="${x}" y="${y + index * lineHeight}" text-anchor="${anchor}" class="${className}">${escapeXml(line)}</text>`,
    )
    .join("\n");
}

function card({
  id,
  x,
  y,
  width,
  height,
  title,
  lines = [],
  stroke = colors.blue,
  fill = colors.white,
  icon,
  titleAnchor = "middle",
}) {
  const titleX = titleAnchor === "middle" ? x + width / 2 : x + 34;
  const textX = icon ? x + 112 : titleAnchor === "middle" ? x + width / 2 : x + 34;
  const iconSvg = icon
    ? `<image href="${icon}" x="${x + 28}" y="${y + 27}" width="62" height="62"/>`
    : "";
  const adjustedTitleX = icon ? x + 112 : titleX;
  const adjustedAnchor = icon ? "start" : titleAnchor;
  const bodyY = y + (icon ? 76 : 78);
  return `<g class="node" data-node="${id}" data-card="${id}">
    <rect class="card" x="${x}" y="${y}" width="${width}" height="${height}" rx="20" fill="${fill}" stroke="${stroke}" filter="url(#shadow)"/>
    ${iconSvg}
    <text x="${adjustedTitleX}" y="${y + 48}" text-anchor="${adjustedAnchor}" class="cardTitle">${escapeXml(title)}</text>
    ${textLines({ x: textX, y: bodyY, lines, className: "small", lineHeight: 27, anchor: titleAnchor })}
  </g>`;
}

function arrowHeadPoints(x, y, direction, size) {
  const half = size / 2;
  if (direction === "right") return `${x},${y} ${x - size},${y - half} ${x - size},${y + half}`;
  if (direction === "left") return `${x},${y} ${x + size},${y - half} ${x + size},${y + half}`;
  if (direction === "down") return `${x},${y} ${x - half},${y - size} ${x + half},${y - size}`;
  if (direction === "up") return `${x},${y} ${x - half},${y + size} ${x + half},${y + size}`;
  throw new Error(`unsupported arrow direction: ${direction}`);
}

function connector({
  id,
  d,
  endX,
  endY,
  direction,
  color = colors.blue,
  size = 14,
  dashed = false,
  label = id,
}) {
  const dash = dashed ? ' stroke-dasharray="12 10"' : "";
  return `<g class="edge" data-edge="${id}" data-label="${escapeXml(label)}">
    <path data-connector="${id}" class="edgePath" style="marker-end:none" d="${d}" stroke="${color}"${dash}/>
    <polygon data-connector-head="${id}" data-arrow-direction="${direction}" points="${arrowHeadPoints(endX, endY, direction, size)}" fill="${color}" stroke="${color}" stroke-dasharray="none" style="stroke-dasharray:none"/>
  </g>`;
}

function architectureDiagram() {
  const serviceXs = [120, 665, 1210, 1755];
  const services = [
    {
      id: "meter",
      title: "Meter",
      authority: ["immutable price versions", "publishes PriceActivated"],
      topic: "meter.events.v1",
    },
    {
      id: "usage",
      title: "Usage",
      authority: ["accepted usage receipts", "publishes UsageAccepted"],
      topic: "usage.events.v1",
    },
    {
      id: "billing",
      title: "Billing",
      authority: ["price evidence + rated charges", "publishes ChargeRated"],
      topic: "billing.events.v1",
    },
    {
      id: "invoice",
      title: "Invoice",
      authority: ["immutable document lines", "publishes correction events"],
      topic: "invoice.events.v1",
    },
  ];
  const serviceCards = services
    .map((service, index) =>
      card({
        id: `${service.id}-service`,
        x: serviceXs[index],
        y: 205,
        width: 430,
        height: 145,
        title: service.title,
        lines: service.authority,
        stroke: colors.blue,
        fill: colors.blueSoft,
      }),
    )
    .join("\n");
  const databaseCards = services
    .map((service, index) =>
      card({
        id: `${service.id}-database`,
        x: serviceXs[index],
        y: 405,
        width: 430,
        height: 125,
        title: `${service.title} PostgreSQL`,
        lines: ["ExposedJdbcRepository", "local fact + inbox/outbox"],
        stroke: colors.green,
        fill: colors.greenSoft,
        icon: postgresIcon,
        titleAnchor: "start",
      }),
    )
    .join("\n");
  const topicCards = services
    .map((service, index) =>
      card({
        id: `${service.id}-topic`,
        x: serviceXs[index] + 20,
        y: 665,
        width: 390,
        height: 112,
        title: service.topic,
        lines: [index === 3 ? "future public invoice facts" : "versioned immutable envelope"],
        stroke: colors.amber,
        fill: colors.amberSoft,
        icon: kafkaIcon,
        titleAnchor: "start",
      }),
    )
    .join("\n");
  const localConnectors = services
    .map((service, index) => {
      const x = serviceXs[index] + 215;
      return connector({
        id: `local-${service.id}`,
        d: `M${x} 350 V405`,
        endX: x,
        endY: 405,
        direction: "down",
        color: colors.green,
        label: `${service.title} owns its PostgreSQL database`,
      });
    })
    .join("\n");
  const publishConnectors = services
    .map((service, index) => {
      const x = serviceXs[index] + 215;
      return connector({
        id: `publish-${service.id}`,
        d: `M${x} 530 V665`,
        endX: x,
        endY: 665,
        direction: "down",
        color: colors.amber,
        label: `${service.title} outbox publishes ${service.topic}`,
      });
    })
    .join("\n");
  const consumerConnectors = [0, 1, 2]
    .map((index) => {
      const startX = serviceXs[index] + 410;
      const targetX = serviceXs[index + 1];
      const corridorX = serviceXs[index] + 500;
      const targetY = 280 + index * 24;
      return connector({
        id: `consume-${services[index].id}-${services[index + 1].id}`,
        d: `M${startX} 721 H${corridorX - 20} Q${corridorX} 721 ${corridorX} 701 V${targetY + 20} Q${corridorX} ${targetY} ${corridorX + 20} ${targetY} H${targetX}`,
        endX: targetX,
        endY: targetY,
        direction: "right",
        color: colors.blue,
        label: `${services[index + 1].title} consumes ${services[index].topic}`,
      });
    })
    .join("\n");
  const queryConnectors = services
    .map((service, index) => {
      const x = serviceXs[index] + 215;
      return connector({
        id: `query-consumes-${service.id}`,
        d: `M${x} 777 V930`,
        endX: x,
        endY: 930,
        direction: "down",
        color: colors.purple,
        label: `Query consumes ${service.topic}`,
      });
    })
    .join("\n");
  return svgDocument({
    kind: "architecture",
    title: "Usage Billing Microservice Ownership",
    description:
      "Four financial services own separate PostgreSQL authorities and publish versioned facts to Kafka; Query consumes every public topic without owning financial commands.",
    body: `<text x="1200" y="88" text-anchor="middle" class="title">Usage Billing Microservice Ownership</text>
  <text x="1200" y="124" text-anchor="middle" class="subtitle">Local PostgreSQL is the correctness boundary; Kafka carries replayable facts between owners.</text>
  <rect x="82" y="160" width="2236" height="410" rx="28" fill="${colors.blueSoft}" stroke="${colors.blue}" stroke-width="2.5"/>
  <text x="112" y="198" class="regionTitle">Independent Spring Boot 4 / Java 25 services</text>
  ${serviceCards}
  ${databaseCards}
  ${localConnectors}
  <rect x="82" y="605" width="2236" height="215" rx="28" fill="${colors.amberSoft}" stroke="${colors.amber}" stroke-width="2.5"/>
  <text x="112" y="645" class="regionTitle">Kafka public event contracts</text>
  ${topicCards}
  ${publishConnectors}
  ${consumerConnectors}
  <rect x="82" y="930" width="2236" height="292" rx="28" fill="${colors.purpleSoft}" stroke="${colors.purple}" stroke-width="2.5"/>
  <text x="112" y="986" class="regionTitle">Query read-side boundary</text>
  ${queryConnectors}
  ${card({
    id: "query-service",
    x: 150,
    y: 1020,
    width: 1080,
    height: 145,
    title: "Query",
    lines: ["read models + checkpoints", "quarantine visibility + redrive audit"],
    stroke: colors.purple,
    fill: colors.white,
  })}
  ${card({
    id: "query-database",
    x: 1510,
    y: 1020,
    width: 650,
    height: 145,
    title: "Query PostgreSQL",
    lines: ["projection + inbox", "quarantine + recovery journal"],
    stroke: colors.green,
    fill: colors.white,
    icon: postgresIcon,
    titleAnchor: "start",
  })}
  ${connector({
    id: "query-local-authority",
    d: "M1230 1092 H1510",
    endX: 1510,
    endY: 1092,
    direction: "right",
    color: colors.green,
    label: "Query owns its local read-side state",
  })}
  <rect x="330" y="1250" width="1740" height="56" rx="24" fill="${colors.graySoft}" stroke="${colors.border}" stroke-width="2"/>
  <line x1="400" y1="1278" x2="470" y2="1278" stroke="${colors.green}" stroke-width="4"/><text x="490" y="1284" class="legend">local authority</text>
  <line x1="850" y1="1278" x2="920" y2="1278" stroke="${colors.amber}" stroke-width="4"/><text x="940" y="1284" class="legend">outbox publication</text>
  <line x1="1370" y1="1278" x2="1440" y2="1278" stroke="${colors.blue}" stroke-width="4"/><text x="1460" y="1284" class="legend">service consumption</text>
  <line x1="1830" y1="1278" x2="1900" y2="1278" stroke="${colors.purple}" stroke-width="4"/><text x="1920" y="1284" class="legend">read-side subscription</text>`,
  });
}

function stateBox({ id, x, y, width = 270, title, lines, stroke, fill }) {
  return card({ id, x, y, width, height: 120, title, lines, stroke, fill });
}

function stateDiagram() {
  return svgDocument({
    kind: "state",
    title: "Durable Outbox and Inbox Decisions",
    description:
      "The producer retries one claimed outbox row until published or quarantined; the consumer commits an offset only after an applied, duplicate, or quarantined local decision.",
    body: `<text x="1200" y="88" text-anchor="middle" class="title">Durable Outbox and Inbox Decisions</text>
  <text x="1200" y="124" text-anchor="middle" class="subtitle">Retries move the same durable record; duplicate delivery is a normal terminal receiver outcome.</text>
  <rect x="80" y="170" width="1080" height="1060" rx="30" fill="${colors.blueSoft}" stroke="${colors.blue}" stroke-width="3"/>
  <text x="120" y="225" class="regionTitle">Producer outbox</text>
  <text x="120" y="258" class="small">Owner + lease fencing protects completion after publication.</text>
  ${stateBox({ id: "outbox-pending", x: 150, y: 330, title: "PENDING", lines: ["committed with local fact"], stroke: colors.blue, fill: colors.white })}
  ${stateBox({ id: "outbox-in-flight", x: 510, y: 330, title: "IN_FLIGHT", lines: ["claimed by owner + lease"], stroke: colors.amber, fill: colors.white })}
  ${stateBox({ id: "outbox-published", x: 870, y: 330, title: "PUBLISHED", lines: ["terminal success"], stroke: colors.green, fill: colors.white })}
  ${stateBox({ id: "outbox-retry", x: 510, y: 670, title: "RETRY_WAIT", lines: ["same row, later attempt"], stroke: colors.amber, fill: colors.white })}
  ${stateBox({ id: "outbox-quarantine", x: 870, y: 670, title: "QUARANTINED", lines: ["retry policy exhausted"], stroke: colors.red, fill: colors.white })}
  ${connector({ id: "outbox-claim", d: "M420 390 H510", endX: 510, endY: 390, direction: "right", color: colors.blue, label: "eligible row is claimed" })}
  ${connector({ id: "outbox-complete", d: "M780 390 H870", endX: 870, endY: 390, direction: "right", color: colors.green, label: "matching owner and lease completes publication" })}
  ${connector({ id: "outbox-retry-wait", d: "M620 450 V670", endX: 620, endY: 670, direction: "down", color: colors.amber, label: "retryable transport failure" })}
  ${connector({
    id: "outbox-reclaim",
    d: "M510 730 H420 Q400 730 400 710 V490 Q400 470 420 470 H580 Q600 470 600 450",
    endX: 600,
    endY: 450,
    direction: "up",
    color: colors.blue,
    label: "retry time reached or lease expired",
  })}
  ${connector({
    id: "outbox-quarantine",
    d: "M780 420 H810 Q830 420 830 440 V710 Q830 730 850 730 H870",
    endX: 870,
    endY: 730,
    direction: "right",
    color: colors.red,
    label: "permanent or exhausted failure",
  })}
  <rect x="150" y="910" width="930" height="210" rx="24" fill="${colors.white}" stroke="${colors.border}" stroke-width="2"/>
  <text x="190" y="956" class="cardTitle">Crash window stays safe</text>
  ${textLines({
    x: 190,
    y: 997,
    lines: [
      "Kafka may accept the record before PUBLISHED is stored.",
      "The lease expires and the same outbox row is published again.",
      "The receiver's inbox absorbs the replay.",
    ],
    className: "body",
    lineHeight: 34,
  })}
  <rect x="1240" y="170" width="1080" height="1060" rx="30" fill="${colors.purpleSoft}" stroke="${colors.purple}" stroke-width="3"/>
  <text x="1280" y="225" class="regionTitle">Consumer inbox / quarantine</text>
  <text x="1280" y="258" class="small">Offset commit follows a durable local decision, never a log line.</text>
  ${stateBox({ id: "inbox-received", x: 1645, y: 320, width: 270, title: "RECEIVED", lines: ["validate local envelope"], stroke: colors.purple, fill: colors.white })}
  ${stateBox({ id: "inbox-applied", x: 1290, y: 650, width: 270, title: "APPLIED", lines: ["new eventId", "local effect committed"], stroke: colors.green, fill: colors.white })}
  ${stateBox({ id: "inbox-duplicate", x: 1645, y: 650, width: 270, title: "DUPLICATE", lines: ["same ID + same digest", "no second effect"], stroke: colors.teal, fill: colors.white })}
  ${stateBox({ id: "inbox-quarantined", x: 2000, y: 650, width: 270, title: "QUARANTINED", lines: ["bad schema or digest", "reason is durable"], stroke: colors.red, fill: colors.white })}
  ${connector({
    id: "inbox-apply",
    d: "M1700 440 V510 Q1700 530 1680 530 H1445 Q1425 530 1425 550 V650",
    endX: 1425,
    endY: 650,
    direction: "down",
    color: colors.green,
    label: "first valid event",
  })}
  ${connector({
    id: "inbox-duplicate",
    d: "M1780 440 V650",
    endX: 1780,
    endY: 650,
    direction: "down",
    color: colors.teal,
    label: "same ID and digest",
  })}
  ${connector({
    id: "inbox-quarantine",
    d: "M1860 440 V510 Q1860 530 1880 530 H2115 Q2135 530 2135 550 V650",
    endX: 2135,
    endY: 650,
    direction: "down",
    color: colors.red,
    label: "unsupported schema or digest conflict",
  })}
  <rect x="1320" y="910" width="930" height="210" rx="24" fill="${colors.white}" stroke="${colors.border}" stroke-width="2"/>
  <text x="1360" y="956" class="cardTitle">Retryable failure is not a terminal inbox state</text>
  ${textLines({
    x: 1360,
    y: 997,
    lines: [
      "A transient database error is propagated to Kafka.",
      "No offset is committed; the same envelope is redelivered.",
      "APPLIED, DUPLICATE, and QUARANTINED may commit the offset.",
    ],
    className: "body",
    lineHeight: 34,
  })}
  <text x="1200" y="1285" text-anchor="middle" class="footer">Exactly-once is not claimed: local idempotency makes at-least-once delivery operationally safe.</text>`,
  });
}

function sequenceParticipant({ x, title, subtitle, stroke, icon }) {
  const iconSvg = icon ? `<image href="${icon}" x="${x - 112}" y="206" width="48" height="48"/>` : "";
  return `<g class="node" data-node="${escapeXml(title)}" data-card="${escapeXml(title)}">
    <rect class="card" x="${x - 205}" y="180" width="410" height="105" rx="18" fill="${colors.white}" stroke="${stroke}" filter="url(#shadow)"/>
    ${iconSvg}
    <text x="${x + (icon ? 52 : 0)}" y="222" text-anchor="middle" class="participant">${escapeXml(title)}</text>
    <text x="${x + (icon ? 52 : 0)}" y="255" text-anchor="middle" class="small">${escapeXml(subtitle)}</text>
    <line x1="${x}" y1="285" x2="${x}" y2="1430" class="lifeline"/>
  </g>`;
}

function sequenceLabel({ number, x, y, width, text, color }) {
  return `<rect x="${x}" y="${y}" width="${width}" height="42" rx="21" class="labelPill" stroke="${color}"/>
    <circle cx="${x + 24}" cy="${y + 21}" r="15" fill="${colors.white}" stroke="${color}" stroke-width="3"/>
    <text x="${x + 24}" y="${y + 27}" text-anchor="middle" class="num" fill="${color}">${number}</text>
    <text x="${x + 50}" y="${y + 28}" class="message">${escapeXml(text)}</text>`;
}

function sequenceDiagram({
  title,
  subtitle,
  participants,
  messages,
  frames = [],
  activations = [],
  footer,
  height = 1500,
}) {
  const width = 2600;
  const xs = [260, 780, 1300, 1820, 2340];
  const participantSvg = participants
    .map((participant, index) =>
      sequenceParticipant({
        x: xs[index],
        title: participant.title,
        subtitle: participant.subtitle,
        stroke: participant.color,
        icon: participant.icon,
      }),
    )
    .join("\n");
  const frameSvg = frames
    .map((frame) => {
      const divider = frame.dividerY
        ? `<line x1="${frame.x}" y1="${frame.dividerY}" x2="${frame.x + frame.width}" y2="${frame.dividerY}" stroke="${frame.color}" stroke-width="2" stroke-dasharray="10 8"/>
          <text x="${frame.x + 24}" y="${frame.dividerY + 30}" class="frameLabel" fill="${frame.color}">${escapeXml(frame.elseLabel ?? "else")}</text>`
        : "";
      return `<rect x="${frame.x}" y="${frame.y}" width="${frame.width}" height="${frame.height}" rx="18" class="frame alt" fill="none" stroke="${frame.color}"/>
        <rect x="${frame.x}" y="${frame.y}" width="${Math.max(210, frame.label.length * 13)}" height="38" rx="18" fill="${colors.white}" stroke="${frame.color}" stroke-width="2"/>
        <text x="${frame.x + 20}" y="${frame.y + 27}" class="frameLabel" fill="${frame.color}">${escapeXml(frame.label)}</text>
        ${divider}`;
    })
    .join("\n");
  const activationSvg = activations
    .map(
      (activation) =>
        `<rect class="activation" x="${xs[activation.participant] - 10}" y="${activation.y}" width="20" height="${activation.height}" rx="8" fill="${activation.fill ?? colors.blueSoft}" stroke="${activation.stroke ?? colors.blue}"/>`,
    )
    .join("\n");
  const messageSvg = messages
    .map((message, index) => {
      const from = xs[message.from];
      const to = xs[message.to];
      const direction = to > from ? "right" : "left";
      const labelWidth = Math.min(500, Math.max(270, message.label.length * 10.5 + 78));
      const labelX = (from + to - labelWidth) / 2;
      return `${sequenceLabel({
        number: index + 1,
        x: labelX,
        y: message.y - 62,
        width: labelWidth,
        text: message.label,
        color: message.color,
      })}
      ${connector({
        id: `message-${index + 1}`,
        d: `M${from} ${message.y} H${to}`,
        endX: to,
        endY: message.y,
        direction,
        color: message.color,
        size: 16,
        dashed: message.dashed,
        label: message.label,
      })}`;
    })
    .join("\n");
  return svgDocument({
    kind: "sequence",
    title,
    description: subtitle,
    width,
    height,
    body: `<text x="1300" y="88" text-anchor="middle" class="title">${escapeXml(title)}</text>
  <text x="1300" y="124" text-anchor="middle" class="subtitle">${escapeXml(subtitle)}</text>
  ${participantSvg}
  ${frameSvg}
  ${activationSvg}
  ${messageSvg}
  <text x="1300" y="${height - 58}" text-anchor="middle" class="footer">${escapeXml(footer)}</text>`,
  });
}

function deliveryDiagram() {
  return sequenceDiagram({
    title: "At-Least-Once Delivery Without Double Billing",
    subtitle: "The producer retries one committed outbox row; the consumer's inbox turns replay into a durable duplicate success.",
    participants: [
      { title: "Meter API", subtitle: "price command", color: colors.blue },
      { title: "Meter DB", subtitle: "PostgreSQL fact + outbox", color: colors.green, icon: postgresIcon },
      { title: "Outbox relay", subtitle: "owner + lease claim", color: colors.amber },
      { title: "Kafka", subtitle: "at-least-once transport", color: colors.amber, icon: kafkaIcon },
      { title: "Usage DB", subtitle: "PostgreSQL inbox + evidence", color: colors.purple, icon: postgresIcon },
    ],
    frames: [
      {
        x: 55,
        y: 350,
        width: 2520,
        height: 410,
        label: "happy path — durable fact before publication",
        color: colors.green,
      },
      {
        x: 55,
        y: 790,
        width: 2520,
        height: 590,
        label: "alt — broker accepted before PUBLISHED was stored",
        color: colors.amber,
        dividerY: 1080,
        elseLabel: "replay reaches an already committed inbox",
      },
    ],
    activations: [
      { participant: 0, y: 390, height: 130 },
      { participant: 1, y: 390, height: 260, fill: colors.greenSoft, stroke: colors.green },
      { participant: 2, y: 520, height: 690, fill: colors.amberSoft, stroke: colors.amber },
      { participant: 4, y: 650, height: 630, fill: colors.purpleSoft, stroke: colors.purple },
    ],
    messages: [
      { from: 0, to: 1, y: 470, label: "commit PriceActivated + PENDING outbox", color: colors.green },
      { from: 2, to: 1, y: 590, label: "claim the row with owner + lease", color: colors.amber },
      { from: 2, to: 3, y: 710, label: "publish the immutable envelope", color: colors.blue },
      { from: 3, to: 4, y: 900, label: "deliver PriceActivated", color: colors.blue },
      { from: 4, to: 3, y: 1020, label: "commit inbox + evidence, then offset", color: colors.teal },
      { from: 2, to: 1, y: 1150, label: "lease expiry exposes the same row again", color: colors.amber },
      { from: 2, to: 3, y: 1250, label: "republish the same eventId + digest", color: colors.amber },
      { from: 3, to: 4, y: 1350, label: "DUPLICATE; no second local effect", color: colors.teal },
    ],
    footer: "Kafka offset progress follows the receiver's committed APPLIED or DUPLICATE decision.",
  });
}

function poisonRecoveryDiagram() {
  return sequenceDiagram({
    title: "Poison Isolation and Audited Redrive",
    subtitle: "A permanent contract failure becomes durable Query state; retrieving and republishing the original envelope stays external.",
    participants: [
      { title: "Kafka", subtitle: "public topic", color: colors.amber, icon: kafkaIcon },
      { title: "Query consumer", subtitle: "local decoder contract", color: colors.blue },
      { title: "Query DB", subtitle: "PostgreSQL quarantine + audit", color: colors.purple, icon: postgresIcon },
      { title: "Operator", subtitle: "authenticated recovery actor", color: colors.teal },
      { title: "Retained source", subtitle: "external immutable envelope", color: colors.amber },
    ],
    frames: [
      {
        x: 60,
        y: 350,
        width: 1510,
        height: 540,
        label: "alt — unsupported schema or payload digest conflict",
        color: colors.red,
      },
      {
        x: 55,
        y: 920,
        width: 2520,
        height: 430,
        label: "recovery — audit locally, replay from the retained source",
        color: colors.amber,
      },
    ],
    activations: [
      { participant: 1, y: 400, height: 500 },
      { participant: 2, y: 515, height: 700, fill: colors.purpleSoft, stroke: colors.purple },
      { participant: 3, y: 880, height: 350, fill: colors.tealSoft, stroke: colors.teal },
    ],
    messages: [
      { from: 0, to: 1, y: 470, label: "deliver unsupported schema or bad digest", color: colors.red },
      { from: 1, to: 2, y: 590, label: "store event metadata + quarantine reason", color: colors.red },
      { from: 2, to: 1, y: 710, label: "QUARANTINED is a durable decision", color: colors.teal },
      { from: 1, to: 0, y: 830, label: "commit offset after quarantine", color: colors.teal },
      { from: 3, to: 2, y: 1000, label: "request redrive with actor + correlationId", color: colors.purple },
      { from: 2, to: 3, y: 1100, label: "audit request recorded; payload unchanged", color: colors.teal },
      { from: 3, to: 4, y: 1200, label: "retrieve the retained original envelope", color: colors.amber },
      { from: 4, to: 0, y: 1320, label: "republish the exact immutable envelope", color: colors.amber, dashed: true },
    ],
    footer: "Redrive never edits amount, price, eventId, or digest; it reintroduces retained source evidence.",
  });
}

function correctionDiagram() {
  return sequenceDiagram({
    title: "Financial Correction Appends a Compensating Fact",
    subtitle: "Billing owns idempotent adjustment creation; Invoice preserves the original line and appends a referenced correction.",
    participants: [
      { title: "Correction API", subtitle: "authenticated command", color: colors.blue },
      { title: "Billing DB", subtitle: "PostgreSQL adjustment + outbox", color: colors.green, icon: postgresIcon },
      { title: "Billing relay", subtitle: "fenced publication", color: colors.amber },
      { title: "Kafka", subtitle: "billing.events.v1", color: colors.amber, icon: kafkaIcon },
      { title: "Invoice DB", subtitle: "PostgreSQL inbox + lines", color: colors.purple, icon: postgresIcon },
    ],
    frames: [
      {
        x: 55,
        y: 350,
        width: 2520,
        height: 740,
        label: "opt — adjustmentEventId is first seen",
        color: colors.green,
      },
      {
        x: 1600,
        y: 1120,
        width: 970,
        height: 280,
        label: "alt — same eventId + same digest",
        color: colors.teal,
      },
    ],
    activations: [
      { participant: 0, y: 400, height: 250 },
      { participant: 1, y: 400, height: 430, fill: colors.greenSoft, stroke: colors.green },
      { participant: 2, y: 710, height: 270, fill: colors.amberSoft, stroke: colors.amber },
      { participant: 4, y: 850, height: 530, fill: colors.purpleSoft, stroke: colors.purple },
    ],
    messages: [
      { from: 0, to: 1, y: 470, label: "POST negative amount + correctionOf", color: colors.blue },
      { from: 1, to: 0, y: 590, label: "dedupe by adjustmentEventId", color: colors.teal },
      { from: 0, to: 1, y: 710, label: "commit AdjustmentPosted + PENDING outbox", color: colors.green },
      { from: 2, to: 1, y: 830, label: "claim committed outbox row", color: colors.amber },
      { from: 2, to: 3, y: 950, label: "publish AdjustmentPosted", color: colors.blue },
      { from: 3, to: 4, y: 1070, label: "deliver correction with original event reference", color: colors.blue },
      { from: 4, to: 3, y: 1250, label: "append correction line; commit offset", color: colors.teal },
      { from: 3, to: 4, y: 1360, label: "DUPLICATE; original and correction stay unchanged", color: colors.teal },
    ],
    footer: "Financial history is repaired by a new fact; no published event or invoice line is rewritten.",
  });
}

function extractionDiagram() {
  return sequenceDiagram({
    title: "Staged Extraction With Route-Only Rollback",
    subtitle: "Move one authority boundary at a time, prove immutable parity, and roll traffic back without copying databases backward.",
    participants: [
      { title: "Traffic router", subtitle: "cutover and rollback", color: colors.blue },
      { title: "Legacy ledger", subtitle: "initial authority", color: colors.gray },
      { title: "Meter + Usage", subtitle: "first extracted boundary", color: colors.green },
      { title: "Billing + Invoice", subtitle: "financial materialization", color: colors.purple },
      { title: "Query + parity", subtitle: "read-side evidence", color: colors.teal },
    ],
    frames: [
      { x: 55, y: 350, width: 2520, height: 390, label: "phase 1 — extract source facts first", color: colors.green },
      { x: 55, y: 760, width: 2520, height: 430, label: "phase 2/3 — add financial and read-side consumers", color: colors.purple },
      { x: 55, y: 1210, width: 2520, height: 170, label: "alt — parity or operations fail", color: colors.red },
    ],
    activations: [
      { participant: 0, y: 400, height: 900 },
      { participant: 1, y: 400, height: 400, fill: colors.graySoft, stroke: colors.gray },
      { participant: 2, y: 500, height: 520, fill: colors.greenSoft, stroke: colors.green },
      { participant: 3, y: 790, height: 390, fill: colors.purpleSoft, stroke: colors.purple },
      { participant: 4, y: 620, height: 600, fill: colors.tealSoft, stroke: colors.teal },
    ],
    messages: [
      { from: 0, to: 1, y: 470, label: "keep legacy as the initial source of truth", color: colors.gray },
      { from: 1, to: 2, y: 580, label: "mirror price + usage inputs", color: colors.green },
      { from: 2, to: 4, y: 690, label: "compare price evidence + accepted usage IDs", color: colors.teal },
      { from: 0, to: 2, y: 870, label: "cut over Meter + Usage after parity", color: colors.blue },
      { from: 2, to: 3, y: 970, label: "publish immutable source-event IDs", color: colors.purple },
      { from: 3, to: 4, y: 1070, label: "compare rated totals + invoice references", color: colors.teal },
      { from: 0, to: 3, y: 1170, label: "route downstream only after outbox drain", color: colors.blue },
      { from: 0, to: 1, y: 1320, label: "rollback routing; retain all durable history", color: colors.red },
    ],
    footer: "Never copy a service database backward or rewrite published financial history during rollback.",
  });
}

const diagrams = {
  architecture: architectureDiagram,
  "outbox-inbox-state": stateDiagram,
  delivery: deliveryDiagram,
  "poison-recovery": poisonRecoveryDiagram,
  correction: correctionDiagram,
  extraction: extractionDiagram,
};

const slugOptionIndex = process.argv.indexOf("--slug");
const selectedSlugs =
  slugOptionIndex >= 0
    ? [process.argv[slugOptionIndex + 1]]
    : Object.keys(diagrams);

for (const slug of selectedSlugs) {
  const factory = diagrams[slug];
  if (!factory) throw new Error(`unknown diagram slug: ${slug}`);
  fs.mkdirSync(outputDirectory, { recursive: true });
  const source = path.join(outputDirectory, `${prefix}-${slug}-01.svg`);
  const target = path.join(outputDirectory, `${prefix}-${slug}-01.png`);
  fs.writeFileSync(source, factory().replace(/[ \t]+$/gm, ""));
  const result = spawnSync("cairosvg", [source, "-o", target, "-s", "2"], { stdio: "inherit" });
  if (result.status !== 0) process.exit(result.status ?? 1);
  console.log(`generated ${slug}: ${path.relative(root, source)} + ${path.relative(root, target)}`);
}
