import { createHash } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import {
    link,
    lstat,
    mkdir,
    open,
    readFile,
    realpath,
    rm,
} from "node:fs/promises";
import { dirname, isAbsolute, join, normalize, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
    canonicalJson,
    parseStrictJson,
    readStrictJson,
    validateContractRoot,
} from "./validate-contract.mjs";

const IDENTIFIER = /^[a-z0-9][a-z0-9._-]{0,63}$/u;
const HOSTED_RUN_ID = /^(?:examples|nightly)-([0-9]+)-([1-9][0-9]*)$/u;
const HOSTED_WORKFLOW_IDENTITY = /^[0-9]+-[1-9][0-9]*$/u;
const IMPLEMENTATIONS = ["job-core", "job-spring", "job-ktor", "ticket-spring"];
const TERMINAL_STATUSES = ["PASS", "FAIL", "ERROR", "UNAVAILABLE"];
const ERROR_CODES = [
    "NONE",
    "INVALID_PROFILE",
    "INVALID_REALIZATION",
    "KEY_SCOPE_VIOLATION",
    "EXECUTION_ERROR",
    "INJECTION_TIMEOUT",
    "FAILURE_DETECTION_TIMEOUT",
    "WORKLOAD_TIMEOUT",
    "RECOVERY_TIMEOUT",
    "CLEANUP_TIMEOUT",
    "REPORT_SERIALIZATION",
    "JOURNAL_ERROR",
    "PARENT_CLEANUP_ERROR",
    "PREFLIGHT_UNAVAILABLE",
];
const REPORT_FIELDS = [
    "reportSchemaVersion",
    "suiteSchemaVersion",
    "profileSchemaVersion",
    "runId",
    "profileId",
    "mode",
    "implementation",
    "startedAt",
    "endedAt",
    "environment",
    "phaseDurationsNanos",
    "workload",
    "failureInjection",
    "invariantResults",
    "observations",
    "deadlines",
    "observationScope",
    "crossImplementationComparable",
    "productionCapacityClaim",
    "result",
    "cleanup",
    "knownLimitations",
];
const RESOURCE_LABEL_KEYS = [
    "io.bluetape4k.high-contention.profile-id",
    "io.bluetape4k.high-contention.resource-key",
    "io.bluetape4k.high-contention.resource-type",
    "io.bluetape4k.high-contention.run-id",
];
const EXPECTED_RESOURCES = new Map([
    ["network", "network"],
    ["redis", "container"],
    ["toxiproxy", "container"],
]);
const FORBIDDEN_PATTERNS = [
    /postgresql:\/\//iu,
    /jdbc:postgresql:/iu,
    /redis:\/\//iu,
    /toxiproxyControlEndpoint/iu,
    /authorization/iu,
    /password/iu,
    /credential/iu,
    /token=|token%3d/iu,
];

function exactObject(value, fields, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
    const expected = [...fields].sort();
    const actual = Object.keys(value).sort();
    if (canonicalJson(actual) !== canonicalJson(expected)) {
        throw new Error(`${label} fields do not match the closed schema`);
    }
    return value;
}

function identifier(value, label) {
    if (typeof value !== "string" || !IDENTIFIER.test(value) || value === "." || value === "..") {
        throw new Error(`${label} must be a bounded identifier`);
    }
    return value;
}

function array(value, label) {
    if (!Array.isArray(value)) throw new Error(`${label} must be an array`);
    return value;
}

function enumValue(value, allowed, label) {
    if (!allowed.includes(value)) throw new Error(`${label} is outside its closed vocabulary`);
    return value;
}

function workflowIdentity(runId, value) {
    const hosted = HOSTED_RUN_ID.exec(runId);
    const expected = hosted ? `${hosted[1]}-${hosted[2]}` : "local-0";
    if (
        typeof value !== "string" ||
        (value !== "local-0" && !HOSTED_WORKFLOW_IDENTITY.test(value)) ||
        value !== expected
    ) {
        throw new Error("workflow run and attempt does not match the run identifier");
    }
    return value;
}

function sha256(bytes) {
    return createHash("sha256").update(bytes).digest("hex");
}

function assertNoRedactionLeak(text, label) {
    for (const pattern of FORBIDDEN_PATTERNS) {
        if (pattern.test(text)) throw new Error(`${label} contains forbidden evidence`);
    }
    const fullDigests = text.match(/[0-9a-f]{64}/giu) ?? [];
    if (fullDigests.length > 256) {
        throw new Error(`${label} contains an unbounded number of full digests`);
    }
}

