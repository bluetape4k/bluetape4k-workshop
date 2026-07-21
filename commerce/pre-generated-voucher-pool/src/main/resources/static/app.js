"use strict";

const MAX_POLL_ATTEMPTS = 10;
const POLL_INTERVAL_MILLIS = 2000;
const REQUEST_TIMEOUT_MILLIS = 5000;
const EVENT_HANDSHAKE_TIMEOUT_MILLIS = 5000;
// Four times the server's default 15-second heartbeat allows scheduler and network delivery grace.
const EVENT_IDLE_TIMEOUT_MILLIS = 60000;
const MAX_SSE_BUFFER_CHARS = 64 * 1024;
const MAX_SSE_EVENT_CHARS = 48 * 1024;

const pendingCommands = new Set();

const secretState = {
    revealedCode: null,
    operatorSecret: null,
    operatorGuard: null,
    previewToken: null,
};

const state = {
    tenant: "",
    principal: "",
    campaignId: "",
    reservationId: null,
    reservationRevision: null,
    allocationId: null,
    allocationRevision: null,
    replacementAvailable: false,
    pollingAttempts: 0,
    scopeController: null,
    transportController: null,
    transportScopeAbort: null,
    eventCursor: null,
    scopeVersion: 0,
    scopeReady: false,
    revokePreview: null,
};

const elements = {
    showCustomer: document.querySelector("#show-customer"),
    showOperator: document.querySelector("#show-operator"),
    logout: document.querySelector("#logout"),
    customerView: document.querySelector("#customer-view"),
    operatorView: document.querySelector("#operator-view"),
    customerTitle: document.querySelector("#customer-title"),
    operatorTitle: document.querySelector("#operator-title"),
    customerScope: document.querySelector("#customer-scope"),
    tenant: document.querySelector("#tenant"),
    principal: document.querySelector("#principal"),
    campaignId: document.querySelector("#campaign-id"),
    connectionStatus: document.querySelector("#connection-status"),
    reservationStatus: document.querySelector("#reservation-status"),
    allocationStatus: document.querySelector("#allocation-status"),
    transportStatus: document.querySelector("#transport-status"),
    reserveVoucher: document.querySelector("#reserve-voucher"),
    allocateVoucher: document.querySelector("#allocate-voucher"),
    revealVoucher: document.querySelector("#reveal-voucher"),
    redeemVoucher: document.querySelector("#redeem-voucher"),
    customerActionHelp: document.querySelector("#customer-action-help"),
    revealedCode: document.querySelector("#revealed-code"),
    clearRevealedCode: document.querySelector("#clear-revealed-code"),
    replacementHelp: document.querySelector("#replacement-help"),
    replacementMessage: document.querySelector("#replacement-message"),
    safeRequestId: document.querySelector("#safe-request-id"),
    requestReplacement: document.querySelector("#request-replacement"),
    operatorEscalation: document.querySelector("#operator-escalation"),
    operatorScope: document.querySelector("#operator-scope"),
    operatorTenant: document.querySelector("#operator-tenant"),
    operatorSecret: document.querySelector("#operator-secret"),
    operatorGuard: document.querySelector("#operator-guard"),
    aggregateType: document.querySelector("#aggregate-type"),
    aggregateId: document.querySelector("#aggregate-id"),
    aggregateRevision: document.querySelector("#aggregate-revision"),
    revokeIdentity: document.querySelector("#revoke-identity"),
    revokeRevision: document.querySelector("#revoke-revision"),
    revokeAffectedCount: document.querySelector("#revoke-affected-count"),
    typedAggregateIdentity: document.querySelector("#typed-aggregate-identity"),
    revokePreview: document.querySelector("#revoke-preview"),
    revokeAggregate: document.querySelector("#revoke-aggregate"),
    liveStatus: document.querySelector("#live-status"),
    revealConfirmation: document.querySelector("#reveal-confirmation"),
    revealConfirmationDescription: document.querySelector("#reveal-confirmation-description"),
    revealConfirmationAccept: document.querySelector("#reveal-confirmation-accept"),
    replacementConfirmation: document.querySelector("#replacement-confirmation"),
    replacementConfirmationAccept: document.querySelector("#replacement-confirmation-accept"),
    revokeConfirmation: document.querySelector("#revoke-confirmation"),
    revokeConfirmationDescription: document.querySelector("#revoke-confirmation-description"),
    revokeConfirmationAccept: document.querySelector("#revoke-confirmation-accept"),
};

