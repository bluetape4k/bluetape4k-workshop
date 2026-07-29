import { createHash } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { lstat, open, realpath } from "node:fs/promises";
import { dirname, isAbsolute, join, normalize, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const PROFILE_FIELDS = [
    "profileSchemaVersion",
    "profileId",
    "mode",
    "seed",
    "arrivalCurve",
    "operationCount",
    "concurrency",
    "dispatcherBacklogCapacity",
    "maxScheduleDelayMs",
    "warmupOperationCount",
    "workloadDurationMs",
    "epochs",
    "retryShape",
    "contentionShape",
    "expectedSubmissionOutcomes",
    "failure",
    "operationTimeoutMs",
    "injectionDeadlineMs",
    "failureDetectionDeadlineMs",
    "workloadJoinDeadlineMs",
    "recoveryDeadlineMs",
    "cleanupActionBudgetsMs",
    "reportFinalizeReserveMs",
    "cleanupReserveMs",
    "profileDeadlineMs",
    "expectedInvariants",
    "observationFields",
    "knownLimitations",
];

const MODES = ["ci-correctness", "local-reference"];
const ARRIVAL_CURVES = ["burst", "step", "retry-storm"];
const FAILURE_KINDS = [
    "none",
    "duplicate-submission",
    "redis-path-outage",
    "redis-key-loss",
    "slow-provider",
    "worker-restart",
    "duplicate-delivery",
];
const IMPLEMENTATIONS = ["job-core", "job-spring", "job-ktor", "ticket-spring"];
const PROFILE_IDS = [
    "burst",
    "duplicate-storm",
    "redis-path-outage",
    "redis-key-loss",
    "slow-provider",
    "worker-restart",
    "duplicate-delivery",
];
const FAILURE_BY_PROFILE = new Map([
    ["burst", "none"],
    ["duplicate-storm", "duplicate-submission"],
    ["redis-path-outage", "redis-path-outage"],
    ["redis-key-loss", "redis-key-loss"],
    ["slow-provider", "slow-provider"],
    ["worker-restart", "worker-restart"],
    ["duplicate-delivery", "duplicate-delivery"],
]);
const DESCRIPTOR_FIELDS = [
    "childDescriptorSchemaVersion",
    "runId",
    "profileId",
    "mode",
    "implementation",
    "parentManifestDigest",
    "resourceLabels",
    "descriptorDigest",
];
const FORBIDDEN_DESCRIPTOR_FIELDS = [
    "contractRoot",
    "outputRoot",
    "modeOverride",
    "implementationOverride",
    "profileFile",
    "dockerHost",
    "toxiproxyControlEndpoint",
];
const REPORT_RESULTS = ["PASS", "FAIL", "ERROR", "UNAVAILABLE"];
const REPORT_ERROR_CODES = [
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
const REPORT_SUBMISSION_DISPOSITIONS = [
    "SUCCEEDED",
    "FAILED_CLOSED",
    "EXECUTION_FAILED",
    "LOCALLY_REJECTED",
    "CANCELLED",
    "TIMED_OUT",
    "DUPLICATE_SUPPRESSED",
    "IGNORED_FENCED",
];
const REPORT_FAILURE_POINTS = [
    "NONE",
    "BEFORE_AUTHORITY",
    "AFTER_AUTHORITY",
    "UNKNOWN",
];
const REPORT_REQUIRED_TOP_LEVEL_FIELDS = [
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

class StrictJsonParser {
    constructor(text, source) {
        this.text = text;
        this.source = source;
        this.index = 0;
    }

    parse() {
        this.skipWhitespace();
        const value = this.parseValue();
        this.skipWhitespace();
        if (this.index !== this.text.length) {
            this.fail("unexpected trailing content");
        }
        return value;
    }

    parseValue() {
        this.skipWhitespace();
        const character = this.text[this.index];
        if (character === "{") return this.parseObject();
        if (character === "[") return this.parseArray();
        if (character === '"') return this.parseString();
        if (character === "-" || this.isDigit(character)) return this.parseNumber();
        if (this.text.startsWith("true", this.index)) return this.consumeLiteral("true", true);
        if (this.text.startsWith("false", this.index)) return this.consumeLiteral("false", false);
        if (this.text.startsWith("null", this.index)) return this.consumeLiteral("null", null);
        this.fail("expected a JSON value");
    }

    parseObject() {
        const value = {};
        const keys = new Set();
        this.expect("{");
        this.skipWhitespace();
        if (this.peek("}")) {
            this.index += 1;
            return value;
        }

        while (true) {
            this.skipWhitespace();
            if (!this.peek('"')) this.fail("expected an object key");
            const key = this.parseString();
            if (keys.has(key)) {
                this.fail(`duplicate object key "${key}"`);
            }
            keys.add(key);
            this.skipWhitespace();
            this.expect(":");
            value[key] = this.parseValue();
            this.skipWhitespace();
            if (this.peek("}")) {
                this.index += 1;
                return value;
            }
            this.expect(",");
        }
    }

    parseArray() {
        const value = [];
        this.expect("[");
        this.skipWhitespace();
        if (this.peek("]")) {
            this.index += 1;
            return value;
        }

        while (true) {
            value.push(this.parseValue());
            this.skipWhitespace();
            if (this.peek("]")) {
                this.index += 1;
                return value;
            }
            this.expect(",");
        }
    }

    parseString() {
        const start = this.index;
        this.index += 1;
        let escaped = false;
        while (this.index < this.text.length) {
            const character = this.text[this.index];
            this.index += 1;
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character === "\\") {
                escaped = true;
                continue;
            }
            if (character === '"') {
                const raw = this.text.slice(start, this.index);
                try {
                    return JSON.parse(raw);
                } catch (error) {
                    this.fail(`invalid JSON string: ${error.message}`);
                }
            }
        }
        this.fail("unterminated JSON string");
    }

    parseNumber() {
        const remaining = this.text.slice(this.index);
        const match = remaining.match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
        if (!match) this.fail("invalid JSON number");
        this.index += match[0].length;
        const value = Number(match[0]);
        if (!Number.isFinite(value)) this.fail("JSON number is not finite");
        return value;
    }

    consumeLiteral(literal, value) {
        this.index += literal.length;
        return value;
    }

    expect(character) {
        this.skipWhitespace();
        if (!this.peek(character)) this.fail(`expected "${character}"`);
        this.index += 1;
    }

    peek(character) {
        return this.text[this.index] === character;
    }

    skipWhitespace() {
        while (this.index < this.text.length && /\s/u.test(this.text[this.index])) {
            this.index += 1;
        }
    }

    isDigit(character) {
        return character !== undefined && character >= "0" && character <= "9";
    }

    fail(message) {
        throw new Error(`${this.source}: ${message} at offset ${this.index}`);
    }
}

export function parseStrictJson(text, source = "JSON") {
    return new StrictJsonParser(text, source).parse();
}

export function canonicalJson(value) {
    if (Array.isArray(value)) {
        return `[${value.map(canonicalJson).join(",")}]`;
    }
    if (value !== null && typeof value === "object") {
        return `{${Object.keys(value)
            .sort()
            .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
            .join(",")}}`;
    }
    return JSON.stringify(value);
}

export function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

function assertObject(value, label) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        throw new Error(`${label} must be an object`);
    }
    return value;
}

