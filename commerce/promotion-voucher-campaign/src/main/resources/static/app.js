"use strict";

const state = {
    tenant: "",
    principal: "",
    campaignId: "",
    revision: 0,
    operatorSecret: "",
    streamAbort: null,
    reconnects: 0,
    fallbackAttempts: 0,
    lastEventId: null,
    latestClaim: null,
};

const elements = {
    connectionForm: document.querySelector("#connection-form"),
    tenant: document.querySelector("#tenant"),
    principal: document.querySelector("#principal"),
    campaignId: document.querySelector("#campaign-id"),
    campaignState: document.querySelector("#campaign-state"),
    remainingCapacity: document.querySelector("#remaining-capacity"),
    policyRevision: document.querySelector("#policy-revision"),
    streamState: document.querySelector("#stream-state"),
    redisState: document.querySelector("#redis-state"),
    reconciliationState: document.querySelector("#reconciliation-state"),
    liveStatus: document.querySelector("#live-status"),
    timeline: document.querySelector("#timeline"),
    allocate: document.querySelector("#allocate"),
    allocateReason: document.querySelector("#allocate-reason"),
    claimState: document.querySelector("#claim-state"),
    claimId: document.querySelector("#claim-id"),
    operatorSecret: document.querySelector("#operator-secret"),
    operatorActions: Array.from(document.querySelectorAll(".operator-action")),
    operatorDisabledReason: document.querySelector("#operator-disabled-reason"),
    scenario: document.querySelector("#scenario"),
    runScenario: document.querySelector("#run-scenario"),
    resetScenario: document.querySelector("#reset-scenario"),
    scenarioResult: document.querySelector("#scenario-result"),
    refreshOperatorEvidence: document.querySelector("#refresh-operator-evidence"),
    reviewList: document.querySelector("#review-list"),
    reconciliationList: document.querySelector("#reconciliation-list"),
    confirmation: document.querySelector("#confirmation"),
    confirmationTitle: document.querySelector("#confirmation-title"),
    confirmationDescription: document.querySelector("#confirmation-description"),
    confirmationAccept: document.querySelector("#confirmation-accept"),
};

let focusReturnTarget = null;
let pendingConfirmation = null;

function announce(message) {
    elements.liveStatus.textContent = message;
}

function setText(element, value) {
    element.textContent = value == null ? "—" : String(value);
}

function clearOperatorSecret() {
    state.operatorSecret = "";
    elements.operatorSecret.value = "";
    refreshActions();
}

function restoreFocus() {
    if (focusReturnTarget instanceof HTMLElement) {
        focusReturnTarget.focus();
    }
    focusReturnTarget = null;
}

function requestConfirmation(target, title, description, execute) {
    focusReturnTarget = target;
    pendingConfirmation = execute;
    elements.confirmationTitle.textContent = title;
    elements.confirmationDescription.textContent = description;
    elements.confirmation.returnValue = "";
    elements.confirmation.showModal();
    elements.confirmationAccept.focus();
}

function randomKey() {
    const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    const bytes = new Uint8Array(8);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, value => alphabet[value % alphabet.length]).join("");
}

function customerHeaders() {
    return {
        "Content-Type": "application/json",
        "X-Workshop-Tenant": state.tenant,
        "X-Workshop-Principal": state.principal,
    };
}

function operatorHeaders() {
    return {
        "Content-Type": "application/json",
        "X-Workshop-Tenant": state.tenant,
        "X-Workshop-Operator-Secret": state.operatorSecret,
        "X-Workshop-Guard": "voucher-workshop-operator",
        "X-Workshop-Origin": location.origin,
        "Origin": location.origin,
    };
}

async function api(path, options = {}) {
    const response = await fetch(path, { cache: "no-store", ...options });
    const payload = await response.json().catch(() => ({ code: "INVALID_RESPONSE", reason: "Response was not JSON" }));
    if (!response.ok) {
        const failure = new Error(payload.reason || payload.code || `HTTP ${response.status}`);
        failure.status = response.status;
        failure.payload = payload;
        failure.response = response;
        throw failure;
    }
    return payload;
}

