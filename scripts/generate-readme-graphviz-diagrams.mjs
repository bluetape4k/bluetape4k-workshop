#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const outDir = path.join(root, "docs/images/readme-diagrams");
const skippedDirs = new Set([".git", ".gradle", "build", ".worktrees", ".omx", ".omc", "docs"]);
const titleFont = "Architects Daughter";
const detailFont = "Comic Mono";
const pxPerInch = 96;
const canvasPad = 28;
const framePad = 10;
const arrowMarker = "M 1 1 L 7 4 L 1 7 Z";

function walkReadmes(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkReadmes(file, out);
    } else if (entry.name === "README.md") {
      out.push(path.dirname(file));
    }
  }
  return out;
}

function read(file) {
  return fs.readFileSync(file, "utf8");
}

function titleOf(dir) {
  const text = read(path.join(dir, "README.md"));
  return text.match(/^#\s+(.+?)\s*$/m)?.[1] || path.basename(dir).replace(/[-_]/g, " ");
}

function humanizeRel(rel) {
  const sourceText = rel === "."
    ? "repository root"
    : rel.replace(/src\/main\/kotlin\/io\/bluetape4k\/workshop\//, "");
  return sourceText
    .replace(/[/_-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function imageTitleOf(dir, rel) {
  const title = titleOf(dir);
  return /[^\x00-\x7F]/.test(title) ? humanizeRel(rel) : title;
}

function slugOf(rel) {
  return rel
    .replace(/^\.$/, "root-readme")
    .replace(/[^A-Za-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();
}

function domainOf(rel) {
  if (rel === ".") return "Workshop";
  if (rel.startsWith("spring-security/")) return "Security";
  if (rel.startsWith("spring-data/")) return "Spring Data";
  if (rel.startsWith("spring-boot/")) return "Spring Boot";
  if (rel.startsWith("spring-modulith/")) return "Modulith";
  if (rel.startsWith("spring-cloud/")) return "Spring Cloud";
  return rel.split("/")[0]
    .replace(/[-_]/g, " ")
    .replace(/\b\w/g, (m) => m.toUpperCase());
}

function wrapLabel(text, width = 22) {
  const words = text.replace(/["\\]/g, "").split(/\s+/).filter(Boolean);
  const lines = [];
  let line = "";
  for (const word of words) {
    const next = line ? `${line} ${word}` : word;
    if (next.length > width && line) {
      lines.push(line);
      line = word;
    } else {
      line = next;
    }
  }
  if (line) lines.push(line);
  return lines.slice(0, 3).join("\\n");
}

function wrapLines(text, width = 30, limit = 3) {
  return wrapLabel(text, width).split("\\n").filter(Boolean).slice(0, limit);
}

function detailItems(items, width = 30, limit = 4) {
  return uniqueLimit(items, limit)
    .flatMap((item) => wrapLines(item, width, 2))
    .slice(0, limit);
}

function node(id, title, details, fill, group = id) {
  const lines = [title, ...detailItems(details, 32, 4)];
  return {
    id,
    group,
    title,
    details: lines.slice(1),
    label: lines.join("\\n"),
    fill,
  };
}

function escapeXml(text) {
  return String(text)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function listFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skippedDirs.has(entry.name)) continue;
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      listFiles(file, out);
    } else {
      out.push(file);
    }
  }
  return out;
}

function classNameOf(file) {
  return path.basename(file).replace(/\.(kt|kts|yml|yaml|properties|sql|http)$/i, "");
}

function uniqueLimit(items, limit) {
  return [...new Set(items.filter(Boolean))].slice(0, limit);
}

function readMaybe(file) {
  try {
    return fs.readFileSync(file, "utf8");
  } catch {
    return "";
  }
}

function classifySource(file) {
  const name = classNameOf(file);
  const rel = path.relative(root, file).replaceAll(path.sep, "/");
  const text = readMaybe(file);
  const lower = `${rel}\n${text}`.toLowerCase();
  if (/springbootapplication|application\.kt|app\.kt|demoapplication|fun\s+main/.test(lower)) return "entry";
  if (/restcontroller|controller|handler|router|route|webfilter|filter|listener|consumer|producer|scheduler|gateway/.test(lower)) return "api";
  if (/service|manager|usecase|component|lock|warmer|aggregator|poller/.test(lower)) return "service";
  if (/repository|dao|table|entity|r2dbc|jdbc|exposed|redis|kafka|mongodb|elasticsearch|s3|client|store|container|testcontainer|redisson/.test(lower)) return "infra";
  if (/domain|dto|model|message|event|result|inventory|user|order|customer/.test(lower)) return "domain";
  return name.endsWith("Test") || rel.includes("/src/test/") ? "test" : "service";
}

function runtimeOf(files, buildText) {
  const corpus = `${files.map((file) => `${path.relative(root, file)}\n${readMaybe(file)}`).join("\n")}\n${buildText}`.toLowerCase();
  const runtimes = [];
  if (/postgres|r2dbc|jdbc|exposed|querydsl|jpa|h2/.test(corpus)) runtimes.push("SQL / R2DBC");
  if (/redis|redisson|lettuce/.test(corpus)) runtimes.push("Redis");
  if (/kafka/.test(corpus)) runtimes.push("Kafka");
  if (/mongodb|mongo/.test(corpus)) runtimes.push("MongoDB");
  if (/elastic/.test(corpus)) runtimes.push("Elasticsearch");
  if (/localstack|s3|aws/.test(corpus)) runtimes.push("AWS / S3");
  if (/zookeeper|curator/.test(corpus)) runtimes.push("ZooKeeper");
  if (/wiremock/.test(corpus)) runtimes.push("WireMock");
  if (/testcontainers/.test(corpus)) runtimes.push("Testcontainers");
  return uniqueLimit(runtimes, 3);
}

function bluetape4kDeps(buildText) {
  return uniqueLimit([...buildText.matchAll(/bluetape4k[-.:]([A-Za-z0-9_-]+)/g)]
    .map((match) => match[1].replace(/[-_]+/g, " ")), 3);
}

function frameworkDeps(buildText) {
  return uniqueLimit([
    ...[...buildText.matchAll(/libs\.spring\.([A-Za-z0-9_.-]+)/g)].map((match) => `Spring ${match[1].replace(/[._-]+/g, " ")}`),
    ...[...buildText.matchAll(/libs\.kafka\.([A-Za-z0-9_.-]+)/g)].map((match) => `Kafka ${match[1].replace(/[._-]+/g, " ")}`),
    ...[...buildText.matchAll(/libs\.testcontainers\.([A-Za-z0-9_.-]+)/g)].map((match) => `Testcontainers ${match[1].replace(/[._-]+/g, " ")}`),
  ], 4);
}

function childModules(dir) {
  return fs.readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !skippedDirs.has(entry.name))
    .map((entry) => path.join(dir, entry.name))
    .filter((child) => fs.existsSync(path.join(child, "build.gradle.kts")) || fs.existsSync(path.join(child, "README.md")))
    .map((child) => path.basename(child).replace(/[-_]+/g, " "))
    .slice(0, 4);
}

function sourceDetail(file, dir) {
  const rel = path.relative(dir, file).replaceAll(path.sep, "/");
  if (rel.includes("/src/test/")) return "verification";
  if (rel.includes("/controller/") || /Controller\.kt$/.test(rel)) return "HTTP adapter";
  if (rel.includes("/service/") || /Service\.kt$/.test(rel)) return "service component";
  if (rel.includes("/repository/") || /Repository\.kt$/.test(rel)) return "repository";
  if (rel.includes("/model/") || rel.includes("/domain/") || rel.includes("/schema/")) return "domain model";
  if (rel.includes("/config/") || /Config\.kt$/.test(rel)) return "configuration";
  if (/Application\.kt$|App\.kt$/.test(rel)) return "application bootstrap";
  return rel.split("/").slice(-2, -1)[0]?.replace(/[-_]+/g, " ") || "component";
}

function sourceNodes(group, files, dir, titlePrefix, fill, limit = 5) {
  return uniqueLimit(files.map((file) => file), limit).map((file, index) => {
    const className = classNameOf(file);
    const displayName = className
      .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
      .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2");
    return node(
      `${group}${index}`,
      displayName,
      [sourceDetail(file, dir)],
      fill,
      group,
    );
  });
}

function itemNodes(group, items, titlePrefix, fill, limit = 5) {
  return uniqueLimit(items, limit).map((item, index) =>
    node(`${group}${index}`, item, [titlePrefix], fill, group),
  );
}

function pushUniqueEdge(edges, from, to, label) {
  if (!from || !to || from === to) return;
  const key = `${from}->${to}`;
  if (edges.some(([existingFrom, existingTo]) => `${existingFrom}->${existingTo}` === key)) return;
  edges.push([from, to, label]);
}

function profileFor(rel) {
  const key = (() => {
    if (rel.startsWith("spring-security/")) return "security";
    if (rel.startsWith("spring-data/")) return "springData";
    if (rel.startsWith("spring-boot/")) return "springBoot";
    if (rel.startsWith("spring-modulith/")) return "modulith";
    if (rel.startsWith("spring-cloud/")) return "springCloud";
    if (rel.startsWith("image-processing/")) return "image";
    if (rel.startsWith("virtualthreads/")) return "virtualthreads";
    return rel.split("/")[0].replace(/-/g, "");
  })();
  const profiles = {
    aws: {
      subtitle: "S3 client workflow and LocalStack runtime",
      entryTitle: "Sample Bootstraps",
      apiTitle: "S3 Example Modules",
      childrenTitle: "S3 Example Modules",
      serviceTitle: "Storage Workflow",
      infraTitle: "AWS Client Layer",
      frameworkTitle: "Spring Cloud AWS",
      btTitle: "bluetape4k AWS APIs",
      runtimeTitle: "LocalStack / S3",
      layers: {
        surface: ["AWS Example Surface", "sample apps, S3 tests, module catalog"],
        service: ["Storage Workflow Layer", "bucket setup, object IO, service rules"],
        integration: ["AWS Integration Layer", "S3 clients, Spring Cloud AWS, helpers"],
        runtime: ["Local AWS Runtime", "LocalStack, S3, Testcontainers"],
      },
    },
    exposed: {
      subtitle: "HTTP services, Exposed repositories, and SQL runtime",
      entryTitle: "Web/Test Entrypoints",
      apiTitle: "MVC/WebFlux Controllers",
      serviceTitle: "Application Services",
      infraTitle: "Exposed Persistence",
      frameworkTitle: "Exposed Framework",
      btTitle: "bluetape4k Data APIs",
      runtimeTitle: "SQL Test Runtime",
      layers: {
        surface: ["Web/API Surface", "controllers, app bootstraps, HTTP tests"],
        service: ["Application Layer", "services, domain rules, transactions"],
        integration: ["Exposed Persistence Layer", "repositories, tables, mappers, helpers"],
        runtime: ["SQL Runtime", "R2DBC/JDBC, Redis, containers"],
      },
    },
    messaging: {
      subtitle: "message producers, consumers, and broker-backed flows",
      entryTitle: "Messaging App/Test Surface",
      apiTitle: "Producer / Consumer Adapters",
      serviceTitle: "Message Workflow",
      infraTitle: "Kafka Topic Layer",
      frameworkTitle: "Spring Kafka Layer",
      btTitle: "bluetape4k Kafka APIs",
      runtimeTitle: "Kafka Broker Runtime",
      layers: {
        surface: ["Messaging Surface", "apps, controllers, producer tests"],
        service: ["Message Workflow Layer", "request/reply, outbox, handlers"],
        integration: ["Broker Integration Layer", "topics, templates, serializers, helpers"],
        runtime: ["Broker Runtime", "Kafka, Testcontainers, delivery state"],
      },
    },
    graph: {
      subtitle: "graph domain model, traversal services, and graph backends",
      entryTitle: "Graph Demo Surface",
      apiTitle: "Graph Example Modules",
      childrenTitle: "Graph Example Modules",
      serviceTitle: "Graph Service Layer",
      infraTitle: "Graph Operations",
      frameworkTitle: "Graph Driver Layer",
      btTitle: "bluetape4k Graph APIs",
      runtimeTitle: "Graph Backends",
      layers: {
        surface: ["Graph Domain Surface", "sample apps, seed tests, example catalog"],
        service: ["Graph Service Layer", "domain rules, traversal use cases"],
        integration: ["Graph Operations Layer", "queries, repositories, graph helpers"],
        runtime: ["Graph Backends", "Neo4j, Memgraph, AGE, containers"],
      },
    },
    observability: {
      subtitle: "observed requests, coroutine traces, and telemetry export",
      entryTitle: "Observed App/Test Surface",
      apiTitle: "Observed Web Adapters",
      serviceTitle: "Instrumented Services",
      infraTitle: "Telemetry Configuration",
      frameworkTitle: "Micrometer/Spring Layer",
      btTitle: "bluetape4k Observability APIs",
      runtimeTitle: "Metrics / Trace Runtime",
      layers: {
        surface: ["Observed Request Surface", "controllers, tracing apps, test probes"],
        service: ["Instrumented Service Layer", "coroutines, spans, observation scopes"],
        integration: ["Telemetry Integration", "Micrometer, exporters, auto-config"],
        runtime: ["Telemetry Runtime", "metrics, traces, logs, test backends"],
      },
    },
    redis: {
      subtitle: "Redis-backed coordination, cache, and stream examples",
      entryTitle: "Redis Demo Surface",
      apiTitle: "Redis API Adapters",
      serviceTitle: "Redis Use Cases",
      infraTitle: "Redis Data Layer",
      frameworkTitle: "Redis Client Layer",
      btTitle: "bluetape4k Redis APIs",
      runtimeTitle: "Redis Runtime",
      layers: {
        surface: ["Redis Example Surface", "apps, tests, HTTP endpoints"],
        service: ["Redis Use Case Layer", "locks, cache, streams, cluster logic"],
        integration: ["Redis Integration Layer", "Lettuce, Redisson, serializers, helpers"],
        runtime: ["Redis Runtime", "standalone, cluster, containers"],
      },
    },
    ratelimit: {
      subtitle: "rate-limit policy, token buckets, and backing stores",
      entryTitle: "Rate-limit Surface",
      apiTitle: "Web Filter / Controller",
      serviceTitle: "Policy Enforcement",
      infraTitle: "Bucket Storage Layer",
      frameworkTitle: "Bucket4j Layer",
      btTitle: "bluetape4k RateLimit APIs",
      runtimeTitle: "Bucket Runtime",
      layers: {
        surface: ["Request Surface", "web filters, controllers, test clients"],
        service: ["Policy Layer", "token buckets, quota rules, throttling"],
        integration: ["Bucket Storage Layer", "Bucket4j, Redis, Caffeine, helpers"],
        runtime: ["Runtime Store", "Redis, cache, local JVM"],
      },
    },
    springData: {
      subtitle: "Spring Data repositories, reactive APIs, and backing stores",
      entryTitle: "Data Demo Surface",
      apiTitle: "Web/Data Adapters",
      serviceTitle: "Data Service Layer",
      infraTitle: "Repository Layer",
      frameworkTitle: "Spring Data Layer",
      btTitle: "bluetape4k Data APIs",
      runtimeTitle: "Database Runtime",
      layers: {
        surface: ["Data Access Surface", "apps, handlers, repository tests"],
        service: ["Data Service Layer", "query use cases, transaction boundaries"],
        integration: ["Repository Integration", "Spring Data, R2DBC, Mongo, Redis"],
        runtime: ["Database Runtime", "SQL, MongoDB, Elasticsearch, Redis"],
      },
    },
    springBoot: {
      subtitle: "Spring Boot application feature and runtime integration",
      entryTitle: "Boot App/Test Surface",
      apiTitle: "Spring Web Adapters",
      serviceTitle: "Application Feature",
      infraTitle: "Infrastructure Config",
      frameworkTitle: "Spring Boot Layer",
      btTitle: "bluetape4k Boot APIs",
      runtimeTitle: "Boot Runtime",
      layers: {
        surface: ["Spring Boot Surface", "apps, controllers, scheduled tests"],
        service: ["Feature Layer", "services, resilience, events, cache policy"],
        integration: ["Boot Integration Layer", "auto-config, clients, repositories"],
        runtime: ["Runtime Dependencies", "database, cache, messaging, containers"],
      },
    },
    security: {
      subtitle: "security filters, authentication flows, and protected endpoints",
      entryTitle: "Security Demo Surface",
      apiTitle: "Protected Endpoints",
      serviceTitle: "Security Policy Layer",
      infraTitle: "Security Infrastructure",
      frameworkTitle: "Spring Security Layer",
      btTitle: "bluetape4k Security APIs",
      runtimeTitle: "Security Runtime",
      layers: {
        surface: ["Protected Request Surface", "controllers, filters, test clients"],
        service: ["Security Policy Layer", "authentication, authorization, JWT rules"],
        integration: ["Security Integration", "Spring Security, token services, helpers"],
        runtime: ["Identity Runtime", "users, tokens, local JVM"],
      },
    },
    virtualthreads: {
      subtitle: "virtual-thread execution model and blocking boundary examples",
      entryTitle: "Virtual Thread Surface",
      apiTitle: "Threaded Web Adapters",
      serviceTitle: "Execution Model",
      infraTitle: "Blocking Boundary",
      frameworkTitle: "Thread Runtime Layer",
      btTitle: "bluetape4k Coroutine APIs",
      runtimeTitle: "JVM Thread Runtime",
      layers: {
        surface: ["Request/Test Surface", "MVC/WebFlux tests, sample endpoints"],
        service: ["Execution Layer", "virtual threads, coroutines, blocking work"],
        integration: ["Thread Boundary Layer", "dispatchers, JDBC, Tomcat, helpers"],
        runtime: ["JVM Runtime", "virtual threads, platform threads, containers"],
      },
    },
  };
  return profiles[key] || {
    subtitle: `${domainOf(rel)} example runtime and module dependencies`,
    entryTitle: "Example Entrypoints",
    apiTitle: "API / Adapter Layer",
    childrenTitle: "Child Examples",
    serviceTitle: "Application Services",
    infraTitle: "Infrastructure Layer",
    frameworkTitle: "Framework Layer",
    btTitle: "bluetape4k APIs",
    runtimeTitle: "Runtime Dependencies",
    layers: {
      surface: ["Example Surface", "entrypoints, tests, adapters"],
      service: ["Application Layer", "services, domain rules, use cases"],
      integration: ["Integration Layer", "repositories, adapters, helpers"],
      runtime: ["Runtime Layer", "external systems, containers, local JVM"],
    },
  };
}

function architectureModel(dir, rel) {
  const files = listFiles(dir);
  const ktFiles = files.filter((file) => file.endsWith(".kt"));
  const mainKt = ktFiles.filter((file) => file.includes("/src/main/"));
  const testKt = ktFiles.filter((file) => file.includes("/src/test/"));
  const buildFile = path.join(dir, "build.gradle.kts");
  const buildText = readMaybe(buildFile);
  const grouped = new Map();
  for (const file of mainKt) {
    const group = classifySource(file);
    if (!grouped.has(group)) grouped.set(group, []);
    grouped.get(group).push(file);
  }

  const children = childModules(dir);
  const runtimes = runtimeOf(files, buildText);
  const btDeps = bluetape4kDeps(buildText);
  const fwDeps = frameworkDeps(buildText);
  const title = imageTitleOf(dir, rel);
  const module = humanizeRel(rel);
  const profile = profileFor(rel);
  const sourceEvidence = uniqueLimit([
    ...mainKt.map((file) => path.relative(root, file).replaceAll(path.sep, "/")),
    ...testKt.map((file) => path.relative(root, file).replaceAll(path.sep, "/")),
    fs.existsSync(buildFile) ? path.relative(root, buildFile).replaceAll(path.sep, "/") : "",
  ], 8);

  const nodes = [];
  const entryFiles = uniqueLimit([...(grouped.get("entry") || []), ...testKt.filter((file) => /Test|IT|Spec/.test(classNameOf(file)))], 5);
  if (entryFiles.length > 0) {
    nodes.push(...sourceNodes("entry", entryFiles, dir, profile.entryTitle, "#FFF8E7", 5));
  } else {
    nodes.push(node("entry0", title, [profile.entryTitle], "#FFF8E7", "entry"));
  }

  const apiFiles = grouped.get("api") || [];
  if (apiFiles.length > 0) {
    nodes.push(...sourceNodes("api", apiFiles, dir, profile.apiTitle, "#EEF7FF", 5));
  } else if (children.length > 0) {
    nodes.push(...itemNodes("api", children, profile.childrenTitle || "child module", "#EEF7FF", 5));
  }

  const serviceFiles = [...(grouped.get("service") || []), ...(grouped.get("domain") || [])];
  if (serviceFiles.length > 0) {
    nodes.push(...sourceNodes("service", serviceFiles, dir, profile.serviceTitle, "#F1F8E9", 6));
  }

  const infraFiles = grouped.get("infra") || [];
  if (infraFiles.length > 0) {
    nodes.push(...sourceNodes("infra", infraFiles, dir, profile.infraTitle, "#F5F0FF", 5));
  } else if (fwDeps.length > 0) {
    nodes.push(...itemNodes("infra", fwDeps, profile.frameworkTitle, "#F5F0FF", 4));
  }

  if (btDeps.length > 0) {
    nodes.push(...itemNodes("bt", btDeps, profile.btTitle, "#EAF7F0", 4));
  }

  nodes.push(...itemNodes("runtime", runtimes.length ? runtimes : ["In-memory / JVM"], profile.runtimeTitle, "#FFF1F1", 4));

  const idsByGroup = (group) => nodes.filter((item) => item.group === group).map((item) => item.id);
  const edges = [];
  const firstInGroup = (group) => idsByGroup(group)[0];
  const lastInGroup = (group) => idsByGroup(group).at(-1);
  const connectGroup = (fromGroup, toGroup, label) => {
    const from = lastInGroup(fromGroup);
    const to = firstInGroup(toGroup);
    if (from && to && fromGroup === toGroup) return;
    const fromNode = from ? nodes.find((item) => item.id === from) : null;
    const toNode = to ? nodes.find((item) => item.id === to) : null;
    if (fromNode && toNode && fromNode.group === toNode.group) return;
    if (from && to) pushUniqueEdge(edges, from, to, label);
  };
  connectGroup("api", "service", "delegates");
  if (!firstInGroup("api")) connectGroup("entry", "service", "starts");
  connectGroup("service", "infra", "persists / publishes");
  if (!firstInGroup("service")) connectGroup("api", "infra", "uses");
  if (!firstInGroup("api") && !firstInGroup("service")) connectGroup("entry", "infra", "uses");

  const btIds = idsByGroup("bt");
  const infraIds = idsByGroup("infra");
  if (btIds.length > 0) {
    pushUniqueEdge(edges, btIds.at(-1), firstInGroup("runtime"), "adapts");
  } else {
    connectGroup("infra", "runtime", "backs");
    if (infraIds.length === 0) connectGroup("service", "runtime", "runs on");
    if (infraIds.length === 0 && !firstInGroup("service") && !firstInGroup("api")) connectGroup("entry", "runtime", "runs on");
  }

  return { title, domain: domainOf(rel), module, profile, nodes, edges, sourceEvidence };
}

function edgeLabel(from, to, index) {
  if (from === "entry" && to === "api") return "invokes";
  if (to === "service") return "delegates";
  if (to === "infra") return "persists / publishes";
  if (to === "bt") return "extends";
  if (to === "runtime") return index === 0 ? "runs on" : "backs";
  return index === 0 ? "calls" : "uses";
}

function dotFor({ model }) {
  return `digraph G {
  graph [
    rankdir=LR,
    bgcolor="white",
    pad="0.35",
    nodesep="0.70",
    ranksep="0.95",
    splines=ortho,
    outputorder=edgesfirst
  ];
  node [
    shape=box,
    style="rounded,filled",
    penwidth=1.4,
    color="#88A3B5",
    fillcolor="#F6FAFF",
    fontname="${titleFont}",
    fontsize=14,
    margin="0.20,0.16"
  ];
  edge [
    color="#637383",
    arrowsize=0.8,
    penwidth=1.5,
    fontname="${detailFont}",
    fontsize=11
  ];

${model.nodes.map((node) => `  ${node.id} [label="${node.label}", fillcolor="${node.fill}"];`).join("\n")}

${model.edges.map(([from, to, label]) => `  ${from} -> ${to} [label="${label}"];`).join("\n")}
}
`;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: root,
    encoding: "utf8",
    env: { ...process.env, ...options.env },
  });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed\n${result.stderr || result.stdout}`);
  }
  return result.stdout;
}

function fontFilesFor(family) {
  const output = run("fc-list", [family, "file", "family"]);
  return output
    .split(/\r?\n/)
    .filter((line) => line.toLowerCase().includes(family.toLowerCase()))
    .map((line) => line.split(":")[0])
    .filter(Boolean);
}

function fontDirsForRequiredFonts() {
  const fontFiles = [
    ...fontFilesFor(titleFont),
    ...fontFilesFor(detailFont),
  ];
  const missing = [];
  if (!fontFiles.some((file) => file.toLowerCase().includes("architect"))) missing.push(titleFont);
  if (!fontFiles.some((file) => file.toLowerCase().includes("comicmono"))) missing.push(detailFont);
  if (missing.length > 0) {
    throw new Error(`Required diagram fonts not found by fc-list: ${missing.join(", ")}`);
  }
  return [...new Set(fontFiles.map((file) => path.dirname(file)))];
}

function fontconfigEnv() {
  const dirs = fontDirsForRequiredFonts();
  const cacheDir = path.join(root, ".omx/cache/readme-diagram-fontconfig");
  fs.mkdirSync(cacheDir, { recursive: true });
  const config = `<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
${dirs.map((dir) => `  <dir>${dir}</dir>`).join("\n")}
  <cachedir>${cacheDir}</cachedir>
  <match target="pattern">
    <test qual="any" name="family"><string>${titleFont}</string></test>
    <edit name="family" mode="assign" binding="strong"><string>${titleFont}</string></edit>
  </match>
  <match target="pattern">
    <test qual="any" name="family"><string>${detailFont}</string></test>
    <edit name="family" mode="assign" binding="strong"><string>${detailFont}</string></edit>
  </match>
</fontconfig>
`;
  const file = path.join(cacheDir, "fonts.conf");
  fs.writeFileSync(file, config);

  const env = { FONTCONFIG_FILE: file };
  const titleMatch = run("fc-match", [titleFont], { env });
  const detailMatch = run("fc-match", [detailFont], { env });
  if (!titleMatch.includes("ArchitectsDaughter") && !titleMatch.includes("Architects Daughter")) {
    throw new Error(`${titleFont} resolved to fallback: ${titleMatch.trim()}`);
  }
  if (!detailMatch.includes("ComicMono") && !detailMatch.includes("Comic Mono")) {
    throw new Error(`${detailFont} resolved to fallback: ${detailMatch.trim()}`);
  }
  return env;
}

function parsePlain(plain) {
  const graph = { width: 0, height: 0 };
  const nodes = [];
  const edges = [];
  const logicalLines = [];

  for (const line of plain.trim().split(/\r?\n/)) {
    if (logicalLines.length > 0 && logicalLines.at(-1).endsWith("\\")) {
      logicalLines[logicalLines.length - 1] = `${logicalLines.at(-1).slice(0, -1)}${line}`;
    } else {
      logicalLines.push(line);
    }
  }

  for (const rawLine of logicalLines) {
    const parts = rawLine.match(/"[^"]*"|\S+/g)?.map((part) => part.replace(/^"|"$/g, "")) || [];
    if (parts[0] === "graph") {
      graph.width = Number(parts[2]);
      graph.height = Number(parts[3]);
    } else if (parts[0] === "node") {
      nodes.push({
        id: parts[1],
        cx: Number(parts[2]),
        cy: Number(parts[3]),
        width: Number(parts[4]),
        height: Number(parts[5]),
        label: parts[6],
        stroke: parts.at(-2),
        fill: parts.at(-1),
      });
    } else if (parts[0] === "edge") {
      const pointCount = Number(parts[3]);
      const points = [];
      for (let i = 0; i < pointCount; i++) {
        points.push({ x: Number(parts[4 + i * 2]), y: Number(parts[5 + i * 2]) });
      }
      const afterPoints = 4 + pointCount * 2;
      const maybeLabel = parts[afterPoints];
      const hasLabel = Number.isNaN(Number(maybeLabel));
      edges.push({
        from: parts[1],
        to: parts[2],
        points,
        label: hasLabel ? maybeLabel : "",
        labelX: hasLabel ? Number(parts[afterPoints + 1]) : undefined,
        labelY: hasLabel ? Number(parts[afterPoints + 2]) : undefined,
        color: parts.at(-1),
      });
    }
  }

  return { graph, nodes, edges };
}

function svgFromPlain(plain, model) {
  const { graph, nodes, edges } = parsePlain(plain);
  const width = Math.ceil(graph.width * pxPerInch + canvasPad * 2);
  const height = Math.ceil(graph.height * pxPerInch + canvasPad * 2);
  const x = (value) => canvasPad + value * pxPerInch;
  const y = (value) => canvasPad + (graph.height - value) * pxPerInch;
  const attrs = (items) => Object.entries(items).map(([key, value]) => `${key}="${escapeXml(value)}"`).join(" ");
  const textLines = (label) => label.split("\\n");

  const edgeSvg = edges.map((edge) => {
    const points = edge.points.map((point) => `${x(point.x).toFixed(1)},${y(point.y).toFixed(1)}`).join(" ");
    const label = edge.label
      ? `<text class="edge-label" x="${x(edge.labelX).toFixed(1)}" y="${(y(edge.labelY) - 7).toFixed(1)}" text-anchor="middle">${escapeXml(edge.label)}</text>`
      : "";
    return `<polyline class="connector" points="${points}" marker-end="url(#arrow)"/>${label}`;
  }).join("\n    ");

  const nodeSvg = nodes.map((node) => {
    const left = x(node.cx - node.width / 2);
    const top = y(node.cy + node.height / 2);
    const w = node.width * pxPerInch;
    const h = node.height * pxPerInch;
    const lines = textLines(node.label);
    const titleLineHeight = 22;
    const detailLineHeight = 16;
    const blockHeight = titleLineHeight + Math.max(0, lines.length - 1) * detailLineHeight;
    const startY = top + h / 2 - blockHeight / 2 + 17;
    const tspans = lines.map((line, index) => {
      const className = index === 0 ? "node-title" : "node-detail";
      const yPos = index === 0
        ? startY
        : startY + titleLineHeight + (index - 1) * detailLineHeight;
      return `<tspan class="${className}" x="${(left + w / 2).toFixed(1)}" y="${yPos.toFixed(1)}">${escapeXml(line)}</tspan>`;
    }).join("");
    return `<g class="node" data-node="${escapeXml(node.id)}">
      <rect ${attrs({
        x: left.toFixed(1),
        y: top.toFixed(1),
        width: w.toFixed(1),
        height: h.toFixed(1),
        rx: 15,
        fill: node.fill,
        stroke: node.stroke,
      })}/>
      <text text-anchor="middle">${tspans}</text>
    </g>`;
  }).join("\n    ");

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="Source-derived README architecture diagram">
  <metadata>${escapeXml(JSON.stringify({ sourceEvidence: model.sourceEvidence }))}</metadata>
  <defs>
    <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
      <path d="${arrowMarker}" fill="#637383"/>
    </marker>
    <style>
      .canvas { fill: #F8FBFD; }
      .frame { fill: #FFFFFF; stroke: #D5E0E8; stroke-width: 1.2; }
      .node rect { stroke-width: 1.6; }
      .node-title { font-family: "${titleFont}"; font-size: 18px; fill: #102033; }
      .node-detail { font-family: "${detailFont}"; font-size: 12px; fill: #465160; }
      .edge-label { font-family: "${detailFont}"; font-size: 13px; fill: #102033; paint-order: stroke; stroke: #FFFFFF; stroke-width: 4px; stroke-linejoin: round; }
      .connector { fill: none; stroke: #637383; stroke-width: 2; stroke-linecap: square; stroke-linejoin: round; }
    </style>
  </defs>
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="frame" x="${framePad}" y="${framePad}" width="${width - framePad * 2}" height="${height - framePad * 2}" rx="18"/>
  <g>
    ${edgeSvg}
    ${nodeSvg}
  </g>
</svg>
`;
}

function nodeById(model, id) {
  return model.nodes.find((node) => node.id === id);
}

function nodesByGroup(model, groups) {
  return model.nodes.filter((node) => groups.includes(node.group));
}

function layoutLayers(model) {
  const profileLayers = model.profile.layers;
  const bands = [
    {
      id: "surface",
      title: profileLayers.surface[0],
      detail: profileLayers.surface[1],
      fill: "#EAF3FF",
      stroke: "#9AB7D9",
      groups: ["entry", "api"],
    },
    {
      id: "service",
      title: profileLayers.service[0],
      detail: profileLayers.service[1],
      fill: "#EAF7F0",
      stroke: "#9CC7AE",
      groups: ["service"],
    },
    {
      id: "integration",
      title: profileLayers.integration[0],
      detail: profileLayers.integration[1],
      fill: "#FFF6E5",
      stroke: "#D9B978",
      groups: ["infra", "bt"],
    },
    {
      id: "runtime",
      title: profileLayers.runtime[0],
      detail: profileLayers.runtime[1],
      fill: "#F3F5F8",
      stroke: "#B5C0CB",
      groups: ["runtime"],
    },
  ];

  return bands
    .map((band) => ({
      ...band,
      nodes: nodesByGroup(model, band.groups),
    }))
    .filter((band) => band.nodes.length > 0);
}

function cardHeight(node) {
  const titleLines = wrapLines(node.title, 24, 2);
  return Math.max(92, 44 + titleLines.length * 22 + node.details.length * 18);
}

function layerCardRects(model, width) {
  const layers = layoutLayers(model);
  const left = 46;
  const bandWidth = width - left * 2;
  const labelPanelWidth = 174;
  const contentOffset = labelPanelWidth + 54;
  let y = 114;
  const gap = 18;
  const cardRects = new Map();
  const placedLayers = [];

  for (const layer of layers) {
    const columns = Math.min(3, layer.nodes.length);
    const cardGapX = 36;
    const cardGapY = 18;
    const contentWidth = bandWidth - contentOffset - 28;
    const cardWidth = Math.min(300, (contentWidth - cardGapX * (columns - 1)) / columns);
    const rows = Math.ceil(layer.nodes.length / columns);
    const rowHeights = Array.from({ length: rows }, (_, row) => {
      const rowNodes = layer.nodes.slice(row * columns, row * columns + columns);
      return Math.max(...rowNodes.map(cardHeight));
    });
    const gridHeight = rowHeights.reduce((sum, height) => sum + height, 0) + cardGapY * (rows - 1);
    const bandHeight = Math.max(132, gridHeight + 40);
    const totalCardsWidth = cardWidth * columns + cardGapX * (columns - 1);
    const cardStartX = left + contentOffset + (contentWidth - totalCardsWidth) / 2;
    const cardStartY = y + (bandHeight - gridHeight) / 2;

    layer.nodes.forEach((node, index) => {
      const row = Math.floor(index / columns);
      const column = index % columns;
      const h = cardHeight(node);
      const rowY = cardStartY + rowHeights.slice(0, row).reduce((sum, height) => sum + height, 0) + row * cardGapY;
      cardRects.set(node.id, {
        x: cardStartX + column * (cardWidth + cardGapX),
        y: rowY + (rowHeights[row] - h) / 2,
        w: cardWidth,
        h,
      });
    });

    placedLayers.push({ ...layer, x: left, y, w: bandWidth, h: bandHeight, labelPanelWidth });
    y += bandHeight + gap;
  }

  return { layers: placedLayers, cardRects, height: y + 42 };
}

function textSvg(lines, x, centerY, className, lineHeight, anchor = "middle") {
  const blockHeight = (lines.length - 1) * lineHeight;
  const startY = centerY - blockHeight / 2;
  return lines.map((line, index) =>
    `<tspan class="${className}" x="${x.toFixed(1)}" y="${(startY + index * lineHeight).toFixed(1)}">${escapeXml(line)}</tspan>`,
  ).join("");
}

function cardSvg(node, rect) {
  const titleLineHeight = 24;
  const detailLineHeight = 17;
  const titleLines = wrapLines(node.title, 24, 2);
  const allLines = [...titleLines, ...node.details];
  const blockHeight = titleLines.length * titleLineHeight + Math.max(0, node.details.length) * detailLineHeight;
  const startY = rect.y + rect.h / 2 - blockHeight / 2 + 17;
  const title = titleLines.map((line, index) =>
    `<tspan class="node-title" x="${(rect.x + rect.w / 2).toFixed(1)}" y="${(startY + index * titleLineHeight).toFixed(1)}">${escapeXml(line)}</tspan>`,
  ).join("");
  const details = node.details.map((line, index) =>
    `<tspan class="node-detail" x="${(rect.x + rect.w / 2).toFixed(1)}" y="${(startY + titleLines.length * titleLineHeight + index * detailLineHeight).toFixed(1)}">${escapeXml(line)}</tspan>`,
  ).join("");
  const dataLines = allLines.map((line) => escapeXml(line)).join(" | ");
  return `<g class="node" data-node="${escapeXml(node.id)}" data-lines="${dataLines}">
      <rect x="${rect.x.toFixed(1)}" y="${rect.y.toFixed(1)}" width="${rect.w.toFixed(1)}" height="${rect.h.toFixed(1)}" rx="14" fill="${node.fill}" stroke="#8FA6B6" filter="url(#softShadow)"/>
      <text text-anchor="middle">${title}${details}</text>
    </g>`;
}

function centerOf(rect) {
  return { x: rect.x + rect.w / 2, y: rect.y + rect.h / 2 };
}

function boundaryPoint(rect, side) {
  if (side === "top") return { x: rect.x + rect.w / 2, y: rect.y };
  if (side === "bottom") return { x: rect.x + rect.w / 2, y: rect.y + rect.h };
  if (side === "left") return { x: rect.x, y: rect.y + rect.h / 2 };
  return { x: rect.x + rect.w, y: rect.y + rect.h / 2 };
}

function pathThrough(points) {
  const normalized = [];
  for (const point of points) {
    const previous = normalized.at(-1);
    if (!previous || Math.abs(previous.x - point.x) > 0.1 || Math.abs(previous.y - point.y) > 0.1) {
      normalized.push(point);
    }
  }
  return normalized.map((point, index) => `${index === 0 ? "M" : "L"} ${point.x.toFixed(1)} ${point.y.toFixed(1)}`).join(" ");
}

function connectorSvg(model, layout) {
  const { cardRects, layers } = layout;
  const layerByNode = new Map();
  const layerById = new Map();
  for (const layer of layers) {
    layerById.set(layer.id, layer);
    for (const node of layer.nodes) layerByNode.set(node.id, layer.id);
  }
  const edges = model.edges
    .filter(([from, to]) => cardRects.has(from) && cardRects.has(to));
  const colorByTarget = {
    api: "#4F7DB5",
    service: "#4A9667",
    infra: "#B18435",
    bt: "#3E927D",
    runtime: "#B85E63",
  };

  return edges.map(([from, to, label], index) => {
    const sourceNode = nodeById(model, from);
    const targetNode = nodeById(model, to);
    const source = cardRects.get(from);
    const target = cardRects.get(to);
    const sourceCenter = centerOf(source);
    const targetCenter = centerOf(target);
    const sameLayer = layerByNode.get(from) === layerByNode.get(to);
    let route;
    if (sameLayer) {
      const layer = layerById.get(layerByNode.get(from));
      const alignedCenters = Math.abs(sourceCenter.y - targetCenter.y) <= 18;
      if (alignedCenters) {
        const sourceSide = sourceCenter.x <= targetCenter.x ? "right" : "left";
        const targetSide = sourceSide === "right" ? "left" : "right";
        const start = boundaryPoint(source, sourceSide);
        const end = boundaryPoint(target, targetSide);
        const midX = start.x + (end.x - start.x) / 2;
        route = [
          start,
          { x: midX, y: start.y },
          { x: midX, y: end.y },
          end,
        ];
      } else {
        const start = boundaryPoint(source, "top");
        const end = boundaryPoint(target, "top");
        const laneY = Math.max(layer.y + 28, Math.min(source.y, target.y) - 24);
        route = [
          start,
          { x: start.x, y: laneY },
          { x: end.x, y: laneY },
          end,
        ];
      }
    } else {
      const start = boundaryPoint(source, "bottom");
      const end = boundaryPoint(target, "top");
      const midY = start.y + (end.y - start.y) / 2;
      route = [
        start,
        { x: start.x, y: midY },
        { x: end.x, y: midY },
        end,
      ];
    }
    const targetGroup = targetNode?.group || to;
    const color = colorByTarget[targetGroup] || "#637383";
    return `<g class="edge" data-edge="${escapeXml(`${from}->${to}`)}" data-label="${escapeXml(label)}">
      <path d="${pathThrough(route)}" fill="none" stroke="${color}" stroke-width="2.1" stroke-linecap="square" stroke-linejoin="round" marker-end="url(#arrow-${targetGroup})"/>
    </g>`;
  }).join("\n    ");
}

function layeredSvg(model) {
  const width = 1320;
  const layout = layerCardRects(model, width);
  const height = Math.max(660, layout.height);
  const frame = { x: 16, y: 16, w: width - 32, h: height - 32 };
  const subtitle = model.profile.subtitle;
  const metadata = {
    sourceEvidence: model.sourceEvidence,
    rendering: "layered-readme-architecture",
    visualBaseline: "graph module README architecture diagrams",
  };

  const markerDefs = ["api", "service", "infra", "bt", "runtime"].map((id) => {
    const color = { api: "#4F7DB5", service: "#4A9667", infra: "#B18435", bt: "#3E927D", runtime: "#B85E63" }[id] || "#637383";
    return `<marker id="arrow-${id}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
      <path d="${arrowMarker}" fill="${color}"/>
    </marker>`;
  }).join("\n    ");

  const layerSvg = layout.layers.map((layer) => {
    const labelLines = wrapLines(layer.title, 17, 2);
    const detailLines = wrapLines(layer.detail, 19, 3);
    const labelX = layer.x + layer.labelPanelWidth / 2 + 18;
    return `<g class="layer" data-layer="${escapeXml(layer.id)}">
      <rect x="${layer.x.toFixed(1)}" y="${layer.y.toFixed(1)}" width="${layer.w.toFixed(1)}" height="${layer.h.toFixed(1)}" rx="18" fill="${layer.fill}" stroke="${layer.stroke}"/>
      <rect x="${(layer.x + 18).toFixed(1)}" y="${(layer.y + 20).toFixed(1)}" width="${layer.labelPanelWidth}" height="${(layer.h - 40).toFixed(1)}" rx="14" fill="#FFFFFF" stroke="${layer.stroke}" opacity="0.92"/>
      <text text-anchor="middle">${textSvg(labelLines, labelX, layer.y + layer.h / 2 - 16, "layer-title", 23)}</text>
      <text text-anchor="middle">${textSvg(detailLines, labelX, layer.y + layer.h / 2 + 30, "layer-detail", 16)}</text>
    </g>`;
  }).join("\n    ");

  const nodeSvg = layout.layers.flatMap((layer) =>
    layer.nodes.map((node) => cardSvg(node, layout.cardRects.get(node.id))),
  ).join("\n    ");
  const edgeSvg = connectorSvg(model, layout);
  const diagramBody = [layerSvg, edgeSvg, nodeSvg].filter((part) => part.trim()).join("\n    ");

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escapeXml(model.title)} architecture diagram">
  <metadata>${escapeXml(JSON.stringify(metadata))}</metadata>
  <defs>
    <filter id="softShadow" x="-8%" y="-8%" width="116%" height="124%">
      <feDropShadow dx="0" dy="3" stdDeviation="3" flood-color="#7890A5" flood-opacity="0.18"/>
    </filter>
    ${markerDefs}
    <style>
      .canvas { fill: #F8FBFD; }
      .frame { fill: #FFFFFF; stroke: #D5E0E8; stroke-width: 1.2; }
      .diagram-title { font-family: "${titleFont}"; font-size: 31px; fill: #102033; }
      .diagram-subtitle { font-family: "${detailFont}"; font-size: 14px; fill: #51606F; }
      .layer-title { font-family: "${titleFont}"; font-size: 18px; fill: #102033; }
      .layer-detail { font-family: "${detailFont}"; font-size: 11px; fill: #536170; }
      .node rect { stroke-width: 1.5; }
      .node-title { font-family: "${titleFont}"; font-size: 18px; fill: #102033; }
      .node-detail { font-family: "${detailFont}"; font-size: 12px; fill: #465160; }
    </style>
  </defs>
  <rect class="canvas" width="${width}" height="${height}"/>
  <rect class="frame" x="${frame.x}" y="${frame.y}" width="${frame.w}" height="${frame.h}" rx="22"/>
  <text class="diagram-title" x="${(width / 2).toFixed(1)}" y="58" text-anchor="middle">${escapeXml(model.title)}</text>
  <text class="diagram-subtitle" x="${(width / 2).toFixed(1)}" y="84" text-anchor="middle">${escapeXml(subtitle)}</text>
  <g>
    ${diagramBody}
  </g>
</svg>
`;
}

fs.mkdirSync(outDir, { recursive: true });
const renderEnv = fontconfigEnv();

const dirs = walkReadmes(root)
  .filter((dir) => dir !== root)
  .filter((dir) => {
    const rel = path.relative(root, dir).replaceAll(path.sep, "/");
    return !rel.startsWith("graph/");
  })
  .sort();

let generated = 0;
for (const dir of dirs) {
  const rel = path.relative(root, dir).replaceAll(path.sep, "/");
  const slug = slugOf(rel);
  const base = path.join(outDir, `${slug}-readme-architecture-01`);
  const model = architectureModel(dir, rel);
  if (model.sourceEvidence.length === 0) {
    throw new Error(`No source evidence found for ${rel}`);
  }
  const dot = dotFor({ model });
  fs.writeFileSync(`${base}.dot`, dot);
  const plain = run("dot", ["-Tplain", `${base}.dot`], { env: renderEnv });
  fs.writeFileSync(`${base}.plain`, plain);
  run("dot", ["-Tsvg", `${base}.dot`, "-o", `${base}-graphviz.svg`], { env: renderEnv });
  run("dot", ["-Tpng", `${base}.dot`, "-o", `${base}-graphviz.png`], { env: renderEnv });
  fs.writeFileSync(`${base}.svg`, layeredSvg(model));
  run("rsvg-convert", [`${base}.svg`, "-o", `${base}.png`], { env: renderEnv });
  generated++;
}

console.log(`graphviz_readme_diagrams=${generated}`);
