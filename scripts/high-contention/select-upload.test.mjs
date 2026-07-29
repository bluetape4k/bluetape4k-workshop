import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
    lstat,
    mkdir,
    mkdtemp,
    readFile,
    readdir,
    rm,
    symlink,
    writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
    artifactPolicy,
    selectUpload,
    UPLOAD_FAILURE_BYTES,
} from "./select-upload.mjs";

const RUN_ID = "examples-12345-2";
const WORKFLOW_RUN_AND_ATTEMPT = "12345-2";

async function fixture(status = "PASS") {
    const root = await mkdtemp(join(tmpdir(), "high-contention-upload-"));
    const runRoot = join(root, "run");
    const failureRoot = join(root, "failure");
    const stagingRoot = join(root, "staging");
    await mkdir(join(runRoot, "children/job-core/burst"), { recursive: true });
    await mkdir(join(runRoot, "reports/job-core"), { recursive: true });
    await mkdir(failureRoot);

    const files = new Map([
        [
            "run-manifest.json",
            json({
                schemaVersion: 1,
                runId: RUN_ID,
                mode: "ci-correctness",
                workflowRunAndAttempt: WORKFLOW_RUN_AND_ATTEMPT,
                expectedChildren: [{ profileId: "burst", implementation: "job-core" }],
            }),
        ],
        [
            "run-journal.jsonl",
            `${JSON.stringify({
                schemaVersion: 1,
                event: "RUN_FINISHED",
                result: status === "PASS" ? "PASS" : "FAIL",
                maxActiveTopologies: 1,
                cleanupZeroLive: true,
            })}\n`,
        ],
        [
            "children/job-core/burst/child-journal.jsonl",
            `${JSON.stringify({
                schemaVersion: 1,
                event: "CHILD_TERMINAL",
                profileId: "burst",
                implementation: "job-core",
                cleanupZeroLive: true,
            })}\n`,
        ],
        [
            "reports/job-core/burst.json",
            json({
                reportSchemaVersion: 1,
                runId: RUN_ID,
                profileId: "burst",
                mode: "ci-correctness",
                implementation: "job-core",
                environment: {
                    workflowRunAndAttempt: WORKFLOW_RUN_AND_ATTEMPT,
                },
                result: {
                    terminalStatus: status,
                    errorCode: status === "UNAVAILABLE" ? "PREFLIGHT_UNAVAILABLE" : "NONE",
                },
            }),
        ],
        [
            "summary.json",
            json({
                schemaVersion: 1,
                runId: RUN_ID,
                mode: "ci-correctness",
                workflowRunAndAttempt: WORKFLOW_RUN_AND_ATTEMPT,
                result: status === "PASS" ? "PASS" : "FAIL",
            }),
        ],
    ]);
    for (const [relativePath, content] of files) {
        await writeFile(join(runRoot, relativePath), content);
    }
    await writeFile(
        join(runRoot, "upload-manifest.json"),
        json({
            schemaVersion: 1,
            runId: RUN_ID,
            mode: "ci-correctness",
            workflowRunAndAttempt: WORKFLOW_RUN_AND_ATTEMPT,
            files: [...files]
                .map(([path, content]) => ({
                    path,
                    sha256: digest(content),
                }))
                .sort((left, right) => left.path.localeCompare(right.path)),
        }),
    );
    return { root, runRoot, failureRoot, stagingRoot };
}

function json(value) {
    return `${JSON.stringify(value)}\n`;
}

function digest(value) {
    return createHash("sha256").update(value).digest("hex");
}

async function select(paths) {
    return selectUpload({
        ...paths,
        expectedMode: "ci-correctness",
        expectedRunId: RUN_ID,
        expectedWorkflowRunAndAttempt: WORKFLOW_RUN_AND_ATTEMPT,
    });
}

test("valid canonical tree stages only the digest allowlist and manifest", async () => {
    const paths = await fixture();
    try {
        const selected = await select(paths);

        assert.deepEqual(selected, {
            artifactName: `high-contention-ci-correctness-${RUN_ID}`,
            retentionDays: 7,
            source: "canonical",
        });
        const staged = await readdir(paths.stagingRoot, { recursive: true });
        assert.equal(staged.filter((path) => !path.endsWith("/")).length >= 6, true);
        assert.equal(
            await readFile(join(paths.stagingRoot, "reports/job-core/burst.json"), "utf8"),
            await readFile(join(paths.runRoot, "reports/job-core/burst.json"), "utf8"),
        );
    } finally {
        await rm(paths.root, { recursive: true, force: true });
    }
});

test("absent upload manifest fails closed", async () => {
    const paths = await fixture();
    try {
        await rm(join(paths.runRoot, "upload-manifest.json"));
        await assert.rejects(select(paths), /upload manifest or constants-only fallback is required/);
    } finally {
        await rm(paths.root, { recursive: true, force: true });
    }
});

