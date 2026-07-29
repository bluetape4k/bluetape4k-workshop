const resourcesNode = document.querySelector('#resources');
const observedNode = document.querySelector('#observed');
const activeNode = document.querySelector('#active-command');
const activityNode = document.querySelector('#activity');
const ownerCredential = randomHex(32);
const pendingCommands = new Map();
let activeHold = null;
let activeWaitlist = null;

async function refresh() {
  const response = await fetch('/api/resources', { cache: 'no-store' });
  if (!response.ok) {
    observedNode.textContent = `Snapshot unavailable (${response.status})`;
    return;
  }
  const snapshot = await response.json();
  observedNode.textContent = `Observed at ${new Date(snapshot.observedAt).toLocaleString()}`;
  resourcesNode.replaceChildren(...snapshot.resources.map(resourceCard));
  await refreshActiveWaitlist();
}

function resourceCard(resource) {
  const article = document.createElement('article');
  article.className = 'resource';
  const title = document.createElement('h3');
  title.textContent = resource.code;
  const capacity = document.createElement('p');
  capacity.textContent = `${resource.availableCount} of ${resource.capacity} available`;
  const revision = document.createElement('small');
  revision.textContent = `revision ${resource.revision} · policy ${resource.policyVersion} · ${resource.timezone}`;
  const localTime = document.createElement('time');
  localTime.dateTime = resource.localObservedAt;
  localTime.textContent = `Local calendar time ${new Date(resource.localObservedAt).toLocaleString([], { timeZone: resource.timezone })}`;
  const actions = document.createElement('div');
  actions.className = 'resource-actions';
  const hold = actionButton('Create 30s hold', () => createHold(resource));
  const waitlist = actionButton('Join waitlist', () => joinWaitlist(resource), 'secondary');
  actions.append(hold, waitlist);
  article.append(title, capacity, localTime, revision, actions);
  return article;
}

function actionButton(label, action, style = '') {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  button.className = style;
  button.addEventListener('click', async () => {
    button.disabled = true;
    try { await action(); } finally { button.disabled = false; }
  });
  return button;
}

async function createHold(resource) {
  const result = await command(
    `hold:${resource.id}:${resource.revision}`,
    `/api/resources/${resource.id}/holds`,
    { expectedResourceRevision: resource.revision, policyVersion: resource.policyVersion },
  );
  if (!result.ok) return recordFailure('Hold', result);
  activeHold = result.body;
  activeWaitlist = null;
  renderActive();
  record(`Hold ${activeHold.id} created; expires ${new Date(activeHold.expiresAt).toLocaleTimeString()}.`);
  await refresh();
}

async function joinWaitlist(resource) {
  const result = await command(
    `waitlist:${resource.id}:${resource.revision}`,
    `/api/resources/${resource.id}/waitlist`,
    { expectedResourceRevision: resource.revision, policyVersion: resource.policyVersion },
  );
  if (!result.ok) return recordFailure('Waitlist', result);
  activeWaitlist = result.body;
  activeHold = null;
  renderActive();
  record(`Waitlist entry ${activeWaitlist.id} joined at sequence ${activeWaitlist.sequence}.`);
}

async function refreshActiveWaitlist() {
  if (!activeWaitlist || ['CANCELLED', 'ACCEPTED'].includes(activeWaitlist.state)) return;
  const response = await fetch(`/api/waitlist/${activeWaitlist.id}`, {
    cache: 'no-store',
    headers: { 'X-Reservation-Owner': ownerCredential },
  });
  if (!response.ok) return;
  activeWaitlist = await response.json();
  renderActive();
}

async function cancelWaitlist() {
  if (!activeWaitlist) return;
  const result = await command(
    `cancel-waitlist:${activeWaitlist.id}:${activeWaitlist.revision}`,
    `/api/waitlist/${activeWaitlist.id}/cancel`,
    { expectedRevision: activeWaitlist.revision },
  );
  if (!result.ok) return recordFailure('Cancel waitlist', result);
  activeWaitlist = result.body;
  renderActive();
  record(`Waitlist entry ${activeWaitlist.id} cancelled.`);
}

