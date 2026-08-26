#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const moduleArgument = process.argv[2] || "messaging/kafka-multi-broker-failover";
const moduleRoot = path.resolve(root, moduleArgument);
const expectedDigest =
  "apache/kafka@sha256:9516fb7634bad307d17c33b589fde9023003b0cb761374f500002b980a3149b9";
const expectedTopic = "kafka-failover-reference";
const expectedFields = [
  "runId",
  "scenario",
  "phase",
  "image",
  "imageDigest",
  "topic",
  "partition",
  "nodeCount",
  "leader",
  "replicas",
  "isr",
  "coordinator",
  "assignmentCount",
  "rawDeliveryCount",
  "appliedCount",
  "conflictCount",
  "retryCount",
  "status",
];
const expectedPhases = [
  "startup",
  "topic-ready",
  "assignment-ready",
  "prefix-acked",
  "fault-injected",
  "recovery",
  "suffix-acked",
  "replacement-ready",
  "isr-restored",
  "terminal",
];
const inlineCode = String.fromCharCode(96);
const fence = inlineCode.repeat(3);
const failures = [];

function fail(locale, rule, detail) {
  failures.push({ locale, rule, detail });
}

function readLocale(locale) {
  const file = path.join(moduleRoot, locale === "en" ? "README.md" : "README.ko.md");
  if (!fs.existsSync(file)) {
    fail(locale, "file", "README pair is missing");
    return "";
  }
  return fs.readFileSync(file, "utf8").replaceAll("\r\n", "\n");
}

function codeBlocks(markdown) {
  const chunks = markdown.split(fence);
  const blocks = [];
  for (let index = 1; index < chunks.length; index += 2) {
    const newline = chunks[index].indexOf("\n");
    if (newline < 0) {
      blocks.push("");
      continue;
    }
    blocks.push(chunks[index].slice(newline + 1).replace(/\n$/, ""));
  }
  return blocks;
}

function imageTargets(markdown) {
  return [...markdown.matchAll(/!\[[^\]]*]\(([^)]+)\)/g)].map((match) => match[1].trim());
}

function commandLines(markdown) {
  return markdown
    .split("\n")
    .map((line) => line.trim())
    .filter((line) =>
      line.startsWith("./gradlew ") ||
      line.startsWith("./scripts/") ||
      line.startsWith("colima ") ||
      line.startsWith("docker ") ||
      line.startsWith("java -version") ||
      line.startsWith("export KAFKA_FAILOVER_RUN_ID="));
}

function firstRunCommands(markdown) {
  return commandLines(markdown).filter((line) =>
    line.startsWith("./gradlew :messaging-kafka-multi-broker-failover:test"));
}

function blockContaining(markdown, token) {
  return codeBlocks(markdown).find((block) => block.includes(token)) ?? "";
}

function evidenceFields(markdown) {
  const marker = markdown.includes("fixed field order")
    ? "fixed field order"
    : "field 순서";
  const start = markdown.indexOf(marker);
  const end = markdown.indexOf(fence + "json", start < 0 ? 0 : start);
  const section = start < 0 ? "" : markdown.slice(start, end < 0 ? markdown.length : end);
  const expression = new RegExp(inlineCode + "(" + expectedFields.join("|") + ")" + inlineCode, "g");
  return [...section.matchAll(expression)].map((match) => match[1]);
}

function sampleObject(markdown) {
  const block = codeBlocks(markdown).find((candidate) => candidate.trim().startsWith("{"));
  if (!block) return null;
  const line = block.split("\n").map((value) => value.trim()).find((value) => value.startsWith("{"));
  if (!line) return null;
  try {
    return JSON.parse(line);
  } catch {
    return null;
  }
}

function normalizedContract(markdown) {
  const sample = sampleObject(markdown);
  return {
    firstRun: firstRunCommands(markdown),
    preflight: blockContaining(markdown, "docker pull apache/kafka@"),
    runbook: blockContaining(markdown, "with-kafka-failover-lock.sh"),
    fields: evidenceFields(markdown),
    images: imageTargets(markdown),
    sampleKeys: sample ? Object.keys(sample) : [],
    sampleDigest: sample?.imageDigest ?? "",
    sampleTopic: sample?.topic ?? "",
    samplePhase: sample?.phase ?? "",
    sampleStatus: sample?.status ?? "",
  };
}

function assertEqual(rule, left, right) {
  if (JSON.stringify(left) !== JSON.stringify(right)) {
    fail("pair", rule, { english: left, korean: right });
  }
}

function assertContains(locale, markdown, token) {
  if (!markdown.includes(token)) fail(locale, "required token", token);
}

function assertPattern(locale, markdown, pattern, label) {
  if (!pattern.test(markdown)) fail(locale, "required pattern", label);
}