function renderSnapshot(snapshot) {
    state.revision = Number(snapshot.revision || 0);
    setText(elements.campaignState, `${snapshot.state} · authoritative`);
    setText(elements.remainingCapacity, snapshot.remainingCapacity);
    setText(elements.policyRevision, `v${snapshot.policyVersion} / r${snapshot.revision}`);
    refreshActions(snapshot.state);
}

function addTimeline(label, detail, timestamp = new Date().toISOString()) {
    const item = document.createElement("li");
    const title = document.createElement("strong");
    const description = document.createElement("span");
    const time = document.createElement("time");
    title.textContent = label;
    description.textContent = ` · ${detail}`;
    time.dateTime = timestamp;
    time.textContent = `${timestamp} UTC · ${new Date(timestamp).toLocaleString()}`;
    item.append(title, description, time);
    elements.timeline.prepend(item);
}

function refreshActions(campaignState = elements.campaignState.textContent.split(" ")[0]) {
    const loaded = Boolean(state.campaignId);
    const active = campaignState === "ACTIVE";
    elements.allocate.disabled = !loaded || !active;
    elements.allocateReason.textContent = active ? "Ready. A fresh 8-character Base58 idempotency key will be used." : "Allocation requires an active campaign.";
    const operatorReady = loaded && state.operatorSecret.length > 0;
    const allowed = {
        activate: campaignState === "DRAFT" || campaignState === "PAUSED",
        pause: campaignState === "ACTIVE",
        end: campaignState === "ACTIVE" || campaignState === "PAUSED",
    };
    elements.operatorActions.forEach(button => {
        button.disabled = !operatorReady || !allowed[button.dataset.action];
        button.setAttribute("aria-describedby", "operator-disabled-reason");
        button.title = button.disabled ? `${button.textContent} is unavailable for ${campaignState || "the current state"}.` : "";
    });
    const availableActions = elements.operatorActions.filter(button => !button.disabled).map(button => button.textContent).join(", ");
    elements.operatorDisabledReason.textContent = operatorReady
        ? `Available for ${campaignState}: ${availableActions || "none"}.`
        : "Load a campaign and enter the operator secret.";
    elements.runScenario.disabled = !operatorReady;
    elements.resetScenario.disabled = !operatorReady;
    elements.refreshOperatorEvidence.disabled = !operatorReady;
}

async function customerCommand(path, body, key = randomKey(), principal = state.principal) {
    return api(path, {
        method: "POST",
        headers: {
            ...customerHeaders(),
            "X-Workshop-Principal": principal,
            "Idempotency-Key": key,
        },
        body: JSON.stringify(body),
    });
}

async function operatorCommand(path, body, key = randomKey(), extraHeaders = {}) {
    return api(path, {
        method: "POST",
        headers: { ...operatorHeaders(), ...extraHeaders, "Idempotency-Key": key },
        body: JSON.stringify(body),
    });
}

async function operatorQuery(path) {
    return api(path, { headers: operatorHeaders() });
}

async function customerQuery(path) {
    return api(path, { headers: customerHeaders() });
}

function emptyEvidenceItem(message) {
    const item = document.createElement("li");
    item.textContent = message;
    return item;
}

function renderReviews(page) {
    elements.reviewList.replaceChildren();
    if (page.items.length === 0) {
        elements.reviewList.append(emptyEvidenceItem("No open reviews"));
        return;
    }
    page.items.forEach(review => {
        const item = document.createElement("li");
        const summary = document.createElement("span");
        const actions = document.createElement("div");
        const approve = document.createElement("button");
        const reject = document.createElement("button");
        summary.textContent = `${review.kind} · ${review.reasonCode} · claim ${review.claimId} · revision ${review.revision}`;
        approve.type = "button";
        approve.textContent = "Approve";
        approve.setAttribute("aria-label", `Approve review ${review.id}`);
        reject.type = "button";
        reject.textContent = "Reject";
        reject.className = "danger";
        reject.setAttribute("aria-label", `Reject review ${review.id}`);
        [approve, reject].forEach((button, index) => {
            const decision = index === 0 ? "approve" : "reject";
            button.addEventListener("click", () => requestConfirmation(
                button,
                `Confirm review ${decision}`,
                `${decision} review ${review.id} at review revision ${review.revision}.`,
                async () => {
                    await operatorCommand(`/operator/api/v1/reviews/${review.id}/${decision}`, {
                        campaignId: review.campaignId,
                        claimId: review.claimId,
                        expectedReviewRevision: review.revision,
                        expectedClaimRevision: review.expectedClaimRevision,
                    });
                    await loadOperatorEvidence();
                    await loadSnapshot();
                    announce(`Review ${review.id} ${decision} completed`);
                },
            ));
        });
        actions.className = "button-row";
        actions.append(approve, reject);
        item.append(summary, actions);
        elements.reviewList.append(item);
    });
}

