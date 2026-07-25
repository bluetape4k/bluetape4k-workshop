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
        "build-logic/src/main/kotlin/io/bluetape4k/workshop/buildlogic/highcontention/HighContentionSuiteTask.kt",
        "utf8",
    );

    for (const workflow of [examples, nightly]) {
        assert.match(workflow, /GRADLE_OPTS: "-Dorg\.gradle\.jvmargs=-Xmx2g/u);
        assert.match(workflow, /-Pkotlin\.compiler\.execution\.strategy=in-process/u);
    }
    assert.equal(
        [...examples.matchAll(/- ['"]build-logic\/\*\*['"]/gu)].length,
        2,
        "push and pull_request filters must watch the included build",
    );
    assert.match(coordinator, /"-Dorg\.gradle\.jvmargs=-Xmx2g"/u);
    assert.match(
        coordinator,
        /"-Pkotlin\.compiler\.execution\.strategy=in-process"/u,
    );
});

test("high-contention Gradle code is isolated in an explicitly applied included plugin build", async () => {
    const settings = await readFile("settings.gradle.kts", "utf8");
    const rootBuild = await readFile("build.gradle.kts", "utf8");
    const moduleBuilds = await Promise.all(
        [
            "operations/job-console-core/build.gradle.kts",
            "operations/job-console-spring/build.gradle.kts",
            "operations/job-console-ktor/build.gradle.kts",
            "commerce/concert-ticket-flash-sale/build.gradle.kts",
        ].map((path) => readFile(path, "utf8")),
    );

    assert.match(settings, /includeBuild\("build-logic"\)/u);
    assert.match(
        rootBuild,
        /id\("io\.bluetape4k\.workshop\.high-contention-root"\)/u,
    );
    for (const moduleBuild of moduleBuilds) {
        assert.match(
            moduleBuild,
            /id\("io\.bluetape4k\.workshop\.high-contention-profile"\)/u,
        );
    }
    for (const file of [
        "HighContentionArtifactValidator.kt",
        "HighContentionProcessProbeTask.kt",
        "HighContentionProfileTasks.kt",
        "HighContentionSuiteTask.kt",
    ]) {
        await assert.rejects(
            readFile(`buildSrc/src/main/kotlin/${file}`, "utf8"),
            { code: "ENOENT" },
        );
    }
});

test("PR-gated high-contention validation checks the shared diagram artifacts", async () => {
    const examples = await readFile(".github/workflows/Examples.yml", "utf8");
    const smoke = await readFile("scripts/smoke-validate.sh", "utf8");
    const readmeValidator = await readFile("scripts/validate-high-contention-readme.mjs", "utf8");

    assert.match(
        examples,
        /\.\/scripts\/smoke-validate\.sh high-contention-contract/u,
    );
    assert.match(
        smoke,
        /node scripts\/validate-high-contention-readme\.mjs/u,
    );
    assert.match(
        readmeValidator,
        /high-contention-profile-runner-architecture-01\.svg/u,
    );
    assert.match(readmeValidator, /\blstat\b/u);
    assert.match(readmeValidator, /data-connector/u);
    assert.match(readmeValidator, /marker-end/u);
    assert.match(readmeValidator, /process \\?\+ container reaping/u);
    assert.match(readmeValidator, /evidence-to-cleanup/u);
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
