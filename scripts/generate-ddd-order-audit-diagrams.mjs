#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
const iconPath = "/Users/debop/work/bluetape4k/bluetape4k-wiki/docs/icons/testcontainers/database/postgresql.svg";
const pgIcon = fs.readFileSync(iconPath).toString("base64");

fs.mkdirSync(outDir, { recursive: true });

const architecturePath = path.join(outDir, "spring-modulith-ddd-order-audit-readme-architecture-01.svg");
const sequencePath = path.join(outDir, "spring-modulith-ddd-order-audit-readme-sequence-01.svg");

fs.writeFileSync(architecturePath, architectureSvg(pgIcon));
fs.writeFileSync(sequencePath, sequenceSvg());

console.log(`generated ${path.relative(root, architecturePath)}`);
console.log(`generated ${path.relative(root, sequencePath)}`);

function architectureSvg(icon) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1780" height="1120" viewBox="0 0 1780 1120" role="img" aria-labelledby="title desc">
  <title id="title">DDD Order Audit Architecture</title>
  <desc id="desc">Order commands persist PostgreSQL state and Modulith publication rows in one transaction, then after-commit listeners create fulfillment and JaVers audit records.</desc>
  <defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.12"/></filter>
    <style>
      .bg{fill:#F8FAFC}.title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.body,.small,.legendText,.footer{font-family:"Comic Mono";fill:#3E4C59}.subtitle{font-size:18px}.layerTitle{font-family:"Architects Daughter";font-size:25px;fill:#344154}.cardTitle{font-family:"Architects Daughter";font-size:24px;fill:#16202A}.body{font-size:16px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.layer{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:2}.card{stroke-width:2.2}.blue{fill:#EFF6FF;stroke:#4F86C6}.green{fill:#F0F8F0;stroke:#6E8F4F}.amber{fill:#FFF7E8;stroke:#9B7D54}.red{fill:#FFF0F0;stroke:#B86868}.store{fill:#FFFAF2;stroke:#8C6F33}.connector{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.command{stroke:#4F86C6;marker-end:url(#arrow-command)}.tx{stroke:#6E8F4F;marker-end:url(#arrow-tx)}.after{stroke:#9B7D54;marker-end:url(#arrow-after)}.replay{stroke:#B86868;marker-end:url(#arrow-replay)}.legendBox{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:1.8}.legendText{font-size:14px}
    </style>
    <marker id="arrow-command" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="#4F86C6" stroke="#4F86C6" stroke-dasharray="none" style="stroke-dasharray:none"/></marker>
    <marker id="arrow-tx" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="#6E8F4F" stroke="#6E8F4F" stroke-dasharray="none" style="stroke-dasharray:none"/></marker>
    <marker id="arrow-after" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="#9B7D54" stroke="#9B7D54" stroke-dasharray="none" style="stroke-dasharray:none"/></marker>
    <marker id="arrow-replay" viewBox="0 0 10 10" markerWidth="14" markerHeight="14" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="#B86868" stroke="#B86868" stroke-dasharray="none" style="stroke-dasharray:none"/></marker>
  </defs>
  <rect class="bg" width="1780" height="1120"/>
  <text class="title" x="890" y="64" text-anchor="middle">DDD Order Audit Architecture</text>
  <text class="subtitle" x="890" y="96" text-anchor="middle">PostgreSQL owns command state and publication rows; after-commit handlers own fulfillment and JaVers history.</text>

  <rect class="layer" x="78" y="132" width="1624" height="198" rx="22"/>
  <text class="layerTitle" x="112" y="176">Command model</text>
  ${card("order-command-service", 180, 215, 360, 80, "OrderCommandService", "place / approve use cases", "blue")}
  ${card("order-aggregate", 710, 215, 360, 80, "Order aggregate", "invariants + safe events", "blue")}
  ${card("domain-events", 1240, 215, 360, 80, "Domain events", "OrderPlaced / OrderApproved", "blue")}

  <rect class="layer" x="78" y="388" width="1624" height="260" rx="22"/>
  <text class="layerTitle" x="112" y="432">Single PostgreSQL transaction</text>
  ${card("order-jpa-repository", 180, 485, 360, 82, "OrderJpaRepository", "orders + order_lines", "green")}
  ${card("event-publication-registry", 710, 485, 360, 82, "Event publication registry", "same commit as order row", "green")}
  <g class="node" data-node="postgresql-testcontainer" filter="url(#shadow)">
    <rect class="card store" x="1240" y="462" width="360" height="128" rx="16"/>
    <image data-bluetape4k-icon="postgresql" data-source="docs/icons/testcontainers/database/postgresql.svg" x="1394" y="476" width="52" height="52" href="data:image/svg+xml;base64,${icon}" preserveAspectRatio="xMidYMid meet"/>
    <text class="cardTitle" x="1420" y="552" text-anchor="middle">PostgreSQL Testcontainer</text>
    <text class="body" x="1420" y="576" text-anchor="middle">orders + publications</text>
  </g>

  <rect class="layer" x="78" y="706" width="1624" height="232" rx="22"/>
  <text class="layerTitle" x="112" y="750">After-commit side effects</text>
  ${card("fulfillment-listener", 180, 800, 360, 82, "Fulfillment listener", "reserves after approval", "amber")}
  ${card("javers-audit-service", 710, 800, 360, 82, "JaVers audit service", "afterCommit snapshots", "amber")}
  ${card("replay-controls", 1240, 800, 360, 82, "Replay controls", "FAILED row -> resubmit", "red")}

  ${edge("order-command-service-&gt;order-aggregate", "command validates aggregate", "command-to-aggregate", "command", "M 540 255 L 710 255")}
  ${edge("order-aggregate-&gt;domain-events", "aggregate emits domain events", "aggregate-to-events", "command", "M 1070 255 L 1240 255")}
  ${edge("order-command-service-&gt;order-jpa-repository", "persist order rows", "service-to-order-repo", "tx", "M 520 295 L 520 485")}
  ${edge("order-command-service-&gt;event-publication-registry", "register publication row", "service-to-publication", "tx", "M 540 275 L 640 275 Q 660 275 660 295 L 660 356 Q 660 376 680 376 L 874 376 Q 890 376 890 392 L 890 485")}
  ${edge("order-jpa-repository-&gt;event-publication-registry", "same transaction boundary", "repo-to-postgres", "tx", "M 540 526 L 710 526")}
  ${edge("event-publication-registry-&gt;postgresql-testcontainer", "publication rows stored", "registry-to-postgres", "tx", "M 1070 526 L 1240 526")}
  ${edge("event-publication-registry-&gt;fulfillment-listener", "after-commit fulfillment", "publication-to-fulfillment", "after", "M 820 567 L 820 675 Q 820 694 801 694 L 379 694 Q 360 694 360 713 L 360 800")}
  ${edge("event-publication-registry-&gt;javers-audit-service", "after-commit audit", "publication-to-audit", "after", "M 890 567 L 890 800")}
  ${edge("event-publication-registry-&gt;replay-controls", "failed publication replay", "publication-to-replay", "replay", "M 960 567 L 960 655 Q 960 674 979 674 L 1401 674 Q 1420 674 1420 693 L 1420 800")}
  ${edge("fulfillment-listener-&gt;postgresql-testcontainer", "reservation write", "fulfillment-to-postgres", "after", "M 540 841 L 620 841 Q 640 841 640 861 L 640 918 Q 640 932 654 932 L 1662 932 Q 1676 932 1676 918 L 1676 540 Q 1676 526 1662 526 L 1600 526")}

  <rect class="legendBox" x="208" y="990" width="1364" height="78" rx="16"/>
  ${legend(290, 1024, "#4F86C6", "command/event creation")}
  ${legend(610, 1024, "#6E8F4F", "same DB transaction")}
  ${legend(940, 1024, "#9B7D54", "after-commit effect")}
  ${legend(1260, 1024, "#B86868", "failed publication replay")}
  <text class="footer" x="890" y="1092" text-anchor="middle">Reader contract: order row and publication row commit together; fulfillment and JaVers happen only after commit.</text>
</svg>
`;
}

function sequenceSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1680" height="1120" viewBox="0 0 1680 1120" role="img" aria-labelledby="title desc">
  <title id="title">DDD Order Audit Sequence</title>
  <desc id="desc">Order approval persists state and publication rows, then after-commit listeners reserve fulfillment and write JaVers snapshots; failed publications can be replayed.</desc>
  <defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.14"/></filter>
    <style>
      .title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.msg,.footer,.note{font-family:"Comic Mono";fill:#36464F}.subtitle{font-size:17px}.participant{font-family:"Architects Daughter";font-size:19px;fill:#1F3138}.role{font-family:"Comic Mono";font-size:12px;fill:#546A73}.msg{font-size:13px}.footer{font-size:14px;fill:#60727D}.frame{fill:#FBFCF8;stroke:#41545D;stroke-width:3}.header{fill:#FFFFFF;stroke:#546E7A;stroke-width:2}.lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8;stroke-linecap:round}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.call,.return{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.command{stroke:#4F86C6;marker-end:url(#seq-blue)}.tx{stroke:#6E8F4F;marker-end:url(#seq-green)}.audit{stroke:#9B7D54;marker-end:url(#seq-amber)}.error{stroke:#B86868;marker-end:url(#seq-red)}.return{stroke:#2D948C;stroke-width:2.6;stroke-dasharray:8 6;marker-end:url(#seq-teal)}.label{fill:#FFFFFF;stroke:#D7E0E4;stroke-width:1.5}.blueLabel{stroke:#9CC2D1}.greenLabel{stroke:#B6C9A4}.amberLabel{stroke:#C8B38F}.redLabel{stroke:#D9ABAB}.tealLabel{stroke:#9DCBC6}.badge{stroke-width:1.8}.blueBadge{fill:#EAF4F8;stroke:#4F86C6}.greenBadge{fill:#EEF7F0;stroke:#6E8F4F}.amberBadge{fill:#FFF7E8;stroke:#9B7D54}.redBadge{fill:#FFF0F0;stroke:#B86868}.tealBadge{fill:#EBFAF8;stroke:#2D948C}.badgeText{font-family:"Comic Mono";font-size:12px;font-weight:700}.blueText{fill:#2F6F8E}.greenText{fill:#55783F}.amberText{fill:#7F6038}.redText{fill:#9D4F4F}.tealText{fill:#247C75}.alt{fill:none;stroke:#D08A39;stroke-width:2.2;stroke-dasharray:12 8}.divider{stroke:#D08A39;stroke-width:1.8;stroke-dasharray:9 7;opacity:.72}.branch{fill:#FFFFFF;stroke:#D08A39;stroke-width:1.6}.branchText{font-family:"Architects Daughter";font-size:18px;fill:#8A5A22}
    </style>
    ${seqMarker("seq-blue", "#4F86C6")}
    ${seqMarker("seq-green", "#6E8F4F")}
    ${seqMarker("seq-amber", "#9B7D54")}
    ${seqMarker("seq-red", "#B86868")}
    ${seqMarker("seq-teal", "#2D948C")}
  </defs>
  <rect x="24" y="24" width="1632" height="1072" rx="20" class="frame"/>
  <text x="840" y="76" text-anchor="middle" class="title">DDD Order Audit Sequence</text>
  <text x="840" y="108" text-anchor="middle" class="subtitle">Publication rows commit with order state; fulfillment and JaVers run after commit and can be replayed safely.</text>

  ${participant(180, "Client / Test", "command caller")}
  ${participant(500, "OrderCommandService", "transaction boundary")}
  ${participant(820, "PostgreSQL", "orders + publications")}
  ${participant(1140, "FulfillmentHandler", "after-commit listener")}
  ${participant(1460, "JaVers Audit", "in-memory history")}

  ${lifeline(180, 228, 1005)}
  ${lifeline(500, 228, 1005)}
  ${lifeline(820, 228, 1005)}
  ${lifeline(1140, 228, 1005)}
  ${lifeline(1460, 228, 1005)}
  <rect class="activation" x="492" y="300" width="16" height="360" rx="6"/>
  <rect class="activation" x="812" y="380" width="16" height="156" rx="6"/>
  <rect class="activation" x="1132" y="580" width="16" height="235" rx="6"/>
  <rect class="activation" x="1452" y="650" width="16" height="92" rx="6"/>

  ${message(1, 180, 500, 324, "approve(orderId)", "command", "blue", 242, 282)}
  ${message(2, 500, 820, 404, "save order row", "tx", "green", 548, 362)}
  ${message(3, 500, 820, 486, "register publication row", "tx", "green", 528, 444)}
  ${message(4, 820, 500, 548, "transaction commit", "return", "teal", 570, 506)}
  ${message(5, 820, 1140, 604, "deliver OrderApproved", "tx", "green", 866, 562)}
  ${message(6, 1140, 820, 684, "insert reservation", "audit", "amber", 880, 642)}
  ${message(7, 500, 1460, 754, "afterCommit JaVers snapshot", "audit", "amber", 730, 712)}
  ${message(8, 1460, 500, 834, "history + diff query", "return", "teal", 842, 792)}

  <rect x="82" y="835" width="1516" height="200" rx="16" class="alt"/>
  <rect x="116" y="819" width="168" height="32" rx="16" class="branch"/>
  <text x="200" y="841" text-anchor="middle" class="branchText">failure replay</text>
  <line x1="82" y1="935" x2="1598" y2="935" class="divider"/>
  ${message(9, 1140, 820, 900, "mark FAILED", "error", "red", 912, 854)}
  ${message(10, 180, 820, 960, "resubmit failed publication", "error", "red", 324, 914)}
  ${message(11, 820, 1140, 1010, "deliver again; idempotent reservation", "tx", "green", 830, 964)}

  <rect x="96" y="1042" width="1488" height="34" rx="12" fill="#FFFFFF" stroke="#CCD7DA" stroke-width="2"/>
  <text x="840" y="1065" text-anchor="middle" class="footer">Reader contract: labels stay above their lines; replay uses the same orderId and cannot create duplicate reservations.</text>
</svg>
`;
}