function normalizedDescendant(value, label) {
    if (
        typeof value !== "string" ||
        value.length === 0 ||
        isAbsolute(value) ||
        value.includes("\\") ||
        normalize(value) !== value ||
        value === "." ||
        value.startsWith("../") ||
        value.includes("/../")
    ) {
        throw new Error(`${label} must be a normalized descendant path`);
    }
    return value;
}

async function trustedDirectory(path, label) {
    const absolute = resolve(path);
    const metadata = await lstat(absolute);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
        throw new Error(`${label} must be a trusted directory`);
    }
    return realpath(absolute);
}

async function readJsonl(root, relativePath) {
    const absolute = join(root, normalizedDescendant(relativePath, relativePath));
    const metadata = await lstat(absolute);
    if (!metadata.isFile() || metadata.isSymbolicLink()) {
        throw new Error(`${relativePath} must be a regular file`);
    }
    const text = await readFile(absolute, "utf8");
    if (!text.endsWith("\n")) throw new Error(`${relativePath} must end with a complete record`);
    assertNoRedactionLeak(text, relativePath);
    const records = [];
    for (const [index, line] of text.trimEnd().split("\n").entries()) {
        if (line.length === 0) throw new Error(`${relativePath} contains a blank record`);
        records.push(parseStrictJson(line, `${relativePath}:${index + 1}`));
    }
    return records;
}

function validateResult(report, label) {
    if (report.result === null || typeof report.result !== "object" || Array.isArray(report.result)) {
        throw new Error(`${label}.result must be an object`);
    }
    const status = enumValue(report.result.terminalStatus, TERMINAL_STATUSES, `${label}.terminalStatus`);
    const errorCode = enumValue(report.result.errorCode, ERROR_CODES, `${label}.errorCode`);
    if ((status === "PASS" || status === "FAIL") && errorCode !== "NONE") {
        throw new Error(`${label} PASS or FAIL must use errorCode NONE`);
    }
    if (status === "ERROR" && errorCode === "NONE") {
        throw new Error(`${label} ERROR requires a non-NONE errorCode`);
    }
    if (status === "UNAVAILABLE" && errorCode !== "PREFLIGHT_UNAVAILABLE") {
        throw new Error(`${label} UNAVAILABLE requires PREFLIGHT_UNAVAILABLE`);
    }
    if (report.cleanup?.result !== "PASS") {
        throw new Error(`${label} cleanup did not reach PASS`);
    }
    return status;
}

function validateSchedule(report, label) {
    const schedule = report.workload?.schedule;
    if (schedule) {
        if (schedule.expectedScheduleSha256 !== schedule.realizedTokenManifestSha256) {
            throw new Error(`${label} realized schedule digest does not match`);
        }
        return;
    }
    if (
        report.workload?.expectedScheduleSha256 !==
        report.workload?.realizedScheduleSha256
    ) {
        throw new Error(`${label} realized schedule digest does not match`);
    }
}

function walkEvidenceReferences(value, references = []) {
    if (Array.isArray(value)) {
        value.forEach((entry) => walkEvidenceReferences(entry, references));
    } else if (value !== null && typeof value === "object") {
        for (const [key, child] of Object.entries(value)) {
            if (key === "evidenceReference") {
                references.push(normalizedDescendant(child, "evidenceReference"));
            } else {
                walkEvidenceReferences(child, references);
            }
        }
    }
    return references;
}

function validateChildJournal(record, expected) {
    exactObject(
        record,
        [
            "schemaVersion",
            "event",
            "profileId",
            "implementation",
            "resources",
            "timedOut",
            "exitCode",
            "cleanupZeroLive",
            "parentDeletedResourceCount",
            "observedPids",
        ],
        "child journal terminal record",
    );
    if (
        record.schemaVersion !== 1 ||
        record.event !== "CHILD_TERMINAL" ||
        record.profileId !== expected.profileId ||
        record.implementation !== expected.implementation ||
        record.timedOut !== false ||
        record.exitCode !== 0 ||
        record.cleanupZeroLive !== true ||
        !Number.isSafeInteger(record.parentDeletedResourceCount) ||
        record.parentDeletedResourceCount < 0
    ) {
        throw new Error("child journal terminal record does not match its expected child");
    }
    const resources = array(record.resources, "child journal resources");
    if (resources.length !== EXPECTED_RESOURCES.size) {
        throw new Error("child journal resource allocation is incomplete");
    }
    const seen = new Set();
    for (const labels of resources) {
        exactObject(labels, RESOURCE_LABEL_KEYS, "child journal resource labels");
        const resourceKey = labels["io.bluetape4k.high-contention.resource-key"];
        const resourceType = labels["io.bluetape4k.high-contention.resource-type"];
        if (
            labels["io.bluetape4k.high-contention.run-id"] !== expected.runId ||
            labels["io.bluetape4k.high-contention.profile-id"] !== expected.profileId ||
            EXPECTED_RESOURCES.get(resourceKey) !== resourceType ||
            seen.has(resourceKey)
        ) {
            throw new Error("child journal resource labels do not match the exact allocation");
        }
        seen.add(resourceKey);
    }
}

