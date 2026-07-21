const form = document.querySelector('#recovery-form');
const buyerInput = document.querySelector('#buyer-id');
const attemptInput = document.querySelector('#attempt-id');
const snapshot = document.querySelector('#snapshot');
const activity = document.querySelector('#activity');
const transportState = document.querySelector('#transport-state');
const fallback = document.querySelector('[data-polling-fallback]');
let pollingTimer = null;
let stream = null;

function record(message) {
  const item = document.createElement('li');
  item.textContent = `${new Date().toLocaleTimeString()} — ${message}`;
  activity.prepend(item);
  while (activity.children.length > 6) activity.lastElementChild.remove();
}

async function refreshSnapshot() {
  const buyer = buyerInput.value.trim();
  const attempt = attemptInput.value.trim();
  if (!buyer || !attempt) {
    snapshot.textContent = 'Enter both UUIDs to query the durable owner-scoped snapshot.';
    return;
  }
  try {
    const response = await fetch(`/api/v1/purchase-attempts/${encodeURIComponent(attempt)}`, {
      cache: 'no-store',
      headers: { 'X-Demo-Buyer': buyer },
    });
    const body = await response.json().catch(() => ({ status: response.status }));
    snapshot.textContent = JSON.stringify(body, null, 2);
    record(response.ok ? `Observed ${body.state || 'snapshot'} from PostgreSQL.` : `Lookup returned ${response.status}.`);
  } catch {
    snapshot.textContent = 'Snapshot unavailable. Keep the same attempt ID and retry.';
    record('Network outcome is ambiguous; identity and attempt ID were retained in memory.');
  }
}

function switchToPolling() {
  if (stream) stream.close();
  stream = null;
  document.body.dataset.transport = 'polling';
  transportState.textContent = 'Polling fallback';
  fallback.hidden = false;
  if (!pollingTimer) pollingTimer = setInterval(refreshSnapshot, 3000);
}

function connectObservation() {
  // Network SSE is intentionally an extraction adapter, not part of the core in-memory stream contract.
  document.body.dataset.transport = 'snapshot';
  transportState.textContent = 'Snapshot first';
  fallback.hidden = true;
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  await refreshSnapshot();
});

window.ticketDemo = Object.freeze({
  disconnectSseForTest: switchToPolling,
  refreshSnapshot,
});

connectObservation();
