import { createHash } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import {
    lstat,
    mkdir,
    open,
    readdir,
    realpath,
} from "node:fs/promises";
import { basename, dirname, join, normalize, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { parseStrictJson } from "./validate-contract.mjs";

const IDENTIFIER = /^[a-z0-9][a-z0-9._-]{0,63}$/u;
const SHA256 = /^[0-9a-f]{64}$/u;
const IMPLEMENTATIONS = new Set(["job-core", "job-spring", "job-ktor", "ticket-spring"]);
const MODES = new Set(["ci-correctness", "local-reference"]);
const TERMINAL_STATUSES = new Set(["PASS", "FAIL", "ERROR", "UNAVAILABLE"]);
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
const FAILURE_FILE = "upload-failure-summary.json";
const MANIFEST_FILE = "upload-manifest.json";
const MAX_FILE_BYTES = 16 * 1024 * 1024;

export const UPLOAD_FAILURE_BYTES =
    '{"schemaVersion":1,"result":"ERROR","errorCode":"ARTIFACT_VALIDATION_FAILED"}\n';

export function artifactPolicy(mode, runId) {
    const validMode = enumValue(mode, MODES, "mode");
    const validRunId = identifier(runId, "runId");
    return {
        artifactName: `high-contention-${validMode}-${validRunId}`,
        retentionDays: validMode === "ci-correctness" ? 7 : 14,
    };
}

export async function selectUpload({
    runRoot: runRootValue,
    failureRoot: failureRootValue,
    stagingRoot: stagingRootValue,
    expectedMode,
    expectedRunId,
    expectedWorkflowRunAndAttempt,
}) {
    const mode = enumValue(expectedMode, MODES, "expected mode");
    const runId = identifier(expectedRunId, "expected runId");
    const workflowRunAndAttempt = workflowIdentity(
        expectedWorkflowRunAndAttempt,
        "expected workflow run and attempt",
    );
    const runRoot = await trustedDirectory(runRootValue, "run root");
    const failureRoot = await trustedDirectory(failureRootValue, "failure root");
    const stagingRoot = await createTrustedStagingRoot(stagingRootValue);
    const policy = artifactPolicy(mode, runId);
    const manifestState = await pathState(join(runRoot, MANIFEST_FILE));
    const fallbackState = await pathState(join(failureRoot, FAILURE_FILE));

    if (manifestState === "file") {
        if (fallbackState !== "absent") {
            throw new Error("constants-only fallback must not accompany a canonical upload manifest");
        }
        await stageCanonical({
            runRoot,
            stagingRoot,
            mode,
            runId,
            workflowRunAndAttempt,
        });
        return { ...policy, source: "canonical" };
    }
    if (manifestState !== "absent") {
        throw new Error("upload manifest must be a regular file, not a symbolic link");
    }
    if (fallbackState === "file") {
        await stageFallback(failureRoot, stagingRoot);
        return { ...policy, source: "constants-only-fallback" };
    }
    throw new Error("upload manifest or constants-only fallback is required");
}

async function stageCanonical({
    runRoot,
    stagingRoot,
    mode,
    runId,
    workflowRunAndAttempt,
}) {
    const manifestBytes = await readTrustedFile(runRoot, MANIFEST_FILE);
    const manifest = parseStrictJson(manifestBytes.toString("utf8"), MANIFEST_FILE);
    exactObject(
        manifest,
        ["schemaVersion", "runId", "mode", "workflowRunAndAttempt", "files"],
        MANIFEST_FILE,
    );
    if (
        manifest.schemaVersion !== 1 ||
        manifest.runId !== runId ||
        manifest.mode !== mode
    ) {
        throw new Error("upload manifest tuple mismatch");
    }
    if (manifest.workflowRunAndAttempt !== workflowRunAndAttempt) {
        throw new Error("workflow run and attempt mismatch");
    }
    if (!Array.isArray(manifest.files) || manifest.files.length === 0) {
        throw new Error("upload manifest files must not be empty");
    }

    const entries = manifest.files.map((entry, index) => {
        exactObject(entry, ["path", "sha256"], `upload manifest files[${index}]`);
        const path = canonicalArtifactPath(entry.path);
        if (!SHA256.test(entry.sha256)) {
            throw new Error(`${path} digest is invalid`);
        }
        return { path, sha256: entry.sha256 };
    });
    const paths = entries.map((entry) => entry.path);
    if (
        new Set(paths).size !== paths.length ||
        JSON.stringify(paths) !== JSON.stringify([...paths].sort())
    ) {
        throw new Error("upload manifest paths must be unique and sorted");
    }
    for (const required of ["run-manifest.json", "run-journal.jsonl", "summary.json"]) {
        if (!paths.includes(required)) {
            throw new Error(`upload manifest is missing ${required}`);
        }
    }

    const actualFiles = await listTrustedFiles(runRoot);
    const expectedFiles = [...paths, MANIFEST_FILE].sort();
    if (JSON.stringify(actualFiles) !== JSON.stringify(expectedFiles)) {
        throw new Error("run tree contains an unknown canonical artifact");
    }

    const runManifest = parseStrictJson(
        (await readTrustedFile(runRoot, "run-manifest.json")).toString("utf8"),
        "run-manifest.json",
    );
    exactObject(
        runManifest,
        ["schemaVersion", "runId", "mode", "workflowRunAndAttempt", "expectedChildren"],
        "run-manifest.json",
    );
    if (
        runManifest.schemaVersion !== 1 ||
        runManifest.runId !== runId ||
        runManifest.mode !== mode ||
        runManifest.workflowRunAndAttempt !== workflowRunAndAttempt
    ) {
        throw new Error("run manifest workflow run and attempt mismatch");
    }

    for (const entry of entries) {
        const bytes = await readTrustedFile(runRoot, entry.path);
        if (sha256(bytes) !== entry.sha256) {
            throw new Error(`${entry.path} digest mismatch`);
        }
        assertNoRedactionLeak(bytes.toString("utf8"), entry.path);
        if (entry.path.startsWith("reports/")) {
            validateReport(
                parseStrictJson(bytes.toString("utf8"), entry.path),
                entry.path,
                mode,
                runId,
                workflowRunAndAttempt,
            );
        }
        if (entry.path === "summary.json") {
            validateSummary(
                parseStrictJson(bytes.toString("utf8"), entry.path),
                mode,
                runId,
                workflowRunAndAttempt,
            );
        }
        await writeStagedFile(stagingRoot, entry.path, bytes);
    }
    assertNoRedactionLeak(manifestBytes.toString("utf8"), MANIFEST_FILE);
    await writeStagedFile(stagingRoot, MANIFEST_FILE, manifestBytes);
}

function validateReport(report, path, mode, runId, workflowRunAndAttempt) {
    if (
        report === null ||
        typeof report !== "object" ||
        Array.isArray(report) ||
        report.runId !== runId ||
        report.mode !== mode
    ) {
        throw new Error(`${path} tuple mismatch`);
    }
    if (
        report.environment === null ||
        typeof report.environment !== "object" ||
        Array.isArray(report.environment) ||
        report.environment.workflowRunAndAttempt !== workflowRunAndAttempt
    ) {
        throw new Error("report workflow run and attempt mismatch");
    }
    if (
        report.result === null ||
        typeof report.result !== "object" ||
        !TERMINAL_STATUSES.has(report.result.terminalStatus)
    ) {
        throw new Error(`${path} terminal status is invalid`);
    }
}

function validateSummary(summary, mode, runId, workflowRunAndAttempt) {
    if (
        summary === null ||
        typeof summary !== "object" ||
        Array.isArray(summary) ||
        summary.schemaVersion !== 1 ||
        summary.runId !== runId ||
        summary.mode !== mode ||
        summary.workflowRunAndAttempt !== workflowRunAndAttempt
    ) {
        throw new Error("summary tuple mismatch");
    }
}

async function stageFallback(failureRoot, stagingRoot) {
    const files = await listTrustedFiles(failureRoot);
    if (JSON.stringify(files) !== JSON.stringify([FAILURE_FILE])) {
        throw new Error("failure upload root must contain only the constants-only fallback");
    }
    const bytes = await readTrustedFile(failureRoot, FAILURE_FILE);
    if (bytes.toString("utf8") !== UPLOAD_FAILURE_BYTES) {
        throw new Error("upload failure summary is not the constants-only fallback");
    }
    await writeStagedFile(stagingRoot, FAILURE_FILE, bytes);
}

function canonicalArtifactPath(value) {
    if (typeof value !== "string" || value === "" || value.includes("\\")) {
        throw new Error("upload artifact path is invalid");
    }
    const normalized = normalize(value);
    if (
        normalized !== value ||
        normalized.startsWith(`..${sep}`) ||
        normalized === ".." ||
        normalized.startsWith(sep)
    ) {
        throw new Error("upload artifact path escaped the run root");
    }
    if (
        value === "run-manifest.json" ||
        value === "run-journal.jsonl" ||
        value === "summary.json"
    ) {
        return value;
    }
    const components = value.split("/");
    if (
        components.length === 4 &&
        components[0] === "children" &&
        IMPLEMENTATIONS.has(components[1]) &&
        IDENTIFIER.test(components[2]) &&
        components[3] === "child-journal.jsonl"
    ) {
        return value;
    }
    if (
        components.length === 3 &&
        components[0] === "reports" &&
        IMPLEMENTATIONS.has(components[1]) &&
        IDENTIFIER.test(components[2].replace(/\.json$/u, "")) &&
        components[2].endsWith(".json")
    ) {
        return value;
    }
    if (
        components.length === 4 &&
        components[0] === "evidence" &&
        IMPLEMENTATIONS.has(components[1]) &&
        IDENTIFIER.test(components[2]) &&
        IDENTIFIER.test(components[3].replace(/\.json$/u, "")) &&
        components[3].endsWith(".json")
    ) {
        return value;
    }
    throw new Error(`${value} is outside the canonical artifact allowlist`);
}

function assertNoRedactionLeak(text, label) {
    for (const pattern of FORBIDDEN_PATTERNS) {
        if (pattern.test(text)) {
            throw new Error(`${label} contains forbidden evidence`);
        }
    }
}

async function trustedDirectory(value, label) {
    const absolute = resolve(value);
    const metadata = await lstat(absolute);
    if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
        throw new Error(`${label} is not a trusted directory`);
    }
    return realpath(absolute);
}