function renderReconciliationBacklog(page) {
    elements.reconciliationList.replaceChildren();
    if (page.items.length === 0) {
        elements.reconciliationList.append(emptyEvidenceItem("No reconciliation backlog"));
        setText(elements.reconciliationState, "No signal");
        return;
    }
    page.items.forEach(entry => {
        const item = document.createElement("li");
        item.textContent = `${entry.status} · ${entry.aggregateType} ${entry.aggregateId} · sequence ${entry.observedSequence} · attempt ${entry.attempt}`;
        elements.reconciliationList.append(item);
    });
    setText(elements.reconciliationState, `${page.items.length} visible backlog item(s)`);
}

async function loadOperatorEvidence() {
    const [reviews, backlog] = await Promise.all([
        operatorQuery("/operator/api/v1/reviews?status=OPEN&limit=20"),
        operatorQuery("/operator/api/v1/reconciliation/backlog?limit=20"),
    ]);
    renderReviews(reviews);
    renderReconciliationBacklog(backlog);
}

async function allocate(principal = state.principal, key = randomKey()) {
    const claim = await customerCommand(
        `/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/claims`,
        { userRef: principal },
        key,
        principal,
    );
    if (principal === state.principal) state.latestClaim = claim;
    return claim;
}

async function prepareFixture(scenario) {
    return operatorCommand(
        `/operator/api/v1/fixtures/${encodeURIComponent(scenario)}/run`,
        { principalRef: state.principal, campaignId: state.campaignId },
    );
}

async function resetTenant(capacity = 10) {
    if (state.streamAbort) state.streamAbort.abort();
    state.lastEventId = null;
    state.reconnects = 0;
    state.fallbackAttempts = 0;
    await operatorCommand("/operator/api/v1/fixtures/reset", {});
    const now = Date.now();
    await operatorCommand(
        "/operator/api/v1/campaigns",
        {
            campaignId: state.campaignId,
            startsAt: new Date(now - 60_000).toISOString(),
            endsAt: new Date(now + 3_600_000).toISOString(),
            capacity,
            perUserLimit: 1,
            redemptionTtlSeconds: 600,
        },
        randomKey(),
        { "If-None-Match": "*" },
    );
    await operatorCommand(`/operator/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/activate`, { expectedRevision: 0 });
    state.latestClaim = null;
    await loadSnapshot();
    connectStream();
}

async function redeemLatest(key = randomKey()) {
    const claim = state.latestClaim || await allocate();
    if (!claim.code) throw new Error("Scenario requires an allocated voucher code");
    return customerCommand(
        `/api/v1/claims/${encodeURIComponent(claim.claimId)}/redeem`,
        { code: claim.code, expectedRevision: claim.revision, redemptionReference: randomKey() },
        key,
    );
}

async function loadSnapshot() {
    const snapshot = await api(`/api/v1/campaigns/${encodeURIComponent(state.campaignId)}`, {
        headers: customerHeaders(),
    });
    renderSnapshot(snapshot);
    addTimeline("Snapshot", `${snapshot.state}, remaining ${snapshot.remainingCapacity}`, snapshot.observedAt);
    return snapshot;
}

function parseEventBlock(block) {
    const parsed = { id: null, event: "message", data: "" };
    block.split("\n").forEach(line => {
        const separator = line.indexOf(":");
        if (separator < 0) return;
        const field = line.slice(0, separator);
        const value = line.slice(separator + 1).trimStart();
        if (field === "id") parsed.id = value;
        if (field === "event") parsed.event = value;
        if (field === "data") parsed.data += value;
    });
    return parsed;
}

