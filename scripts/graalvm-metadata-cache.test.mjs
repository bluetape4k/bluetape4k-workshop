import assert from "node:assert/strict";
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";
import test from "node:test";

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "repair-graalvm-metadata-cache.sh");

test("removes incomplete repositories and preserves schema-bearing repositories", async () => {
    const gradleHome = await mkdtemp(join(tmpdir(), "graalvm-metadata-cache-"));
    const repositoryRoot = join(gradleHome, "native-build-tools", "repositories");
    const incomplete = join(repositoryRoot, "incomplete", "exploded");
    const complete = join(repositoryRoot, "complete", "exploded", "schemas");
    try {
        await mkdir(incomplete, { recursive: true });
        await mkdir(complete, { recursive: true });
        await writeFile(join(incomplete, "index.json"), "[]\n");
        await writeFile(join(complete, "reachability-metadata-schema.json"), "{}\n");

        const result = await run(gradleHome);

        assert.equal(result.code, 0, result.stderr);
        await assert.rejects(access(join(repositoryRoot, "incomplete")), { code: "ENOENT" });
        await access(join(repositoryRoot, "complete"));
        assert.match(result.stdout, /removed 1 incomplete repository entry/u);
    } finally {
        await rm(gradleHome, { recursive: true, force: true });
    }
});

test("does not create a cache when Gradle has not initialized one", async () => {
    const gradleHome = await mkdtemp(join(tmpdir(), "graalvm-metadata-cache-empty-"));
    try {
        const result = await run(gradleHome);

        assert.equal(result.code, 0, result.stderr);
        assert.match(result.stdout, /no existing repository cache/u);
        await assert.rejects(readFile(join(gradleHome, "native-build-tools", "repositories")), {
            code: "ENOENT",
        });
    } finally {
        await rm(gradleHome, { recursive: true, force: true });
    }
});

function run(gradleHome) {
    return new Promise((resolve, reject) => {
        const child = spawn("bash", [SCRIPT], {
            env: { ...process.env, GRADLE_USER_HOME: gradleHome },
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