async function createTrustedStagingRoot(value) {
    const requested = resolve(value);
    const parent = await trustedDirectory(dirname(requested), "staging parent");
    const absolute = join(parent, basename(requested));
    if (await pathState(absolute) !== "absent") {
        throw new Error("staging root must not already exist");
    }
    await mkdir(absolute);
    return trustedDirectory(absolute, "staging root");
}

async function readTrustedFile(root, relativePath) {
    const target = join(root, relativePath);
    await requireTrustedParents(root, target);
    const metadata = await lstat(target);
    if (metadata.isSymbolicLink()) {
        throw new Error(`${relativePath} must not be a symbolic link`);
    }
    if (!metadata.isFile() || metadata.size > MAX_FILE_BYTES) {
        throw new Error(`${relativePath} must be a bounded regular file`);
    }
    const resolved = await realpath(target);
    if (!contained(root, resolved)) {
        throw new Error(`${relativePath} escaped its trusted root`);
    }
    const handle = await open(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    try {
        const before = await handle.stat();
        const bytes = await handle.readFile();
        const after = await handle.stat();
        if (
            before.dev !== after.dev ||
            before.ino !== after.ino ||
            before.size !== after.size ||
            before.mtimeMs !== after.mtimeMs
        ) {
            throw new Error(`${relativePath} changed while being selected`);
        }
        return bytes;
    } finally {
        await handle.close();
    }
}

async function requireTrustedParents(root, target) {
    let current = root;
    for (const component of relative(root, dirname(target)).split(sep).filter(Boolean)) {
        current = join(current, component);
        const metadata = await lstat(current);
        if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
            throw new Error(`${current} is not a trusted parent directory`);
        }
    }
}