function setText(target, value) {
    target.textContent = String(value);
}

function announce(message) {
    elements.liveStatus.textContent = "";
    window.setTimeout(() => {
        elements.liveStatus.textContent = message;
    }, 0);
}

function safeIdentifier(value) {
    const candidate = String(value || "");
    return /^[A-Za-z0-9._:-]{1,128}$/.test(candidate) ? candidate : "unavailable";
}

function randomKey() {
    const bytes = new Uint8Array(12);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
}

function customerHeaders(extra = {}) {
    return {
        "Content-Type": "application/json",
        "X-Workshop-Tenant": state.tenant,
        "X-Workshop-Principal": state.principal,
        ...extra,
    };
}

function operatorHeaders(extra = {}) {
    return {
        "Content-Type": "application/json",
        "X-Workshop-Tenant": elements.operatorTenant.value.trim(),
        "X-Workshop-Operator-Secret": secretState.operatorSecret,
        "X-Workshop-Guard": secretState.operatorGuard,
        "X-Workshop-Origin": location.origin,
        "Origin": location.origin,
        ...extra,
    };
}

async function api(path, options = {}) {
    const outerSignal = options.signal;
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (outerSignal?.aborted) abort();
    else outerSignal?.addEventListener("abort", abort, { once: true });
    let timedOut = false;
    const timeout = window.setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, REQUEST_TIMEOUT_MILLIS);
    try {
        const response = await fetch(path, { cache: "no-store", ...options, signal: controller.signal });
        let payload;
        try {
            payload = await awaitWithAbort(response.json(), controller.signal);
        } catch (failure) {
            if (controller.signal.aborted || isAbortFailure(failure)) throw failure;
            if (failure?.name !== "SyntaxError") throw failure;
            payload = {};
        }
        if (timedOut) throw new RequestTimeoutError();
        if (!response.ok) {
            const code = safeIdentifier(payload.code || `HTTP_${response.status}`);
            const requestId = safeIdentifier(payload.requestId);
            const failure = new Error(`Request failed (${code}; request ${requestId})`);
            failure.code = code;
            failure.safeRequestId = requestId;
            failure.payload = {
                code,
                requestId,
                replacementAvailable: payload.replacementAvailable === true,
            };
            throw failure;
        }
        return payload;
    } catch (failure) {
        if (timedOut) throw new RequestTimeoutError();
        throw failure;
    } finally {
        window.clearTimeout(timeout);
        outerSignal?.removeEventListener("abort", abort);
    }
}

function isAbortFailure(failure) {
    return failure?.name === "AbortError";
}

class RequestTimeoutError extends Error {
    constructor() {
        super("Request timed out safely; retry the action");
        this.name = "RequestTimeoutError";
    }
}

class EventStreamTimeoutError extends Error {
    constructor(phase) {
        super(`Event stream ${phase} timed out`);
        this.name = "EventStreamTimeoutError";
    }
}

async function awaitWithAbort(operation, signal) {
    if (signal.aborted) throw signal.reason || Object.assign(new Error("The operation was aborted"), { name: "AbortError" });
    let rejectAbort;
    const aborted = new Promise((_, reject) => { rejectAbort = reject; });
    const onAbort = () => rejectAbort(signal.reason || Object.assign(new Error("The operation was aborted"), { name: "AbortError" }));
    signal.addEventListener("abort", onAbort, { once: true });
    try {
        return await Promise.race([operation, aborted]);
    } finally {
        signal.removeEventListener("abort", onAbort);
    }
}

function clearRevealedCode() {
    secretState.revealedCode = null;
    elements.revealedCode.replaceChildren();
    elements.revealedCode.textContent = "Not revealed";
    elements.redeemVoucher.disabled = true;
}

