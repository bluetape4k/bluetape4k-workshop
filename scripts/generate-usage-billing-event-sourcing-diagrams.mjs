#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const output = path.join(root, "docs/images/readme-diagrams");
const check = process.argv.includes("--check");
const onlyIndex = process.argv.indexOf("--only");
const only = onlyIndex >= 0 ? process.argv[onlyIndex + 1] : undefined;
const edgeColors = {
  blueEdge: "#4F86C6",
  greenEdge: "#6E8F4F",
  amberEdge: "#9B7D54",
  redEdge: "#B86868",
  purpleEdge: "#8065A8",
  tealEdge: "#2D948C",
};

const diagrams = new Map([
  ["usage-billing-event-sourcing-architecture-01.svg", architecture()],
  ["usage-billing-event-sourcing-aggregate-state-01.svg", aggregateState()],
  ["usage-billing-event-sourcing-command-sequence-01.svg", commandSequence()],
  ["usage-billing-event-sourcing-replay-sequence-01.svg", replaySequence()],
  ["usage-billing-event-sourcing-projection-state-01.svg", projectionState()],
  ["usage-billing-event-sourcing-rebuild-01.svg", rebuild()],
  ["usage-billing-event-sourcing-correction-01.svg", correction()],
  ["usage-billing-event-sourcing-microservices-01.svg", microservices()],
]);

fs.mkdirSync(output, { recursive: true });
for (const [name, svg] of diagrams) {
  const cairoSafeSvg = cairoSafe(svg);
  if (only && name !== only) continue;
  const target = path.join(output, name);
  if (check) {
    if (!fs.existsSync(target) || fs.readFileSync(target, "utf8") !== cairoSafeSvg) {
      throw new Error(`diagram is stale: ${name}`);
    }
    console.log(`checked ${name}`);
  } else {
    fs.writeFileSync(target, cairoSafeSvg);
    console.log(`generated ${name}`);
  }
}

function cairoSafe(svg) {
  return svg.replaceAll("→", "->").replaceAll("≥", ">=").replaceAll("•", ";");
}

function marker(id, color, size = 14) {
  return `<marker id="seq-${id}" viewBox="0 0 10 10" markerWidth="${size}" markerHeight="${size}" refX="9" refY="5" orient="auto" markerUnits="userSpaceOnUse"><path d="M 0 0 L 10 5 L 0 10 Z" fill="${color}" stroke="${color}" stroke-dasharray="none"/></marker>`;
}

