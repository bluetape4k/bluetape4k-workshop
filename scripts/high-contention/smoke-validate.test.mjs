import assert from "node:assert/strict";
import { chmod, copyFile, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import test from "node:test";

const SCRIPT = new URL("../smoke-validate.sh", import.meta.url);

test("high-contention runner rejects shell syntax without executing it", async () => {
    const fixture = await createFixture();
    const marker = join(fixture, "injected");
    try {
        const result = await run(fixture, `safe; touch ${marker}`);

        assert.notEqual(result.code, 0);
        await assert.rejects(
            readFile(marker),
            (error) => error?.code === "ENOENT",
        );
    } finally {
        await rm(fixture, { recursive: true, force: true });
    }
});

test("high-contention runner passes one quoted property and removes its shell channel", async () => {
    const fixture = await createFixture();
    try {
        const result = await run(fixture, "issue-522-final");

        assert.equal(result.code, 0, result.stderr);
        assert.deepEqual(
            JSON.parse(await readFile(join(fixture, "gradlew-invocation.json"), "utf8")),
            {
                args: [
                    "highContentionCi",
                    "-PhighContentionRunId=issue-522-final",
                    "--max-workers=1",
                ],
                runIdEnvironment: null,
            },
        );
    } finally {
        await rm(fixture, { recursive: true, force: true });
    }
});

async function createFixture() {
    const root = await mkdtemp(join(tmpdir(), "high-contention-smoke-"));
    await copyFile(SCRIPT, join(root, "smoke-validate.sh"));
    await writeFile(
        join(root, "gradlew"),
        `#!/usr/bin/env node
import { writeFileSync } from "node:fs";
writeFileSync(
  "gradlew-invocation.json",
  JSON.stringify({
    args: process.argv.slice(2),
    runIdEnvironment: process.env.HIGH_CONTENTION_RUN_ID ?? null,
  }),
);
`,
    );
    await chmod(join(root, "smoke-validate.sh"), 0o755);
    await chmod(join(root, "gradlew"), 0o755);
    return root;
}

async function run(cwd, runId) {
    return new Promise((resolve, reject) => {
        const child = spawn("./smoke-validate.sh", ["high-contention-ci"], {
            cwd,
            env: { ...process.env, HIGH_CONTENTION_RUN_ID: runId },
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stdout = "";
        let stderr = "";
        child.stdout.on("data", (chunk) => {
            stdout += chunk;
        });
        child.stderr.on("data", (chunk) => {
            stderr += chunk;
        });
        child.on("error", reject);
        child.on("close", (code) => resolve({ code, stdout, stderr }));
    });
}
