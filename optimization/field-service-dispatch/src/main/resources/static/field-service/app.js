(() => {
  'use strict';
  const status = document.querySelector('#status');
  const visits = document.querySelector('#visits');
  const workers = document.querySelector('#workers');
  const plans = document.querySelector('#plans');
  let timer = null;
  let inFlight = false;
  let visibilityEpoch = 0;
  let retryDelay = 2000;
  const cache = {
    visits: { etag: null, body: [] },
    workers: { etag: null, body: [] },
    plans: { etag: null, body: [] },
  };

  const text = (value) => document.createTextNode(String(value));
  const replaceList = (target, values, label) => {
    target.replaceChildren();
    values.forEach((value) => {
      const item = document.createElement('li');
      item.append(text(`${label}: ${value.visitId || value.workerId || 'unknown'}`));
      target.append(item);
    });
  };

  const replacePlans = (target, values, visitValues) => {
    target.replaceChildren();
    const manualPinCount = visitValues.filter((visit) => visit.manualPin || visit.startedPin).length;
    values.forEach((entry) => {
      const plan = entry.plan || entry;
      const item = document.createElement('li');
      const scoreSummary = plan.score || {};
      const score = scoreSummary;
      const reasons = (plan.explanations || []).map((explanation) => explanation.reason).join(', ') || '없음';
      const assignedRoutes = (plan.routes || []).reduce((count, route) => count + (route.visits || []).length, 0);
      item.textContent = `revision ${plan.planRevision}: ${plan.state} / assigned ${score.assignedCount ?? assignedRoutes} / unassigned ${score.unassignedCount ?? 0} / hard ${score.hardScore ?? 0} / manualPin ${manualPinCount} / constraint ${reasons}`;
      target.append(item);
    });
    if (values.length === 0) {
      const empty = document.createElement('li');
      empty.textContent = '계획 없음';
      target.append(empty);
    }
  };

  async function get(path, entry) {
    const headers = entry.etag ? { 'If-None-Match': entry.etag } : {};
    const response = await fetch(path, { headers });
    if (response.status === 304) return entry.body;
    if (response.status === 429 || response.status >= 500) throw Object.assign(new Error('retryable response'), { retryable: true });
    if (!response.ok) throw new Error('조회 실패');
    entry.etag = response.headers.get('ETag');
    entry.body = await response.json();
    return entry.body;
  }

  async function refresh() {
    if (inFlight || document.visibilityState === 'hidden') return;
    inFlight = true;
    const requestEpoch = visibilityEpoch;
    try {
      const [visitBody, workerBody, planBody] = await Promise.all([
        get('/api/field-service/visits', cache.visits),
        get('/api/field-service/workers', cache.workers),
        get('/api/field-service/plans?planId=field-service&limit=20', cache.plans),
      ]);
      if (requestEpoch !== visibilityEpoch || document.visibilityState === 'hidden') return;
      replaceList(visits, visitBody, '방문');
      replaceList(workers, workerBody, '작업자');
      replacePlans(plans, planBody, visitBody);
      status.textContent = `마지막 조회: ${new Date().toLocaleTimeString('ko-KR')}`;
      retryDelay = 2000;
      schedule(retryDelay);
    } catch (error) {
      if (requestEpoch !== visibilityEpoch || document.visibilityState === 'hidden') return;
      status.textContent = error.retryable ? '잠시 후 다시 조회합니다' : '조회에 실패했습니다';
      if (error.retryable) {
        schedule(retryDelay);
        retryDelay = Math.min(retryDelay * 2, 10000);
      }
    } finally {
      inFlight = false;
    }
  }

  function schedule(delay = 2000) {
    if (timer !== null) clearTimeout(timer);
    timer = setTimeout(() => { timer = null; refresh(); }, delay);
  }

  document.querySelector('#refresh').addEventListener('click', refresh);
  document.addEventListener('visibilitychange', () => {
    visibilityEpoch += 1;
    if (document.visibilityState === 'visible') { schedule(2000); }
    else if (timer !== null) { clearTimeout(timer); timer = null; }
  });
  void refresh();
  void visibilityEpoch;
})();
