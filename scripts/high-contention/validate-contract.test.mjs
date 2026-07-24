import assert from "node:assert/strict";
import { cp, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { validateContractRoot } from "./validate-contract.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const canonicalContractRoot = join(repositoryRoot, "profiles/high-contention/v1");

async function withContractCopy(run) {
    const temporaryRoot = await mkdtemp(join(tmpdir(), "high-contention-contract-"));
    const contractRoot = join(temporaryRoot, "v1");
    await cp(canonicalContractRoot, contractRoot, { recursive: true });

    try {
        await run(contractRoot);
    } finally {
        await rm(temporaryRoot, { force: true, recursive: true });
    }
}

async function readJson(path) {
    return JSON.parse(await readFile(path, "utf8"));
}

async function writeJson(path, value) {
    await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

async function expectInvalid(mutator, expectedMessage) {
    await withContractCopy(async (contractRoot) => {
        await mutator(contractRoot);
        await assert.rejects(
            validateContractRoot(contractRoot),
            (error) => {
                assert.match(error.message, expectedMessage);
                return true;
            },
        );
    });
}

test("canonical v1 contract validates with the complete ordered matrix", async () => {
    const result = await validateContractRoot(canonicalContractRoot);

    assert.equal(result.profileDocumentCount, 14);
    assert.deepEqual(result.implementations, [
        "job-core",
        "job-spring",
        "job-ktor",
        "ticket-spring",
    ]);
    assert.equal(result.matrixEntryCount, 56);
});

test("missing schema versions fail closed", async () => {
    await expectInvalid(async (contractRoot) => {
        const profilePath = join(contractRoot, "profiles/ci-correctness/burst.json");
        const profile = await readJson(profilePath);
        delete profile.profileSchemaVersion;
        await writeJson(profilePath, profile);
    }, /profileSchemaVersion/);
});

test("duplicate JSON object keys are rejected before normal parsing", async () => {
    await expectInvalid(async (contractRoot) => {
        const manifestPath = join(contractRoot, "suite-manifest.json");
        const manifest = await readFile(manifestPath, "utf8");
        await writeFile(
            manifestPath,
            manifest.replace(
                '"suiteSchemaVersion": 1,',
                '"suiteSchemaVersion": 1,\n  "suiteSchemaVersion": 1,',
            ),
            "utf8",
        );
    }, /duplicate object key.*suiteSchemaVersion/i);
});

test("duplicate mode profile implementation tuples are rejected", async () => {
    await expectInvalid(async (contractRoot) => {
        const manifestPath = join(contractRoot, "suite-manifest.json");
        const manifest = await readJson(manifestPath);
        manifest.entries[0].implementations.push(manifest.entries[0].implementations[0]);
        await writeJson(manifestPath, manifest);
    }, /duplicate matrix tuple/i);
});

test("profile paths cannot escape the trusted contract root", async () => {
    await expectInvalid(async (contractRoot) => {
        const manifestPath = join(contractRoot, "suite-manifest.json");
        const manifest = await readJson(manifestPath);
        manifest.entries[0].profileFile = "../escape.json";
        await writeJson(manifestPath, manifest);
    }, /profileFile.*normalized descendant/i);
});

test("step operation totals must match epoch totals", async () => {
    await expectInvalid(async (contractRoot) => {
        const profilePath = join(contractRoot, "profiles/ci-correctness/redis-path-outage.json");
        const profile = await readJson(profilePath);
        profile.epochs[0].operationCount += 1;
        await writeJson(profilePath, profile);
    }, /operationCount.*epoch/i);
});

test("cleanup reserve must cover action budgets and report finalization", async () => {
    await expectInvalid(async (contractRoot) => {
        const profilePath = join(contractRoot, "profiles/ci-correctness/worker-restart.json");
        const profile = await readJson(profilePath);
        profile.cleanupReserveMs = 1;
        await writeJson(profilePath, profile);
    }, /cleanupReserveMs/);
});

test("unknown closed enum values are rejected", async () => {
    await expectInvalid(async (contractRoot) => {
        const profilePath = join(contractRoot, "profiles/ci-correctness/slow-provider.json");
        const profile = await readJson(profilePath);
        profile.failure.kind = "surprise-chaos";
        await writeJson(profilePath, profile);
    }, /failure.kind/);
});

test("unknown profile fields are rejected", async () => {
    await expectInvalid(async (contractRoot) => {
        const profilePath = join(contractRoot, "profiles/ci-correctness/burst.json");
        const profile = await readJson(profilePath);
        profile.unreviewedOption = true;
        await writeJson(profilePath, profile);
    }, /unknown field.*unreviewedOption/i);
});

test("child descriptors remain comparison evidence and cannot carry configuration", async () => {
    await expectInvalid(async (contractRoot) => {
        const descriptorContractPath = join(contractRoot, "child-descriptor-contract.json");
        const descriptorContract = await readJson(descriptorContractPath);
        descriptorContract.allowedFields.push("outputRoot");
        await writeJson(descriptorContractPath, descriptorContract);
    }, /forbidden descriptor field.*outputRoot/i);
});

test("vector digests detect changed golden data", async () => {
    await expectInvalid(async (contractRoot) => {
        const vectorPath = join(contractRoot, "schedule-vectors.json");
        const vectors = await readJson(vectorPath);
        vectors.vectors[0].expectedTokens[0].offsetNanos += 1;
        await writeJson(vectorPath, vectors);
    }, /schedule vector digest/i);
});

test("symlinked profile components are rejected", async (context) => {
    if (process.platform === "win32") {
        context.skip("symlink creation is not portable on Windows");
        return;
    }

    await expectInvalid(async (contractRoot) => {
        const profilesPath = join(contractRoot, "profiles");
        const realProfilesPath = join(contractRoot, "profiles-real");
        await cp(profilesPath, realProfilesPath, { recursive: true });
        await rm(profilesPath, { recursive: true });
        await symlink(realProfilesPath, profilesPath, "dir");
    }, /symbolic link/i);
});
