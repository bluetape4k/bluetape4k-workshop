import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { join, relative } from "node:path";
import test from "node:test";

const SOURCE_ROOTS = [
    "operations/job-console-core/src/testFixtures/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention",
    "operations/job-console-core/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/highcontention",
    "operations/job-console-spring/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/spring",
    "operations/job-console-ktor/src/test/kotlin/io/bluetape4k/workshop/operations/jobconsole/ktor",
    "commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/highcontention",
];

const FORBIDDEN = [
    ["raw sleep", /Thread\.sleep\s*\(/u],
    ["raw PostgreSQL datasource", /PGSimpleDataSource/u],
    ["per-test Exposed connection", /Database\.connect\s*\(/u],
    ["raw Toxiproxy container", /ToxiproxyContainer/u],
    ["Toxiproxy singleton launcher", /ToxiproxyServer\.Launcher/u],
    ["global Redis flush", /\b(?:FLUSHALL|flushAll)\b/u],
];

test("high-contention sources keep polling and infrastructure behind approved boundaries", async () => {
    const violations = [];
    for (const root of SOURCE_ROOTS) {
        for (const path of await kotlinFiles(root)) {
            const source = await readFile(path, "utf8");
            for (const [label, pattern] of FORBIDDEN) {
                if (pattern.test(source)) {
                    violations.push(`${relative(".", path)}: ${label}`);
                }
            }
        }
    }
    assert.deepEqual(violations, []);
});

test("hosted parent and nested child Gradle runtimes stay within runner memory", async () => {
    const examples = await readFile(".github/workflows/Examples.yml", "utf8");
    const nightly = await readFile(".github/workflows/nightly.yml", "utf8");
    const coordinator = await readFile(
        "buildSrc/src/main/kotlin/HighContentionSuiteTask.kt",
        "utf8",
    );

    for (const workflow of [examples, nightly]) {
        assert.match(workflow, /GRADLE_OPTS: "-Dorg\.gradle\.jvmargs=-Xmx2g/u);
        assert.match(workflow, /-Pkotlin\.compiler\.execution\.strategy=in-process/u);
    }
    assert.match(coordinator, /"-Dorg\.gradle\.jvmargs=-Xmx2g"/u);
    assert.match(
        coordinator,
        /"-Pkotlin\.compiler\.execution\.strategy=in-process"/u,
    );
});

async function kotlinFiles(root) {
    const files = [];
    for (const entry of await readdir(root, { withFileTypes: true })) {
        const path = join(root, entry.name);
        if (entry.isDirectory()) {
            files.push(...await kotlinFiles(path));
        } else if (entry.isFile() && entry.name.endsWith(".kt")) {
            files.push(path);
        }
    }
    return files.sort();
}
