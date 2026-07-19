const state = { source: null, snapshot: null };
const byId = (id) => document.getElementById(id);

byId('create-order').addEventListener('click', createOrder);
byId('load-order').addEventListener('click', () => loadOrder(byId('order-id').value.trim()));
byId('replay').addEventListener('click', replayFailed);

async function createOrder() {
  const suffix = crypto.randomUUID().slice(0, 8);
  const response = await fetch('/api/v1/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `browser-${crypto.randomUUID()}` },
    body: JSON.stringify({
      tenantId: 'browser-tenant', customerReference: `browser-${suffix}`,
      providerMode: byId('provider-mode').value,
      lines: [
        { sku: 'sku-alpha', quantity: 1, unitPrice: 12.50 },
        { sku: 'sku-beta', quantity: 2, unitPrice: 8.25 }
      ]
    })
  });
  const body = await response.json();
  if (!response.ok) return showError(body.code || 'CREATE_FAILED');
  byId('order-id').value = body.orderId;
  await loadOrder(body.orderId);
}

async function loadOrder(orderId) {
  if (!orderId) return;
  const response = await fetch(`/api/v1/orders/${orderId}`);
  if (!response.ok) return showError('ORDER_NOT_FOUND');
  render(await response.json());
  connect(orderId);
}

function connect(orderId) {
  state.source?.close();
  state.source = new EventSource(`/api/v1/orders/${orderId}/events`);
  state.source.addEventListener('snapshot', (event) => render(JSON.parse(event.data)));
  state.source.addEventListener('audit', () => refresh(orderId));
  state.source.onopen = () => byId('connection').textContent = 'live';
  state.source.onerror = () => byId('connection').textContent = 'reconnecting';
}

async function refresh(orderId) {
  const response = await fetch(`/api/v1/orders/${orderId}`);
  if (response.ok) render(await response.json());
}

function render(snapshot) {
  state.snapshot = snapshot;
  const p = snapshot.operations.publications;
  byId('operations').innerHTML = [
    metric('Publication failed', p.failed),
    metric('Oldest lag', `${p.oldestIncompleteLagSeconds}s`),
    metric('Unresolved provider', snapshot.operations.unresolvedProviderEvents),
    metric('Completed publications', p.completed)
  ].join('');

  const aggregates = [
    card('Order', snapshot.order.status, snapshot.order.revision, snapshot.order.cancelReason),
    paymentCard(snapshot.payment),
    card('Inventory', snapshot.reservation.status, snapshot.reservation.revision, snapshot.reservation.reasonCode),
    ...snapshot.fulfillments.map(fulfillmentCard),
    ...snapshot.cancellations.map((c) => card('Cancellation', c.status, c.revision, c.reasonCode)),
    ...snapshot.refunds.map((r) => card('Refund', r.status, r.revision, r.reasonCode))
  ];
  byId('aggregates').innerHTML = aggregates.join('');
  byId('lines').innerHTML = snapshot.lines.map(lineCard).join('');
  byId('audit').innerHTML = snapshot.audit.map((a) => `
    <tr><td>${a.id}</td><td>${a.aggregateType}</td><td>${a.revision}</td>
    <td>${a.fromStatus || '—'} → ${a.toStatus}</td><td>${a.reasonCode || '—'}</td><td>${a.actorType}</td></tr>
  `).join('');
}

function paymentCard(payment) {
  const action = payment.status === 'AUTHORIZING'
    ? `<button onclick="reconcileDelayed('${payment.id}')">Deliver delayed success</button>`
    : '';
  return card('Payment', payment.status, payment.revision, null, action);
}

function fulfillmentCard(fulfillment) {
  const next = { REQUESTED: 'ALLOCATED', ALLOCATED: 'PICKING', PICKING: 'SHIPPED', SHIPPED: 'DELIVERED' }[fulfillment.status];
  const quantities = fulfillment.lines.map((line) => `${line.lineId.slice(0, 8)} · qty ${line.quantity}`).join('<br>');
  const action = next
    ? `<button onclick="advanceFulfillment('${fulfillment.id}', '${next}')">Advance to ${next}</button>`
    : '';
  return card(fulfillment.groupReference, fulfillment.status, fulfillment.revision, fulfillment.cancelReason, `${quantities}${action}`);
}

function lineCard(line) {
  const remaining = line.quantity - line.cancelledQuantity;
  const cancellableQuantity = state.snapshot.fulfillments
    .filter((group) => !['SHIPPED', 'DELIVERED', 'CANCELLED'].includes(group.status))
    .flatMap((group) => group.lines)
    .filter((link) => link.lineId === line.lineId)
    .reduce((total, link) => total + link.quantity, 0);
  const action = remaining > 0 && cancellableQuantity > 0
    ? `<button onclick="cancelLine('${line.lineId}')">Cancel one</button>`
    : '';
  return card(`Line ${line.sku}`, `${remaining} active`, line.cancelledQuantity, null, action);
}

async function advanceFulfillment(groupId, target) {
  await command(`/api/v1/orders/${state.snapshot.order.id}/fulfillments/${groupId}/advance`, { target });
}

async function cancelLine(lineId) {
  await command(`/api/v1/orders/${state.snapshot.order.id}/lines/${lineId}/cancel`, {
    quantity: 1,
    reasonCode: 'CUSTOMER_REQUEST'
  });
}

async function reconcileDelayed(paymentAttemptId) {
  await command(`/api/v1/operations/payments/${paymentAttemptId}/reconcile-delayed`, undefined, true);
}

async function command(url, body, operator = false) {
  const headers = body ? { 'Content-Type': 'application/json' } : {};
  if (operator) headers['X-Workshop-Operator'] = 'local-console';
  const response = await fetch(url, {
    method: 'POST',
    headers,
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok) return showError((await response.json()).code || 'COMMAND_FAILED');
  await refresh(state.snapshot.order.id);
}

async function replayFailed() {
  const response = await fetch('/api/v1/operations/publications/replay-failed?batchSize=10', {
    method: 'POST',
    headers: { 'X-Workshop-Operator': 'local-console' }
  });
  if (!response.ok) showError('REPLAY_FAILED');
}

function metric(label, value) { return `<article class="card"><small>${label}</small><strong>${value}</strong></article>`; }
function card(label, status, revision, reason, action = '') {
  return `<article class="card"><small>${label}</small><strong>${status}</strong><code>revision ${revision}</code>${reason ? `<p>${reason}</p>` : ''}${action}</article>`;
}
function showError(code) { byId('connection').innerHTML = `<span class="error">${code}</span>`; }