function renderStreamEvent(event) {
    if (event.id && event.id === state.lastEventId && event.event === "audit") return;
    if (event.id) state.lastEventId = event.id;
    const payload = event.data ? JSON.parse(event.data) : {};
    if (event.event === "snapshot" || event.event === "reset") {
        renderSnapshot(payload);
        announce(event.event === "reset" ? "Stream reset from authoritative snapshot" : "Authoritative snapshot received");
        addTimeline(event.event === "reset" ? "↻ Reset" : "● Snapshot", `${payload.state}, revision ${payload.revision}`, payload.observedAt);
    } else if (event.event === "audit") {
        addTimeline(`✓ ${payload.reasonCode}`, `${payload.aggregateType} revision ${payload.revision}`, payload.occurredAt || new Date().toISOString());
        loadSnapshot().catch(failure => announce(`Snapshot refresh failed: ${failure.message}`));
        if (String(payload.reasonCode).includes("RECONCILI")) {
            setText(elements.reconciliationState, `Observed · r${payload.revision}`);
        }
        announce(`Audit event ${payload.reasonCode}`);
    } else if (event.event === "error") {
        setText(elements.streamState, `Error · ${payload.code}`);
        announce(`Stream error: ${payload.reason || payload.code}`);
    }
}

function alternateSnapshotPath(response) {
    const link = response.headers.get("Link") || "";
    const match = link.match(/^<([^>]+)>;\s*rel="alternate"/);
    if (!match) return `/api/v1/campaigns/${encodeURIComponent(state.campaignId)}`;
    const alternate = new URL(match[1], location.origin);
    if (alternate.origin !== location.origin) throw new Error("Cross-origin fallback was rejected");
    return `${alternate.pathname}${alternate.search}`;
}

async function pollFallback(response) {
    const retryAfter = Math.min(Number(response.headers.get("Retry-After") || 2), 10);
    state.fallbackAttempts += 1;
    if (state.fallbackAttempts > 10) throw new Error("Stream capacity retry limit reached");
    setText(elements.streamState, `Polling fallback · ${retryAfter}s`);
    announce("Stream capacity reached. Using authoritative snapshot polling.");
    const snapshot = await api(alternateSnapshotPath(response), { headers: customerHeaders() });
    renderSnapshot(snapshot);
    addTimeline("Fallback snapshot", `${snapshot.state}, remaining ${snapshot.remainingCapacity}`, snapshot.observedAt);
    await new Promise(resolve => window.setTimeout(resolve, retryAfter * 1000));
}

async function connectStream() {
    if (state.streamAbort) state.streamAbort.abort();
    const abort = new AbortController();
    state.streamAbort = abort;
    const headers = customerHeaders();
    delete headers["Content-Type"];
    if (state.lastEventId) headers["Last-Event-ID"] = state.lastEventId;
    setText(elements.streamState, "Connecting");

    try {
        const response = await fetch(`/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/events`, {
            headers,
            cache: "no-store",
            signal: abort.signal,
        });
        if (!response.ok) {
            if (response.status === 503) {
                await pollFallback(response);
                if (!abort.signal.aborted) return connectStream();
            }
            throw new Error(`Stream rejected with HTTP ${response.status}`);
        }
        state.reconnects = 0;
        state.fallbackAttempts = 0;
        setText(elements.streamState, "Live · SSE");
        announce("Live event stream connected");
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (!abort.signal.aborted) {
            const chunk = await reader.read();
            if (chunk.done) break;
            buffer += decoder.decode(chunk.value, { stream: true });
            let boundary = buffer.indexOf("\n\n");
            while (boundary >= 0) {
                renderStreamEvent(parseEventBlock(buffer.slice(0, boundary)));
                buffer = buffer.slice(boundary + 2);
                boundary = buffer.indexOf("\n\n");
            }
        }
        if (!abort.signal.aborted) throw new Error("Stream closed");
    } catch (failure) {
        if (abort.signal.aborted) return;
        state.reconnects += 1;
        const delay = Math.min(500 * (2 ** state.reconnects), 5000);
        setText(elements.streamState, `Reconnecting · ${delay / 1000}s`);
        announce(`Stream disconnected. Reconnecting in ${delay / 1000} seconds.`);
        await new Promise(resolve => window.setTimeout(resolve, delay));
        if (!abort.signal.aborted) return connectStream();
    }
}