function clearSensitiveState() {
    clearRevealedCode();
    secretState.operatorSecret = null;
    secretState.operatorGuard = null;
    secretState.previewToken = null;
    state.revokePreview = null;
    elements.operatorSecret.value = "";
    elements.operatorGuard.value = "";
    elements.typedAggregateIdentity.value = "";
    elements.revokeAggregate.disabled = true;
}

function refreshCustomerActions() {
    elements.reserveVoucher.disabled = !state.scopeReady || !state.campaignId || pendingCommands.has("reserve");
    elements.allocateVoucher.disabled = !state.scopeReady || !state.reservationId || pendingCommands.has("allocate");
    elements.revealVoucher.disabled = !state.scopeReady || !state.allocationId || pendingCommands.has("reveal");
    elements.redeemVoucher.disabled =
        !state.scopeReady || !secretState.revealedCode || !state.allocationId || pendingCommands.has("redeem");
    setText(
        elements.customerActionHelp,
        state.allocationId ? "Allocation ready for a one-time reveal." : "Reserve and allocate before revealing.",
    );
}

function refreshOperatorActions() {
    elements.revokeAggregate.disabled = !state.revokePreview || pendingCommands.has("revoke");
}

async function withCommandLatch(name, controls, command) {
    if (pendingCommands.has(name)) return undefined;
    pendingCommands.add(name);
    controls.forEach(control => { control.disabled = true; });
    refreshCustomerActions();
    refreshOperatorActions();
    try {
        return await command();
    } finally {
        pendingCommands.delete(name);
        controls.forEach(control => { control.disabled = false; });
        refreshCustomerActions();
        refreshOperatorActions();
    }
}

function showView(name) {
    clearSensitiveState();
    const customer = name === "customer";
    elements.customerView.hidden = !customer;
    elements.operatorView.hidden = customer;
    elements.showCustomer.setAttribute("aria-pressed", String(customer));
    elements.showOperator.setAttribute("aria-pressed", String(!customer));
    (customer ? elements.customerTitle : elements.operatorTitle).focus();
}

function logout() {
    resetCustomerScope();
    clearSensitiveState();
    state.tenant = "";
    state.principal = "";
    state.campaignId = "";
    refreshCustomerActions();
    announce("Session secrets cleared");
}

function resetCustomerScope() {
    state.scopeVersion += 1;
    state.scopeReady = false;
    if (state.scopeController) state.scopeController.abort();
    abortTransport();
    state.scopeController = null;
    state.eventCursor = null;
    state.pollingAttempts = 0;
    state.reservationId = null;
    state.reservationRevision = null;
    state.allocationId = null;
    state.allocationRevision = null;
    state.replacementAvailable = false;
    clearSensitiveState();
    elements.replacementHelp.hidden = true;
    elements.requestReplacement.hidden = true;
    elements.operatorEscalation.hidden = true;
    setText(elements.safeRequestId, "unavailable");
    setText(elements.connectionStatus, "Not connected");
    setText(elements.reservationStatus, "Not reserved");
    setText(elements.allocationStatus, "Not allocated");
    setText(elements.transportStatus, "Disconnected");
    refreshCustomerActions();
}

function abortTransport() {
    if (state.transportController) state.transportController.abort();
    if (state.scopeController && state.transportScopeAbort) {
        state.scopeController.signal.removeEventListener("abort", state.transportScopeAbort);
    }
    state.transportController = null;
    state.transportScopeAbort = null;
}

function createTransportController() {
    abortTransport();
    const controller = new AbortController();
    const scopeController = state.scopeController;
    if (scopeController) {
        const abort = () => controller.abort();
        if (scopeController.signal.aborted) abort();
        else scopeController.signal.addEventListener("abort", abort, { once: true });
        state.transportScopeAbort = abort;
    }
    state.transportController = controller;
    return controller;
}

function renderSnapshot(snapshot) {
    const reservations = Array.isArray(snapshot.reservations) ? snapshot.reservations.length : 0;
    const allocations = Array.isArray(snapshot.allocations) ? snapshot.allocations.length : 0;
    setText(elements.connectionStatus, `Connected · ${reservations} reservation(s), ${allocations} allocation(s)`);
}