function defs() {
  return `<defs><filter id="shadow" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="4" stdDeviation="4" flood-color="#64748B" flood-opacity="0.14"/></filter>${marker("blue", "#4F86C6")}${marker("green", "#6E8F4F")}${marker("amber", "#9B7D54")}${marker("red", "#B86868")}${marker("purple", "#8065A8")}${marker("teal", "#2D948C")}<style>
  .bg{fill:#F8FAFC}.frame{fill:#FFF;stroke:#CAD6DF;stroke-width:2}.alt{fill:none;stroke:#DFC69A;stroke-width:2}.zone{stroke-width:2}.blueZone{fill:#EFF6FF;stroke:#A8C7EA}.greenZone{fill:#F0F8F0;stroke:#B8D7AE}.purpleZone{fill:#F8F3FF;stroke:#D7C3EF}.amberZone{fill:#FFF7E8;stroke:#DFC69A}.title{font-family:"Architects Daughter";font-size:40px;fill:#263238}.subtitle,.body,.small,.msg,.footer{font-family:"Comic Mono";fill:#3E4C59}.subtitle{font-size:17px}.zoneTitle{font-family:"Architects Daughter";font-size:25px;fill:#344154}.cardTitle,.participant{font-family:"Architects Daughter";font-size:22px;fill:#16202A}.body{font-size:15px}.small{font-size:13px;fill:#5B6975}.footer{font-size:14px;fill:#60727D}.card{fill:#FFF;stroke:#9FB0BC;stroke-width:2}.blueCard{fill:#EFF6FF;stroke:#4F86C6}.greenCard{fill:#F0F8F0;stroke:#6E8F4F}.amberCard{fill:#FFF7E8;stroke:#9B7D54}.redCard{fill:#FFF0F0;stroke:#B86868}.purpleCard{fill:#F8F3FF;stroke:#8065A8}.edgePath{fill:none;stroke-width:3;stroke-linecap:round;stroke-linejoin:round}.blueEdge{stroke:#4F86C6;marker-end:url(#seq-blue)}.greenEdge{stroke:#6E8F4F;marker-end:url(#seq-green)}.amberEdge{stroke:#9B7D54;marker-end:url(#seq-amber)}.redEdge{stroke:#B86868;marker-end:url(#seq-red)}.purpleEdge{stroke:#8065A8;marker-end:url(#seq-purple)}.tealEdge{stroke:#2D948C;marker-end:url(#seq-teal)}.dashed{stroke-dasharray:9 7}.lifeline{stroke:#9AAAB1;stroke-width:2;stroke-dasharray:7 8}.activation{fill:#EAF6EF;stroke:#6F9278;stroke-width:1.5}.labelPill{fill:#FFF;stroke:#D7E0E4;stroke-width:1.5}.badge{font-family:"Comic Mono";font-size:13px;font-weight:700}.frameLabel{font-family:"Architects Daughter";font-size:18px;fill:#8B6A3E}
</style></defs>`;
}

