(() => {
  const operator = 'manager-demo';
  const role = 'manager';
  const headers = () => ({ 'X-Demo-Operator': operator, 'X-Demo-Role': role });
  const status = document.querySelector('#status');
  const plans = document.querySelector('#plans');
  const render = (items) => {
    plans.replaceChildren(...items.map((plan) => {
      const row = document.createElement('tr');
      row.innerHTML = `<td>${plan.revision}</td><td>${plan.coverageMinor}</td><td>${plan.gaps}</td><td>${plan.fairnessMinor}</td><td>${(plan.reasons || []).join(', ')}</td>`;
      return row;
    }));
  };
  const refresh = async () => {
    const response = await fetch('/api/shift-coverage/plans', { headers: headers() });
    if (!response.ok) { status.textContent = `조회 실패: ${response.status}`; return; }
    render(await response.json());
    status.textContent = '계획을 조회했습니다.';
  };
  document.querySelector('#refresh').addEventListener('click', refresh);
  document.querySelector('#replan').addEventListener('click', async () => {
    const response = await fetch('/api/shift-coverage/replans', { method: 'POST', headers: { ...headers(), 'Idempotency-Key': `replan-${Date.now()}` } });
    status.textContent = response.status === 202 ? 'replan을 접수했습니다.' : `replan 실패: ${response.status}`;
    if (response.status === 202) await refresh();
  });
  refresh();
})();