function validateRunJournal(records, expectedChildren) {
    records.forEach((record, index) => {
        if (record.event === "CHILD_STARTING") {
            exactObject(
                record,
                ["schemaVersion", "event", "ordinal", "profileId", "implementation"],
                `run journal record ${index}`,
            );
        } else if (record.event === "CHILD_FINISHED") {
            exactObject(
                record,
                [
                    "schemaVersion",
                    "event",
                    "ordinal",
                    "profileId",
                    "implementation",
                    "terminalStatus",
                    "cleanupZeroLive",
                    "childProcessesZeroLive",
                    "parentDeletedResourceCount",
                    "observedProcessCount",
                    "continuation",
                ],
                `run journal record ${index}`,
            );
        } else if (record.event === "RUN_FINISHED") {
            exactObject(
                record,
                [
                    "schemaVersion",
                    "event",
                    "result",
                    "maxActiveTopologies",
                    "cleanupZeroLive",
                ],
                `run journal record ${index}`,
            );
        } else {
            throw new Error(`run journal record ${index} has an unknown event`);
        }
    });
    const starts = records.filter((record) => record.event === "CHILD_STARTING");
    const finishes = records.filter((record) => record.event === "CHILD_FINISHED");
    const terminal = records.at(-1);
    if (
        starts.length !== expectedChildren.length ||
        finishes.length !== expectedChildren.length ||
        terminal?.event !== "RUN_FINISHED" ||
        terminal.maxActiveTopologies !== 1 ||
        terminal.cleanupZeroLive !== true
    ) {
        throw new Error("run journal is not closed with one active topology and zero-live cleanup");
    }
    starts.forEach((record, index) => {
        const expected = expectedChildren[index];
        if (
            record.ordinal !== index ||
            record.profileId !== expected.profileId ||
            record.implementation !== expected.implementation ||
            finishes[index]?.ordinal !== index ||
            finishes[index]?.profileId !== expected.profileId ||
            finishes[index]?.implementation !== expected.implementation ||
            finishes[index]?.childProcessesZeroLive !== true ||
            finishes[index]?.cleanupZeroLive !== true
        ) {
            throw new Error("run journal child ordering or zero-live gate is invalid");
        }
    });
    return { runResult: terminal.result, finishes };
}

async function writeNoReplaceJson(path, value) {
    const parent = dirname(path);
    await mkdir(parent, { recursive: true });
    const temporary = join(parent, `.${path.split(sep).at(-1)}.tmp`);
    const handle = await open(temporary, fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY, 0o600);
    try {
        await handle.writeFile(`${canonicalJson(value)}\n`, "utf8");
        await handle.sync();
    } finally {
        await handle.close();
    }
    try {
        await link(temporary, path);
    } finally {
        await rm(temporary, { force: true });
    }
}