async function listTrustedFiles(root) {
    const files = [];
    async function visit(directory, relativeDirectory) {
        for (const entry of await readdir(directory, { withFileTypes: true })) {
            const relativePath = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
            if (entry.isSymbolicLink()) {
                throw new Error(`${relativePath} must not be a symbolic link`);
            }
            if (entry.isDirectory()) {
                await visit(join(directory, entry.name), relativePath);
            } else if (entry.isFile()) {
                files.push(relativePath);
            } else {
                throw new Error(`${relativePath} is not a regular artifact`);
            }
        }
    }
    await visit(root, "");
    return files.sort();
}

async function writeStagedFile(stagingRoot, relativePath, bytes) {
    let current = stagingRoot;
    for (const component of dirname(relativePath).split("/").filter((value) => value !== ".")) {
        current = join(current, component);
        const state = await pathState(current);
        if (state === "absent") {
            await mkdir(current);
        } else if (state !== "directory") {
            throw new Error("staging path contains an unsafe component");
        }
    }
    const target = join(stagingRoot, relativePath);
    const handle = await open(
        target,
        fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | fsConstants.O_NOFOLLOW,
        0o600,
    );
    try {
        await handle.writeFile(bytes);
        await handle.sync();
    } finally {
        await handle.close();
    }
}