async function loadCustomerSnapshot({ signal, announceResult = false } = {}) {
    const snapshot = await api("/api/v1/snapshots", { headers: customerHeaders(), signal });
    renderSnapshot(snapshot);
    if (announceResult) announce("Customer snapshot loaded");
    return snapshot;
}

async function reserveVoucher() {
    return withCommandLatch("reserve", [elements.reserveVoucher], async () => {
        if (!state.scopeReady || !state.campaignId) return;
        const scopeVersion = state.scopeVersion;
        const reservation = await api(`/api/v1/campaigns/${encodeURIComponent(state.campaignId)}/reservations`, {
            method: "POST",
            headers: customerHeaders({ "Idempotency-Key": randomKey(), "If-None-Match": "*" }),
            body: "{}",
            signal: state.scopeController?.signal,
        });
        if (scopeVersion !== state.scopeVersion) return;
        state.reservationId = reservation.reservationId;
        state.reservationRevision = reservation.revision;
        setText(elements.reservationStatus, `${reservation.state} · revision ${reservation.revision}`);
        refreshCustomerActions();
        announce("Voucher reserved");
    });
}

async function allocateVoucher() {
    return withCommandLatch("allocate", [elements.allocateVoucher], async () => {
        if (!state.scopeReady || !state.reservationId) return;
        const scopeVersion = state.scopeVersion;
        const reservationId = state.reservationId;
        const reservationRevision = state.reservationRevision;
        const allocation = await api(`/api/v1/reservations/${encodeURIComponent(reservationId)}/allocate`, {
            method: "POST",
            headers: customerHeaders({
                "Idempotency-Key": randomKey(),
                "If-Match": `"${reservationRevision}"`,
            }),
            signal: state.scopeController?.signal,
        });
        if (scopeVersion !== state.scopeVersion) return;
        state.allocationId = allocation.allocationId;
        state.allocationRevision = allocation.revision;
        setText(elements.allocationStatus, `${allocation.state} · revision ${allocation.revision}`);
        refreshCustomerActions();
        announce("Voucher allocated");
    });
}

function confirmReveal() {
    if (!state.scopeReady || !state.allocationId || pendingCommands.has("reveal")) return;
    elements.revealConfirmationDescription.textContent = "Reveal this voucher once?";
    elements.revealConfirmation.returnValue = "";
    elements.revealConfirmation.showModal();
    elements.revealConfirmationAccept.focus();
}

async function revealVoucherCode() {
    return withCommandLatch("reveal", [elements.revealVoucher, elements.revealConfirmationAccept], async () => {
        if (!state.scopeReady || !state.allocationId) return;
        const scopeVersion = state.scopeVersion;
        const allocationId = state.allocationId;
        const allocationRevision = state.allocationRevision;
        const result = await api(`/api/v1/allocations/${encodeURIComponent(allocationId)}/code-reveals`, {
            method: "POST",
            headers: customerHeaders({
                "Idempotency-Key": randomKey(),
                "If-Match": `"${allocationRevision}"`,
            }),
            signal: state.scopeController?.signal,
        });
        if (scopeVersion !== state.scopeVersion) return;
        state.allocationRevision = result.revision;
        if (result.outcome === "ALREADY_REVEALED") {
            state.replacementAvailable = result.replacementAvailable === true;
            showReplacement(result.safeRequestId, state.replacementAvailable);
            announce("Voucher was already revealed. Confirm recovery explicitly.");
            return;
        }
        secretState.revealedCode = typeof result.code === "string" ? result.code : null;
        elements.revealedCode.textContent = secretState.revealedCode || "Code unavailable";
        refreshCustomerActions();
        elements.clearRevealedCode.focus();
        announce("Voucher code revealed once");
    });
}

function showReplacement(requestId, replacementAvailable) {
    elements.replacementHelp.hidden = false;
    elements.requestReplacement.hidden = !replacementAvailable;
    elements.operatorEscalation.hidden = replacementAvailable;
    setText(
        elements.replacementMessage,
        replacementAvailable
            ? "The reveal response cannot be replayed. Confirm a replacement reservation."
            : "Replacement is unavailable. Escalate with the safe request ID.",
    );
    elements.safeRequestId.textContent = safeIdentifier(requestId);
}