function start(title, subtitle, width = 1600, height = 950) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-labelledby="title desc"><title id="title">${title}</title><desc id="desc">${subtitle}</desc>${defs()}<rect class="bg" width="${width}" height="${height}"/><rect class="frame" x="28" y="24" width="${width - 56}" height="${height - 48}" rx="22"/><text class="title" x="${width / 2}" y="66" text-anchor="middle">${title}</text><text class="subtitle" x="${width / 2}" y="98" text-anchor="middle">${subtitle}</text>`;
}

function card(id, x, y, w, h, title, lines, css = "card") {
  const text = lines.map((line, index) => `<text class="body" x="${x + w / 2}" y="${y + 61 + index * 22}" text-anchor="middle">${line}</text>`).join("");
  return `<g class="node" data-node="${id}" filter="url(#shadow)"><rect class="card ${css}" x="${x}" y="${y}" width="${w}" height="${h}" rx="15"/><text class="cardTitle" x="${x + w / 2}" y="${y + 33}" text-anchor="middle">${title}</text>${text}</g>`;
}

function connectorArrow(d) {
  const tokens = d.match(/[MLQ]|-?\d+(?:\.\d+)?/g) || [];
  let index = 0;
  let current = { x: 0, y: 0 };
  let tangentStart = current;

  while (index < tokens.length) {
    const command = tokens[index++];
    if (command === "M" || command === "L") {
      tangentStart = current;
      current = { x: Number(tokens[index++]), y: Number(tokens[index++]) };
    } else if (command === "Q") {
      const control = { x: Number(tokens[index++]), y: Number(tokens[index++]) };
      tangentStart = control;
      current = { x: Number(tokens[index++]), y: Number(tokens[index++]) };
    } else {
      throw new Error(`unsupported connector command in ${d}`);
    }
  }

  const dx = current.x - tangentStart.x;
  const dy = current.y - tangentStart.y;
  const halfWidth = 8;
  const depth = 14;
  if (Math.abs(dx) >= Math.abs(dy)) {
    if (dx >= 0) return { direction: "right", points: `${current.x},${current.y} ${current.x - depth},${current.y - halfWidth} ${current.x - depth},${current.y + halfWidth}` };
    return { direction: "left", points: `${current.x},${current.y} ${current.x + depth},${current.y - halfWidth} ${current.x + depth},${current.y + halfWidth}` };
  }
  if (dy >= 0) return { direction: "down", points: `${current.x},${current.y} ${current.x - halfWidth},${current.y - depth} ${current.x + halfWidth},${current.y - depth}` };
  return { direction: "up", points: `${current.x},${current.y} ${current.x - halfWidth},${current.y + depth} ${current.x + halfWidth},${current.y + depth}` };
}

function edge(id, label, css, d) {
  const color = css.split(" ").map((className) => edgeColors[className]).find(Boolean);
  if (!color) throw new Error(`missing edge color for ${id}: ${css}`);
  const arrow = connectorArrow(d);
  return `<g class="edge" data-edge="${id}" data-label="${label}"><path data-connector="${id}" class="edgePath ${css}" style="marker-end:none" d="${d}"/><polygon data-connector-head="${id}" data-arrow-direction="${arrow.direction}" points="${arrow.points}" fill="${color}" stroke="${color}" stroke-dasharray="none" style="stroke-dasharray:none"/></g>`;
}

function zone(x, y, w, h, title, css) {
  return `<rect class="zone ${css}" x="${x}" y="${y}" width="${w}" height="${h}" rx="22"/><text class="zoneTitle" x="${x + 28}" y="${y + 42}">${title}</text>`;
}

function participant(cx, title, role, bottom) {
  return `<g filter="url(#shadow)"><rect class="card" x="${cx - 125}" y="140" width="250" height="76" rx="12"/><text class="participant" x="${cx}" y="171" text-anchor="middle">${title}</text><text class="small" x="${cx}" y="196" text-anchor="middle">${role}</text></g><line class="lifeline" x1="${cx}" y1="216" x2="${cx}" y2="${bottom}"/>`;
}

function message(number, x1, x2, y, label, css) {
  const width = Math.max(250, label.length * 8.5 + 62);
  const x = Math.min(x1, x2) + Math.abs(x2 - x1) / 2 - width / 2;
  const color = css.includes("red") ? "#B86868" : css.includes("green") ? "#6E8F4F" : css.includes("amber") ? "#9B7D54" : css.includes("purple") ? "#8065A8" : css.includes("teal") ? "#2D948C" : "#4F86C6";
  return `<rect class="labelPill" x="${x}" y="${y - 42}" width="${width}" height="32" rx="16"/><circle cx="${x + 20}" cy="${y - 26}" r="12" fill="#FFF" stroke="${color}" stroke-width="2"/><text class="badge num" x="${x + 20}" y="${y - 21}" text-anchor="middle">${number}</text><text class="msg" x="${x + 40}" y="${y - 21}">${label}</text>${edge(`message-${number}`, label, css, `M${x1} ${y} L${x2} ${y}`)}`;
}

function architecture() {
  return `${start("Event-Sourced Usage Billing - Authority Map", "Commands append facts; replay and projections derive every current view.")}${zone(70,130,1460,170,"HTTP and application boundary","blueZone")}${card("tenant-api",120,205,300,75,"Tenant API",["command receipt + security"],"blueCard")}${card("commands",650,205,300,75,"Command services",["replay before append"],"greenCard")}${card("operator",1180,205,300,75,"Operator API",["rebuild, rollback, reconcile"],"amberCard")}${zone(70,340,1460,260,"Correctness authority","purpleZone")}${card("event-store",130,445,360,100,"Append-only event store",["stream version + hash chain","global position keyset"],"purpleCard")}${card("snapshot",620,445,360,100,"Optional snapshots",["validated acceleration only","corrupt means replay genesis"],"amberCard")}${card("projection",1110,445,360,100,"Projection generations",["lease + owner fencing","marker + view + checkpoint"],"greenCard")}${zone(70,640,1460,200,"Derived query and operations","greenZone")}${card("reconcile",180,720,340,90,"Reconciliation",["event replay vs ACTIVE view"],"amberCard")}${card("read-model",630,720,340,90,"Billing read model",["tenant + generation scoped"],"greenCard")}${card("health",1080,720,340,90,"Health and metrics",["lag, failure, quarantine"],"blueCard")}${edge("api-command","authenticated command","blueEdge","M420 243 L650 243")}${edge("command-store","append expected version","greenEdge","M800 280 L800 320 Q 800 330 790 330 L310 330 Q 300 330 300 340 L300 445")}${edge("operator-projection","bounded recovery","amberEdge","M1330 280 L1330 445")}${edge("store-snapshot","validated seed","amberEdge","M490 495 L620 495")}${edge("store-projection","global position feed","purpleEdge","M490 525 L520 525 Q 535 525 535 540 L535 565 Q 535 580 550 580 L1070 580 Q 1080 580 1080 570 L1080 520 Q 1080 505 1090 505 L1110 505")}${edge("projection-read","active alias","greenEdge","M1200 545 L1200 650 Q 1200 665 1185 665 L815 665 Q 800 665 800 680 L800 720")}${edge("projection-health","lag and failure","blueEdge","M1350 545 L1350 720")}${edge("store-reconcile","authoritative replay","amberEdge dashed","M300 545 L300 720")}${edge("read-reconcile","actual total","amberEdge","M630 765 L520 765")}<text class="footer" x="800" y="895" text-anchor="middle">PostgreSQL + JetBrains Exposed + ExposedJdbcRepository; no raw SQL and no mutable billing truth.</text></svg>`;
}

function aggregateState() {
  return `${start("Aggregate State Diagram", "Each aggregate owns a small invariant; cross-stream history stays append-only.")}${zone(70,130,1460,690,"Allowed transitions","greenZone")}${card("meter-empty",110,215,220,86,"Meter.Empty",["no price"],"blueCard")}${card("meter-active",430,215,250,86,"Meter.Active",["append PriceActivated"],"greenCard")}${edge("meter-register","MeterRegistered","greenEdge","M330 258 L430 258")}${card("usage-empty",820,215,220,86,"Usage.Empty",["source not seen"],"blueCard")}${card("usage-accepted",1140,215,250,86,"Usage.Accepted",["terminal identity"],"greenCard")}${edge("usage-accept","UsageAccepted","greenEdge","M1040 258 L1140 258")}${card("period-empty",110,405,220,86,"Period.Empty",["not opened"],"blueCard")}${card("period-open",410,405,220,86,"Period.Open",["accept usage"],"greenCard")}${card("period-closing",710,405,240,86,"Period.Closing",["cursor + total"],"amberCard")}${card("period-final",1030,405,250,86,"Period.Finalized",["terminal amount"],"greenCard")}${edge("period-open-edge","BillingPeriodOpened","greenEdge","M330 448 L410 448")}${edge("period-close-edge","BillingCloseStarted","amberEdge","M630 448 L710 448")}${edge("period-final-edge","BillingPeriodFinalized","greenEdge","M950 448 L1030 448")}${card("invoice-empty",110,605,220,86,"Invoice.Empty",["not issued"],"blueCard")}${card("invoice-issued",430,605,250,86,"Invoice.Issued",["terminal snapshot"],"greenCard")}${edge("invoice-issue","InvoiceIssued","greenEdge","M330 648 L430 648")}${card("adjust-empty",820,605,220,86,"Adjustment.Empty",["no correction"],"blueCard")}${card("adjust-posted",1140,605,250,86,"Adjustment.Posted",["DEBIT or CREDIT"],"amberCard")}${edge("adjust-post","AdjustmentPosted","amberEdge","M1040 648 L1140 648")}<rect class="frame" x="190" y="850" width="1220" height="48" rx="13"/><text class="body" x="800" y="881" text-anchor="middle">Forbidden: reopen finalized period, mutate invoice, overwrite usage, or edit an adjustment.</text></svg>`;
}

function commandSequence() {
  return `${start("Command Append Sequence", "Fenced idempotency and optimistic stream versioning make retries safe.",1600,1050)}${participant(170,"Client","tenant caller",960)}${participant(485,"Controller","security + receipt",960)}${participant(800,"Command service","replay invariant",960)}${participant(1115,"Event store","Exposed repository",960)}${participant(1430,"PostgreSQL","durable authority",960)}<rect class="activation" x="477" y="300" width="16" height="530" rx="6"/><rect class="activation" x="792" y="440" width="16" height="240" rx="6"/><rect class="activation" x="1107" y="500" width="16" height="270" rx="6"/><rect class="activation" x="1422" y="355" width="16" height="475" rx="6"/>${message(1,170,485,330,"POST command + Idempotency-Key","blueEdge")}${message(2,485,1430,390,"insert owner or inspect receipt","purpleEdge")}${message(3,485,800,455,"replay aggregate and validate","greenEdge")}${message(4,800,1115,520,"append expected stream version","greenEdge")}${message(5,1115,1430,585,"lock head, hash, append event","purpleEdge")}${message(6,1430,1115,650,"global position + new version","tealEdge")}${message(7,485,1430,720,"terminal response CAS by owner","purpleEdge")}${message(8,485,170,795,"201 or exact stored replay","tealEdge")}<rect class="alt" x="360" y="850" width="1080" height="72" rx="14"/><text class="frameLabel" x="385" y="878">alt conflict</text><text class="small" x="385" y="906">same key + different fingerprint → 409; stale receipt owner cannot commit a terminal response.</text><text class="footer" x="800" y="990" text-anchor="middle">The receipt and domain append share the intended transaction boundary; retries never invent a second fact.</text></svg>`;
}

function replaySequence() {
  return `${start("Deterministic Replay Sequence", "Hash verification precedes upcast and reduce; snapshots may accelerate but never own truth.",1600,1050)}${participant(170,"Use case","load aggregate",960)}${participant(485,"Snapshot repo","optional seed",960)}${participant(800,"Event store","ordered stream",960)}${participant(1115,"Codec registry","upcast + decode",960)}${participant(1430,"Reducer","pure state fold",960)}<rect class="activation" x="162" y="300" width="16" height="540" rx="6"/><rect class="activation" x="477" y="350" width="16" height="130" rx="6"/><rect class="activation" x="792" y="490" width="16" height="250" rx="6"/><rect class="activation" x="1107" y="610" width="16" height="160" rx="6"/><rect class="activation" x="1422" y="680" width="16" height="160" rx="6"/>${message(1,170,485,330,"load latest reducer-version snapshot","amberEdge")}${message(2,485,170,395,"seed or absent / corrupt","tealEdge")}${message(3,170,800,500,"load stream after snapshot version","blueEdge")}${message(4,800,170,565,"events ordered by streamVersion","tealEdge")}${message(5,170,800,630,"verify previousHash + canonical hash","redEdge")}${message(6,170,1115,695,"decode schema version through upcasters","purpleEdge")}${message(7,1115,1430,760,"evolve immutable state","greenEdge")}${message(8,1430,170,825,"same state, version, last hash","tealEdge")}<rect class="alt" x="350" y="875" width="1100" height="60" rx="14"/><text class="frameLabel" x="375" y="902">loop per event</text><text class="small" x="535" y="902">tamper or broken schema chain fails closed; invalid snapshot restarts from genesis.</text><text class="footer" x="800" y="990" text-anchor="middle">Original stored payload and hash remain unchanged even when an older schema is upcast for today&apos;s reducer.</text></svg>`;
}

function projectionState() {
  return `${start("Projection Generation State Diagram", "Shadow rebuilds fail in isolation and switch only after a fenced catch-up.")}${zone(70,140,1460,660,"Generation lifecycle","purpleZone")}${card("active",100,350,260,100,"ACTIVE",["serves queries","one alias target"],"greenCard")}${card("building",480,350,280,100,"BUILDING",["private read model","catch up to watermark"],"blueCard")}${card("failed",880,350,240,100,"FAILED",["poison quarantined","never serves reads"],"redCard")}${card("retired",1260,350,240,100,"RETIRED",["kept for rollback","not written"],"amberCard")}${edge("begin-rebuild","operator begins N+1","blueEdge","M360 382 L480 382")}<text class="small" x="420" y="370" text-anchor="middle">begin N+1</text>${edge("building-active","checkpoint ≥ watermark + fenced alias","greenEdge","M480 425 L360 425")}<text class="small" x="420" y="447" text-anchor="middle">fenced switch</text>${edge("building-failed","decode or handler failure","redEdge","M760 400 L880 400")}<text class="small" x="820" y="388" text-anchor="middle">handler failure</text>${edge("active-retired","successful switch retires N","amberEdge","M310 450 L310 515 Q 310 530 325 530 L1380 530 Q 1395 530 1395 515 L1395 450")}<text class="small" x="850" y="555" text-anchor="middle">successful switch retires N</text>${edge("retired-active","conditional rollback","purpleEdge","M1365 350 L1365 305 Q 1365 290 1350 290 L230 290 Q 215 290 215 305 L215 350")}<text class="small" x="800" y="280" text-anchor="middle">conditional rollback</text><rect class="frame" x="220" y="835" width="1160" height="54" rx="14"/><text class="body" x="800" y="869" text-anchor="middle">Forbidden: FAILED → ACTIVE, unfenced checkpoint write, partial alias switch, and event skipping.</text></svg>`;
}

function rebuild() {
  return `${start("Online Projection Rebuild", "High-watermark capture, shadow catch-up, conditional switch, and bounded rollback.")}${zone(70,140,1460,650,"Safe online rebuild","blueZone")}${card("capture",100,300,240,100,"1. Capture",["event-store watermark","ACTIVE generation N"],"blueCard")}${card("create",400,300,240,100,"2. Create N+1",["BUILDING tables","isolated writes"],"purpleCard")}${card("catchup",700,300,240,100,"3. Catch up",["global position pages","lease + fencing"],"greenCard")}${card("switch",1000,300,240,100,"4. Switch",["checkpoint ≥ watermark","conditional alias CAS"],"amberCard")}${card("observe",1300,300,240,100,"5. Observe",["lag + reconciliation","rollback window"],"greenCard")}${edge("capture-create","freeze target","blueEdge","M340 350 L400 350")}${edge("create-catchup","private generation","purpleEdge","M640 350 L700 350")}${edge("catchup-switch","fenced ownership","greenEdge","M940 350 L1000 350")}${edge("switch-observe","ACTIVE N+1","amberEdge","M1240 350 L1300 350")}${card("events",180,570,400,100,"Append-only event store",["new events continue during rebuild"],"purpleCard")}${card("old-view",700,570,300,100,"Generation N",["serves until switch"],"greenCard")}${card("new-view",1120,570,300,100,"Generation N+1",["shadow then ACTIVE"],"blueCard")}${edge("events-new","repeat pages to moving head","purpleEdge","M500 670 L500 710 Q 500 725 515 725 L1255 725 Q 1270 725 1270 710 L1270 670")}<text class="small" x="885" y="750" text-anchor="middle">repeat pages to moving head</text>${edge("old-new","atomic alias only","amberEdge dashed","M1000 620 L1120 620")}<text class="small" x="1060" y="608" text-anchor="middle">alias CAS</text><text class="footer" x="800" y="860" text-anchor="middle">A failed BUILDING generation leaves the healthy ACTIVE view untouched; rollback targets a retained generation.</text></svg>`;
}

function correction() {
  return `${start("Billing Correction Without History Rewrite", "Late facts add debit or credit evidence; issued history remains immutable.")}${zone(70,140,1460,650,"Append-only correction flow","amberZone")}${card("original",110,280,300,110,"Original rated usage",["UsageRated + provenance","never updated"],"purpleCard")}${card("finding",520,280,300,110,"Operator finding",["late usage or overcharge","digest + observed position"],"amberCard")}${card("adjust",930,280,300,110,"Adjustment stream",["DEBIT or CREDIT","reason + source link"],"blueCard")}${card("view",1240,550,270,110,"ACTIVE projection",["original +/- adjustment","generation scoped"],"greenCard")}${card("reconcile",690,550,320,110,"Reconciliation",["replay expected total","compare provenance"],"amberCard")}${edge("original-finding","inspect immutable evidence","amberEdge dashed","M410 335 L520 335")}${edge("finding-adjust","digest still current","blueEdge","M820 335 L930 335")}${edge("original-view","project debit","greenEdge","M260 390 L260 685 Q 260 700 275 700 L1360 700 Q 1375 700 1375 685 L1375 660")}${edge("adjust-view","project signed delta","greenEdge","M1080 390 L1080 480 Q 1080 495 1095 495 L1355 495 Q 1370 495 1370 550")}${edge("view-reconcile","actual total","amberEdge","M1240 605 L1010 605")}${edge("original-reconcile","replay expected","purpleEdge","M350 390 L350 590 Q 350 605 365 605 L690 605")}<text class="footer" x="800" y="860" text-anchor="middle">Repair never overwrites a read-model row: append evidence or rebuild a clean projection generation.</text></svg>`;
}

function microservices() {
  return `${start("Microservice Extraction Guide", "Separate database ownership and versioned events replace shared tables; no XA and no broker exactly-once claim.")}${zone(60,135,1480,580,"Service-owned authority","greenZone")}${card("meter",90,235,250,110,"Meter Service",["meter + price streams","own PostgreSQL"],"blueCard")}${card("usage",390,235,250,110,"Usage Service",["usage streams + inbox","own PostgreSQL"],"greenCard")}${card("billing",690,235,250,110,"Billing Service",["period + rating streams","own PostgreSQL"],"purpleCard")}${card("invoice",990,235,250,110,"Invoice Service",["invoice + adjustment","own PostgreSQL"],"amberCard")}${card("projection",1290,235,220,110,"Query Service",["generation views","rebuildable DB"],"greenCard")}${card("kafka",470,505,660,105,"Kafka event transport",["transactional outbox → at-least-once → tenant/event inbox dedup","schema compatibility + replayable provenance"],"purpleCard")}${edge("meter-kafka","PriceActivated","blueEdge","M215 345 L215 530 Q 215 545 230 545 L470 545")}${edge("usage-kafka","UsageAccepted","greenEdge","M515 345 L515 505")}${edge("billing-kafka","UsageRated / Finalized","purpleEdge","M815 345 L815 505")}${edge("invoice-kafka","InvoiceIssued / Adjustment","amberEdge","M1115 345 L1115 450 Q 1115 465 1100 465 L980 465 Q 965 465 965 505")}${edge("kafka-projection","consume with inbox dedup","greenEdge","M1130 555 L1240 555 Q 1255 555 1255 390 Q 1255 375 1270 375 L1385 375 Q 1400 375 1400 365 L1400 345")}<rect class="frame" x="180" y="755" width="1240" height="76" rx="14"/><text class="body" x="800" y="785" text-anchor="middle">No shared database • no distributed transaction • no claim of end-to-end exactly-once</text><text class="small" x="800" y="813" text-anchor="middle">Each service commits domain event + outbox locally; consumers make effects idempotent with inbox receipts.</text><text class="footer" x="800" y="880" text-anchor="middle">Follow-up boundary and failure analysis: GitHub issue #555.</text></svg>`;
}
