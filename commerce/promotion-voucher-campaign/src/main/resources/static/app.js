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
    confirmation: document.querySelector("#confirmation"),
    confirmationTitle: document.querySelector("#confirmation-title"),
    confirmationDescription: document.querySelector("#confirmation-description"),
    confirmationAccept: document.querySelector("#confirmation-accept"),
};

let focusReturnTarget = null;
let pendingOperatorAction = null;
let pendingOperatorRevision = null;

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
        const claim = await api(`/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/claims`, {
            method: "POST",
            headers: { ...customerHeaders(), "Idempotency-Key": randomKey() },
            body: JSON.stringify({ userRef: state.principal }),
        });
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
});

elements.operatorActions.forEach(button => {
    button.addEventListener("click", () => {
        focusReturnTarget = button;
        pendingOperatorAction = button.dataset.action;
        pendingOperatorRevision = state.revision;
        elements.confirmationTitle.textContent = `Confirm ${pendingOperatorAction}`;
        elements.confirmationDescription.textContent = `${pendingOperatorAction} changes authoritative campaign state at revision ${state.revision}.`;
        elements.confirmation.showModal();
        elements.confirmationAccept.focus();
    });
});

elements.confirmation.addEventListener("close", async () => {
    const action = pendingOperatorAction;
    const expectedRevision = pendingOperatorRevision;
    pendingOperatorAction = null;
    pendingOperatorRevision = null;
    restoreFocus();
    if (elements.confirmation.returnValue !== "confirm" || !action) return;
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
            announce(`Operator action failed: ${failure.message}`);
        }
    }
});

window.addEventListener("pagehide", () => {
    if (state.streamAbort) state.streamAbort.abort();
    clearOperatorSecret();
});

window.addEventListener("beforeunload", clearOperatorSecret);