const english = readLocale("en");
const korean = readLocale("ko");
if (english && korean) {
  const englishContract = normalizedContract(english);
  const koreanContract = normalizedContract(korean);
  assertEqual("first run commands", englishContract.firstRun, koreanContract.firstRun);
  assertEqual("preflight commands", englishContract.preflight, koreanContract.preflight);
  assertEqual("failure runbook commands", englishContract.runbook, koreanContract.runbook);
  assertEqual("evidence field order", englishContract.fields, koreanContract.fields);
  assertEqual("README images", englishContract.images, koreanContract.images);
  assertEqual("evidence sample keys", englishContract.sampleKeys, koreanContract.sampleKeys);
  assertEqual("evidence sample digest", englishContract.sampleDigest, koreanContract.sampleDigest);
  assertEqual("evidence sample topic", englishContract.sampleTopic, koreanContract.sampleTopic);
  assertEqual("evidence sample phase", englishContract.samplePhase, koreanContract.samplePhase);
  assertEqual("evidence sample status", englishContract.sampleStatus, koreanContract.sampleStatus);

  for (const [locale, markdown] of [["en", english], ["ko", korean]]) {
    for (const token of [
      "KRaft",
      "kafka-1",
      "kafka-2",
      "kafka-3",
      "data-leader-failover",
      "group-coordinator-failover",
      "at-least-once",
      "dedup",
      "exactly-once",
      "rawDeliveryCount",
      "appliedCount",
      "conflictCount",
      "AdminClient",
      "performance.jsonl",
      "evidence.jsonl",
      "broker-",
      "terminal",
      "PASS",
      "FAIL",
      "#555",
      "#558",
      "#559",
      "Toxiproxy",
      "black-box",
      "ZooKeeper",
      "Kafka Connect",
      "cloud credential",
      "XA",
      "production deployment",
      "sanitizer",
      "lock",
      "docker inspect",
      "java -version",
      "colima status",
      "docker context show",
      "docker info",
      "docker network ls",
      "docker pull",
      expectedDigest,
      expectedTopic,
    ]) {
      assertContains(locale, markdown, token);
    }
    assertPattern(locale, markdown, /3 partitions/u, "three partitions");
    assertPattern(locale, markdown, /replication factor[\s\S]{0,30}3/u, "replication factor three");
    assertPattern(locale, markdown, /min\.insync\.replicas.?=.?2/u, "minimum ISR");
    assertPattern(locale, markdown, /run-scoped/u, "run-scoped evidence directory");
    assertPattern(locale, markdown, /AdminClient[\s\S]{0,120}retr(?:y|i)/u, "AdminClient retry interpretation");
    assertPattern(locale, markdown, /producer[\s\S]{0,120}retr(?:y|i)/u, "producer retry interpretation");
    assertPattern(locale, markdown, /(rawDeliveryCount[\s\S]{0,140}(increase|늘|증가)|(?:increase|늘|증가)[\s\S]{0,140}rawDeliveryCount)/u, "duplicate counter interpretation");
    assertPattern(locale, markdown, /appliedCount[\s\S]{0,140}(unique|고유|application)/u, "applied counter interpretation");
    assertPattern(locale, markdown, /conflictCount[\s\S]{0,140}(different|다른|payload fingerprint)/u, "conflict counter interpretation");
    assertPattern(locale, markdown, /terminal[\s\S]{0,120}(PASS|FAIL)/u, "terminal status interpretation");
    assertPattern(locale, markdown, /(external Kafka|외부 Kafka)/u, "external Kafka boundary");
    assertPattern(locale, markdown, /distributed[\s\S]{0,30}transaction/u, "distributed transaction boundary");
    assertPattern(
      locale,
      markdown,
      /--tests\s+["']\*KafkaMultiBrokerFailoverIntegrationTest["']/u,
      "executable class test filter",
    );
    assertPattern(
      locale,
      markdown,
      /--tests\s+["']\*KafkaMultiBrokerFailoverIntegrationTest\.dataLeaderFailover["']/u,
      "executable data-leader method filter",
    );
    assertPattern(
      locale,
      markdown,
      /--tests\s+["']\*KafkaMultiBrokerFailoverIntegrationTest\.groupCoordinatorFailover["']/u,
      "executable group-coordinator method filter",
    );
    if (/--tests\s+["']\*KafkaMultiBrokerFailoverIntegrationTest\.(?:data|group)-(?:leader|coordinator)-failover/u.test(markdown)) {
      fail(locale, "non-executable display-name filter", "use Kotlin method names or the class filter");
    }
  }

  const sample = sampleObject(english);
  if (!sample) {
    fail("pair", "evidence sample", "JSON evidence sample is missing or invalid");
  } else {
    assertEqual("evidence field list", Object.keys(sample), expectedFields);
    if (sample.imageDigest !== expectedDigest.slice("apache/kafka@".length)) {
      fail("pair", "evidence sample digest", sample.imageDigest);
    }
    if (sample.topic !== expectedTopic || sample.phase !== "terminal" || sample.status !== "PASS") {
      fail("pair", "evidence sample values", {
        topic: sample.topic,
        phase: sample.phase,
        status: sample.status,
      });
    }
  }
  if (englishContract.fields.join(",") !== expectedFields.join(",")) {
    fail("pair", "evidence field contract", englishContract.fields);
  }
  if (!english.includes(expectedDigest) || !korean.includes(expectedDigest)) {
    fail("pair", "approved image digest", expectedDigest);
  }
  for (const phase of expectedPhases) {
    assertContains("en", english, phase);
    assertContains("ko", korean, phase);
  }
}

if (failures.length > 0) {
  console.error(JSON.stringify({ failures: failures.length, details: failures }, null, 2));
  process.exit(1);
}

console.log(JSON.stringify({
  validated: ["README.md", "README.ko.md"],
  fields: expectedFields.length,
  phases: expectedPhases.length,
  failures: 0,
}, null, 2));
