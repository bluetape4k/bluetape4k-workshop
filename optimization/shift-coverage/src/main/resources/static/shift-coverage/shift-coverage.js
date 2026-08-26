(() => {
  'use strict';

  const operator = 'manager-demo';
  const role = 'manager';
  const status = document.querySelector('#status');
  const plans = document.querySelector('#plans');

  const requestHeaders = (requestId) => {
    const headers = {
      'X-Demo-Operator': operator,
      'X-Demo-Role': role,
    };
    if (requestId) headers['X-Request-Id'] = requestId;
    return headers;
  };

  const parseBody = async (response) => {
    const text = await response.text();
    if (text.length === 0) return null;
    try {
      return JSON.parse(text);
    } catch (_error) {
      return null;
    }
  };

  const render = (items) => {
    plans.replaceChildren();
    items.forEach((plan) => {
      const row = document.createElement('tr');
      [
        plan.revision,
        plan.coverageMinor,
        plan.gaps,
        plan.fairnessMinor,
        (plan.reasons || []).join(', ') || '없음',
      ].forEach((value) => {
        const cell = document.createElement('td');
        cell.textContent = String(value ?? '');
        row.append(cell);
      });
      plans.append(row);
    });
    if (items.length === 0) {
      const row = document.createElement('tr');
      const cell = document.createElement('td');
      cell.colSpan = 5;
      cell.textContent = '계획 없음';
      row.append(cell);
      plans.append(row);
    }
  };

  const showFailure = (response, payload) => {
    const code = payload?.code || 'REQUEST_INVALID';
    if (response.status === 429 || code === 'REPLAN_REJECTED') {
      const retryAfter = Number(response.headers.get('Retry-After') || payload?.retryAfter || 1);
      status.textContent = `요청이 많습니다. ${retryAfter}초 후 다시 시도하세요.`;
      return;
    }
    if (response.status === 413 || code === 'RESPONSE_TOO_LARGE') {
      status.textContent = '입력이 너무 큽니다. 요청을 줄이세요.';
      return;
    }
    if (response.status === 409 && code === 'REVISION_CONFLICT') {
      status.textContent = '계획이 변경되었습니다. 새로고침합니다.';
      void refresh();
      return;
    }
    status.textContent = `요청 실패: ${code}`;
  };

  const refresh = async () => {
    const requestId = `shift-coverage-read-${Date.now()}`;
    const response = await fetch('/api/shift-coverage/plans', { headers: requestHeaders(requestId) });
    const payload = await parseBody(response);
    if (!response.ok) {
      showFailure(response, payload);
      return;
    }
    render(Array.isArray(payload) ? payload : []);
    status.textContent = '계획을 조회했습니다.';
  };

  document.querySelector('#refresh').addEventListener('click', () => { void refresh(); });
  document.querySelector('#replan').addEventListener('click', async () => {
    const requestId = `shift-coverage-replan-${Date.now()}`;
    const response = await fetch('/api/shift-coverage/replans', {
      method: 'POST',
      headers: {
        ...requestHeaders(requestId),
        'Idempotency-Key': requestId,
      },
    });
    const payload = await parseBody(response);
    if (response.status === 202) {
      status.textContent = 'replan을 접수했습니다. 최신 계획을 확인합니다.';
      window.setTimeout(() => { void refresh(); }, 1000);
      return;
    }
    if (response.status === 429 || response.status === 413 || response.status === 409) {
      showFailure(response, payload);
      return;
    }
    showFailure(response, payload);
  });

  void refresh();
})();