function card(id, x, y, w, h, title, body, colorClass) {
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${colorClass}" x="${x}" y="${y}" width="${w}" height="${h}" rx="16"/><text class="cardTitle" x="${x + w / 2}" y="${y + 34}" text-anchor="middle">${title}</text><text class="body" x="${x + w / 2}" y="${y + 60}" text-anchor="middle">${body}</text></g>`;
}

function edge(dataEdge, label, id, edgeClass, d) {
  return `<g class="edge" data-edge="${dataEdge}" data-label="${label}"><path data-connector="${id}" class="connector ${edgeClass}" d="${d}"/></g>`;
}

function legend(x, y, color, label) {
  return `<path d="M ${x} ${y} L ${x + 74} ${y}" fill="none" stroke="${color}" stroke-width="4" stroke-linecap="round"/><polygon points="${x + 74},${y} ${x + 58},${y - 8} ${x + 58},${y + 8}" fill="${color}"/><text class="legendText" x="${x + 92}" y="${y + 5}">${label}</text>`;
}

function seqMarker(id, color) {
  return `<marker id="${id}" viewBox="0 0 10 10" markerWidth="16" markerHeight="16" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none" style="stroke-dasharray:none" stroke-linecap="round" stroke-linejoin="round"/></marker>`;
}

function participant(cx, title, role) {
  const x = cx - 125;
  return `<g filter="url(#shadow)"><rect x="${x}" y="154" width="250" height="74" rx="10" class="header"/><text x="${cx}" y="184" text-anchor="middle" class="participant">${title}</text><text x="${cx}" y="208" text-anchor="middle" class="role">${role}</text></g>`;
}

function lifeline(x, y1, y2) {
  return `<line x1="${x}" y1="${y1}" x2="${x}" y2="${y2}" class="lifeline"/>`;
}

function message(number, x1, x2, y, text, pathClass, colorName, labelX, labelY) {
  const width = Math.max(260, text.length * 8.8 + 64);
  const dir = x2 >= x1 ? "" : " reverse";
  const arrowClass = pathClass === "return" ? "return" : `call ${pathClass}`;
  const labelClass = `${colorName}Label`;
  const badgeClass = `${colorName}Badge`;
  const textClass = `${colorName}Text`;
  return `<rect x="${labelX}" y="${labelY}" width="${width.toFixed(1)}" height="34" rx="17" class="label labelPill ${labelClass}"/><circle cx="${labelX + 24}" cy="${labelY + 17}" r="13" class="badge ${badgeClass}"/><text x="${labelX + 24}" y="${labelY + 22}" text-anchor="middle" class="badgeText num ${textClass}">${number}</text><text x="${labelX + 48}" y="${labelY + 22}" class="msg ${textClass}">${escapeXml(text)}</text><path data-connector="seq-${number}" d="M ${x1} ${y} L ${x2} ${y}" class="${arrowClass}${dir}"/>`;
}

function escapeXml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
