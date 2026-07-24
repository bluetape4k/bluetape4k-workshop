const MAX_PROJECTION_RETRIES = 5;
const state = {
    lastVerifiedProjection: null,
    minimumStreamPosition: null,
    expectedGenerationToken: null,
    requiresFreshConfirmation: false,
    pendingAction: null,
};

const statusNode = document.querySelector("#projection-status");
const lagBanner = document.querySelector("#projection-lag-banner");
const verifiedProjection = document.querySelector("#verified-projection");
const disabledActionReason = document.querySelector("#disabled-action-reason");
const confirmation = document.querySelector("#confirm-destructive-action");

function announce(message) {
    statusNode.textContent = message;
}

function renderVerifiedProjection(snapshot) {
    state.lastVerifiedProjection = snapshot;
    verifiedProjection.replaceChildren();
    for (const [name, value] of Object.entries(snapshot)) {
        const term = document.createElement("dt");
        const description = document.createElement("dd");
        term.textContent = name;
        description.textContent = String(value);
        verifiedProjection.append(term, description);
    }
}

async function loadProjection(campaignId, minimumPosition = null) {
    let attempts = 0;
    while (attempts < MAX_PROJECTION_RETRIES) {
        const headers = {
            "X-Workshop-Tenant": "tenant-a",
            "X-Workshop-Principal": "operator-browser",
        };
        if (minimumPosition !== null) {
            headers["X-Min-Stream-Position"] = String(minimumPosition);
        }
        const response = await fetch(`/api/v1/campaigns/${campaignId}`, {headers, cache: "no-store"});
        const body = response.status === 404 ? null : await response.json();

        // pending before generic success
        if (body?.code === "PROJECTION_PENDING") {
            lagBanner.hidden = false;
            announce(`Projection pending at ${body.projectionPosition}. Last verified projection remains visible.`);
            attempts += 1;
            if (attempts >= MAX_PROJECTION_RETRIES) {
                document.querySelector("#manual-refresh").focus();
                return state.lastVerifiedProjection;
            }
            await new Promise(resolve => setTimeout(resolve, 1000));
            continue;
        }
        if (!response.ok) {
            announce("Projection request failed.");
            return state.lastVerifiedProjection;
        }
        lagBanner.hidden = true;
        renderVerifiedProjection(body);
        state.minimumStreamPosition = Number(response.headers.get("X-Stream-Position"));
        announce("Verified projection loaded.");
        return body;
    }
    return state.lastVerifiedProjection;
}

function readOperatorContext() {
    return {
        tenant: document.querySelector("#operator-tenant").value.trim(),
        projection: document.querySelector("#projection-name").value.trim(),
        generation: document.querySelector("#projection-generation").value,
        expectedToken: document.querySelector("#expected-generation-token").value,
        poisonEventId: document.querySelector("#poison-event-id").value.trim(),
        secret: document.querySelector("#operator-secret").value,
        guard: document.querySelector("#operator-guard").value,
    };
}

function buildOperatorAction(type) {
    const context = readOperatorContext();
    if (!context.tenant || !context.projection || !context.generation ||
        !context.expectedToken || !context.secret || !context.guard) {
        disabledActionReason.textContent = "Complete the operator context before choosing an action.";
        return null;
    }
    if (type === "poison" && !context.poisonEventId) {
        disabledActionReason.textContent = "A poison event ID is required for retry.";
        return null;
    }
    const base = `/operator/api/v1/projections/${encodeURIComponent(context.projection)}`;
    const paths = {
        poison: `${base}/generations/${context.generation}/poison-events/${context.poisonEventId}/retry`,
        rebuild: `${base}/rebuilds`,
        reconciliation: `${base}/generations/${context.generation}/reconciliation`,
    };
    return {
        type,
        path: paths[type],
        buttonId:
            `#${type === "poison" ? "retry-poison" : type === "rebuild" ? "start-rebuild" : "run-reconciliation"}`,
        idempotencyKey: crypto.randomUUID(),
        expectedToken: context.expectedToken,
        tenant: context.tenant,
        secret: context.secret,
        guard: context.guard,
        body: type === "rebuild" ? {targetPosition: state.minimumStreamPosition ?? 0} : {},
    };
}

function setActionBusy(action, busy) {
    document.querySelector(action.buttonId).disabled = busy;
    disabledActionReason.textContent = busy ? "The confirmed action is in progress." : "";
}

async function operatorMutation(action) {
    if (state.requiresFreshConfirmation) {
        disabledActionReason.textContent = "Refresh state and confirm again after a stale token.";
        return null;
    }
    setActionBusy(action, true);
    try {
        const response = await fetch(action.path, {
            method: "POST",
            cache: "no-store",
            headers: {
                "Content-Type": "application/json",
                "Idempotency-Key": action.idempotencyKey,
                "X-Expected-Generation-Token": String(action.expectedToken),
                "X-Workshop-Tenant": action.tenant,
                "X-Workshop-Principal": "operator-browser",
                "X-Workshop-Operator-Secret": action.secret,
                "X-Workshop-Operator-Guard": action.guard,
                "X-Workshop-Operator-Role": "OPERATOR",
                "X-Workshop-Origin": window.location.origin,
            },
            body: JSON.stringify(action.body),
        });
        if (response.status === 412) {
            state.requiresFreshConfirmation = true;
            state.pendingAction = null;
            announce("State changed. Refresh and confirm the action again.");
            return null;
        }
        return response;
    } finally {
        setActionBusy(action, false);
    }
}

function requestConfirmation(type) {
    const action = buildOperatorAction(type);
    if (!action) return;
    state.pendingAction = action;
    state.requiresFreshConfirmation = false;
    disabledActionReason.textContent = "";
    confirmation.showModal();
    document.querySelector("#confirm-action").focus();
}

async function retryPoison(action) {
    return operatorMutation(action);
}

async function startRebuild(action) {
    return operatorMutation(action);
}

async function runReconciliation(action) {
    return operatorMutation(action);
}

document.querySelector("#campaign-query").addEventListener("submit", event => {
    event.preventDefault();
    loadProjection(document.querySelector("#campaign-id").value, state.minimumStreamPosition);
});
document.querySelector("#manual-refresh").addEventListener("click", () => {
    state.requiresFreshConfirmation = false;
    loadProjection(document.querySelector("#campaign-id").value);
});
document.querySelector("#retry-poison").addEventListener("click", () => requestConfirmation("poison"));
document.querySelector("#start-rebuild").addEventListener("click", () => requestConfirmation("rebuild"));
document.querySelector("#run-reconciliation").addEventListener("click", () => requestConfirmation("reconciliation"));
document.querySelector("#cancel-action").addEventListener("click", () => {
    state.pendingAction = null;
    confirmation.close();
});
document.querySelector("#confirm-action").addEventListener("click", async () => {
    const action = state.pendingAction;
    confirmation.close();
    if (action?.type === "poison") await retryPoison(action);
    if (action?.type === "rebuild") await startRebuild(action);
    if (action?.type === "reconciliation") await runReconciliation(action);
});