elements.connectionForm.addEventListener("submit", async event => {
    event.preventDefault();
    state.tenant = elements.tenant.value.trim();
    state.principal = elements.principal.value.trim();
    state.campaignId = elements.campaignId.value.trim();
    state.lastEventId = null;
    state.fallbackAttempts = 0;
    try {
        await loadSnapshot();
        connectStream();
    } catch (failure) {
        setText(elements.streamState, `Failed · ${failure.payload?.code || failure.message}`);
        announce(`Campaign load failed: ${failure.message}`);
    }
});

elements.allocate.addEventListener("click", async () => {
    try {
        const claim = await allocate();
        setText(elements.claimState, claim.state);
        setText(elements.claimId, claim.claimId);
        setText(elements.reconciliationState, claim.reviewId ? `Review ${claim.reviewId} pending` : "No pending review");
        addTimeline(claim.reviewId ? "⚠ Review required" : "✓ Voucher allocated", `claim ${claim.claimId}`);
        announce(claim.reviewId ? "Allocation requires review" : "Voucher allocated");
    } catch (failure) {
        announce(`Allocation failed: ${failure.message}`);
    }
});

elements.operatorSecret.addEventListener("change", () => {
    state.operatorSecret = elements.operatorSecret.value;
    elements.operatorSecret.value = "";
    refreshActions();
    announce("Operator secret captured in session memory");
    loadOperatorEvidence().catch(failure => announce(`Operator evidence refresh failed: ${failure.message}`));
});

elements.refreshOperatorEvidence.addEventListener("click", () => {
    loadOperatorEvidence()
        .then(() => announce("Operator evidence refreshed"))
        .catch(failure => announce(`Operator evidence refresh failed: ${failure.message}`));
});

elements.operatorActions.forEach(button => {
    button.addEventListener("click", () => {
        const action = button.dataset.action;
        const expectedRevision = state.revision;
        requestConfirmation(
            button,
            `Confirm ${action}`,
            `${action} changes authoritative campaign state at revision ${expectedRevision}.`,
            async () => {
                try {
                    const updated = await api(`/operator/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/${action}`, {
                        method: "POST",
                        headers: { ...operatorHeaders(), "Idempotency-Key": randomKey() },
                        body: JSON.stringify({ expectedRevision }),
                    });
                    renderSnapshot(updated);
                    announce(`Campaign ${action} completed`);
                } catch (failure) {
                    if (failure.status === 412) {
                        await loadSnapshot();
                        announce("Stale revision refreshed from authoritative snapshot");
                    } else {
                        throw failure;
                    }
                }
            },
        );
    });
});

elements.confirmation.addEventListener("close", async () => {
    const execute = pendingConfirmation;
    pendingConfirmation = null;
    restoreFocus();
    if (elements.confirmation.returnValue !== "confirm" || !execute) return;
    try {
        await execute();
    } catch (failure) {
        announce(`Operator action failed: ${failure.message}`);
    }
});

function requireRejected(result, expectedStatus, expectedCodes, label) {
    if (result.status !== "rejected") throw new Error(`${label} unexpectedly succeeded`);
    const failure = result.reason;
    if (failure?.status !== expectedStatus || !expectedCodes.includes(failure?.payload?.code)) {
        throw new Error(`${label} returned ${failure?.status || "network failure"} ${failure?.payload?.code || failure?.message || "unknown"}`);
    }
}