test("digest mismatch and redaction leak fail closed", async () => {
    const first = await fixture();
    try {
        await writeFile(join(first.runRoot, "summary.json"), json({ tampered: true }));
        await assert.rejects(select(first), /digest mismatch/);
        await assert.rejects(
            lstat(first.stagingRoot),
            (error) => error?.code === "ENOENT",
        );
    } finally {
        await rm(first.root, { recursive: true, force: true });
    }

    const second = await fixture();
    try {
        const reportPath = join(second.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.environment.endpoint = "redis://secret";
        const content = json(report);
        await writeFile(reportPath, content);
        const manifestPath = join(second.runRoot, "upload-manifest.json");
        const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
        manifest.files.find((entry) => entry.path === "reports/job-core/burst.json").sha256 =
            digest(content);
        await writeFile(manifestPath, json(manifest));

        await assert.rejects(select(second), /forbidden evidence/);
        await assert.rejects(
            lstat(second.stagingRoot),
            (error) => error?.code === "ENOENT",
        );
    } finally {
        await rm(second.root, { recursive: true, force: true });
    }
});

test("symlink and unknown run file fail closed", async (context) => {
    if (process.platform === "win32") {
        context.skip("symlink creation is not portable on Windows");
        return;
    }
    const first = await fixture();
    try {
        const reportPath = join(first.runRoot, "reports/job-core/burst.json");
        const outside = join(first.root, "outside.json");
        await writeFile(outside, await readFile(reportPath));
        await rm(reportPath);
        await symlink(outside, reportPath);
        await assert.rejects(select(first), /symbolic link/);
    } finally {
        await rm(first.root, { recursive: true, force: true });
    }

    const second = await fixture();
    try {
        await writeFile(join(second.runRoot, "unexpected.log"), "raw output\n");
        await assert.rejects(select(second), /unknown canonical artifact/);
    } finally {
        await rm(second.root, { recursive: true, force: true });
    }
});

test("UNAVAILABLE report is preserved without being promoted to PASS", async () => {
    const paths = await fixture("UNAVAILABLE");
    try {
        const selected = await select(paths);
        const staged = JSON.parse(
            await readFile(join(paths.stagingRoot, "reports/job-core/burst.json"), "utf8"),
        );

        assert.equal(selected.source, "canonical");
        assert.equal(staged.result.terminalStatus, "UNAVAILABLE");
    } finally {
        await rm(paths.root, { recursive: true, force: true });
    }
});

test("constants-only fallback stages no raw run evidence", async () => {
    const paths = await fixture();
    try {
        await rm(paths.runRoot, { recursive: true });
        await writeFile(join(paths.failureRoot, "upload-failure-summary.json"), UPLOAD_FAILURE_BYTES);

        const selected = await select(paths);
        assert.equal(selected.source, "constants-only-fallback");
        assert.deepEqual(await readdir(paths.stagingRoot), ["upload-failure-summary.json"]);
        assert.equal(
            await readFile(join(paths.stagingRoot, "upload-failure-summary.json"), "utf8"),
            UPLOAD_FAILURE_BYTES,
        );
    } finally {
        await rm(paths.root, { recursive: true, force: true });
    }
});

test("workflow run and attempt mismatch fails for manifest and report", async () => {
    const first = await fixture();
    try {
        await assert.rejects(
            selectUpload({
                ...first,
                expectedMode: "ci-correctness",
                expectedRunId: RUN_ID,
                expectedWorkflowRunAndAttempt: "12345-3",
            }),
            /workflow run and attempt mismatch/,
        );
    } finally {
        await rm(first.root, { recursive: true, force: true });
    }

    const second = await fixture();
    try {
        const reportPath = join(second.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.environment.workflowRunAndAttempt = "12345-3";
        const content = json(report);
        await writeFile(reportPath, content);
        const manifestPath = join(second.runRoot, "upload-manifest.json");
        const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
        manifest.files.find((entry) => entry.path === "reports/job-core/burst.json").sha256 =
            digest(content);
        await writeFile(manifestPath, json(manifest));

        await assert.rejects(select(second), /report workflow run and attempt mismatch/);
    } finally {
        await rm(second.root, { recursive: true, force: true });
    }
});

test("artifact names and retention are exact for both modes", () => {
    assert.deepEqual(artifactPolicy("ci-correctness", "examples-123-1"), {
        artifactName: "high-contention-ci-correctness-examples-123-1",
        retentionDays: 7,
    });
    assert.deepEqual(artifactPolicy("local-reference", "nightly-456-2"), {
        artifactName: "high-contention-local-reference-nightly-456-2",
        retentionDays: 14,
    });
});

test("staged files remain regular files", async () => {
    const paths = await fixture();
    try {
        await select(paths);
        const metadata = await lstat(join(paths.stagingRoot, "upload-manifest.json"));
        assert.equal(metadata.isFile(), true);
        assert.equal(metadata.isSymbolicLink(), false);
    } finally {
        await rm(paths.root, { recursive: true, force: true });
    }
});
