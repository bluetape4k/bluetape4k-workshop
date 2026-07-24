import assert from "node:assert/strict";
import { cp, mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { validateRun } from "./validate-run.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const contractRoot = join(repositoryRoot, "profiles/high-contention/v1");

async function fixture() {
    const root = await mkdtemp(join(tmpdir(), "high-contention-run-"));
    const runRoot = join(root, "run-1");
    await mkdir(join(runRoot, "children/job-core/burst"), { recursive: true });
    await mkdir(join(runRoot, "reports/job-core"), { recursive: true });
    const expectedChild = { profileId: "burst", implementation: "job-core" };
    await writeJson(join(runRoot, "run-manifest.json"), {
        schemaVersion: 1,
        runId: "run-1",
        mode: "ci-correctness",
        workflowRunAndAttempt: "local-0",
        expectedChildren: [expectedChild],
    });
    await writeJsonl(join(runRoot, "run-journal.jsonl"), [
        {
            schemaVersion: 1,
            event: "CHILD_STARTING",
            ordinal: 0,
            profileId: "burst",
            implementation: "job-core",
        },
        {
            schemaVersion: 1,
            event: "CHILD_FINISHED",
            ordinal: 0,
            profileId: "burst",
            implementation: "job-core",
            terminalStatus: "PASS",
            cleanupZeroLive: true,
            childProcessesZeroLive: true,
            parentDeletedResourceCount: 0,
            observedProcessCount: 2,
            continuation: "CONTINUE",
        },
        {
            schemaVersion: 1,
            event: "RUN_FINISHED",
            result: "PASS",
            maxActiveTopologies: 1,
            cleanupZeroLive: true,
        },
    ]);
    const labels = (resourceKey, resourceType) => ({
        "io.bluetape4k.high-contention.run-id": "run-1",
        "io.bluetape4k.high-contention.profile-id": "burst",
        "io.bluetape4k.high-contention.resource-key": resourceKey,
        "io.bluetape4k.high-contention.resource-type": resourceType,
    });
    await writeJsonl(
        join(runRoot, "children/job-core/burst/child-journal.jsonl"),
        [
            {
                schemaVersion: 1,
                event: "CHILD_TERMINAL",
                profileId: "burst",
                implementation: "job-core",
                resources: [
                    labels("network", "network"),
                    labels("redis", "container"),
                    labels("toxiproxy", "container"),
                ],
                timedOut: false,
                exitCode: 0,
                cleanupZeroLive: true,
                parentDeletedResourceCount: 0,
                observedPids: [101, 102],
            },
        ],
    );
    await writeJson(join(runRoot, "reports/job-core/burst.json"), validReport());
    return { root, runRoot };
}

function validReport() {
    return {
        reportSchemaVersion: 1,
        suiteSchemaVersion: 1,
        profileSchemaVersion: 1,
        runId: "run-1",
        profileId: "burst",
        mode: "ci-correctness",
        implementation: "job-core",
        startedAt: "2026-07-24T00:00:00Z",
        endedAt: "2026-07-24T00:00:01Z",
        environment: { workflowRunAndAttempt: "local-0" },
        phaseDurationsNanos: { workload: 1 },
        workload: {
            schedule: {
                expectedScheduleSha256: "a".repeat(64),
                realizedTokenManifestSha256: "a".repeat(64),
            },
        },
        failureInjection: { type: "none", steps: [] },
        invariantResults: [
            { invariantId: "tenant-fifo", status: "PASS" },
            { invariantId: "one-active-job", status: "PASS" },
            { invariantId: "queue-version-converges", status: "PASS" },
        ],
        observations: {
            profileFields: {
                throughputOpsPerSecond: 1.0,
                latencyP50Nanos: 1,
                latencyP95Nanos: 1,
                latencyP99Nanos: 1,
            },
        },
        deadlines: [],
        observationScope: "test",
        crossImplementationComparable: false,
        productionCapacityClaim: false,
        result: {
            terminalStatus: "PASS",
            correctness: "PASS",
            errorCode: "NONE",
        },
        cleanup: { result: "PASS", resourceOutcomes: [] },
        knownLimitations: [],
    };
}

async function writeJson(path, value) {
    await writeFile(path, `${JSON.stringify(value)}\n`, "utf8");
}

async function writeJsonl(path, records) {
    await writeFile(path, `${records.map(JSON.stringify).join("\n")}\n`, "utf8");
}

test("valid closed run writes one summary and upload manifest", async () => {
    const { root, runRoot } = await fixture();
    try {
        assert.deepEqual(await validateRun(contractRoot, runRoot), { result: "PASS" });
        const summary = JSON.parse(await readFile(join(runRoot, "summary.json"), "utf8"));
        const upload = JSON.parse(await readFile(join(runRoot, "upload-manifest.json"), "utf8"));
        assert.equal(summary.result, "PASS");
        assert.equal(summary.maxActiveTopologies, 1);
        assert.equal(summary.workflowRunAndAttempt, "local-0");
        assert.equal(upload.mode, "ci-correctness");
        assert.equal(upload.workflowRunAndAttempt, "local-0");
        assert.ok(upload.files.some((entry) => entry.path === "reports/job-core/burst.json"));
        assert.ok(upload.files.every((entry) => /^[0-9a-f]{64}$/u.test(entry.sha256)));
    } finally {
        await rm(root, { force: true, recursive: true });
    }
});

test("workflow identity mismatch between manifest and report fails closed", async () => {
    const { root, runRoot } = await fixture();
    try {
        const reportPath = join(runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.environment.workflowRunAndAttempt = "123-2";
        await writeJson(reportPath, report);

        await assert.rejects(validateRun(contractRoot, runRoot), /workflow run and attempt/);
    } finally {
        await rm(root, { force: true, recursive: true });
    }
});

test("profile failure, invariant, and observation evidence is mandatory", async () => {
    const failureFixture = await fixture();
    try {
        const reportPath = join(failureFixture.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.failureInjection.type = "worker-restart";
        await writeJson(reportPath, report);
        await assert.rejects(
            validateRun(contractRoot, failureFixture.runRoot),
            /failure injection does not match/,
        );
    } finally {
        await rm(failureFixture.root, { force: true, recursive: true });
    }

    const invariantFixture = await fixture();
    try {
        const reportPath = join(invariantFixture.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.invariantResults.pop();
        await writeJson(reportPath, report);
        await assert.rejects(
            validateRun(contractRoot, invariantFixture.runRoot),
            /invariant evidence does not match/,
        );
    } finally {
        await rm(invariantFixture.root, { force: true, recursive: true });
    }

    const observationFixture = await fixture();
    try {
        const reportPath = join(observationFixture.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        delete report.observations.profileFields.latencyP99Nanos;
        await writeJson(reportPath, report);
        await assert.rejects(
            validateRun(contractRoot, observationFixture.runRoot),
            /missing declared observation field latencyP99Nanos/,
        );
    } finally {
        await rm(observationFixture.root, { force: true, recursive: true });
    }

    const typedObservationFixture = await fixture();
    try {
        const reportPath = join(
            typedObservationFixture.runRoot,
            "reports/job-core/burst.json",
        );
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.observations.profileFields.latencyP95Nanos = "not-a-measurement";
        await writeJson(reportPath, report);
        await assert.rejects(
            validateRun(contractRoot, typedObservationFixture.runRoot),
            /latencyP95Nanos must be a non-negative safe integer/,
        );
    } finally {
        await rm(typedObservationFixture.root, { force: true, recursive: true });
    }
});

test("missing child report fails closed", async () => {
    const { root, runRoot } = await fixture();
    try {
        await rm(join(runRoot, "reports/job-core/burst.json"));
        await assert.rejects(validateRun(contractRoot, runRoot), /reports\/job-core\/burst\.json/);
    } finally {
        await rm(root, { force: true, recursive: true });
    }
});

test("duplicate report keys and unknown fields fail closed", async () => {
    const first = await fixture();
    try {
        const reportPath = join(first.runRoot, "reports/job-core/burst.json");
        const text = await readFile(reportPath, "utf8");
        await writeFile(
            reportPath,
            text.replace('"reportSchemaVersion":1', '"reportSchemaVersion":1,"reportSchemaVersion":1'),
        );
        await assert.rejects(validateRun(contractRoot, first.runRoot), /duplicate object key/i);
    } finally {
        await rm(first.root, { force: true, recursive: true });
    }

    const second = await fixture();
    try {
        const reportPath = join(second.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.unreviewed = true;
        await writeJson(reportPath, report);
        await assert.rejects(validateRun(contractRoot, second.runRoot), /closed schema/);
    } finally {
        await rm(second.root, { force: true, recursive: true });
    }
});

test("label mismatch and topology overlap fail closed", async () => {
    const first = await fixture();
    try {
        const journalPath = join(
            first.runRoot,
            "children/job-core/burst/child-journal.jsonl",
        );
        const record = JSON.parse((await readFile(journalPath, "utf8")).trim());
        record.resources[0]["io.bluetape4k.high-contention.run-id"] = "other";
        await writeJsonl(journalPath, [record]);
        await assert.rejects(validateRun(contractRoot, first.runRoot), /exact allocation/);
    } finally {
        await rm(first.root, { force: true, recursive: true });
    }

    const second = await fixture();
    try {
        const journalPath = join(second.runRoot, "run-journal.jsonl");
        const records = (await readFile(journalPath, "utf8"))
            .trim()
            .split("\n")
            .map(JSON.parse);
        records.at(-1).maxActiveTopologies = 2;
        await writeJsonl(journalPath, records);
        await assert.rejects(validateRun(contractRoot, second.runRoot), /one active topology/);
    } finally {
        await rm(second.root, { force: true, recursive: true });
    }
});

test("redaction leaks and terminal replacement fail closed", async () => {
    const first = await fixture();
    try {
        const reportPath = join(first.runRoot, "reports/job-core/burst.json");
        const report = JSON.parse(await readFile(reportPath, "utf8"));
        report.knownLimitations = ["redis://secret"];
        await writeJson(reportPath, report);
        await assert.rejects(validateRun(contractRoot, first.runRoot), /forbidden evidence/);
    } finally {
        await rm(first.root, { force: true, recursive: true });
    }

    const second = await fixture();
    try {
        await validateRun(contractRoot, second.runRoot);
        await assert.rejects(validateRun(contractRoot, second.runRoot), /EEXIST/);
    } finally {
        await rm(second.root, { force: true, recursive: true });
    }
});

test("symlinked run root is rejected", async (context) => {
    if (process.platform === "win32") {
        context.skip("symlink creation is not portable on Windows");
        return;
    }
    const { root, runRoot } = await fixture();
    const linked = join(root, "linked-run");
    try {
        await symlink(runRoot, linked, "dir");
        await assert.rejects(validateRun(contractRoot, linked), /trusted directory/);
    } finally {
        await rm(root, { force: true, recursive: true });
    }
});

test("symlinked artifact parent is rejected before parsing", async (context) => {
    if (process.platform === "win32") {
        context.skip("symlink creation is not portable on Windows");
        return;
    }
    const { root, runRoot } = await fixture();
    try {
        const reports = join(runRoot, "reports");
        const outside = join(root, "outside-reports");
        await cp(reports, outside, { recursive: true });
        await rm(reports, { recursive: true });
        await symlink(outside, reports, "dir");

        await assert.rejects(validateRun(contractRoot, runRoot), /trusted parent|symbolic link/);
    } finally {
        await rm(root, { force: true, recursive: true });
    }
});