function confirmReplacement() {
    if (!state.scopeReady || !state.allocationId || !state.replacementAvailable || pendingCommands.has("replacement")) return;
    elements.replacementConfirmation.returnValue = "";
    elements.replacementConfirmation.showModal();
    elements.replacementConfirmationAccept.focus();
}

async function replaceLostReveal() {
    return withCommandLatch("replacement", [elements.requestReplacement, elements.replacementConfirmationAccept], async () => {
        if (!state.scopeReady || !state.allocationId || !state.replacementAvailable) return;
        const scopeVersion = state.scopeVersion;
        const allocationId = state.allocationId;
        const allocationRevision = state.allocationRevision;
        const replacement = await api(`/api/v1/allocations/${encodeURIComponent(allocationId)}/replacements`, {
            method: "POST",
            headers: customerHeaders({
                "Idempotency-Key": randomKey(),
                "If-Match": `"${allocationRevision}"`,
            }),
            body: JSON.stringify({ confirmLostReveal: true }),
            signal: state.scopeController?.signal,
        });
        if (scopeVersion !== state.scopeVersion) return;
        navigateToReservation(replacement);
    });
}

function navigateToReservation(replacement) {
    clearRevealedCode();
    state.replacementAvailable = false;
    state.reservationId = replacement.reservationId;
    state.reservationRevision = replacement.revision;
    state.allocationId = null;
    state.allocationRevision = null;
    elements.replacementHelp.hidden = true;
    setText(elements.reservationStatus, `${replacement.state} · revision ${replacement.revision}`);
    setText(elements.allocationStatus, "Replacement awaits allocation");
    refreshCustomerActions();
    elements.reservationStatus.focus();
    announce("Replacement reservation created");
}

async function redeemVoucher() {
    return withCommandLatch("redeem", [elements.redeemVoucher], async () => {
        const code = secretState.revealedCode;
        if (!state.scopeReady || !code || !state.allocationId) return;
        const scopeVersion = state.scopeVersion;
        const allocationId = state.allocationId;
        const allocationRevision = state.allocationRevision;
        const allocation = await api(`/api/v1/allocations/${encodeURIComponent(allocationId)}/redeem`, {
            method: "POST",
            headers: customerHeaders({
                "Idempotency-Key": randomKey(),
                "If-Match": `"${allocationRevision}"`,
            }),
            body: JSON.stringify({ code }),
            signal: state.scopeController?.signal,
        });
        if (scopeVersion !== state.scopeVersion) return;
        clearRevealedCode();
        state.allocationRevision = allocation.revision;
        setText(elements.allocationStatus, `${allocation.state} · revision ${allocation.revision}`);
        announce("Voucher redeemed and code cleared");
    });
}

function eventHeaders() {
    const headers = customerHeaders({ Accept: "text/event-stream" });
    delete headers["Content-Type"];
    if (state.eventCursor) headers["Last-Event-ID"] = state.eventCursor;
    return headers;
}

function parseEventFrame(frame) {
    if (frame.length > MAX_SSE_EVENT_CHARS) throw new Error("event stream frame exceeded its safety bound");
    let type = "message";
    let id = null;
    const data = [];
    for (const line of frame.split("\n")) {
        if (!line || line.startsWith(":")) continue;
        const separator = line.indexOf(":");
        const field = separator < 0 ? line : line.slice(0, separator);
        const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
        if (field === "event") type = value;
        else if (field === "data") data.push(value);
        else if (field === "id") {
            if (value.includes("\0") || value.length > 128) throw new Error("event stream cursor is malformed");
            id = value;
        }
    }
    return { type, id, data: data.join("\n") };
}

function parseEventPayload(event) {
    if (!event.data) throw new Error(`event stream ${event.type} frame has no data`);
    try {
        return JSON.parse(event.data);
    } catch (_failure) {
        throw new Error(`event stream ${event.type} payload is malformed`);
    }
}