function assertArray(value, label) {
    if (!Array.isArray(value)) throw new Error(`${label} must be an array`);
    return value;
}

function assertExactFields(value, allowedFields, label) {
    const object = assertObject(value, label);
    const allowed = new Set(allowedFields);
    for (const field of Object.keys(object)) {
        if (!allowed.has(field)) throw new Error(`${label}: unknown field "${field}"`);
    }
    for (const field of allowedFields) {
        if (!Object.hasOwn(object, field)) throw new Error(`${label}: missing field "${field}"`);
    }
    return object;
}

function assertString(value, label) {
    if (typeof value !== "string" || value.length === 0) {
        throw new Error(`${label} must be a non-empty string`);
    }
    return value;
}

function assertStringArray(value, label) {
    const array = assertArray(value, label);
    const seen = new Set();
    for (const [index, entry] of array.entries()) {
        const validEntry = assertString(entry, `${label}[${index}]`);
        if (seen.has(validEntry)) throw new Error(`${label} contains duplicate "${validEntry}"`);
        seen.add(validEntry);
    }
    return array;
}

function assertExactStringArray(value, expected, label) {
    const actual = assertStringArray(value, label);
    if (actual.length !== expected.length || actual.some((entry, index) => entry !== expected[index])) {
        throw new Error(`${label} must equal ${JSON.stringify(expected)}`);
    }
}

function assertInteger(value, label, { min = 0, max = Number.MAX_SAFE_INTEGER } = {}) {
    if (!Number.isSafeInteger(value) || value < min || value > max) {
        throw new Error(`${label} must be an integer in [${min}, ${max}]`);
    }
    return value;
}

