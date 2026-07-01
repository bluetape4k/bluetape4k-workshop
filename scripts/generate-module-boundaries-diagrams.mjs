#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
fs.mkdirSync(outDir, { recursive: true });

const architecturePath = path.join(outDir, "spring-modulith-module-boundaries-readme-architecture-01.svg");
const sequencePath = path.join(outDir, "spring-modulith-module-boundaries-readme-sequence-01.svg");

fs.writeFileSync(architecturePath, architectureSvg());
fs.writeFileSync(sequencePath, sequenceSvg());
render(architecturePath);
render(sequencePath);

console.log(`generated ${rel(architecturePath)}`);
console.log(`generated ${rel(sequencePath)}`);

function rel(file) {
  return path.relative(root, file).replaceAll(path.sep, "/");
}

function render(svgFile) {
  const pngFile = svgFile.replace(/\.svg$/, ".png");
  execFileSync("rsvg-convert", ["--keep-aspect-ratio", "-f", "png", "-o", pngFile, svgFile], {
    stdio: "pipe",
  });
}

function architectureSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1700" height="1160" viewBox="0 0 1700 1160" role="img" aria-labelledby="title desc">
  <title id="title">Spring Modulith Module Boundary Architecture</title>
  <desc id="desc">Catalog exports a named API, ordering exports events, payment and notification react to those events, and a test fixture proves direct internal imports are rejected.</desc>
  <defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="5" stdDeviation="5" flood-color="#64748B" flood-opacity="0.12"/></filter>
    <style>
      .bg{fill:#F8FAFC}.title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.body,.small,.legendText,.footer{font-family:"Comic Mono";fill:#3B4A54}.subtitle{font-size:18px}.layerTitle{font-family:"Architects Daughter";font-size:25px;fill:#344154}.cardTitle{font-family:"Architects Daughter";font-size:23px;fill:#16202A}.body{font-size:15px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.layer{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:2}.card{stroke-width:2.2}.blue{fill:#EFF6FF;stroke:#4F86C6}.green{fill:#F0F8F0;stroke:#6E8F4F}.amber{fill:#FFF7E8;stroke:#9B7D54}.red{fill:#FFF0F0;stroke:#B86868}.violet{fill:#F6F3FF;stroke:#7E6AAE}.connector{fill:none;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round}.allowed{stroke:#4F86C6;marker-end:url(#arrow-allowed)}.event{stroke:#6E8F4F;marker-end:url(#arrow-event)}.rejected{stroke:#B86868;stroke-dasharray:12 8;marker-end:url(#arrow-rejected)}.legendBox{fill:#FFFFFF;stroke:#CAD6DF;stroke-width:1.8}.legendText{font-size:14px}
    </style>
    ${marker("arrow-allowed", "#4F86C6")}
    ${marker("arrow-event", "#6E8F4F")}
    ${marker("arrow-rejected", "#B86868")}
  </defs>
  <rect class="bg" width="1700" height="1160"/>
  <text class="title" x="850" y="66" text-anchor="middle">Spring Modulith Module Boundaries</text>
  <text class="subtitle" x="850" y="100" text-anchor="middle">Named interfaces expose contracts; internal packages stay private and are enforced by ApplicationModules.verify().</text>

  <rect class="layer" x="70" y="136" width="1560" height="166" rx="20"/>
  <text class="layerTitle" x="106" y="180">Catalog module</text>
  ${card("catalog-api", 120, 200, 360, 86, "Catalog :: api", "read-only item snapshots", "blue")}

  <rect class="layer" x="70" y="360" width="1560" height="190" rx="20"/>
  <text class="layerTitle" x="106" y="404">Ordering module</text>
  ${card("ordering-service", 120, 430, 360, 86, "OrderingService", "validates request + publishes", "green")}
  ${card("ordering-events", 620, 430, 360, 86, "Ordering :: events", "OrderPlacedEvent contract", "green")}
  ${card("ordering-internal", 1120, 430, 360, 86, "Ordering internal", "private generator/repository", "violet")}

  <rect class="layer" x="70" y="608" width="1560" height="186" rx="20"/>
  <text class="layerTitle" x="106" y="652">Event consumers</text>
  ${card("payment-module", 370, 680, 360, 86, "Payment module", "authorizes from order event", "amber")}
  ${card("notification-module", 870, 680, 360, 86, "Notification module", "enqueues customer message", "amber")}

  <rect class="layer" x="70" y="850" width="1560" height="164" rx="20"/>
  <text class="layerTitle" x="106" y="894">Boundary verification</text>
  ${card("invalid-fixture", 1120, 914, 360, 86, "Invalid test fixture", "payment -> ordering.internal", "red")}

  ${edge("catalog-api-&gt;ordering-service", "ordering uses exported catalog api", "catalog-to-ordering", "allowed", "M 300 286 L 300 390 Q 300 410 300 430")}
  ${edge("ordering-service-&gt;ordering-events", "ordering exports order placed event", "ordering-to-events", "event", "M 480 473 L 620 473")}
  ${edge("ordering-events-&gt;payment-module", "payment consumes ordering events only", "events-to-payment", "event", "M 800 516 L 800 600 Q 800 620 780 620 L 570 620 Q 550 620 550 640 L 550 680")}
  ${edge("ordering-events-&gt;notification-module", "notification consumes ordering events only", "events-to-notification", "event", "M 800 516 L 800 600 Q 800 620 820 620 L 1070 620 Q 1050 620 1050 640 L 1050 680")}
  ${edge("invalid-fixture-&gt;ordering-internal", "boundary test rejects internal import", "fixture-to-internal", "rejected", "M 1300 914 L 1300 826 Q 1300 806 1300 786 L 1300 550 Q 1300 530 1300 516")}

  <rect class="legendBox" x="176" y="1052" width="1348" height="64" rx="16"/>
  ${legend(260, 1086, "#4F86C6", "allowed named interface dependency")}
  ${legend(710, 1086, "#6E8F4F", "event contract handoff")}
  ${legend(1130, 1086, "#B86868", "rejected direct internal import", true)}
  <text class="footer" x="850" y="1140" text-anchor="middle">Reader contract: changing a dashed edge into production code should fail the module-boundary test.</text>
</svg>
`;
}

function sequenceSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="1680" height="1040" viewBox="0 0 1680 1040" role="img" aria-labelledby="title desc">
  <title id="title">Spring Modulith Module Boundary Sequence</title>
  <desc id="desc">Ordering reads catalog through an exported API, publishes an order event, and payment plus notification react through the exported event contract while a direct internal import is rejected.</desc>
  <defs>
    <filter id="shadow" x="-8%" y="-8%" width="116%" height="124%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.14"/></filter>
    <style>
      .title{font-family:"Architects Daughter";font-size:42px;fill:#263238}.subtitle,.msg,.footer,.role{font-family:"Comic Mono";fill:#36464F}.subtitle{font-size:17px}.participant{font-family:"Architects Daughter";font-size:19px;fill:#1F3138}.role{font-size:12px;fill:#546A73}.msg{font-size:13px}.footer{font-size:14px;fill:#60727D}.frame{fill:#FBFCF8;stroke:#41545D;stroke-width:3}.header{fill:#FFFFFF;stroke:#546E7A;stroke-width:2}.lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8;stroke-linecap:round}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.call,.return{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.allowed{stroke:#4F86C6;marker-end:url(#seq-blue)}.event{stroke:#6E8F4F;marker-end:url(#seq-green)}.rejected{stroke:#B86868;stroke-dasharray:12 8;marker-end:url(#seq-red)}.return{stroke:#2D948C;stroke-width:2.6;stroke-dasharray:8 6;marker-end:url(#seq-teal)}.label{fill:#FFFFFF;stroke:#D7E0E4;stroke-width:1.5}.blueLabel{stroke:#9CC2D1}.greenLabel{stroke:#B6C9A4}.redLabel{stroke:#D9ABAB}.tealLabel{stroke:#9DCBC6}.badge{stroke-width:1.8}.blueBadge{fill:#EAF4F8;stroke:#4F86C6}.greenBadge{fill:#EEF7F0;stroke:#6E8F4F}.redBadge{fill:#FFF0F0;stroke:#B86868}.tealBadge{fill:#EBFAF8;stroke:#2D948C}.badgeText{font-family:"Comic Mono";font-size:12px;font-weight:700}.blueText{fill:#2F6F8E}.greenText{fill:#55783F}.redText{fill:#9D4F4F}.tealText{fill:#247C75}.alt{fill:none;stroke:#D08A39;stroke-width:2.2;stroke-dasharray:12 8}.divider{stroke:#D08A39;stroke-width:1.8;stroke-dasharray:9 7;opacity:.72}.branch{fill:#FFFFFF;stroke:#D08A39;stroke-width:1.6}.branchText{font-family:"Architects Daughter";font-size:18px;fill:#8A5A22}
    </style>
    ${marker("seq-blue", "#4F86C6")}
    ${marker("seq-green", "#6E8F4F")}
    ${marker("seq-red", "#B86868")}
    ${marker("seq-teal", "#2D948C")}
  </defs>
  <rect x="24" y="24" width="1632" height="992" rx="20" class="frame"/>
  <text x="840" y="76" text-anchor="middle" class="title">Module Boundary Sequence</text>
  <text x="840" y="108" text-anchor="middle" class="subtitle">Ordering calls only catalog :: api, then payment and notification react through ordering :: events.</text>

  ${participant(160, "Client / Test", "caller")}
  ${participant(440, "OrderingService", "ordering module")}
  ${participant(720, "Catalog :: api", "named interface")}
  ${participant(1000, "Payment", "event consumer")}
  ${participant(1280, "Notification", "event consumer")}
  ${participant(1520, "Verifier", "ApplicationModules")}

  ${lifeline(160, 228, 940)}
  ${lifeline(440, 228, 940)}
  ${lifeline(720, 228, 940)}
  ${lifeline(1000, 228, 940)}
  ${lifeline(1280, 228, 940)}
  ${lifeline(1520, 228, 940)}
  <rect class="activation" x="432" y="300" width="16" height="312" rx="6"/>
  <rect class="activation" x="712" y="380" width="16" height="88" rx="6"/>
  <rect class="activation" x="992" y="560" width="16" height="98" rx="6"/>
  <rect class="activation" x="1272" y="640" width="16" height="98" rx="6"/>
  <rect class="activation" x="1512" y="810" width="16" height="84" rx="6"/>

  ${message(1, 160, 440, 324, "placeOrder(request)", "allowed", "blue", 218, 282)}
  ${message(2, 440, 720, 404, "findItem(sku)", "allowed", "blue", 478, 362)}
  ${message(3, 720, 440, 484, "CatalogItemSnapshot", "return", "teal", 474, 442)}
  ${message(4, 440, 1000, 584, "publish OrderPlacedEvent", "event", "green", 542, 542)}
  ${message(5, 440, 1280, 664, "publish OrderPlacedEvent", "event", "green", 648, 622)}
  ${message(6, 440, 160, 744, "OrderReceipt", "return", "teal", 204, 702)}

  <rect x="78" y="775" width="1524" height="156" rx="16" class="alt"/>
  <rect x="116" y="759" width="224" height="32" rx="16" class="branch"/>
  <text x="228" y="781" text-anchor="middle" class="branchText">boundary check</text>
  <line x1="78" y1="846" x2="1602" y2="846" class="divider"/>
  ${message(7, 160, 1520, 834, "verify valid module graph", "allowed", "blue", 344, 792)}
  ${message(8, 1520, 1000, 898, "reject payment -> ordering.internal", "rejected", "red", 992, 856)}

  <rect x="96" y="958" width="1488" height="34" rx="12" fill="#FFFFFF" stroke="#CCD7DA" stroke-width="2"/>
  <text x="840" y="981" text-anchor="middle" class="footer">Reader contract: call labels stay above lines, arrowheads match line colors, and the branch body is transparent.</text>
</svg>
`;
}