async function executeScenario(scenario) {
    await resetTenant(scenario === "capacity-race" ? 2 : 10);
    const fixture = await prepareFixture(scenario);
    let detail;
    switch (scenario) {
        case "happy-allocation": {
            await allocate();
            const redeemed = await redeemLatest();
            if (redeemed.state !== "REDEEMED") throw new Error(`Expected REDEEMED, observed ${redeemed.state}`);
            detail = `claim ${redeemed.claimId} reached ${redeemed.state}`;
            break;
        }
        case "same-key-response-loss": {
            const key = randomKey();
            const first = await allocate(state.principal, key);
            const replay = await allocate(state.principal, key);
            if (first.claimId !== replay.claimId) throw new Error("Replay returned another claim");
            detail = `claim ${first.claimId} replayed without a second effect`;
            break;
        }
        case "capacity-race": {
            const attempts = await Promise.allSettled(
                Array.from({ length: 8 }, (_, index) => allocate(`${state.principal}-${index}`)),
            );
            const winners = attempts.filter(result => result.status === "fulfilled");
            const rejected = attempts.filter(result => result.status === "rejected");
            if (winners.length !== 2) throw new Error(`Expected 2 capacity winners, observed ${winners.length}`);
            rejected.forEach((result, index) => requireRejected(result, 409, ["CAPACITY_EXHAUSTED"], `capacity loser ${index + 1}`));
            const snapshot = await loadSnapshot();
            if (snapshot.remainingCapacity !== 0) {
                throw new Error(`Expected remaining capacity 0, observed ${snapshot.remainingCapacity}`);
            }
            detail = `${winners.length} winners and ${rejected.length} authoritative rejections`;
            break;
        }
        case "allocation-review":
        case "bloom-false-positive": {
            const claim = await allocate();
            if (!claim.reviewId) throw new Error("Expected an allocation review");
            detail = `review ${claim.reviewId} opened without exposing a code`;
            break;
        }
        case "redemption-review": {
            state.latestClaim = await allocate();
            await prepareFixture(scenario);
            const reviewed = await redeemLatest();
            if (reviewed.state !== "REVIEW_REQUIRED") throw new Error("Expected a redemption review");
            detail = `redemption entered ${reviewed.state}`;
            break;
        }
        case "pause-allocation-race": {
            const [pause, allocation] = await Promise.allSettled([
                operatorCommand(`/operator/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/pause`, { expectedRevision: 1 }),
                allocate(),
            ]);
            if ((pause.status === "fulfilled" ? 1 : 0) + (allocation.status === "fulfilled" ? 1 : 0) !== 1) {
                throw new Error("Expected exactly one pause/allocation winner");
            }
            if (pause.status === "rejected") requireRejected(pause, 412, ["STALE_REVISION"], "pause loser");
            if (allocation.status === "rejected") {
                requireRejected(allocation, 409, ["CAMPAIGN_PAUSED", "CONCURRENT_MODIFICATION"], "allocation loser");
            }
            const snapshot = await loadSnapshot();
            const expectedState = pause.status === "fulfilled" ? "PAUSED" : "ACTIVE";
            if (snapshot.state !== expectedState) throw new Error(`Expected ${expectedState}, observed ${snapshot.state}`);
            const expectedRemaining = allocation.status === "fulfilled" ? 9 : 10;
            if (snapshot.remainingCapacity !== expectedRemaining) {
                throw new Error(`Expected remaining capacity ${expectedRemaining}, observed ${snapshot.remainingCapacity}`);
            }
            detail = `${pause.status === "fulfilled" ? "pause" : "allocation"} won revision 1; ${expectedState} with remaining ${expectedRemaining}`;
            break;
        }
        case "redeem-revoke-race": {
            state.latestClaim = await allocate();
            const claim = state.latestClaim;
            const outcomes = await Promise.allSettled([
                redeemLatest(),
                operatorCommand(`/operator/api/v1/claims/${encodeURIComponent(claim.claimId)}/revoke`, {
                    campaignId: state.campaignId,
                    expectedRevision: claim.revision,
                }),
            ]);
            const winners = outcomes.filter(result => result.status === "fulfilled");
            const losers = outcomes.filter(result => result.status === "rejected");
            if (winners.length !== 1 || losers.length !== 1) {
                throw new Error("Expected exactly one terminal winner");
            }
            if (![409, 412].includes(losers[0].reason?.status)) throw new Error("Terminal loser returned an unexpected status");
            requireRejected(losers[0], losers[0].reason?.status, ["STALE_REVISION", "ALREADY_REDEEMED", "CLAIM_REVOKED"], "terminal loser");
            const finalClaim = await customerQuery(`/api/v1/claims/${encodeURIComponent(claim.claimId)}`);
            if (!["REDEEMED", "REVOKED"].includes(finalClaim.state)) {
                throw new Error(`Expected terminal claim state, observed ${finalClaim.state}`);
            }
            const terminalSnapshot = await loadSnapshot();
            const expectedTerminalRemaining = finalClaim.state === "REVOKED" ? 10 : 9;
            if (terminalSnapshot.remainingCapacity !== expectedTerminalRemaining) {
                throw new Error(`Expected terminal remaining capacity ${expectedTerminalRemaining}, observed ${terminalSnapshot.remainingCapacity}`);
            }
            detail = "exactly one terminal command won";
            break;
        }
        case "policy-change": {
            const body = { expectedRevision: 1, capacity: 12, perUserLimit: 1, redemptionTtlSeconds: 600 };
            const outcomes = await Promise.allSettled([
                operatorCommand(`/operator/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/policy`, body),
                operatorCommand(`/operator/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/policy`, body),
            ]);
            const winners = outcomes.filter(result => result.status === "fulfilled");
            const losers = outcomes.filter(result => result.status === "rejected");
            if (winners.length !== 1 || losers.length !== 1) {
                throw new Error("Expected one policy revision winner");
            }
            requireRejected(losers[0], 412, ["STALE_REVISION"], "policy loser");
            const policySnapshot = await loadSnapshot();
            if (policySnapshot.policyVersion !== 1) {
                throw new Error(`Expected policy version 1, observed ${policySnapshot.policyVersion}`);
            }
            if (policySnapshot.capacity !== 12 || policySnapshot.remainingCapacity !== 12) {
                throw new Error(`Expected policy capacity 12, observed ${policySnapshot.capacity}`);
            }
            detail = "one policy update won the expected revision";
            break;
        }
        case "redis-outage": {
            const claim = await allocate();
            detail = `advisory UNKNOWN fell back to PostgreSQL claim ${claim.claimId}`;
            break;
        }
        case "delayed-duplicate-out-of-order":
            if (fixture.executionMode !== "SERVER_EVENT" || fixture.evidence?.join(",") !== "APPLIED,IGNORED,CONFLICT") {
                throw new Error(`Unexpected delayed-event evidence ${fixture.evidence?.join(",") || "none"}`);
            }
            detail = "fixture submitted apply, duplicate, and stale event evidence";
            break;
        default:
            throw new Error(`Unsupported scenario ${scenario}`);
    }
    await loadSnapshot();
    return `${fixture.executionMode}: ${detail}`;
}