function processEventFrame(frame) {
    const event = parseEventFrame(frame);
    let result = { audit: false, reset: false };
    if (event.type === "snapshot" || event.type === "reset") {
        renderSnapshot(parseEventPayload(event));
        result = { audit: false, reset: event.type === "reset" };
    } else if (event.type === "audit") {
        parseEventPayload(event);
        result = { audit: true, reset: false };
    } else if (event.type === "error") {
        const payload = parseEventPayload(event);
        throw new Error(`event stream stopped (${safeIdentifier(payload.code)})`);
    } else if (event.type === "heartbeat" && event.data) {
        parseEventPayload(event);
    }
    if (event.id !== null) state.eventCursor = event.id;
    state.pollingAttempts = 0;
    return result;
}

async function readEventStream(response, signal, timeoutController = null) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let fragments = "";
    try {
        while (!signal.aborted) {
            const chunk = await readEventChunk(reader, signal, timeoutController);
            if (chunk.done) {
                fragments += decoder.decode();
                if (fragments.trim()) throw new Error("event stream ended with a partial frame");
                return;
            }
            if (chunk.value.byteLength > MAX_SSE_BUFFER_CHARS * 4) {
                throw new Error("event stream chunk exceeded its safety bound");
            }
            fragments += decoder.decode(chunk.value, { stream: true });
            fragments = fragments.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
            if (fragments.length > MAX_SSE_BUFFER_CHARS) {
                throw new Error("event stream buffer exceeded its safety bound");
            }
            let refreshRequired = false;
            let reset = false;
            let boundary = fragments.indexOf("\n\n");
            while (boundary >= 0) {
                const frame = fragments.slice(0, boundary);
                fragments = fragments.slice(boundary + 2);
                if (frame.trim()) {
                    const processed = processEventFrame(frame);
                    refreshRequired ||= processed.audit;
                    reset ||= processed.reset;
                }
                boundary = fragments.indexOf("\n\n");
            }
            if (refreshRequired && !signal.aborted) {
                await loadCustomerSnapshot({ signal, announceResult: false });
            }
            if (reset) return;
        }
    } finally {
        if (typeof reader.cancel === "function") await reader.cancel().catch(() => undefined);
    }
}

async function readEventChunk(reader, signal, timeoutController) {
    let timedOut = false;
    const timeout = window.setTimeout(() => {
        timedOut = true;
        timeoutController?.abort();
    }, EVENT_IDLE_TIMEOUT_MILLIS);
    try {
        const chunk = await awaitWithAbort(reader.read(), signal);
        if (timedOut) throw new EventStreamTimeoutError("idle read");
        return chunk;
    } catch (failure) {
        if (timedOut) throw new EventStreamTimeoutError("idle read");
        throw failure;
    } finally {
        window.clearTimeout(timeout);
    }
}

async function openEventStream(controller) {
    let timedOut = false;
    const timeout = window.setTimeout(() => {
        timedOut = true;
        controller.abort();
    }, EVENT_HANDSHAKE_TIMEOUT_MILLIS);
    try {
        const response = await fetch("/api/v1/events", {
            headers: eventHeaders(),
            cache: "no-store",
            signal: controller.signal,
        });
        if (timedOut) throw new EventStreamTimeoutError("handshake");
        return response;
    } catch (failure) {
        if (timedOut) throw new EventStreamTimeoutError("handshake");
        throw failure;
    } finally {
        window.clearTimeout(timeout);
    }
}

async function connectEventStream() {
    state.pollingAttempts = 0;
    const scopeVersion = state.scopeVersion;
    const controller = createTransportController();
    setText(elements.transportStatus, "Connecting · SSE");
    let fallback = false;
    try {
        const response = await openEventStream(controller);
        if (!response.ok) throw new Error("event stream unavailable");
        setText(elements.transportStatus, "Live · SSE");
        await readEventStream(response, controller.signal, controller);
        fallback = !controller.signal.aborted;
    } catch (failure) {
        fallback = failure instanceof EventStreamTimeoutError || !controller.signal.aborted;
    }
    const sameScope = scopeVersion === state.scopeVersion && state.transportController === controller;
    if (!fallback || !sameScope) return;
    const pollingController = createTransportController();
    setText(elements.transportStatus, "Polling fallback · 0/10");
    await pollFallback(pollingController.signal);
}