function card(id, x, y, w, h, title, body, colorClass) {
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${colorClass}" x="${x}" y="${y}" width="${w}" height="${h}" rx="12"/><text class="cardTitle" x="${x + w / 2}" y="${y + 34}" text-anchor="middle">${title}</text><text class="body" x="${x + w / 2}" y="${y + 60}" text-anchor="middle">${body}</text></g>`;
}

function edge(dataEdge, label, id, edgeClass, d) {
  return `<g class="edge" data-edge="${dataEdge}" data-label="${escapeXml(label)}"><path data-connector="${id}" class="connector ${edgeClass}" d="${d}"/></g>`;
}

function marker(id, color) {
  return `<marker id="${id}" viewBox="0 0 10 10" markerWidth="16" markerHeight="16" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none" style="stroke-dasharray:none" stroke-linecap="round" stroke-linejoin="round"/></marker>`;
}

function legend(x, y, color, label, dashed = false) {
  const dash = dashed ? ` stroke-dasharray="12 8"` : "";
  return `<path d="M ${x} ${y} L ${x + 74} ${y}" fill="none" stroke="${color}" stroke-width="4" stroke-linecap="round"${dash}/><polygon points="${x + 74},${y} ${x + 58},${y - 8} ${x + 58},${y + 8}" fill="${color}"/><text class="legendText" x="${x + 92}" y="${y + 5}">${escapeXml(label)}</text>`;
}