async function pathState(path) {
    try {
        const metadata = await lstat(path);
        if (metadata.isSymbolicLink()) return "symlink";
        if (metadata.isFile()) return "file";
        if (metadata.isDirectory()) return "directory";
        return "other";
    } catch (error) {
        if (error?.code === "ENOENT") return "absent";
        throw error;
    }
}

function exactObject(value, fields, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
    const actual = Object.keys(value).sort();
    const expected = [...fields].sort();
    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`${label} fields do not match the closed schema`);
    }
}

function identifier(value, label) {
    if (typeof value !== "string" || !IDENTIFIER.test(value) || value === "." || value === "..") {
        throw new Error(`${label} must be a bounded identifier`);
    }
    return value;
}

function workflowIdentity(value, label) {
    if (typeof value !== "string" || !/^[0-9]+-[1-9][0-9]*$/u.test(value)) {
        throw new Error(`${label} is invalid`);
    }
    return value;
}

function enumValue(value, allowed, label) {
    if (!allowed.has(value)) {
        throw new Error(`${label} is outside its closed vocabulary`);
    }
    return value;
}

function sha256(bytes) {
    return createHash("sha256").update(bytes).digest("hex");
}

function contained(root, target) {
    return target === root || target.startsWith(`${root}${sep}`);
}

async function main() {
    const [
        ,
        ,
        runRoot,
        failureRoot,
        stagingRoot,
        expectedMode,
        expectedRunId,
        expectedWorkflowRunAndAttempt,
    ] = process.argv;
    if (
        !runRoot ||
        !failureRoot ||
        !stagingRoot ||
        !expectedMode ||
        !expectedRunId ||
        !expectedWorkflowRunAndAttempt
    ) {
        throw new Error(
            "usage: node scripts/high-contention/select-upload.mjs " +
                "<run-root> <failure-root> <staging-root> <mode> <run-id> " +
                "<workflow-run-and-attempt>",
        );
    }
    const result = await selectUpload({
        runRoot,
        failureRoot,
        stagingRoot,
        expectedMode,
        expectedRunId,
        expectedWorkflowRunAndAttempt,
    });
    process.stdout.write(`${JSON.stringify(result)}\n`);
}

const currentFile = fileURLToPath(import.meta.url);
const invokedFile = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedFile && pathToFileURL(invokedFile).href === pathToFileURL(currentFile).href) {
    main().catch((error) => {
        process.stderr.write(`high-contention upload selection failed: ${error.message}\n`);
        process.exitCode = 1;
    });
}
