import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { webcrypto } from "node:crypto";

const applicationPath = process.argv[2];
const htmlPath = process.argv[3];
assert.ok(applicationPath && htmlPath, "usage: node voucher-pool-browser-contract.mjs <app.js> <index.html>");

function attributesOf(source) {
    const attributes = new Map();
    const pattern = /([A-Za-z_:][\w:.-]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
    for (const match of source.matchAll(pattern)) {
        attributes.set(match[1].toLowerCase(), match[2] ?? match[3] ?? match[4] ?? "");
    }
    return attributes;
}

function textOf(source) {
    return source.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

function parseHtml(source) {
    assert.ok(source.length <= 128 * 1024, "browser contract HTML exceeds bounded parser input");
    const elements = new Map();
    const startTag = /<([a-z][\w-]*)([^<>]*)>/gi;
    for (const match of source.matchAll(startTag)) {
        const tag = match[1].toLowerCase();
        const attributes = attributesOf(match[2]);
        const id = attributes.get("id");
        if (!id) continue;
        assert.equal(elements.has(id), false, `duplicate HTML id: ${id}`);
        const remainder = source.slice(match.index + match[0].length);
        const closing = remainder.match(new RegExp(`^([\\s\\S]*?)<\\/${tag}\\s*>`, "i"));
        const text = closing ? textOf(closing[1]) : "";
        elements.set(id, { id, tag, attributes, text, labels: [] });
    }
    const labelPattern = /<label([^>]*)>([\s\S]*?)<\/label\s*>/gi;
    for (const match of source.matchAll(labelPattern)) {
        const attributes = attributesOf(match[1]);
        const target = attributes.get("for");
        if (target && elements.has(target)) elements.get(target).labels.push(textOf(match[2]));
    }
    for (const element of elements.values()) {
        element.accessibleName = element.attributes.get("aria-label") || element.labels[0] || element.text;
        element.focusable =
            ["button", "input", "select", "textarea"].includes(element.tag) ||
            element.attributes.has("tabindex") ||
            (element.tag === "a" && element.attributes.has("href"));
    }
    return elements;
}

function requireSemantics(elements) {
    const element = id => {
        assert.ok(elements.has(id), `required HTML element is missing: #${id}`);
        return elements.get(id);
    };
    assert.equal(element("reveal-voucher").accessibleName, "Reveal voucher code");
    for (const [id, label] of [
        ["operator-secret", "Operator secret"],
        ["operator-guard", "Operator guard"],
        ["typed-aggregate-identity", "Type the aggregate identity to confirm"],
    ]) {
        assert.equal(element(id).labels.includes(label), true, `label[for] association is missing for #${id}`);
    }
    for (const id of ["operator-secret", "operator-guard"]) {
        assert.equal(element(id).attributes.get("type"), "password");
        assert.equal(element(id).attributes.get("autocomplete"), "off");
    }
    for (const id of ["reveal-confirmation", "replacement-confirmation", "revoke-confirmation"]) {
        const dialog = element(id);
        const labelledBy = dialog.attributes.get("aria-labelledby");
        const describedBy = dialog.attributes.get("aria-describedby");
        assert.ok(labelledBy && elements.has(labelledBy), `valid aria-labelledby is required for #${id}`);
        assert.ok(describedBy && elements.has(describedBy), `valid aria-describedby is required for #${id}`);
    }
    assert.equal(element("live-status").attributes.get("aria-live"), "polite");
    assert.equal(element("live-status").attributes.get("aria-atomic"), "true");
    for (const id of [
        "reveal-confirmation-accept", "replacement-confirmation-accept", "revoke-confirmation-accept",
        "clear-revealed-code", "typed-aggregate-identity", "revoke-aggregate", "revoke-preview",
        "reservation-status", "operator-title", "reveal-voucher",
    ]) {
        assert.equal(element(id).focusable, true, `focus target is missing or not focusable: #${id}`);
    }
}

class FakeElement {
    constructor(selector, browser, definition) {
        this.selector = selector;
        this.browser = browser;
        this.definition = definition;
        this.listeners = new Map();
        this.attributes = new Map(definition.attributes);
        this.value = definition.attributes.get("value") || "";
        this.textContent = definition.text;
        this.hidden = definition.attributes.has("hidden");
        this.disabled = definition.attributes.has("disabled");
        this.returnValue = definition.attributes.get("value") || "";
        this.open = false;
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) || [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    async dispatch(type, properties = {}) {
        const event = {
            type,
            target: this,
            preventDefault() { this.defaultPrevented = true; },
            defaultPrevented: false,
            ...properties,
        };
        for (const listener of this.listeners.get(type) || []) {
            await listener(event);
        }
        await this.browser.settle();
        return event;
    }

    focus() {
        assert.equal(this.definition.focusable, true, `attempted to focus non-focusable ${this.selector}`);
        this.browser.activeElement = this;
        this.browser.focusLog.push(this.selector);
    }

    showModal() {
        this.open = true;
    }

    close(returnValue) {
        if (returnValue !== undefined) this.returnValue = returnValue;
        this.open = false;
        void this.dispatch("close");
    }

    replaceChildren() {
        this.textContent = "";
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
        this.browser.attributeWrites.push([this.selector, name, String(value)]);
    }
}

class BrowserHarness {
    constructor(source, htmlElements) {
        this.htmlElements = htmlElements;
        this.elements = new Map();
        this.focusLog = [];
        this.attributeWrites = [];
        this.requests = [];
        this.timerDelays = [];
        this.timerCallbacks = new Map();
        this.autoFireTimerDelays = new Set([0, 2000]);
        this.nextTimerId = 1;
        this.now = 0;
        this.forbiddenAccesses = [];
        this.windowListeners = new Map();
        this.eventSourceConstructions = 0;
        this.fetchHandler = async () => this.jsonResponse({ reservations: [], allocations: [] });

        const document = {
            querySelector: selector => this.element(selector),
        };
        Object.defineProperty(document, "cookie", {
            get: () => { this.forbiddenAccesses.push("document.cookie:get"); return ""; },
            set: () => { this.forbiddenAccesses.push("document.cookie:set"); },
        });
        const forbiddenStorage = name => new Proxy({}, {
            get: (_target, property) => {
                this.forbiddenAccesses.push(`${name}.${String(property)}`);
                return () => undefined;
            },
        });
        const history = new Proxy({}, {
            get: (_target, property) => (..._args) => this.forbiddenAccesses.push(`history.${String(property)}`),
        });
        const windowObject = {
            addEventListener: (type, listener) => {
                const listeners = this.windowListeners.get(type) || [];
                listeners.push(listener);
                this.windowListeners.set(type, listeners);
            },
            setTimeout: (callback, delay) => {
                this.timerDelays.push(delay);
                const id = this.nextTimerId++;
                this.timerCallbacks.set(id, { callback, delay, dueAt: this.now + delay });
                if (this.autoFireTimerDelays.has(delay)) {
                    void Promise.resolve().then(() => this.fireTimer(id));
                }
                return id;
            },
            clearTimeout: id => this.timerCallbacks.delete(id),
            localStorage: forbiddenStorage("localStorage"),
            sessionStorage: forbiddenStorage("sessionStorage"),
            history,
        };
        const harness = this;
        class FakeEventSource {
            constructor() {
                harness.eventSourceConstructions += 1;
            }
        }
        this.context = vm.createContext({
            AbortController,
            Array,
            Boolean,
            Error,
            EventSource: FakeEventSource,
            JSON,
            Math,
            Number,
            Object,
            Promise,
            RegExp,
            String,
            TextDecoder,
            TextEncoder,
            Uint8Array,
            console,
            crypto: webcrypto,
            document,
            encodeURIComponent,
            fetch: async (path, options = {}) => {
                const request = { path: String(path), options };
                this.requests.push(request);
                if (options.signal?.aborted) throw this.abortError();
                const response = Promise.resolve().then(() => this.fetchHandler(request));
                if (!options.signal) return response;
                return Promise.race([
                    response,
                    new Promise((_, reject) => {
                        options.signal.addEventListener("abort", () => reject(this.abortError()), { once: true });
                    }),
                ]);
            },
            history,
            location: { origin: "http://127.0.0.1:8080" },
            window: windowObject,
        });
        windowObject.window = windowObject;
        windowObject.document = document;
        vm.runInContext(source, this.context, { filename: applicationPath });
        this.element("#aggregate-type").value = "batches";
    }

    element(selector) {
        assert.match(selector, /^#[A-Za-z][\w:-]*$/, `unsupported selector in bounded browser harness: ${selector}`);
        const definition = this.htmlElements.get(selector.slice(1));
        assert.ok(definition, `app.js references an element missing from index.html: ${selector}`);
        if (!this.elements.has(selector)) this.elements.set(selector, new FakeElement(selector, this, definition));
        return this.elements.get(selector);
    }

    evaluate(expression) {
        return vm.runInContext(expression, this.context);
    }

    async call(expression) {
        return await this.evaluate(expression);
    }

    async settle() {
        for (let attempt = 0; attempt < 32; attempt += 1) await Promise.resolve();
    }

    fireTimer(id) {
        const timer = this.timerCallbacks.get(id);
        if (!timer) return;
        this.timerCallbacks.delete(id);
        timer.callback();
    }

    fireTimers(delay) {
        for (const [id, timer] of [...this.timerCallbacks]) {
            if (timer.delay === delay) this.fireTimer(id);
        }
    }

    advanceTime(milliseconds) {
        this.now += milliseconds;
        const due = [...this.timerCallbacks]
            .filter(([_id, timer]) => timer.dueAt <= this.now)
            .sort((left, right) => left[1].dueAt - right[1].dueAt);
        due.forEach(([id]) => this.fireTimer(id));
    }

    abortError() {
        return Object.assign(new Error("The operation was aborted"), { name: "AbortError" });
    }

    async dispatchWindow(type) {
        for (const listener of this.windowListeners.get(type) || []) await listener({ type });
        await this.settle();
    }

    jsonResponse(payload, ok = true, status = ok ? 200 : 500, headers = {}) {
        const normalizedHeaders = new Map(
            Object.entries(headers).map(([name, value]) => [name.toLowerCase(), String(value)]),
        );
        return {
            ok,
            status,
            headers: { get: name => normalizedHeaders.get(String(name).toLowerCase()) ?? null },
            json: async () => payload,
        };
    }

    streamResponse(chunks, ok = true, status = ok ? 200 : 500) {
        const encoded = chunks.map(chunk => typeof chunk === "string" ? new TextEncoder().encode(chunk) : chunk);
        let index = 0;
        return {
            ok,
            status,
            body: {
                getReader: () => ({
                    read: async () => index < encoded.length
                        ? { done: false, value: encoded[index++] }
                        : { done: true, value: undefined },
                    cancel: async () => undefined,
                }),
            },
            json: async () => ({}),
        };
    }

    secretLeaks(...secrets) {
        const candidates = secrets.filter(Boolean);
        const domValues = [...this.elements.values()].flatMap(element => [
            element.value,
            element.textContent,
            ...element.attributes.values(),
        ]);
        return domValues.filter(value => candidates.some(secret => String(value).includes(secret)));
    }
}

const source = readFileSync(applicationPath, "utf8");
const htmlElements = parseHtml(readFileSync(htmlPath, "utf8"));
requireSemantics(htmlElements);

async function replacementContract() {
    const browser = new BrowserHarness(source, htmlElements);
    browser.evaluate('state.tenant = "tenant-a"; state.principal = "customer-a"; state.scopeReady = true; state.allocationId = "allocation-1"; state.allocationRevision = 7');
    const responses = [
        browser.jsonResponse({
            allocationId: "allocation-1", outcome: "ALREADY_REVEALED", revision: 7,
            codeAvailable: false, replacementAvailable: true, safeRequestId: "safe-request-1",
            nextAction: "CONFIRM_REPLACEMENT_OR_REFRESH",
        }),
        browser.jsonResponse({ reservationId: "reservation-2", state: "ACTIVE", revision: 0 }),
    ];
    browser.fetchHandler = async () => responses.shift();

    await browser.element("#reveal-voucher").dispatch("click", { key: "Enter" });
    assert.equal(browser.element("#reveal-confirmation-description").textContent, "Reveal this voucher once?");
    const cancelledRevealCommands = browser.requests.length;
    await browser.element("#reveal-confirmation").dispatch("cancel", { key: "Escape" });
    assert.equal(browser.requests.length, cancelledRevealCommands);
    assert.equal(browser.element("#live-status").textContent, "Reveal cancelled");
    await browser.element("#reveal-voucher").dispatch("click", { key: "Enter" });
    browser.element("#reveal-confirmation").returnValue = "confirm";
    await browser.element("#reveal-confirmation").dispatch("close");
    const reveal = browser.requests[0];
    assert.equal(reveal.path, "/api/v1/allocations/allocation-1/code-reveals");
    assert.equal(reveal.options.method, "POST");
    assert.equal(reveal.options.headers["If-Match"], '"7"');
    assert.equal(browser.element("#request-replacement").hidden, false, "available replacement must be reachable");
    assert.equal(browser.element("#operator-escalation").hidden, true);
    assert.equal(browser.element("#safe-request-id").textContent, "safe-request-1");
    assert.equal(browser.element("#live-status").textContent, "Voucher was already revealed. Confirm recovery explicitly.");

    await browser.element("#request-replacement").dispatch("click", { key: "Enter" });
    assert.equal(browser.activeElement, browser.element("#replacement-confirmation-accept"));
    browser.element("#replacement-confirmation").returnValue = "confirm";
    await browser.element("#replacement-confirmation").dispatch("close");
    const replacement = browser.requests[1];
    assert.equal(replacement.path, "/api/v1/allocations/allocation-1/replacements");
    assert.equal(replacement.options.method, "POST");
    assert.deepEqual(JSON.parse(replacement.options.body), { confirmLostReveal: true });
    assert.equal(replacement.options.headers["X-Workshop-Tenant"], "tenant-a");
    assert.equal(replacement.options.headers["X-Workshop-Principal"], "customer-a");
    assert.equal(replacement.options.headers["If-Match"], '"7"');
    assert.ok(replacement.options.headers["Idempotency-Key"]);
    assert.equal(browser.evaluate("state.reservationId"), "reservation-2");
    assert.equal(browser.evaluate("state.allocationId"), null);
    assert.equal(browser.element("#reservation-status").textContent, "ACTIVE · revision 0");
    assert.equal(browser.activeElement, browser.element("#reservation-status"));

    const unavailable = new BrowserHarness(source, htmlElements);
    unavailable.evaluate('state.scopeReady = true; state.allocationId = "allocation-3"; state.allocationRevision = 2');
    unavailable.fetchHandler = async () => unavailable.jsonResponse({
        allocationId: "allocation-1",
        outcome: "ALREADY_REVEALED",
        revision: 2,
        codeAvailable: false,
        replacementAvailable: false,
        safeRequestId: "safe-request-2",
        nextAction: "CONTACT_OPERATOR_WITH_REQUEST_ID",
    });
    await unavailable.element("#reveal-voucher").dispatch("click", { key: "Enter" });
    unavailable.element("#reveal-confirmation").returnValue = "confirm";
    await unavailable.element("#reveal-confirmation").dispatch("close");
    assert.equal(unavailable.element("#request-replacement").hidden, true, "unavailable replacement must stay disabled");
    assert.equal(unavailable.element("#operator-escalation").hidden, false);
    assert.equal(unavailable.element("#safe-request-id").textContent, "safe-request-2");
    await unavailable.element("#operator-escalation").dispatch("click", { key: "Enter" });
    assert.equal(unavailable.element("#operator-view").hidden, false);
    assert.equal(unavailable.activeElement, unavailable.element("#operator-title"));
}

async function secretLifecycleContract() {
    const browser = new BrowserHarness(source, htmlElements);
    const rawCode = "RAW-VOUCHER-CODE";
    const operatorSecret = "non-default-operator-secret";
    const operatorGuard = "non-default-guard";
    browser.evaluate('state.scopeReady = true; state.allocationId = "allocation-secret"; state.allocationRevision = 0');
    browser.fetchHandler = async () => browser.jsonResponse({
        allocationId: "allocation-secret", outcome: "VOUCHER_REVEALED", revision: 1,
        codeAvailable: true, code: rawCode, replacementAvailable: false, safeRequestId: "safe-code",
        nextAction: "COPY_ONCE_OR_REDEEM",
    });
    await browser.element("#reveal-voucher").dispatch("click", { key: "Enter" });
    browser.element("#reveal-confirmation").returnValue = "confirm";
    await browser.element("#reveal-confirmation").dispatch("close");
    assert.equal(browser.element("#revealed-code").textContent, rawCode);
    assert.equal(browser.activeElement, browser.element("#clear-revealed-code"), "successful reveal must focus code clearing");
    assert.equal(browser.element("#live-status").textContent, "Voucher code revealed once");

    browser.element("#operator-tenant").value = "tenant-a";
    browser.element("#operator-secret").value = operatorSecret;
    browser.element("#operator-guard").value = operatorGuard;
    await browser.element("#operator-secret").dispatch("input");
    await browser.element("#operator-guard").dispatch("input");
    await browser.dispatchWindow("pagehide");
    assert.equal(browser.element("#revealed-code").textContent, "Not revealed");
    assert.equal(browser.element("#operator-secret").value, "");
    assert.equal(browser.element("#operator-guard").value, "");
    assert.equal(browser.evaluate("secretState.revealedCode"), null);
    assert.equal(browser.evaluate("secretState.operatorSecret"), null);
    assert.equal(browser.evaluate("secretState.operatorGuard"), null);
    assert.deepEqual(browser.secretLeaks(rawCode, operatorSecret, operatorGuard), []);

    browser.element("#operator-secret").value = operatorSecret;
    browser.element("#operator-guard").value = operatorGuard;
    await browser.element("#operator-secret").dispatch("input");
    await browser.element("#operator-guard").dispatch("input");
    await browser.element("#show-operator").dispatch("click", { key: "Enter" });
    assert.equal(browser.element("#operator-secret").value, "");
    assert.equal(browser.element("#operator-guard").value, "");
    assert.equal(browser.activeElement, browser.element("#operator-title"));

    browser.element("#operator-secret").value = operatorSecret;
    browser.element("#operator-guard").value = operatorGuard;
    await browser.element("#operator-secret").dispatch("input");
    await browser.element("#operator-guard").dispatch("input");
    await browser.element("#logout").dispatch("click", { key: "Enter" });
    assert.equal(browser.element("#operator-secret").value, "");
    assert.equal(browser.element("#operator-guard").value, "");
    assert.equal(browser.element("#revealed-code").textContent, "Not revealed");
    assert.equal(browser.element("#live-status").textContent, "Session secrets cleared");
    assert.deepEqual(browser.forbiddenAccesses, []);
    assert.equal(browser.eventSourceConstructions, 0);
}

async function revokeHandlerContract() {
    const browser = new BrowserHarness(source, htmlElements);
    const operatorSecret = "non-default-operator-secret";
    const operatorGuard = "non-default-guard";
    browser.element("#operator-tenant").value = "tenant-a";
    browser.element("#operator-secret").value = operatorSecret;
    browser.element("#operator-guard").value = operatorGuard;
    browser.element("#aggregate-id").value = "batch-a";
    browser.element("#aggregate-revision").value = "4";
    await browser.element("#operator-secret").dispatch("input");
    await browser.element("#operator-guard").dispatch("input");
    const responses = [
        browser.jsonResponse({ aggregateId: "batch-a", revision: 4, affectedCount: 3, previewToken: "preview-token" }),
        browser.jsonResponse({ accepted: true }),
        browser.jsonResponse({ aggregateId: "batch-a", revision: 5, affectedCount: 2, previewToken: "preview-token-2" }),
        browser.jsonResponse({ code: "POOL_BUSY", requestId: "request-2" }, false, 409),
    ];
    browser.fetchHandler = async () => responses.shift();

    await browser.element("#operator-scope").dispatch("submit");
    const preview = browser.requests[0];
    assert.equal(preview.path, "/operator/api/v1/batches/batch-a/revoke-preview");
    assert.equal(preview.options.method, "POST");
    assert.equal(preview.options.headers["If-Match"], '"4"');
    assert.equal(preview.options.headers["X-Workshop-Operator-Secret"], operatorSecret);
    assert.equal(preview.options.headers["X-Workshop-Guard"], operatorGuard);
    assert.equal(browser.activeElement, browser.element("#typed-aggregate-identity"), "preview must focus identity confirmation");
    assert.equal(browser.element("#live-status").textContent, "Revoke preview loaded for 3 vouchers");

    browser.element("#typed-aggregate-identity").value = "wrong-batch";
    const previewCommandCount = browser.requests.length;
    await browser.element("#revoke-aggregate").dispatch("click", { key: "Enter" });
    assert.equal(browser.requests.length, previewCommandCount, "identity mismatch must not issue revoke");
    assert.equal(browser.activeElement, browser.element("#typed-aggregate-identity"));
    assert.equal(browser.element("#live-status").textContent, "Identity does not match");

    browser.element("#typed-aggregate-identity").value = "batch-a";
    await browser.element("#revoke-aggregate").dispatch("click", { key: "Enter" });
    assert.equal(browser.activeElement, browser.element("#revoke-confirmation-accept"));
    assert.equal(browser.element("#revoke-confirmation-description").textContent, "Revoke 3 vouchers?");
    await browser.element("#revoke-confirmation").dispatch("cancel", { key: "Escape" });
    assert.equal(browser.activeElement, browser.element("#revoke-aggregate"));
    assert.equal(browser.requests.length, previewCommandCount, "revoke cancellation must not issue a command");
    assert.equal(browser.element("#live-status").textContent, "Revoke cancelled");

    await browser.element("#revoke-aggregate").dispatch("click", { key: "Enter" });
    browser.element("#revoke-confirmation").returnValue = "confirm";
    await browser.element("#revoke-confirmation").dispatch("close");
    const command = browser.requests[1];
    assert.equal(command.path, "/operator/api/v1/batches/batch-a/revoke");
    assert.equal(command.options.method, "POST");
    assert.deepEqual(JSON.parse(command.options.body), { previewToken: "preview-token", confirmedBatchId: "batch-a" });
    assert.equal(command.options.headers["If-Match"], '"4"');
    assert.equal(command.options.headers["X-Workshop-Operator-Secret"], operatorSecret);
    assert.equal(command.options.headers["X-Workshop-Guard"], operatorGuard);
    assert.equal(browser.activeElement, browser.element("#revoke-preview"), "successful revoke must focus restart action");
    assert.equal(browser.element("#live-status").textContent, "Revoke command accepted");

    browser.element("#aggregate-revision").value = "5";
    await browser.element("#operator-scope").dispatch("submit");
    browser.element("#typed-aggregate-identity").value = "batch-a";
    await browser.element("#revoke-aggregate").dispatch("click", { key: "Enter" });
    browser.element("#revoke-confirmation").returnValue = "confirm";
    await browser.element("#revoke-confirmation").dispatch("close");
    assert.equal(browser.activeElement, browser.element("#revoke-aggregate"), "failed revoke must restore command focus");
    assert.match(browser.element("#live-status").textContent, /Request failed/);
    assert.equal(browser.requests.length, 4);
    assert.equal(browser.requests.some(({ path }) => path.includes(operatorSecret) || path.includes(operatorGuard)), false);
    assert.equal(browser.attributeWrites.some(write => write.some(value => value.includes(operatorSecret) || value.includes(operatorGuard))), false);
}

async function boundedPollingContract() {
    const browser = new BrowserHarness(source, htmlElements);
    browser.context.testSignal = new AbortController().signal;
    browser.fetchHandler = async () => browser.jsonResponse({ reservations: [], allocations: [] });
    await browser.call("pollFallback(testSignal)");
    assert.equal(browser.requests.filter(request => request.path === "/api/v1/snapshots").length, 10);
    assert.equal(browser.element("#transport-status").textContent, "Stopped · polling limit reached");
    assert.equal(browser.timerDelays.filter(delay => delay === 2000).length, 10);

    const aborting = new BrowserHarness(source, htmlElements);
    const controller = new AbortController();
    aborting.context.testSignal = controller.signal;
    aborting.fetchHandler = async () => new Promise(() => undefined);
    const abortedPoll = aborting.call("pollFallback(testSignal)");
    await aborting.settle();
    assert.equal(aborting.requests.length, 1);
    assert.equal(aborting.requests[0].options.signal.aborted, false, "outer signal must reach the in-flight fetch");
    controller.abort();
    await abortedPoll;
    assert.equal(aborting.requests[0].options.signal.aborted, true, "outer abort must cancel the in-flight fetch");
    assert.equal(aborting.requests.length, 1, "abort must stop polling after the in-flight request");
    assert.equal(aborting.element("#live-status").textContent, "", "normal scope abort must remain aria-live silent");

    const timingOut = new BrowserHarness(source, htmlElements);
    timingOut.autoFireTimerDelays.add(5000);
    timingOut.evaluate("state.pollingAttempts = 9");
    timingOut.context.testSignal = new AbortController().signal;
    timingOut.fetchHandler = async () => new Promise(() => undefined);
    await timingOut.call("pollFallback(testSignal)");
    assert.equal(timingOut.requests.length, 1, "a hanging snapshot request must consume one bounded attempt");
    assert.equal(timingOut.element("#transport-status").textContent, "Stopped · polling limit reached");
}

async function responseBodyTimeoutContract() {
    const command = new BrowserHarness(source, htmlElements);
    command.evaluate(`
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        refreshCustomerActions();
    `);
    command.fetchHandler = async () => ({
        ok: true,
        status: 200,
        json: async () => new Promise(() => undefined),
    });
    const activation = command.element("#reserve-voucher").dispatch("click", { key: "Enter" });
    await command.settle();
    assert.equal(command.element("#reserve-voucher").disabled, true);
    assert.equal(
        [...command.timerCallbacks.values()].some(timer => timer.delay === 5000),
        true,
        "request deadline must remain armed while the response body is pending",
    );
    command.fireTimers(5000);
    await activation;
    assert.equal(command.element("#reserve-voucher").disabled, false, "timeout must release the command latch");
    assert.equal(command.element("#live-status").textContent, "Request timed out safely; retry the action");

    const polling = new BrowserHarness(source, htmlElements);
    polling.evaluate("state.pollingAttempts = 9");
    polling.context.testSignal = new AbortController().signal;
    polling.fetchHandler = command.fetchHandler;
    const bounded = polling.call("pollFallback(testSignal)");
    await polling.settle();
    assert.equal(
        [...polling.timerCallbacks.values()].some(timer => timer.delay === 5000),
        true,
        "polling response-body consumption must retain its deadline",
    );
    polling.fireTimers(5000);
    await bounded;
    assert.equal(polling.requests.length, 1);
    assert.equal(polling.element("#transport-status").textContent, "Stopped · polling limit reached");
}

async function eventStreamTimeoutContract() {
    const handshake = new BrowserHarness(source, htmlElements);
    handshake.fetchHandler = async request => request.path === "/api/v1/events"
        ? new Promise(() => undefined)
        : handshake.jsonResponse({ reservations: [], allocations: [] });
    const connecting = handshake.call("connectEventStream()");
    await handshake.settle();
    const handshakeController = handshake.evaluate("state.transportController");
    assert.equal(
        [...handshake.timerCallbacks.values()].some(timer => timer.delay === 5000),
        true,
        "SSE handshake must have a deadline",
    );
    handshake.fireTimers(5000);
    await connecting;
    assert.equal(handshakeController.signal.aborted, true);
    assert.notEqual(handshake.evaluate("state.transportController"), handshakeController, "polling needs a fresh controller");
    assert.equal(handshake.requests.filter(request => request.path === "/api/v1/snapshots").length, 10);
    assert.equal(handshake.element("#transport-status").textContent, "Stopped · polling limit reached");

    const idle = new BrowserHarness(source, htmlElements);
    let resolveRead;
    idle.fetchHandler = async request => request.path === "/api/v1/events"
        ? {
            ok: true,
            status: 200,
            body: {
                getReader: () => ({
                    read: async () => new Promise(resolve => { resolveRead = resolve; }),
                    cancel: async () => undefined,
                }),
            },
            json: async () => ({}),
        }
        : idle.jsonResponse({ reservations: [], allocations: [] });
    const streaming = idle.call("connectEventStream()");
    await idle.settle();
    const streamController = idle.evaluate("state.transportController");
    assert.equal(
        [...idle.timerCallbacks.values()].some(timer => timer.delay === 60000),
        true,
        "SSE reader grace must safely exceed the server heartbeat interval",
    );
    for (let heartbeat = 1; heartbeat <= 3; heartbeat += 1) {
        idle.advanceTime(15000);
        await idle.settle();
        assert.equal(streamController.signal.aborted, false, "normal 15s heartbeat cadence must keep SSE alive");
        assert.equal(idle.requests.filter(request => request.path === "/api/v1/snapshots").length, 0);
        resolveRead({
            done: false,
            value: new TextEncoder().encode(`id: ${heartbeat}:0\nevent: heartbeat\ndata: {}\n\n`),
        });
        await idle.settle();
    }
    idle.advanceTime(59999);
    await idle.settle();
    assert.equal(streamController.signal.aborted, false);
    idle.advanceTime(1);
    await streaming;
    assert.equal(streamController.signal.aborted, true);
    assert.notEqual(idle.evaluate("state.transportController"), streamController);
    assert.equal(idle.requests.filter(request => request.path === "/api/v1/snapshots").length, 10);
    assert.equal(idle.element("#transport-status").textContent, "Stopped · polling limit reached");
}

async function eventStreamContract() {
    const browser = new BrowserHarness(source, htmlElements);
    browser.evaluate('state.tenant = "tenant-a"; state.principal = "customer-a"');
    browser.context.testSignal = new AbortController().signal;
    const snapshot = JSON.stringify({ reservations: [{}], allocations: [] });
    const reset = JSON.stringify({ reservations: [], allocations: [{ resourceId: "allocation-2" }] });
    browser.context.testResponse = browser.streamResponse([
        `id: 1:0\nevent: snap`,
        `shot\ndata: ${snapshot}\n\nid: 2:0\nevent: audit\ndata: {"type":"RESERVED"}\n\n` +
            `id: 3:0\nevent: audit\ndata: {"type":"ALLOCATED"}\n\n`,
        `id: 4:0\nevent: reset\ndata: ${reset}\n\n`,
    ]);
    browser.fetchHandler = async request => {
        assert.equal(request.path, "/api/v1/snapshots");
        return browser.jsonResponse({ reservations: [{}, {}], allocations: [{}] });
    };

    await browser.call("readEventStream(testResponse, testSignal)");
    assert.equal(browser.element("#connection-status").textContent, "Connected · 0 reservation(s), 1 allocation(s)");
    assert.equal(browser.requests.length, 1, "audit frames in one chunk must coalesce to one snapshot refresh");
    assert.equal(browser.evaluate("state.eventCursor"), "4:0");
    assert.equal(await browser.call('eventHeaders()["Last-Event-ID"]'), "4:0");
    assert.equal(browser.element("#live-status").textContent, "", "background SSE refreshes must stay aria-live silent");

    const failing = new BrowserHarness(source, htmlElements);
    failing.context.testSignal = new AbortController().signal;
    failing.context.testResponse = failing.streamResponse([
        'id: 5:0\nevent: audit\ndata: {"type":"REDEEMED"}\n\n',
    ]);
    failing.fetchHandler = async () => { throw new Error("snapshot failed"); };
    await assert.rejects(
        failing.call("readEventStream(testResponse, testSignal)"),
        /snapshot failed/,
        "audit refresh failure must escape to the bounded polling fallback",
    );

    const oversized = new BrowserHarness(source, htmlElements);
    oversized.context.testSignal = new AbortController().signal;
    oversized.context.testResponse = oversized.streamResponse([
        `id: 6:0\nevent: snapshot\ndata: ${"x".repeat(70 * 1024)}\n\n`,
    ]);
    await assert.rejects(
        oversized.call("readEventStream(testResponse, testSignal)"),
        /safety bound/,
        "oversized stream input must fail closed",
    );

    const malformed = new BrowserHarness(source, htmlElements);
    malformed.fetchHandler = async request => request.path === "/api/v1/events"
        ? malformed.streamResponse(["id: 7:0\nevent: snapshot\ndata: not-json\n\n"])
        : malformed.jsonResponse({ reservations: [], allocations: [] });
    await malformed.call("connectEventStream()");
    assert.equal(malformed.requests.filter(request => request.path === "/api/v1/snapshots").length, 10);
    assert.equal(malformed.element("#transport-status").textContent, "Stopped · polling limit reached");
    assert.equal(malformed.element("#live-status").textContent, "Event polling stopped after the bounded retry limit");
}

async function customerScopeResetContract() {
    const browser = new BrowserHarness(source, htmlElements);
    const oldScopeController = new AbortController();
    const oldTransportController = new AbortController();
    browser.context.oldScopeController = oldScopeController;
    browser.context.oldTransportController = oldTransportController;
    browser.evaluate(`
        state.tenant = "old-tenant";
        state.principal = "old-principal";
        state.campaignId = "old-campaign";
        state.reservationId = "old-reservation";
        state.reservationRevision = 8;
        state.allocationId = "old-allocation";
        state.allocationRevision = 9;
        state.replacementAvailable = true;
        state.scopeController = oldScopeController;
        state.transportController = oldTransportController;
        secretState.revealedCode = "OLD-RAW-CODE";
    `);
    browser.element("#revealed-code").textContent = "OLD-RAW-CODE";
    browser.element("#replacement-help").hidden = false;
    browser.element("#tenant").value = "new-tenant";
    browser.element("#principal").value = "new-principal";
    browser.element("#campaign-id").value = "new-campaign";
    browser.fetchHandler = async () => browser.jsonResponse({ code: "SAFE_FAILURE", requestId: "request-1" }, false, 503);

    await browser.element("#tenant").dispatch("input");
    assert.equal(oldScopeController.signal.aborted, true, "editing customer scope must abort prior commands immediately");
    assert.equal(oldTransportController.signal.aborted, true, "editing customer scope must abort prior transport immediately");
    assert.equal(browser.evaluate("state.allocationId"), null);
    assert.equal(browser.element("#reveal-voucher").disabled, true);
    await browser.element("#customer-scope").dispatch("submit");
    assert.equal(oldScopeController.signal.aborted, true);
    assert.equal(browser.evaluate("state.reservationId"), null);
    assert.equal(browser.evaluate("state.reservationRevision"), null);
    assert.equal(browser.evaluate("state.allocationId"), null);
    assert.equal(browser.evaluate("state.allocationRevision"), null);
    assert.equal(browser.evaluate("state.replacementAvailable"), false);
    assert.equal(browser.evaluate("secretState.revealedCode"), null);
    assert.equal(browser.element("#revealed-code").textContent, "Not revealed");
    assert.equal(browser.element("#replacement-help").hidden, true);
    assert.equal(browser.element("#reserve-voucher").disabled, true);
    assert.equal(browser.element("#reveal-voucher").disabled, true);
    assert.equal(browser.activeElement, browser.element("#tenant"));
    assert.equal(browser.element("#live-status").textContent, "Customer scope could not be loaded safely");

    const requestCount = browser.requests.length;
    await browser.element("#reveal-voucher").dispatch("click", { key: "Enter" });
    browser.element("#reveal-confirmation").returnValue = "confirm";
    await browser.element("#reveal-confirmation").dispatch("close");
    assert.equal(browser.requests.length, requestCount, "new scope must never issue a command for the old allocation");
    assert.equal(browser.requests.some(request => request.path.includes("old-allocation")), false);
}

async function commandLatchContract() {
    const cases = [
        {
            name: "reserve", command: "reserveVoucher()", control: "#reserve-voucher",
            setup: 'state.campaignId = "campaign-a"', path: "/api/v1/campaigns/campaign-a/reservations",
            response: { reservationId: "reservation-a", state: "ACTIVE", revision: 0 }, restoredDisabled: false,
        },
        {
            name: "reveal", command: "revealVoucherCode()", control: "#reveal-voucher",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 1',
            path: "/api/v1/allocations/allocation-a/code-reveals",
            response: { outcome: "VOUCHER_REVEALED", revision: 2, code: "RAW-CODE" }, restoredDisabled: false,
        },
        {
            name: "allocate", command: "allocateVoucher()", control: "#allocate-voucher",
            setup: 'state.reservationId = "reservation-a"; state.reservationRevision = 1',
            path: "/api/v1/reservations/reservation-a/allocate",
            response: { allocationId: "allocation-a", state: "ALLOCATED", revision: 0 }, restoredDisabled: false,
        },
        {
            name: "replacement", command: "replaceLostReveal()", control: "#request-replacement",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 2; state.replacementAvailable = true',
            path: "/api/v1/allocations/allocation-a/replacements",
            response: { reservationId: "reservation-b", state: "ACTIVE", revision: 0 }, restoredDisabled: false,
        },
        {
            name: "redeem", command: "redeemVoucher()", control: "#redeem-voucher",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 2; secretState.revealedCode = "RAW-CODE"',
            path: "/api/v1/allocations/allocation-a/redeem",
            response: { state: "REDEEMED", revision: 3 }, restoredDisabled: true,
        },
        {
            name: "revoke", command: "runRevoke()", control: "#revoke-aggregate",
            setup: `state.revokePreview = {
                aggregateType: "batches", aggregateIdentity: "batch-a", revision: 4,
                affectedCount: 2, previewToken: "preview-a"
            }; secretState.previewToken = "preview-a"`,
            path: "/operator/api/v1/batches/batch-a/revoke",
            response: { accepted: true }, restoredDisabled: true,
        },
    ];
    for (const testCase of cases) {
        const browser = new BrowserHarness(source, htmlElements);
        browser.evaluate(`
            state.tenant = "tenant-a";
            state.principal = "customer-a";
            state.scopeReady = true;
            ${testCase.setup};
            refreshCustomerActions();
            refreshOperatorActions();
        `);
        let release;
        const gate = new Promise(resolve => { release = resolve; });
        browser.fetchHandler = async request => {
            await gate;
            return browser.jsonResponse(testCase.response);
        };

        const first = browser.call(testCase.command);
        const duplicate = browser.call(testCase.command);
        await browser.settle();
        assert.equal(browser.requests.length, 1, `${testCase.name} duplicate activation must issue one command`);
        assert.equal(browser.requests[0].path, testCase.path);
        assert.equal(browser.element(testCase.control).disabled, true);
        release();
        await Promise.all([first, duplicate]);
        assert.equal(
            browser.element(testCase.control).disabled,
            testCase.restoredDisabled,
            `${testCase.name} latch must restore controls from current state`,
        );
    }
}

async function ambiguousCommandRetryIdempotencyContract() {
    const cases = [
        {
            name: "reserve", command: "reserveVoucher()",
            setup: 'state.campaignId = "campaign-a"',
            response: { reservationId: "reservation-a", state: "ACTIVE", revision: 0 },
        },
        {
            name: "allocate", command: "allocateVoucher()",
            setup: 'state.reservationId = "reservation-a"; state.reservationRevision = 1',
            response: { allocationId: "allocation-a", state: "ALLOCATED", revision: 0 },
        },
        {
            name: "reveal", command: "revealVoucherCode()",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 1',
            response: { outcome: "VOUCHER_REVEALED", revision: 2, code: "RAW-CODE" },
        },
        {
            name: "replacement", command: "replaceLostReveal()",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 2; state.replacementAvailable = true',
            response: { reservationId: "reservation-b", state: "ACTIVE", revision: 0 },
        },
        {
            name: "redeem", command: "redeemVoucher()",
            setup: 'state.allocationId = "allocation-a"; state.allocationRevision = 2; secretState.revealedCode = "RAW-CODE"',
            response: { state: "REDEEMED", revision: 3 },
        },
        {
            name: "revoke", command: "runRevoke()",
            setup: `state.revokePreview = {
                aggregateType: "batches", aggregateIdentity: "batch-a", revision: 4,
                affectedCount: 2, previewToken: "preview-a"
            }; secretState.previewToken = "preview-a"`,
            response: { accepted: true },
        },
    ];

    for (const testCase of cases) {
        const browser = new BrowserHarness(source, htmlElements);
        browser.evaluate(`
            state.tenant = "tenant-a";
            state.principal = "customer-a";
            state.scopeReady = true;
            ${testCase.setup};
            refreshCustomerActions();
            refreshOperatorActions();
        `);
        const committedEffects = new Set();
        let attempt = 0;
        browser.fetchHandler = async request => {
            const idempotencyKey = request.options.headers["Idempotency-Key"];
            assert.ok(idempotencyKey, `${testCase.name} must send an idempotency key`);
            committedEffects.add(idempotencyKey);
            attempt += 1;
            if (attempt === 1) throw new TypeError("response lost after commit");
            return browser.jsonResponse(testCase.response);
        };

        await assert.rejects(browser.call(testCase.command), /response lost after commit/);
        await browser.call(testCase.command);

        assert.equal(browser.requests.length, 2, `${testCase.name} retry must issue a second request`);
        const firstKey = browser.requests[0].options.headers["Idempotency-Key"];
        const retryKey = browser.requests[1].options.headers["Idempotency-Key"];
        assert.equal(retryKey, firstKey, `${testCase.name} ambiguous retry must reuse the exact key`);
        assert.equal(committedEffects.size, 1, `${testCase.name} ambiguous retry must preserve one logical effect`);

        browser.evaluate(`${testCase.setup}; refreshCustomerActions(); refreshOperatorActions();`);
        await browser.call(testCase.command);
        const nextIntentKey = browser.requests[2].options.headers["Idempotency-Key"];
        assert.notEqual(nextIntentKey, retryKey, `${testCase.name} definitive success must clear the completed intent`);
        assert.equal(committedEffects.size, 2, `${testCase.name} next intent must receive a fresh effect identity`);
        assert.equal(browser.requests.some(request => request.path.includes(firstKey)), false);
        assert.equal(browser.attributeWrites.some(write => write.includes(firstKey)), false);
        assert.deepEqual(browser.forbiddenAccesses, []);

        const malformed = new BrowserHarness(source, htmlElements);
        malformed.evaluate(`
            state.tenant = "tenant-a";
            state.principal = "customer-a";
            state.scopeReady = true;
            ${testCase.setup};
            refreshCustomerActions();
            refreshOperatorActions();
        `);
        let malformedAttempt = 0;
        malformed.fetchHandler = async () => {
            malformedAttempt += 1;
            if (malformedAttempt === 1) {
                return {
                    ok: true,
                    status: 200,
                    headers: { get: () => null },
                    json: async () => { throw new SyntaxError("truncated committed response"); },
                };
            }
            return malformed.jsonResponse(testCase.response);
        };
        await assert.rejects(
            malformed.call(testCase.command),
            failure => failure?.name === "SyntaxError",
            `${testCase.name} malformed success must remain ambiguous`,
        );
        await malformed.call(testCase.command);
        assert.equal(
            malformed.requests[1].options.headers["Idempotency-Key"],
            malformed.requests[0].options.headers["Idempotency-Key"],
            `${testCase.name} malformed success retry must reuse the exact key`,
        );

        const retryable = new BrowserHarness(source, htmlElements);
        retryable.evaluate(`
            state.tenant = "tenant-a";
            state.principal = "customer-a";
            state.scopeReady = true;
            ${testCase.setup};
            refreshCustomerActions();
            refreshOperatorActions();
        `);
        let retryableAttempt = 0;
        retryable.fetchHandler = async () => {
            retryableAttempt += 1;
            if (retryableAttempt === 1) {
                const payload = testCase.name === "reserve"
                    ? { code: "BACKEND_TIMEOUT", requestId: "request-retryable" }
                    : { code: "BACKEND_TIMEOUT", requestId: "request-retryable", retryAfterSeconds: 1 };
                return retryable.jsonResponse(payload, false, 503, { "Retry-After": "1" });
            }
            return retryable.jsonResponse(testCase.response);
        };
        await assert.rejects(
            retryable.call(testCase.command),
            failure => failure?.definitiveResponse === false && failure?.retryAfterSeconds === 1,
            `${testCase.name} retryable response must preserve the logical intent`,
        );
        await retryable.call(testCase.command);
        assert.equal(
            retryable.requests[1].options.headers["Idempotency-Key"],
            retryable.requests[0].options.headers["Idempotency-Key"],
            `${testCase.name} retryable response must reuse the exact key`,
        );
    }

    const terminal = new BrowserHarness(source, htmlElements);
    terminal.evaluate(`
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        refreshCustomerActions();
    `);
    terminal.fetchHandler = async () => terminal.requests.length === 1
        ? terminal.jsonResponse({ code: "USER_LIMIT_REACHED", requestId: "request-terminal" }, false, 409)
        : terminal.jsonResponse({ reservationId: "reservation-terminal", state: "ACTIVE", revision: 0 });
    await assert.rejects(terminal.call("reserveVoucher()"), /USER_LIMIT_REACHED/);
    await terminal.call("reserveVoucher()");
    assert.notEqual(
        terminal.requests[1].options.headers["Idempotency-Key"],
        terminal.requests[0].options.headers["Idempotency-Key"],
        "a definitive HTTP error must clear the completed intent",
    );

    const reset = new BrowserHarness(source, htmlElements);
    reset.evaluate(`
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        refreshCustomerActions();
    `);
    let resetAttempt = 0;
    reset.fetchHandler = async () => {
        resetAttempt += 1;
        if (resetAttempt === 1) throw new TypeError("response lost after commit");
        return reset.jsonResponse({ reservationId: "reservation-reset", state: "ACTIVE", revision: 0 });
    };
    await assert.rejects(reset.call("reserveVoucher()"), /response lost after commit/);
    reset.evaluate(`
        resetCustomerScope();
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        refreshCustomerActions();
    `);
    await reset.call("reserveVoucher()");
    assert.notEqual(
        reset.requests[1].options.headers["Idempotency-Key"],
        reset.requests[0].options.headers["Idempotency-Key"],
        "explicit scope reset must discard ambiguous command intent state",
    );
}

async function controllerSeparationContract() {
    const survivesTransportTimeout = new BrowserHarness(source, htmlElements);
    const scopeController = new AbortController();
    survivesTransportTimeout.context.testScopeController = scopeController;
    survivesTransportTimeout.evaluate(`
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        state.scopeController = testScopeController;
        refreshCustomerActions();
    `);
    let releaseReserve;
    survivesTransportTimeout.fetchHandler = async request => {
        if (request.path.includes("/reservations")) {
            return new Promise(resolve => {
                releaseReserve = () => resolve(survivesTransportTimeout.jsonResponse({
                    reservationId: "reservation-survived", state: "ACTIVE", revision: 0,
                }));
            });
        }
        if (request.path === "/api/v1/events") {
            return {
                ok: true,
                status: 200,
                body: {
                    getReader: () => ({
                        read: async () => new Promise(() => undefined),
                        cancel: async () => undefined,
                    }),
                },
                json: async () => ({}),
            };
        }
        return survivesTransportTimeout.jsonResponse({ reservations: [], allocations: [] });
    };
    const reservation = survivesTransportTimeout.element("#reserve-voucher").dispatch("click", { key: "Enter" });
    await survivesTransportTimeout.settle();
    const reservationRequest = survivesTransportTimeout.requests.find(request => request.path.includes("/reservations"));
    const streaming = survivesTransportTimeout.call("connectEventStream()");
    await survivesTransportTimeout.settle();
    const streamController = survivesTransportTimeout.evaluate("state.transportController");
    survivesTransportTimeout.fireTimers(60000);
    await streaming;
    assert.equal(streamController.signal.aborted, true);
    assert.equal(reservationRequest.options.signal.aborted, false, "SSE timeout must not abort an in-flight command");
    assert.equal(survivesTransportTimeout.element("#reserve-voucher").disabled, true);
    assert.equal(survivesTransportTimeout.requests.filter(request => request.path === "/api/v1/snapshots").length, 10);
    releaseReserve();
    await reservation;
    assert.equal(survivesTransportTimeout.evaluate("state.reservationId"), "reservation-survived");
    assert.equal(survivesTransportTimeout.element("#reserve-voucher").disabled, false);

    const scopeChange = new BrowserHarness(source, htmlElements);
    const cancellableScope = new AbortController();
    scopeChange.context.testScopeController = cancellableScope;
    scopeChange.evaluate(`
        state.tenant = "tenant-a";
        state.principal = "customer-a";
        state.campaignId = "campaign-a";
        state.scopeReady = true;
        state.scopeController = testScopeController;
        refreshCustomerActions();
    `);
    let releaseStale;
    scopeChange.fetchHandler = async () => new Promise(resolve => {
        releaseStale = () => resolve(scopeChange.jsonResponse({
            reservationId: "stale-reservation", state: "ACTIVE", revision: 0,
        }));
    });
    const stale = scopeChange.element("#reserve-voucher").dispatch("click", { key: "Enter" });
    await scopeChange.settle();
    const staleRequest = scopeChange.requests[0];
    scopeChange.element("#tenant").value = "tenant-b";
    await scopeChange.element("#tenant").dispatch("input");
    await stale;
    releaseStale();
    await scopeChange.settle();
    assert.equal(staleRequest.options.signal.aborted, true, "scope change must cancel in-flight commands");
    assert.equal(scopeChange.evaluate("state.reservationId"), null, "stale completion must not mutate the new scope");
    assert.equal(scopeChange.element("#live-status").textContent, "", "scope cancellation must remain aria-live silent");
}

async function reconnectContract() {
    const browser = new BrowserHarness(source, htmlElements);
    browser.evaluate("state.pollingAttempts = 9");
    browser.fetchHandler = async () => ({
        ok: true,
        status: 200,
        body: { getReader: () => ({ read: () => new Promise(() => undefined) }) },
        json: async () => ({}),
    });
    void browser.call("connectEventStream()");
    await browser.settle();
    const firstController = browser.evaluate("state.transportController");
    assert.equal(browser.evaluate("state.pollingAttempts"), 0, "each connection must reset its retry budget");

    browser.evaluate("state.pollingAttempts = 7");
    void browser.call("connectEventStream()");
    await browser.settle();
    assert.equal(firstController.signal.aborted, true, "reconnect must abort the prior connection");
    assert.equal(browser.evaluate("state.pollingAttempts"), 0, "reconnect must receive a fresh retry budget");
}

await replacementContract();
await secretLifecycleContract();
await revokeHandlerContract();
await boundedPollingContract();
await responseBodyTimeoutContract();
await eventStreamContract();
await eventStreamTimeoutContract();
await customerScopeResetContract();
await commandLatchContract();
await ambiguousCommandRetryIdempotencyContract();
await controllerSeparationContract();
await reconnectContract();
console.log("voucher-pool browser behavior contract passed");