async function acceptOffer() {
  if (!activeWaitlist?.offerId) return;
  const result = await command(
    `accept-offer:${activeWaitlist.offerId}:${activeWaitlist.offerRevision}`,
    `/api/offers/${activeWaitlist.offerId}/accept`,
    { expectedRevision: activeWaitlist.offerRevision },
  );
  if (!result.ok) return recordFailure('Accept offer', result);
  activeWaitlist = { ...activeWaitlist, state: 'ACCEPTED' };
  renderActive();
  record(`Offer ${result.body.id} accepted; capacity remains authoritatively occupied.`);
  await refresh();
}

async function mutateHold(operation) {
  if (!activeHold) return;
  const result = await command(
    `${operation}:${activeHold.id}:${activeHold.revision}`,
    `/api/holds/${activeHold.id}/${operation}`,
    { expectedRevision: activeHold.revision, policyVersion: activeHold.policyVersion },
  );
  if (!result.ok) return recordFailure(operation, result);
  activeHold = result.body;
  renderActive();
  record(`Hold ${activeHold.id} is now ${activeHold.state}.`);
  await refresh();
}

function renderActive() {
  activeNode.replaceChildren();
  if (activeHold) {
    const summary = document.createElement('p');
    const remaining = Math.max(0, Math.ceil((new Date(activeHold.expiresAt).getTime() - Date.now()) / 1000));
    summary.textContent = `Hold ${activeHold.id} · ${activeHold.state} · revision ${activeHold.revision} · ${remaining}s remaining`;
    activeNode.append(summary);
    if (activeHold.state === 'HELD') {
      activeNode.append(
        actionButton('Confirm', () => mutateHold('confirm')),
        actionButton('Cancel', () => mutateHold('cancel'), 'danger'),
      );
    }
    return;
  }
  if (activeWaitlist) {
    const summary = document.createElement('p');
    summary.textContent = `Waitlist ${activeWaitlist.id} · ${activeWaitlist.state} · position ${activeWaitlist.position || 'pending refresh'}`;
    activeNode.append(summary);
    if (activeWaitlist.state === 'WAITING') {
      activeNode.append(actionButton('Cancel waitlist', cancelWaitlist, 'danger'));
    }
    if (activeWaitlist.state === 'OFFERED' && activeWaitlist.offerId) {
      const remaining = Math.max(0, Math.ceil((new Date(activeWaitlist.offerExpiresAt).getTime() - Date.now()) / 1000));
      const offer = document.createElement('p');
      offer.textContent = `Offer ${activeWaitlist.offerId} expires in ${remaining}s.`;
      activeNode.append(offer, actionButton('Accept offer', acceptOffer));
    }
    return;
  }
  activeNode.textContent = 'No active hold or waitlist entry.';
}

async function command(commandId, uri, payload) {
  const key = pendingCommands.get(commandId) || randomHex(16);
  pendingCommands.set(commandId, key);
  try {
    const response = await fetch(uri, {
      method: 'POST',
      cache: 'no-store',
      headers: {
        'Content-Type': 'application/json',
        'X-Reservation-Owner': ownerCredential,
        'Idempotency-Key': key,
      },
      body: JSON.stringify(payload),
    });
    const body = await response.json();
    if (response.ok || !body.retryable) pendingCommands.delete(commandId);
    return { ok: response.ok, status: response.status, replayed: response.headers.get('Idempotency-Replayed'), body };
  } catch (error) {
    record(`Network outcome is ambiguous; retrying the same command will reuse its idempotency key.`, true);
    return { ok: false, status: 0, body: { reason: 'NETWORK_AMBIGUOUS', retryable: true } };
  }
}

function recordFailure(label, result) {
  record(`${label} rejected: ${result.body.reason || result.status}${result.body.retryable ? ' (retryable)' : ''}.`, true);
}

function record(message, error = false) {
  const item = document.createElement('li');
  item.textContent = `${new Date().toLocaleTimeString()} — ${message}`;
  if (error) item.className = 'error';
  activityNode.prepend(item);
  while (activityNode.children.length > 8) activityNode.lastElementChild.remove();
}

function randomHex(bytes) {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return Array.from(data, value => value.toString(16).padStart(2, '0')).join('');
}

document.querySelector('#refresh').addEventListener('click', refresh);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refresh();
});
refresh();
setInterval(refresh, 2000);
setInterval(renderActive, 1000);