async function pollFallback(signal) {
    while (!signal.aborted) {
        if (state.pollingAttempts >= MAX_POLL_ATTEMPTS) {
            setText(elements.transportStatus, "Stopped · polling limit reached");
            announce("Event polling stopped after the bounded retry limit");
            return;
        }
        state.pollingAttempts += 1;
        setText(elements.transportStatus, `Polling fallback · ${state.pollingAttempts}/${MAX_POLL_ATTEMPTS}`);
        try {
            await loadCustomerSnapshot({ signal, announceResult: false });
        } catch (failure) {
            if (isAbortFailure(failure) && signal.aborted) return;
        }
        if (!await abortAwareDelay(POLL_INTERVAL_MILLIS, signal)) return;
    }
}

function abortAwareDelay(delay, signal) {
    if (signal.aborted) return Promise.resolve(false);
    return new Promise(resolve => {
        let timer = null;
        const finish = completed => {
            if (timer !== null) window.clearTimeout(timer);
            signal.removeEventListener("abort", onAbort);
            resolve(completed);
        };
        const onAbort = () => finish(false);
        signal.addEventListener("abort", onAbort, { once: true });
        timer = window.setTimeout(() => finish(true), delay);
    });
}

async function previewRevoke() {
    secretState.operatorSecret = elements.operatorSecret.value;
    secretState.operatorGuard = elements.operatorGuard.value;
    const aggregateType = elements.aggregateType.value;
    const aggregateIdentity = elements.aggregateId.value.trim();
    const revision = Number(elements.aggregateRevision.value);
    const preview = await api(
        `/operator/api/v1/${aggregateType}/${encodeURIComponent(aggregateIdentity)}/revoke-preview`,
        {
            method: "POST",
            headers: operatorHeaders({ "If-Match": `"${revision}"` }),
        },
    );
    secretState.previewToken = preview.previewToken;
    state.revokePreview = {
        aggregateType,
        aggregateIdentity: String(preview.aggregateId),
        revision: preview.revision,
        affectedCount: preview.affectedCount,
        previewToken: secretState.previewToken,
    };
    elements.revokeIdentity.textContent = state.revokePreview.aggregateIdentity;
    elements.revokeRevision.textContent = String(preview.revision);
    elements.revokeAffectedCount.textContent = String(preview.affectedCount);
    elements.revokeAggregate.disabled = false;
    elements.typedAggregateIdentity.focus();
    announce(`Revoke preview loaded for ${preview.affectedCount} vouchers`);
}

function confirmRevoke(preview, typedIdentity, run) {
    if (pendingCommands.has("revoke")) return;
    if (typedIdentity !== preview.aggregateIdentity) {
        announce("Identity does not match");
        elements.typedAggregateIdentity.focus();
        return;
    }
    elements.revokeConfirmationDescription.textContent = `Revoke ${preview.affectedCount} vouchers?`;
    elements.revokeConfirmation.returnValue = "";
    elements.revokeConfirmation.showModal();
    elements.revokeConfirmationAccept.focus();
}

async function runRevoke() {
    return withCommandLatch("revoke", [elements.revokeAggregate, elements.revokeConfirmationAccept], async () => {
        const preview = state.revokePreview;
        if (!preview || !secretState.previewToken) return;
        const confirmedField = preview.aggregateType === "campaigns" ? "confirmedCampaignId" : "confirmedBatchId";
        const body = {
            previewToken: preview.previewToken,
            [confirmedField]: preview.aggregateIdentity,
        };
        await api(
            `/operator/api/v1/${preview.aggregateType}/${encodeURIComponent(preview.aggregateIdentity)}/revoke`,
            {
                method: "POST",
                headers: operatorHeaders({
                    "Idempotency-Key": randomKey(),
                    "If-Match": `"${preview.revision}"`,
                }),
                body: JSON.stringify(body),
            },
        );
        secretState.previewToken = null;
        state.revokePreview = null;
        elements.revokePreview.focus();
        announce("Revoke command accepted");
    });
}

