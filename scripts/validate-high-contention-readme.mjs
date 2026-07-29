#!/usr/bin/env node

import { lstat, readFile } from "node:fs/promises";

const DOCUMENTS = [
    ["operations/job-console-core/README.md", "en"],
    ["operations/job-console-core/README.ko.md", "ko"],
    ["operations/job-console-spring/README.md", "en"],
    ["operations/job-console-spring/README.ko.md", "ko"],
    ["operations/job-console-ktor/README.md", "en"],
    ["operations/job-console-ktor/README.ko.md", "ko"],
    ["commerce/concert-ticket-flash-sale/README.md", "en"],
    ["commerce/concert-ticket-flash-sale/README.ko.md", "ko"],
];

const DIAGRAM_ASSETS = [
    "docs/images/readme-diagrams/high-contention-profile-runner-architecture-01.png",
    "docs/images/readme-diagrams/high-contention-profile-runner-architecture-01.svg",
];
const DIAGRAM_SVG = DIAGRAM_ASSETS[1];
const EXPECTED_DIRECTIONAL_CONNECTORS = 8;

const COMMON_REQUIREMENTS = new Map([
    ["CI correctness command", /(?:^|\s)\.\/gradlew highContentionCi(?:\s|$)/u],
    ["local reference command", /(?:^|\s)\.\/gradlew highContentionLocalReference(?:\s|$)/u],
    ["run ID property", /-PhighContentionRunId=/u],
    ["Docker prerequisite", /\bDocker\b/u],
    ["JDK 25 prerequisite", /\bJDK 25\b/u],
    ["memory prerequisite", /\b4 GiB\b/u],
    ["canonical report path", /build\/reports\/high-contention\/<run-id>\//u],
    [
        "runner architecture diagram",
        /docs\/images\/readme-diagrams\/high-contention-profile-runner-architecture-01\.png/u,
    ],
]);

const LANGUAGE_REQUIREMENTS = {
    en: new Map([
        ["correctness and observation distinction", /correctness gate[\s\S]*observations?/iu],
        ["no framework ranking", /does not\s+rank\s+frameworks/iu],
        ["no production capacity claim", /does not establish production capacity/iu],
        ["fresh run and clean source boundary", /new run ID[\s\S]{0,120}clean worktree/iu],
        ["PostgreSQL authority", /PostgreSQL remains\s+(?:the\s+)?authorit(?:y|ative)/u],
        [
            "Toxiproxy Redis-only scope",
            /Toxiproxy[\s\S]{0,450}Redis[\s\S]{0,300}does not/iu,
        ],
    ]),
    ko: new Map([
        ["correctness and observation distinction", /정확성 게이트[\s\S]*관찰값/u],
        ["no framework ranking", /프레임워크 순위를\s+매기지 않는다/u],
        ["no production capacity claim", /운영 용량을 입증하지 않는다/u],
        ["fresh run and clean source boundary", /새 run\s+ID[\s\S]{0,120}clean worktree/u],
        ["PostgreSQL authority", /권위는\s+PostgreSQL이[\s\S]{0,50}(?:가집니다|유지합니다)/u],
        [
            "Toxiproxy Redis-only scope",
            /Toxiproxy[\s\S]{0,450}Redis[\s\S]{0,300}대체(?:하지 않습니다|하거나)/u,
        ],
    ]),
};

const MODULE_REQUIREMENTS = new Map([
    ["operations/job-console-core/README.md", /leases, fencing, checkpoints, deduplication/u],
    ["operations/job-console-core/README.ko.md", /Lease, fencing, checkpoint, deduplication/u],
    ["operations/job-console-spring/README.md", /Spring `DataSource` uses HikariCP/u],
    ["operations/job-console-spring/README.ko.md", /Spring `DataSource`는 HikariCP/u],
    ["operations/job-console-ktor/README.md", /application-owned dispatcher/u],
    ["operations/job-console-ktor/README.ko.md", /application-owned dispatcher/u],
    ["commerce/concert-ticket-flash-sale/README.md", /Spring-managed HikariCP/u],
    ["commerce/concert-ticket-flash-sale/README.ko.md", /Spring-managed HikariCP/u],
]);

const failures = [];
for (const path of DIAGRAM_ASSETS) {
    try {
        const metadata = await lstat(path);
        if (!metadata.isFile() || metadata.size === 0) {
            failures.push({ path, missing: "regular non-empty diagram asset" });
        }
    } catch {
        failures.push({ path, missing: "regular non-empty diagram asset" });
    }
}

const diagramSvg = await readFile(DIAGRAM_SVG, "utf8");
const directionalConnectors = [
    ...diagramSvg.matchAll(/<path\b([^>]*\bdata-connector="([^"]+)"[^>]*)\/?>/gu),
];
if (directionalConnectors.length !== EXPECTED_DIRECTIONAL_CONNECTORS) {
    failures.push({
        path: DIAGRAM_SVG,
        missing: `exactly ${EXPECTED_DIRECTIONAL_CONNECTORS} directional connectors`,
    });
}
for (const connector of directionalConnectors) {
    if (!/marker-end="url\(#[^)]+\)"/u.test(connector[1])) {
        failures.push({
            path: DIAGRAM_SVG,
            missing: `${connector[2]} attached directional endpoint`,
        });
    }
}
const coordinator = /<g data-node="coordinator">([\s\S]*?)<\/g>/u.exec(diagramSvg)?.[1] ?? "";
if (!/process \+ container reaping/u.test(coordinator)) {
    failures.push({
        path: DIAGRAM_SVG,
        missing: "root coordinator process and container reaping ownership",
    });
}
if (diagramSvg.includes('data-connector="evidence-to-cleanup"')) {
    failures.push({
        path: DIAGRAM_SVG,
        missing: "cleanup ownership must not float out of the evidence gate",
    });
}

for (const [path, language] of DOCUMENTS) {
    const content = await readFile(path, "utf8");
    for (const [label, pattern] of [
        ...COMMON_REQUIREMENTS,
        ...LANGUAGE_REQUIREMENTS[language],
    ]) {
        if (!pattern.test(content)) {
            failures.push({ path, missing: label });
        }
    }
    if (!MODULE_REQUIREMENTS.get(path).test(content)) {
        failures.push({ path, missing: "module-local profile boundary" });
    }
    const ciRunId = /^CI_RUN_ID=([^\s]+)$/mu.exec(content)?.[1];
    const referenceRunId = /^REFERENCE_RUN_ID=([^\s]+)$/mu.exec(content)?.[1];
    if (!ciRunId || !referenceRunId || ciRunId === referenceRunId) {
        failures.push({ path, missing: "distinct CI and local-reference run ID examples" });
    }
    if (
        !/\.\/gradlew highContentionCi[^\n]*-PhighContentionRunId="\$CI_RUN_ID"/u.test(content)
    ) {
        failures.push({ path, missing: "CI command bound to CI_RUN_ID" });
    }
    if (
        !/\.\/gradlew highContentionLocalReference[^\n]*-PhighContentionRunId="\$REFERENCE_RUN_ID"/u
            .test(content)
    ) {
        failures.push({ path, missing: "local-reference command bound to REFERENCE_RUN_ID" });
    }
}

if (failures.length > 0) {
    console.error(JSON.stringify({ failures }, null, 2));
    process.exit(1);
}

console.log(JSON.stringify({ validated: DOCUMENTS.length }, null, 2));