async function runSelectedScenario() {
    const scenario = elements.scenario.value;
    elements.runScenario.disabled = true;
    setText(elements.scenarioResult, `Running ${scenario}…`);
    try {
        const result = await executeScenario(scenario);
        setText(elements.scenarioResult, result);
        addTimeline("Scenario complete", `${scenario}: ${result}`);
        await loadOperatorEvidence();
        announce(`Scenario ${scenario} completed`);
    } catch (failure) {
        setText(elements.scenarioResult, `Failed: ${failure.payload?.code || failure.message}`);
        announce(`Scenario ${scenario} failed: ${failure.message}`);
    } finally {
        refreshActions();
    }
}

async function resetSelectedTenant() {
    elements.resetScenario.disabled = true;
    try {
        await resetTenant();
        setText(elements.scenarioResult, "Tenant reset and active campaign recreated");
        await loadOperatorEvidence();
        announce("Scenario tenant reset completed");
    } catch (failure) {
        setText(elements.scenarioResult, `Reset failed: ${failure.payload?.code || failure.message}`);
        announce(`Scenario reset failed: ${failure.message}`);
    } finally {
        refreshActions();
    }
}

elements.runScenario.addEventListener("click", () => {
    const scenario = elements.scenario.value;
    requestConfirmation(
        elements.runScenario,
        `Confirm scenario ${scenario}`,
        `Running ${scenario} deletes and recreates only tenant ${state.tenant} before executing the scenario.`,
        runSelectedScenario,
    );
});

elements.resetScenario.addEventListener("click", () => {
    requestConfirmation(
        elements.resetScenario,
        "Confirm tenant reset",
        `Reset deletes and recreates campaign data only for tenant ${state.tenant}.`,
        resetSelectedTenant,
    );
});

window.addEventListener("pagehide", () => {
    if (state.streamAbort) state.streamAbort.abort();
    clearOperatorSecret();
});

window.addEventListener("beforeunload", clearOperatorSecret);