elements.showCustomer.addEventListener("click", () => showView("customer"));
elements.showOperator.addEventListener("click", () => showView("operator"));
elements.logout.addEventListener("click", logout);
elements.clearRevealedCode.addEventListener("click", clearRevealedCode);
for (const input of [elements.tenant, elements.principal, elements.campaignId]) {
    input.addEventListener("input", () => {
        const changed =
            state.tenant !== elements.tenant.value.trim() ||
            state.principal !== elements.principal.value.trim() ||
            state.campaignId !== elements.campaignId.value.trim();
        if (changed) resetCustomerScope();
    });
}
elements.customerScope.addEventListener("submit", async event => {
    event.preventDefault();
    resetCustomerScope();
    state.tenant = elements.tenant.value.trim();
    state.principal = elements.principal.value.trim();
    state.campaignId = elements.campaignId.value.trim();
    const scopeVersion = state.scopeVersion;
    const controller = new AbortController();
    state.scopeController = controller;
    try {
        await loadCustomerSnapshot({ signal: controller.signal, announceResult: true });
        if (controller.signal.aborted || scopeVersion !== state.scopeVersion) return;
        state.scopeReady = true;
        refreshCustomerActions();
        void connectEventStream();
    } catch (failure) {
        if (isAbortFailure(failure) && controller.signal.aborted) return;
        setText(elements.transportStatus, "Snapshot unavailable");
        announce("Customer scope could not be loaded safely");
        elements.tenant.focus();
    }
});
elements.reserveVoucher.addEventListener("click", () => reserveVoucher().catch(reportCommandFailure));
elements.allocateVoucher.addEventListener("click", () => allocateVoucher().catch(reportCommandFailure));
elements.revealVoucher.addEventListener("click", confirmReveal);
elements.revealConfirmation.addEventListener("cancel", event => {
    event.preventDefault();
    elements.revealConfirmation.close("cancel");
    announce("Reveal cancelled");
    elements.revealVoucher.focus();
});
elements.revealConfirmation.addEventListener("close", () => {
    if (elements.revealConfirmation.returnValue === "confirm") {
        revealVoucherCode().catch(reportCommandFailure);
    } else {
        announce("Reveal cancelled");
        elements.revealVoucher.focus();
    }
});
elements.redeemVoucher.addEventListener("click", () => redeemVoucher().catch(reportCommandFailure));
elements.requestReplacement.addEventListener("click", confirmReplacement);
elements.replacementConfirmation.addEventListener("close", () => {
    if (elements.replacementConfirmation.returnValue === "confirm") {
        replaceLostReveal().catch(reportCommandFailure);
    }
});

function reportCommandFailure(failure) {
    if (!isAbortFailure(failure)) announce(failure.message);
}
elements.operatorEscalation.addEventListener("click", () => showView("operator"));
elements.operatorSecret.addEventListener("input", () => {
    secretState.operatorSecret = elements.operatorSecret.value;
});
elements.operatorGuard.addEventListener("input", () => {
    secretState.operatorGuard = elements.operatorGuard.value;
});
elements.operatorScope.addEventListener("submit", async event => {
    event.preventDefault();
    try {
        await previewRevoke();
    } catch (failure) {
        announce(failure.message);
        elements.revokePreview.focus();
    }
});
elements.revokeAggregate.addEventListener("click", () => {
    if (!state.revokePreview) return;
    confirmRevoke(state.revokePreview, elements.typedAggregateIdentity.value.trim(), runRevoke);
});
elements.revokeConfirmation.addEventListener("cancel", event => {
    event.preventDefault();
    elements.revokeConfirmation.close("cancel");
    announce("Revoke cancelled");
    elements.revokeAggregate.focus();
});
elements.revokeConfirmation.addEventListener("close", async () => {
    if (elements.revokeConfirmation.returnValue === "confirm") {
        try {
            await runRevoke();
        } catch (failure) {
            announce(failure.message);
            elements.revokeAggregate.focus();
        }
    } else {
        announce("Revoke cancelled");
        elements.revokeAggregate.focus();
    }
});

window.addEventListener("pagehide", clearSensitiveState);
window.addEventListener("beforeunload", clearSensitiveState);
window.addEventListener("hashchange", clearSensitiveState);
window.addEventListener("popstate", clearSensitiveState);

refreshCustomerActions();