export async function validateRun(contractRootValue, runRootValue) {
    const contractRoot = await trustedDirectory(contractRootValue, "contract root");
    const runRoot = await trustedDirectory(runRootValue, "run root");
    await validateContractRoot(contractRoot);

    const manifest = await readStrictJson(runRoot, "run-manifest.json");
    exactObject(
        manifest,
        ["schemaVersion", "runId", "mode", "workflowRunAndAttempt", "expectedChildren"],
        "run-manifest.json",
    );
    if (
        manifest.schemaVersion !== 1 ||
        !["ci-correctness", "local-reference"].includes(manifest.mode)
    ) {
        throw new Error("run manifest schema or mode is invalid");
    }
    const runId = identifier(manifest.runId, "run manifest runId");
    const workflowRunAndAttempt = workflowIdentity(
        runId,
        manifest.workflowRunAndAttempt,
    );
    const expectedChildren = array(manifest.expectedChildren, "run manifest expectedChildren").map(
        (child, index) => {
            exactObject(child, ["profileId", "implementation"], `expectedChildren[${index}]`);
            return {
                runId,
                profileId: identifier(child.profileId, `expectedChildren[${index}].profileId`),
                implementation: enumValue(
                    child.implementation,
                    IMPLEMENTATIONS,
                    `expectedChildren[${index}].implementation`,
                ),
            };
        },
    );
    if (expectedChildren.length === 0) throw new Error("run manifest selection must not be empty");
    const tupleKeys = expectedChildren.map(
        (child) => `${child.profileId}:${child.implementation}`,
    );
    if (new Set(tupleKeys).size !== tupleKeys.length) {
        throw new Error("run manifest contains duplicate child tuples");
    }

    const runJournal = await readJsonl(runRoot, "run-journal.jsonl");
    const { runResult, finishes } = validateRunJournal(runJournal, expectedChildren);
    const statuses = [];
    const artifactFiles = ["run-manifest.json", "run-journal.jsonl"];
    for (const child of expectedChildren) {
        const journalPath =
            `children/${child.implementation}/${child.profileId}/child-journal.jsonl`;
        const reportPath = `reports/${child.implementation}/${child.profileId}.json`;
        const childJournal = await readJsonl(runRoot, journalPath);
        if (childJournal.length !== 1) throw new Error(`${journalPath} must contain one terminal record`);
        validateChildJournal(childJournal[0], child);

        const report = await readStrictJson(runRoot, reportPath);
        exactObject(report, REPORT_FIELDS, reportPath);
        const rawReport = await readFile(join(runRoot, reportPath), "utf8");
        assertNoRedactionLeak(rawReport, reportPath);
        if (
            report.runId !== runId ||
            report.profileId !== child.profileId ||
            report.mode !== manifest.mode ||
            report.implementation !== child.implementation
        ) {
            throw new Error(`${reportPath} tuple does not match the run manifest`);
        }
        if (
            report.environment === null ||
            typeof report.environment !== "object" ||
            Array.isArray(report.environment) ||
            report.environment.workflowRunAndAttempt !== workflowRunAndAttempt
        ) {
            throw new Error(`${reportPath} workflow run and attempt does not match the run manifest`);
        }
        validateSchedule(report, reportPath);
        walkEvidenceReferences(report).forEach((reference) => {
            if (!reference.startsWith("evidence/")) {
                throw new Error(`${reportPath} evidenceReference is outside the allowlist`);
            }
        });
        const status = validateResult(report, reportPath);
        if (finishes[statuses.length].terminalStatus !== status) {
            throw new Error(`${reportPath} terminal status does not match the run journal`);
        }
        statuses.push(status);
        artifactFiles.push(journalPath, reportPath);
    }

    const childResult = statuses.every((status) => status === "PASS") ? "PASS" : "FAIL";
    if (
        !["PASS", "FAIL", "ERROR"].includes(runResult) ||
        (runResult === "PASS" && childResult !== "PASS")
    ) {
        throw new Error("run terminal result does not match child results");
    }
    const derivedResult = runResult === "ERROR" ? "ERROR" : (
        runResult === "FAIL" || childResult === "FAIL" ? "FAIL" : "PASS"
    );
    const summary = {
        schemaVersion: 1,
        runId,
        mode: manifest.mode,
        workflowRunAndAttempt,
        result: derivedResult,
        childCount: expectedChildren.length,
        terminalStatusCounts: Object.fromEntries(
            TERMINAL_STATUSES.map((status) => [
                status,
                statuses.filter((value) => value === status).length,
            ]),
        ),
        maxActiveTopologies: 1,
        cleanupZeroLive: true,
    };
    await writeNoReplaceJson(join(runRoot, "summary.json"), summary);
    artifactFiles.push("summary.json");
    const uploadManifest = {
        schemaVersion: 1,
        runId,
        mode: manifest.mode,
        workflowRunAndAttempt,
        files: await Promise.all(
            artifactFiles
                .sort()
                .map(async (path) => ({
                    path,
                    sha256: sha256(await readFile(join(runRoot, path))),
                })),
        ),
    };
    await writeNoReplaceJson(join(runRoot, "upload-manifest.json"), uploadManifest);
    return { result: "PASS" };
}

async function main() {
    const [, , contractRoot, runRoot] = process.argv;
    if (!contractRoot || !runRoot) {
        throw new Error(
            "usage: node scripts/high-contention/validate-run.mjs <contract-root> <run-root>",
        );
    }
    const result = await validateRun(contractRoot, runRoot);
    process.stdout.write(`${JSON.stringify(result)}\n`);
}

const currentFile = fileURLToPath(import.meta.url);
const invokedFile = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedFile && pathToFileURL(invokedFile).href === pathToFileURL(currentFile).href) {
    main().catch((error) => {
        process.stderr.write(`high-contention run validation failed: ${error.message}\n`);
        process.exitCode = 1;
    });
}