function assertEnum(value, allowedValues, label) {
    if (!allowedValues.includes(value)) {
        throw new Error(`${label} must be one of ${allowedValues.join(", ")}`);
    }
    return value;
}

function assertNormalizedDescendantPath(value, label) {
    const path = assertString(value, label);
    if (
        isAbsolute(path) ||
        path.includes("\\") ||
        normalize(path) !== path ||
        path === "." ||
        path.startsWith("../") ||
        path.includes("/../") ||
        path.endsWith("/..")
    ) {
        throw new Error(`${label} must be a normalized descendant path`);
    }
    return path;
}

export async function readStrictJson(contractRoot, relativePath) {
    const safeRelativePath = assertNormalizedDescendantPath(relativePath, relativePath);
    const rootPath = resolve(contractRoot);
    const rootMetadata = await lstat(rootPath);
    if (rootMetadata.isSymbolicLink()) throw new Error(`${rootPath} cannot be a symbolic link`);
    if (!rootMetadata.isDirectory()) throw new Error(`${rootPath} must be a directory`);
    const trustedRealRoot = await realpath(rootPath);

    let current = rootPath;
    for (const component of safeRelativePath.split("/")) {
        current = join(current, component);
        const metadata = await lstat(current);
        if (metadata.isSymbolicLink()) {
            throw new Error(`${safeRelativePath} contains a symbolic link component`);
        }
    }

    const resolvedFile = await realpath(current);
    if (resolvedFile !== trustedRealRoot && !resolvedFile.startsWith(`${trustedRealRoot}${sep}`)) {
        throw new Error(`${safeRelativePath} escapes the trusted contract root`);
    }

    const fileHandle = await open(current, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    try {
        const before = await fileHandle.stat({ bigint: true });
        if (!before.isFile()) throw new Error(`${safeRelativePath} must be a regular file`);
        const text = await fileHandle.readFile("utf8");
        const after = await fileHandle.stat({ bigint: true });
        if (
            before.dev !== after.dev ||
            before.ino !== after.ino ||
            before.size !== after.size ||
            before.mtimeNs !== after.mtimeNs
        ) {
            throw new Error(`${safeRelativePath} changed while being read`);
        }
        return parseStrictJson(text, safeRelativePath);
    } finally {
        await fileHandle.close();
    }
}

function validateProfileContract(contract) {
    assertExactFields(
        contract,
        [
            "contractSchemaVersion",
            "profileSchemaVersion",
            "allowedFields",
            "modes",
            "arrivalCurves",
            "failureKinds",
            "limits",
        ],
        "profile-contract.json",
    );
    assertInteger(contract.contractSchemaVersion, "profile contract contractSchemaVersion", { min: 1, max: 1 });
    assertInteger(contract.profileSchemaVersion, "profile contract profileSchemaVersion", { min: 1, max: 1 });
    assertExactStringArray(contract.allowedFields, PROFILE_FIELDS, "profile contract allowedFields");
    assertExactStringArray(contract.modes, MODES, "profile contract modes");
    assertExactStringArray(contract.arrivalCurves, ARRIVAL_CURVES, "profile contract arrivalCurves");
    assertExactStringArray(contract.failureKinds, FAILURE_KINDS, "profile contract failureKinds");
    assertExactFields(contract.limits, MODES, "profile contract limits");
    for (const mode of MODES) {
        const limits = assertExactFields(
            contract.limits[mode],
            ["maxOperationCount", "maxConcurrency", "maxWarmupOperationCount", "maxProfileDeadlineMs"],
            `profile contract limits.${mode}`,
        );
        for (const [field, value] of Object.entries(limits)) {
            assertInteger(value, `profile contract limits.${mode}.${field}`, { min: 1 });
        }
    }
}

function validateReportContract(contract) {
    assertExactFields(
        contract,
        [
            "contractSchemaVersion",
            "reportSchemaVersion",
            "results",
            "errorCodes",
            "submissionDispositions",
            "failurePoints",
            "scheduleObservations",
            "cleanupResults",
            "requiredTopLevelFields",
            "forbiddenEvidencePatterns",
        ],
        "report-contract.json",
    );
    assertInteger(contract.contractSchemaVersion, "report contract contractSchemaVersion", { min: 1, max: 1 });
    assertInteger(contract.reportSchemaVersion, "report contract reportSchemaVersion", { min: 1, max: 1 });
    assertExactStringArray(contract.results, REPORT_RESULTS, "report contract results");
    assertExactStringArray(contract.errorCodes, REPORT_ERROR_CODES, "report contract errorCodes");
    assertExactStringArray(
        contract.submissionDispositions,
        REPORT_SUBMISSION_DISPOSITIONS,
        "report contract submissionDispositions",
    );
    assertExactStringArray(contract.failurePoints, REPORT_FAILURE_POINTS, "report contract failurePoints");
    assertExactStringArray(
        contract.scheduleObservations,
        ["ON_TIME", "MISSED_DEADLINE"],
        "report contract scheduleObservations",
    );
    assertExactStringArray(contract.cleanupResults, ["PASS", "FAIL"], "report contract cleanupResults");
    assertExactStringArray(
        contract.requiredTopLevelFields,
        REPORT_REQUIRED_TOP_LEVEL_FIELDS,
        "report contract requiredTopLevelFields",
    );
    for (const field of ["forbiddenEvidencePatterns"]) {
        assertStringArray(contract[field], `report contract ${field}`);
    }
}

function validateDescriptorContract(contract) {
    assertExactFields(
        contract,
        [
            "contractSchemaVersion",
            "childDescriptorSchemaVersion",
            "allowedFields",
            "resourceLabelFields",
            "requiredLabelKeys",
            "forbiddenConfigurationFields",
        ],
        "child-descriptor-contract.json",
    );
    assertInteger(contract.contractSchemaVersion, "descriptor contract contractSchemaVersion", { min: 1, max: 1 });
    assertInteger(contract.childDescriptorSchemaVersion, "descriptor contract childDescriptorSchemaVersion", {
        min: 1,
        max: 1,
    });
    const allowedFields = assertStringArray(contract.allowedFields, "descriptor contract allowedFields");
    for (const forbiddenField of FORBIDDEN_DESCRIPTOR_FIELDS) {
        if (allowedFields.includes(forbiddenField)) {
            throw new Error(`forbidden descriptor field "${forbiddenField}" cannot be allowed`);
        }
    }
    assertExactStringArray(allowedFields, DESCRIPTOR_FIELDS, "descriptor contract allowedFields");
    assertExactStringArray(
        contract.forbiddenConfigurationFields,
        FORBIDDEN_DESCRIPTOR_FIELDS,
        "descriptor contract forbiddenConfigurationFields",
    );
    assertStringArray(contract.resourceLabelFields, "descriptor contract resourceLabelFields");
    assertStringArray(contract.requiredLabelKeys, "descriptor contract requiredLabelKeys");
}

function validateProfile(profile, entry, limits) {
    assertExactFields(profile, PROFILE_FIELDS, entry.profileFile);
    assertInteger(profile.profileSchemaVersion, `${entry.profileFile}.profileSchemaVersion`, { min: 1, max: 1 });
    if (profile.profileId !== entry.profileId) {
        throw new Error(`${entry.profileFile}.profileId must match manifest profileId`);
    }
    if (profile.mode !== entry.mode) throw new Error(`${entry.profileFile}.mode must match manifest mode`);
    assertString(profile.seed, `${entry.profileFile}.seed`);
    assertEnum(profile.arrivalCurve, ARRIVAL_CURVES, `${entry.profileFile}.arrivalCurve`);
    const operationCount = assertInteger(profile.operationCount, `${entry.profileFile}.operationCount`, {
        min: 1,
        max: limits.maxOperationCount,
    });
    assertInteger(profile.concurrency, `${entry.profileFile}.concurrency`, { min: 1, max: limits.maxConcurrency });
    assertInteger(profile.dispatcherBacklogCapacity, `${entry.profileFile}.dispatcherBacklogCapacity`, {
        min: 1,
        max: limits.maxOperationCount,
    });
    assertInteger(profile.maxScheduleDelayMs, `${entry.profileFile}.maxScheduleDelayMs`, { min: 1 });
    assertInteger(profile.warmupOperationCount, `${entry.profileFile}.warmupOperationCount`, {
        min: 1,
        max: limits.maxWarmupOperationCount,
    });
    const workloadDurationMs = assertInteger(profile.workloadDurationMs, `${entry.profileFile}.workloadDurationMs`, {
        min: 1,
    });

    const epochs = assertArray(profile.epochs, `${entry.profileFile}.epochs`);
    if (profile.arrivalCurve === "step") {
        if (epochs.length === 0) throw new Error(`${entry.profileFile}.epochs must not be empty for step`);
        let epochOperationCount = 0;
        let epochDurationMs = 0;
        for (const [index, epoch] of epochs.entries()) {
            assertExactFields(epoch, ["durationMs", "operationCount"], `${entry.profileFile}.epochs[${index}]`);
            epochDurationMs += assertInteger(epoch.durationMs, `${entry.profileFile}.epochs[${index}].durationMs`, {
                min: 1,
            });
            epochOperationCount += assertInteger(
                epoch.operationCount,
                `${entry.profileFile}.epochs[${index}].operationCount`,
                { min: 1 },
            );
        }
        if (epochOperationCount !== operationCount) {
            throw new Error(`${entry.profileFile}.operationCount must equal the epoch operationCount total`);
        }
        if (epochDurationMs !== workloadDurationMs) {
            throw new Error(`${entry.profileFile}.workloadDurationMs must equal the epoch duration total`);
        }
        if (profile.retryShape !== null) throw new Error(`${entry.profileFile}.retryShape must be null for step`);
    } else if (profile.arrivalCurve === "retry-storm") {
        if (epochs.length !== 0) throw new Error(`${entry.profileFile}.epochs must be empty for retry-storm`);
        const retryShape = assertExactFields(
            profile.retryShape,
            ["identityCount", "attemptsPerIdentity", "epochDurationMs"],
            `${entry.profileFile}.retryShape`,
        );
        const identityCount = assertInteger(retryShape.identityCount, `${entry.profileFile}.retryShape.identityCount`, {
            min: 1,
        });
        const attemptsPerIdentity = assertInteger(
            retryShape.attemptsPerIdentity,
            `${entry.profileFile}.retryShape.attemptsPerIdentity`,
            { min: 2 },
        );
        if (identityCount * attemptsPerIdentity !== operationCount) {
            throw new Error(`${entry.profileFile}.operationCount must equal retry identityCount * attemptsPerIdentity`);
        }
        if (retryShape.epochDurationMs !== workloadDurationMs) {
            throw new Error(`${entry.profileFile}.workloadDurationMs must equal retry epochDurationMs`);
        }
    } else {
        if (epochs.length !== 0) throw new Error(`${entry.profileFile}.epochs must be empty for burst`);
        if (profile.retryShape !== null) throw new Error(`${entry.profileFile}.retryShape must be null for burst`);
    }

    const contentionShape = assertExactFields(
        profile.contentionShape,
        ["authorityCount", "hotAuthorityCount", "identityCount", "sameIdentityRatioPermille"],
        `${entry.profileFile}.contentionShape`,
    );
    const authorityCount = assertInteger(
        contentionShape.authorityCount,
        `${entry.profileFile}.contentionShape.authorityCount`,
        { min: 1, max: operationCount },
    );
    assertInteger(contentionShape.hotAuthorityCount, `${entry.profileFile}.contentionShape.hotAuthorityCount`, {
        min: 1,
        max: authorityCount,
    });
    assertInteger(contentionShape.identityCount, `${entry.profileFile}.contentionShape.identityCount`, {
        min: 1,
        max: operationCount,
    });
    assertInteger(
        contentionShape.sameIdentityRatioPermille,
        `${entry.profileFile}.contentionShape.sameIdentityRatioPermille`,
        { min: 0, max: 1000 },
    );

    const outcomes = assertExactFields(
        profile.expectedSubmissionOutcomes,
        ["minimumDispatched", "minimumCompleted", "maximumLocalRejected", "maximumMissedDeadline"],
        `${entry.profileFile}.expectedSubmissionOutcomes`,
    );
    const minimumDispatched = assertInteger(
        outcomes.minimumDispatched,
        `${entry.profileFile}.expectedSubmissionOutcomes.minimumDispatched`,
        { max: operationCount },
    );
    assertInteger(outcomes.minimumCompleted, `${entry.profileFile}.expectedSubmissionOutcomes.minimumCompleted`, {
        max: minimumDispatched,
    });
    assertInteger(
        outcomes.maximumLocalRejected,
        `${entry.profileFile}.expectedSubmissionOutcomes.maximumLocalRejected`,
        { max: operationCount },
    );
    assertInteger(
        outcomes.maximumMissedDeadline,
        `${entry.profileFile}.expectedSubmissionOutcomes.maximumMissedDeadline`,
        { max: operationCount },
    );

    const failure = assertExactFields(
        profile.failure,
        ["kind", "triggerAcceptedCount", "steps"],
        `${entry.profileFile}.failure`,
    );
    assertEnum(failure.kind, FAILURE_KINDS, `${entry.profileFile}.failure.kind`);
    if (failure.kind !== FAILURE_BY_PROFILE.get(profile.profileId)) {
        throw new Error(`${entry.profileFile}.failure.kind does not match profileId`);
    }
    assertInteger(failure.triggerAcceptedCount, `${entry.profileFile}.failure.triggerAcceptedCount`, {
        max: operationCount,
    });
    assertStringArray(failure.steps, `${entry.profileFile}.failure.steps`);

    for (const field of [
        "operationTimeoutMs",
        "injectionDeadlineMs",
        "failureDetectionDeadlineMs",
        "workloadJoinDeadlineMs",
        "recoveryDeadlineMs",
        "reportFinalizeReserveMs",
        "cleanupReserveMs",
    ]) {
        assertInteger(profile[field], `${entry.profileFile}.${field}`, { min: 1 });
    }
    assertInteger(profile.profileDeadlineMs, `${entry.profileFile}.profileDeadlineMs`, {
        min: 1,
        max: limits.maxProfileDeadlineMs,
    });

    const budgets = assertExactFields(
        profile.cleanupActionBudgetsMs,
        ["application", "clients", "toxiproxy", "redis", "postgresql", "network"],
        `${entry.profileFile}.cleanupActionBudgetsMs`,
    );
    const cleanupBudgetTotal = Object.entries(budgets).reduce(
        (total, [field, value]) =>
            total + assertInteger(value, `${entry.profileFile}.cleanupActionBudgetsMs.${field}`, { min: 1 }),
        0,
    );
    if (profile.cleanupReserveMs < cleanupBudgetTotal + profile.reportFinalizeReserveMs) {
        throw new Error(
            `${entry.profileFile}.cleanupReserveMs must cover cleanup action budgets and reportFinalizeReserveMs`,
        );
    }
    if (profile.cleanupReserveMs >= profile.profileDeadlineMs) {
        throw new Error(`${entry.profileFile}.cleanupReserveMs must be less than profileDeadlineMs`);
    }

    const invariants = assertExactFields(profile.expectedInvariants, ["job", "ticket"], `${entry.profileFile}.expectedInvariants`);
    assertStringArray(invariants.job, `${entry.profileFile}.expectedInvariants.job`);
    assertStringArray(invariants.ticket, `${entry.profileFile}.expectedInvariants.ticket`);
    assertStringArray(profile.observationFields, `${entry.profileFile}.observationFields`);
    assertStringArray(profile.knownLimitations, `${entry.profileFile}.knownLimitations`);
}

function validateVectorFile(value, label, additionalFields = []) {
    assertExactFields(value, ["schemaVersion", ...additionalFields, "vectors", "vectorsSha256"], label);
    assertInteger(value.schemaVersion, `${label}.schemaVersion`, { min: 1, max: 1 });
    const vectors = assertArray(value.vectors, `${label}.vectors`);
    if (vectors.length === 0) throw new Error(`${label}.vectors must not be empty`);
    const actualDigest = sha256(canonicalJson(vectors));
    if (actualDigest !== value.vectorsSha256) {
        const digestLabel = label.startsWith("schedule") ? "schedule vector digest" : "Redis key vector digest";
        throw new Error(`${digestLabel} mismatch: expected ${value.vectorsSha256}, actual ${actualDigest}`);
    }
}

function validateScheduleVectors(value) {
    validateVectorFile(value, "schedule-vectors.json", ["algorithm"]);
    if (value.algorithm !== "hc-v1-sha256-unsigned-rank") {
        throw new Error('schedule-vectors.json.algorithm must be "hc-v1-sha256-unsigned-rank"');
    }
    for (const [index, vector] of value.vectors.entries()) {
        assertExactFields(
            vector,
            [
                "name",
                "profileSchemaVersion",
                "seed",
                "curve",
                "operationCount",
                "durationNanos",
                "authorityWeights",
                "epochs",
                "retryShape",
                "expectedTokens",
            ],
            `schedule-vectors.json.vectors[${index}]`,
        );
        assertString(vector.name, `schedule-vectors.json.vectors[${index}].name`);
        const operationCount = assertInteger(
            vector.operationCount,
            `schedule-vectors.json.vectors[${index}].operationCount`,
            { min: 1 },
        );
        const expectedTokens = assertArray(
            vector.expectedTokens,
            `schedule-vectors.json.vectors[${index}].expectedTokens`,
        );
        if (expectedTokens.length !== operationCount) {
            throw new Error(`schedule-vectors.json.vectors[${index}] token count mismatch`);
        }
    }
}

function validateRedisVectors(value) {
    validateVectorFile(value, "redis-key-vectors.json");
    for (const [index, vector] of value.vectors.entries()) {
        assertExactFields(
            vector,
            ["name", "namespace", "deleteUpperBound", "cases"],
            `redis-key-vectors.json.vectors[${index}]`,
        );
        const namespace = assertString(vector.namespace, `redis-key-vectors.json.vectors[${index}].namespace`);
        if (!namespace.endsWith(":")) {
            throw new Error(`redis-key-vectors.json.vectors[${index}].namespace must end with a delimiter`);
        }
        assertInteger(vector.deleteUpperBound, `redis-key-vectors.json.vectors[${index}].deleteUpperBound`, {
            min: 1,
        });
        for (const [caseIndex, keyCase] of assertArray(
            vector.cases,
            `redis-key-vectors.json.vectors[${index}].cases`,
        ).entries()) {
            assertExactFields(
                keyCase,
                ["name", "key", "expectedOwned"],
                `redis-key-vectors.json.vectors[${index}].cases[${caseIndex}]`,
            );
            assertString(keyCase.name, `redis-key-vectors.json.vectors[${index}].cases[${caseIndex}].name`);
            assertString(keyCase.key, `redis-key-vectors.json.vectors[${index}].cases[${caseIndex}].key`);
            if (typeof keyCase.expectedOwned !== "boolean") {
                throw new Error(
                    `redis-key-vectors.json.vectors[${index}].cases[${caseIndex}].expectedOwned must be boolean`,
                );
            }
        }
    }
}

export async function validateContractRoot(contractRoot) {
    const profileContract = await readStrictJson(contractRoot, "profile-contract.json");
    const reportContract = await readStrictJson(contractRoot, "report-contract.json");
    const descriptorContract = await readStrictJson(contractRoot, "child-descriptor-contract.json");
    const scheduleVectors = await readStrictJson(contractRoot, "schedule-vectors.json");
    const redisVectors = await readStrictJson(contractRoot, "redis-key-vectors.json");
    const manifest = await readStrictJson(contractRoot, "suite-manifest.json");

    validateProfileContract(profileContract);
    validateReportContract(reportContract);
    validateDescriptorContract(descriptorContract);
    validateScheduleVectors(scheduleVectors);
    validateRedisVectors(redisVectors);

    assertExactFields(
        manifest,
        [
            "suiteSchemaVersion",
            "profileSchemaVersion",
            "reportSchemaVersion",
            "childDescriptorSchemaVersion",
            "runDeadlineMs",
            "runCleanupActionBudgetsMs",
            "runJournalFinalizeReserveMs",
            "runCleanupReserveMs",
            "dockerCleanupPollIntervalMs",
            "dockerCleanupQuietPeriodMs",
            "implementations",
            "entries",
        ],
        "suite-manifest.json",
    );
    for (const field of [
        "suiteSchemaVersion",
        "profileSchemaVersion",
        "reportSchemaVersion",
        "childDescriptorSchemaVersion",
    ]) {
        assertInteger(manifest[field], `suite-manifest.json.${field}`, { min: 1, max: 1 });
    }
    assertInteger(manifest.runDeadlineMs, "suite-manifest.json.runDeadlineMs", { min: 1 });
    const runBudgets = assertExactFields(
        manifest.runCleanupActionBudgetsMs,
        ["childProcesses", "dockerDiscovery", "artifactFinalization"],
        "suite-manifest.json.runCleanupActionBudgetsMs",
    );
    const runBudgetTotal = Object.entries(runBudgets).reduce(
        (total, [field, value]) =>
            total + assertInteger(value, `suite-manifest.json.runCleanupActionBudgetsMs.${field}`, { min: 1 }),
        0,
    );
    assertInteger(manifest.runJournalFinalizeReserveMs, "suite-manifest.json.runJournalFinalizeReserveMs", {
        min: 1,
    });
    assertInteger(manifest.runCleanupReserveMs, "suite-manifest.json.runCleanupReserveMs", { min: 1 });
    if (manifest.runCleanupReserveMs < runBudgetTotal + manifest.runJournalFinalizeReserveMs) {
        throw new Error(
            "suite-manifest.json.runCleanupReserveMs must cover action budgets and runJournalFinalizeReserveMs",
        );
    }
    if (manifest.runCleanupReserveMs >= manifest.runDeadlineMs) {
        throw new Error("suite-manifest.json.runCleanupReserveMs must be less than runDeadlineMs");
    }
    const pollInterval = assertInteger(
        manifest.dockerCleanupPollIntervalMs,
        "suite-manifest.json.dockerCleanupPollIntervalMs",
        { min: 1 },
    );
    const quietPeriod = assertInteger(
        manifest.dockerCleanupQuietPeriodMs,
        "suite-manifest.json.dockerCleanupQuietPeriodMs",
        { min: 1 },
    );
    if (quietPeriod < pollInterval * 2) {
        throw new Error("suite-manifest.json.dockerCleanupQuietPeriodMs must cover at least two polls");
    }
    if (runBudgets.dockerDiscovery < quietPeriod + pollInterval) {
        throw new Error("suite-manifest.json dockerDiscovery budget is too small for quiet-period convergence");
    }
    assertExactStringArray(manifest.implementations, IMPLEMENTATIONS, "suite-manifest.json.implementations");

    const entries = assertArray(manifest.entries, "suite-manifest.json.entries");
    if (entries.length !== MODES.length * PROFILE_IDS.length) {
        throw new Error(`suite-manifest.json.entries must contain ${MODES.length * PROFILE_IDS.length} profiles`);
    }

    const matrixTuples = new Set();
    const profileKeys = new Set();
    for (const [index, entry] of entries.entries()) {
        assertExactFields(
            entry,
            ["mode", "profileId", "profileFile", "implementations"],
            `suite-manifest.json.entries[${index}]`,
        );
        assertEnum(entry.mode, MODES, `suite-manifest.json.entries[${index}].mode`);
        assertEnum(entry.profileId, PROFILE_IDS, `suite-manifest.json.entries[${index}].profileId`);
        const profileKey = `${entry.mode}:${entry.profileId}`;
        if (profileKeys.has(profileKey)) throw new Error(`duplicate profile entry "${profileKey}"`);
        profileKeys.add(profileKey);
        assertNormalizedDescendantPath(entry.profileFile, `suite-manifest.json.entries[${index}].profileFile`);
        const expectedProfileFile = `profiles/${entry.mode}/${entry.profileId}.json`;
        if (entry.profileFile !== expectedProfileFile) {
            throw new Error(
                `suite-manifest.json.entries[${index}].profileFile must be ${expectedProfileFile}`,
            );
        }
        const entryImplementations = assertArray(
            entry.implementations,
            `suite-manifest.json.entries[${index}].implementations`,
        );
        const entryImplementationSet = new Set();
        for (const [implementationIndex, implementation] of entryImplementations.entries()) {
            assertString(
                implementation,
                `suite-manifest.json.entries[${index}].implementations[${implementationIndex}]`,
            );
            const tuple = `${entry.mode}:${entry.profileId}:${implementation}`;
            if (entryImplementationSet.has(implementation)) {
                throw new Error(`duplicate matrix tuple "${tuple}"`);
            }
            entryImplementationSet.add(implementation);
        }
        assertExactStringArray(
            entryImplementations,
            IMPLEMENTATIONS,
            `suite-manifest.json.entries[${index}].implementations`,
        );
        for (const implementation of entryImplementations) {
            const tuple = `${entry.mode}:${entry.profileId}:${implementation}`;
            if (matrixTuples.has(tuple)) throw new Error(`duplicate matrix tuple "${tuple}"`);
            matrixTuples.add(tuple);
        }
        const profile = await readStrictJson(contractRoot, entry.profileFile);
        validateProfile(profile, entry, profileContract.limits[entry.mode]);
    }

    return Object.freeze({
        profileDocumentCount: entries.length,
        implementations: Object.freeze([...IMPLEMENTATIONS]),
        matrixEntryCount: matrixTuples.size,
        scheduleVectorsSha256: scheduleVectors.vectorsSha256,
        redisKeyVectorsSha256: redisVectors.vectorsSha256,
    });
}

async function main() {
    const contractRoot = process.argv[2];
    if (!contractRoot) {
        throw new Error("usage: node scripts/high-contention/validate-contract.mjs <contract-root>");
    }
    const result = await validateContractRoot(contractRoot);
    process.stdout.write(`${JSON.stringify(result)}\n`);
}

const currentFile = fileURLToPath(import.meta.url);
const invokedFile = process.argv[1] ? resolve(process.argv[1]) : "";
if (invokedFile && pathToFileURL(invokedFile).href === pathToFileURL(currentFile).href) {
    main().catch((error) => {
        process.stderr.write(`high-contention contract validation failed: ${error.message}\n`);
        process.exitCode = 1;
    });
}