function participant(cx, title, role) {
  const x = cx - 105;
  return `<g filter="url(#shadow)"><rect x="${x}" y="154" width="210" height="74" rx="10" class="header"/><text x="${cx}" y="184" text-anchor="middle" class="participant">${escapeXml(title)}</text><text x="${cx}" y="208" text-anchor="middle" class="role">${escapeXml(role)}</text></g>`;
}

function lifeline(x, y1, y2) {
  return `<line x1="${x}" y1="${y1}" x2="${x}" y2="${y2}" class="lifeline"/>`;
}

function message(number, x1, x2, y, text, pathClass, colorName, labelX, labelY) {
  const width = Math.max(250, text.length * 8.6 + 66);
  const arrowClass = pathClass === "return" ? "return" : `call ${pathClass}`;
  return `<rect x="${labelX}" y="${labelY}" width="${width.toFixed(1)}" height="34" rx="17" class="label labelPill ${colorName}Label"/><circle cx="${labelX + 24}" cy="${labelY + 17}" r="13" class="badge ${colorName}Badge"/><text x="${labelX + 24}" y="${labelY + 22}" text-anchor="middle" class="badgeText num ${colorName}Text">${number}</text><text x="${labelX + 48}" y="${labelY + 22}" class="msg ${colorName}Text">${escapeXml(text)}</text><path data-connector="seq-${number}" d="M ${x1} ${y} L ${x2} ${y}" class="${arrowClass}"/>`;
}

function escapeXml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
