#!/usr/bin/env node

import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  designSource,
  invariantIds,
  locales,
  scenarioIds,
  scenarios,
  sourceBaseline,
  viewIds,
  viewModels,
} from './visual-companions/usage-billing-evolution-model.mjs';

const scriptRoot = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptRoot, '..');
const outputPaths = {
  en: 'docs/visual-companions/en/usage-billing-evolution.html',
  ko: 'docs/visual-companions/ko/usage-billing-evolution.html',
};

function json(value) {
  return JSON.stringify(value).replaceAll('<', '\\u003c');
}

function html(localeId) {
  const copy = locales[localeId];
  const initialView = viewIds[0];
  const initialScenario = scenarioIds[initialView][0];
  const sourceHref = `../../superpowers/specs/${designSource}`;
  const localizedModel = {
    viewIds,
    invariantIds,
    scenarioIds,
    scenarios,
    viewModels,
    copy,
  };

  return `<!doctype html>
<html lang="${copy.lang}">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light dark">
  <title>${copy.title}</title>
  <script>
    (() => {
      const storageKey = 'starlight-theme';
      const params = new URLSearchParams(location.search);
      const requested = params.get('theme');
      const stored = localStorage.getItem(storageKey);
      const resolved = requested === 'light' || requested === 'dark'
        ? requested
        : stored === 'light' || stored === 'dark'
          ? stored
          : matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
      document.documentElement.dataset.theme = resolved;
      document.documentElement.dataset.capture = params.get('capture') === '1' ? 'true' : 'false';
    })();
  </script>
  <style>
    :root {
      color-scheme: light dark;
      --font: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      --mono: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
      --radius: 18px;
      --line: #27364a;
      --line-strong: #41536c;
      --cyan: #33d6e5;
      --violet: #a98bff;
      --amber: #ffbd5b;
      --green: #4fe0a1;
      --red: #ff7188;
    }
    :root[data-theme="dark"] {
      --page: #07101c;
      --surface: #0c1726;
      --surface-2: #111f31;
      --surface-3: #17273b;
      --text: #f3f7fc;
      --muted: #a8b6c9;
      --border: #2b3d55;
      --shadow: 0 24px 80px rgba(0, 0, 0, .34);
      --grid: rgba(119, 153, 193, .08);
    }
    :root[data-theme="light"] {
      --page: #edf3f8;
      --surface: #ffffff;
      --surface-2: #f5f8fb;
      --surface-3: #e9f0f6;
      --text: #122033;
      --muted: #53657b;
      --border: #bdcad8;
      --shadow: 0 24px 60px rgba(35, 58, 87, .14);
      --grid: rgba(35, 76, 117, .08);
      --line: #8ca0b7;
      --line-strong: #647b95;
      --cyan: #007f90;
      --violet: #694bc2;
      --amber: #a76500;
      --green: #087a4e;
      --red: #bd2f4c;
    }
    * { box-sizing: border-box; }
    html { min-width: 320px; background: var(--page); }
    body {
      margin: 0;
      min-height: 100vh;
      color: var(--text);
      background:
        linear-gradient(var(--grid) 1px, transparent 1px),
        linear-gradient(90deg, var(--grid) 1px, transparent 1px),
        radial-gradient(circle at 10% 0%, color-mix(in srgb, var(--cyan) 13%, transparent), transparent 32rem),
        var(--page);
      background-size: 32px 32px, 32px 32px, auto, auto;
      font-family: var(--font);
      line-height: 1.55;
    }
    button, a { font: inherit; }
    button { color: inherit; }
    :focus-visible { outline: 3px solid var(--cyan); outline-offset: 3px; }
    .shell { width: min(1380px, calc(100% - 40px)); margin: 0 auto; padding: 28px 0 52px; }
    .topbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 46px; }
    .brand { display: flex; align-items: center; gap: 12px; font-weight: 800; letter-spacing: -.02em; }
    .brand-mark { width: 38px; height: 38px; border: 1px solid var(--border); border-radius: 12px; display: grid; place-items: center; background: var(--surface); box-shadow: var(--shadow); color: var(--cyan); }
    .actions { display: flex; align-items: center; gap: 10px; }
    .action-link, .theme-toggle { min-height: 42px; border: 1px solid var(--border); border-radius: 12px; padding: 8px 14px; background: color-mix(in srgb, var(--surface) 88%, transparent); text-decoration: none; color: var(--text); cursor: pointer; }
    .action-link:hover, .theme-toggle:hover { border-color: var(--cyan); }
    .hero { max-width: 940px; margin-bottom: 34px; }
    .kicker { margin: 0 0 12px; color: var(--cyan); font: 800 13px/1.4 var(--mono); letter-spacing: .12em; text-transform: uppercase; }
    h1 { margin: 0; font-size: clamp(38px, 5.1vw, 72px); line-height: 1.05; letter-spacing: -.045em; text-wrap: balance; }
    .lead { max-width: 880px; margin: 22px 0 0; color: var(--muted); font-size: clamp(17px, 2vw, 22px); }
    .view-tabs { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 30px 0 22px; }
    .view-tab { border: 1px solid var(--border); border-radius: 16px; padding: 18px 20px; background: var(--surface); cursor: pointer; text-align: left; transition: transform .16s ease, border-color .16s ease; }
    .view-tab:hover { transform: translateY(-2px); border-color: var(--line-strong); }
    .view-tab[aria-pressed="true"] { border-color: var(--active); box-shadow: inset 0 0 0 1px var(--active), 0 16px 40px color-mix(in srgb, var(--active) 12%, transparent); }
    .view-tab small { display: block; color: var(--muted); font: 700 12px/1.4 var(--mono); margin-bottom: 5px; }
    .view-tab strong { display: block; font-size: 18px; }
    .workspace { --active: var(--cyan); border: 1px solid var(--border); border-radius: 24px; background: color-mix(in srgb, var(--surface) 96%, transparent); box-shadow: var(--shadow); overflow: hidden; }
    .workspace[data-view="event-sourcing"] { --active: var(--violet); }
    .workspace[data-view="microservices"] { --active: var(--amber); }
    .workspace-head { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 24px; align-items: start; padding: 28px 30px 24px; border-bottom: 1px solid var(--border); }
    .workspace-head h2 { margin: 0 0 8px; font-size: clamp(25px, 3vw, 38px); letter-spacing: -.035em; }
    .workspace-head p { max-width: 860px; margin: 0; color: var(--muted); font-size: 17px; }
    .authority-pill { align-self: center; min-width: 190px; padding: 12px 15px; border: 1px solid color-mix(in srgb, var(--active) 55%, var(--border)); border-radius: 14px; background: color-mix(in srgb, var(--active) 9%, var(--surface)); }
    .authority-pill span { display: block; color: var(--muted); font: 700 11px/1.3 var(--mono); text-transform: uppercase; letter-spacing: .08em; }
    .authority-pill strong { display: block; margin-top: 4px; font-size: 14px; overflow-wrap: anywhere; }
    .scenario-panel { padding: 24px 30px 0; }
    .scenario-label { display: block; margin-bottom: 10px; color: var(--muted); font: 800 12px/1.4 var(--mono); text-transform: uppercase; letter-spacing: .08em; }
    .scenario-tabs { display: flex; gap: 8px; flex-wrap: wrap; }
    .scenario-tab { min-height: 40px; padding: 8px 13px; border: 1px solid var(--border); border-radius: 999px; background: var(--surface-2); cursor: pointer; }
    .scenario-tab[aria-pressed="true"] { color: var(--page); border-color: var(--active); background: var(--active); font-weight: 800; }
    .flow-wrap { padding: 38px 30px 30px; overflow-x: auto; }
    .flow { min-width: 1120px; display: grid; grid-template-columns: repeat(9, minmax(28px, 1fr)); align-items: stretch; gap: 0; }
    .stage { position: relative; z-index: 1; min-height: 245px; padding: 20px; border: 1px solid var(--border); border-radius: 18px; background: linear-gradient(155deg, color-mix(in srgb, var(--active) 9%, var(--surface)), var(--surface)); box-shadow: 0 16px 32px rgba(0,0,0,.12); }
    .stage[data-active="true"] { border-color: var(--active); box-shadow: inset 0 0 0 1px var(--active), 0 16px 36px color-mix(in srgb, var(--active) 14%, transparent); }
    .stage-index { display: inline-grid; place-items: center; width: 42px; height: 32px; border-radius: 10px; color: var(--active); background: color-mix(in srgb, var(--active) 11%, var(--surface)); font: 900 13px/1 var(--mono); }
    .stage h3 { margin: 22px 0 6px; font-size: 20px; letter-spacing: -.025em; }
    .stage p { min-height: 54px; margin: 0; color: var(--muted); font-size: 14px; }
    .role { display: inline-flex; margin-top: 22px; border: 1px solid var(--border); border-radius: 999px; padding: 5px 9px; color: var(--muted); font: 700 11px/1.2 var(--mono); }
    .connector { position: relative; min-width: 70px; align-self: center; height: 52px; }
    .connector::before { content: ''; position: absolute; left: 10px; right: 13px; top: 25px; height: 3px; border-radius: 3px; background: var(--line-strong); }
    .connector::after { content: ''; position: absolute; right: 5px; top: 17px; width: 14px; height: 14px; border-top: 4px solid var(--line-strong); border-right: 4px solid var(--line-strong); transform: rotate(45deg); }
    .connector span { position: absolute; left: 50%; top: -8px; width: 120px; transform: translateX(-50%); color: var(--muted); text-align: center; font: 700 10px/1.3 var(--mono); }
    .result-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; padding: 0 30px 30px; }
    .result-card { min-height: 118px; padding: 17px; border: 1px solid var(--border); border-radius: 16px; background: var(--surface-2); }
    .result-card span { display: block; color: var(--muted); font: 800 11px/1.3 var(--mono); text-transform: uppercase; letter-spacing: .07em; }
    .result-card strong { display: block; margin-top: 10px; font-size: 16px; overflow-wrap: anywhere; }
    .result-card.final { border-color: color-mix(in srgb, var(--green) 55%, var(--border)); }
    .timeline { margin: 0 30px 30px; padding: 20px 22px; border: 1px solid var(--border); border-radius: 16px; background: #07101c; color: #f3f7fc; }
    :root[data-theme="light"] .timeline { background: #152338; }
    .timeline-title { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 15px; }
    .timeline-title strong { font-size: 15px; }
    .timeline-title code { color: #8eeaf2; font: 700 12px/1.4 var(--mono); }
    .event-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; }
    .event { min-height: 64px; border-left: 3px solid var(--active); border-radius: 8px; padding: 9px 10px; background: #12233a; font: 700 11px/1.35 var(--mono); overflow-wrap: anywhere; }
    .event::before { content: attr(data-index); display: block; margin-bottom: 5px; color: #91a5bd; }
    .invariants { margin-top: 30px; padding: 30px; border: 1px solid var(--border); border-radius: 22px; background: var(--surface); }
    .invariants h2 { margin: 0 0 20px; font-size: 24px; }
    .invariant-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 11px; }
    .invariant { padding: 17px; border: 1px solid var(--border); border-radius: 15px; background: var(--surface-2); }
    .invariant code { color: var(--cyan); font: 800 10px/1.3 var(--mono); overflow-wrap: anywhere; }
    .invariant strong { display: block; margin: 11px 0 7px; font-size: 15px; }
    .invariant p { margin: 0; color: var(--muted); font-size: 13px; }
    .footer { display: flex; justify-content: space-between; gap: 18px; margin-top: 28px; color: var(--muted); font-size: 13px; }
    .footer a { color: inherit; }
    @media (max-width: 900px) {
      .view-tabs { grid-template-columns: 1fr; }
      .workspace-head { grid-template-columns: 1fr; }
      .result-grid { grid-template-columns: 1fr; }
      .invariant-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .topbar, .footer { align-items: flex-start; flex-direction: column; }
    }
    @media (max-width: 520px) {
      .shell { width: min(100% - 24px, 1380px); padding-top: 16px; }
      .actions { width: 100%; flex-wrap: wrap; }
      .hero { margin-top: 34px; }
      .workspace-head, .scenario-panel, .flow-wrap { padding-left: 18px; padding-right: 18px; }
      .result-grid { padding-left: 18px; padding-right: 18px; }
      .timeline { margin-left: 18px; margin-right: 18px; }
      .invariant-grid { grid-template-columns: 1fr; }
    }
    @media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition: none !important; animation: none !important; } }
    :root[data-capture="true"] body { background-color: var(--page); }
    :root[data-capture="true"] .shell { width: 1380px; padding: 24px 0; }
    :root[data-capture="true"] .topbar, :root[data-capture="true"] .hero, :root[data-capture="true"] .view-tabs, :root[data-capture="true"] .scenario-panel, :root[data-capture="true"] .invariants, :root[data-capture="true"] .footer { display: none; }
    :root[data-capture="true"] .workspace { margin-top: 0; box-shadow: none; }
    :root[data-capture="true"] .workspace-head { padding-top: 32px; }
    :root[data-capture="true"] *, :root[data-capture="true"] *::before, :root[data-capture="true"] *::after { transition: none !important; animation: none !important; }
  </style>
</head>
<body>
  <main class="shell">
    <header class="topbar">
      <div class="brand"><span class="brand-mark" aria-hidden="true">B4K</span><span>bluetape4k workshop</span></div>
      <div class="actions">
        <a class="action-link" href="${copy.alternateHref}">${copy.alternateLabel}</a>
        <a class="action-link" href="${sourceHref}">${copy.sourceLabel}</a>
        <button class="theme-toggle" type="button" aria-label="${copy.themeLabel}">${copy.themeLabel}</button>
      </div>
    </header>

    <section class="hero">
      <p class="kicker">${copy.kicker}</p>
      <h1>${copy.title}</h1>
      <p class="lead">${copy.description}</p>
    </section>

    <nav class="view-tabs" aria-label="${copy.title}">
      ${viewIds.map((viewId, index) => `<button class="view-tab" type="button" data-view-id="${viewId}" aria-pressed="${index === 0}"><small>0${index + 1}</small><strong>${copy.viewNames[viewId]}</strong></button>`).join('\n      ')}
    </nav>

    <section id="simulation" class="workspace" data-view="${initialView}" data-source="${designSource}" data-baseline="${sourceBaseline}">
      <div class="workspace-head">
        <div><h2 id="view-title">${copy.viewNames[initialView]}</h2><p id="view-summary">${copy.viewSummaries[initialView]}</p></div>
        <div class="authority-pill"><span>${copy.authorityLabel}</span><strong id="authority-value">${scenarios[initialView][initialScenario].authority}</strong></div>
      </div>
      <div class="scenario-panel">
        <span class="scenario-label">${copy.scenarioLabel}</span>
        <div id="scenario-tabs" class="scenario-tabs"></div>
      </div>
      <div class="flow-wrap"><div id="workflow-capture" class="flow" data-active-view="${initialView}"></div></div>
      <div class="result-grid">
        <article class="result-card"><span>${copy.initialLabel}</span><strong id="initial-value"></strong></article>
        <article class="result-card final"><span>${copy.finalLabel}</span><strong id="final-value"></strong></article>
        <article class="result-card"><span>${copy.actionLabel}</span><strong id="action-value"></strong></article>
      </div>
      <div class="timeline">
        <div class="timeline-title"><strong id="scenario-title"></strong><code id="scenario-id"></code></div>
        <div id="event-list" class="event-list"></div>
      </div>
    </section>

    <section class="invariants">
      <h2>${copy.invariantsTitle}</h2>
      <div class="invariant-grid">
        ${invariantIds.map((id) => `<article class="invariant"><code>${id}</code><strong>${copy.invariants[id][0]}</strong><p>${copy.invariants[id][1]}</p></article>`).join('\n        ')}
      </div>
    </section>

    <footer class="footer"><span>Source baseline · ${sourceBaseline}</span><a href="${sourceHref}">${designSource}</a></footer>
  </main>
  <script>
    const model = ${json(localizedModel)};
    const themeStorageKey = 'starlight-theme';
    const workspace = document.querySelector('.workspace');
    const viewButtons = [...document.querySelectorAll('[data-view-id]')];
    const scenarioTabs = document.querySelector('#scenario-tabs');
    let activeView = model.viewIds.includes(location.hash.slice(1)) ? location.hash.slice(1) : '${initialView}';
    let activeScenario = model.scenarioIds[activeView][0];

    function humanize(value) {
      return value.replaceAll('-', ' ').replaceAll('_', ' ');
    }

    function renderScenarioButtons() {
      scenarioTabs.innerHTML = model.scenarioIds[activeView].map((id) =>
        '<button type="button" class="scenario-tab" data-scenario-id="' + id + '" aria-pressed="' + (id === activeScenario) + '">' + (model.copy.scenarioNames[id] || id) + '</button>'
      ).join('');
      for (const button of scenarioTabs.querySelectorAll('[data-scenario-id]')) {
        button.addEventListener('click', () => {
          activeScenario = button.dataset.scenarioId;
          render();
        });
      }
    }

    function renderFlow() {
      const view = model.viewModels[activeView];
      const eventCount = model.scenarios[activeView][activeScenario].events.length;
      document.querySelector('#workflow-capture').innerHTML = view.stages.map((stage, index) => {
        const labels = model.copy.stageNames[stage.id];
        const active = index < Math.min(eventCount, view.stages.length);
        const card = '<article class="stage" data-active="' + active + '"><span class="stage-index">' + stage.icon + '</span><h3>' + labels[0] + '</h3><p>' + labels[1] + '</p><span class="role">' + model.copy.roleNames[stage.role] + '</span></article>';
        if (index === view.stages.length - 1) return card;
        return card + '<div class="connector" aria-hidden="true"><span>' + (index + 1) + ' → ' + (index + 2) + '</span></div>';
      }).join('');
    }

    function render() {
      const item = model.scenarios[activeView][activeScenario];
      workspace.dataset.view = activeView;
      workspace.querySelector('#workflow-capture').dataset.activeView = activeView;
      document.querySelector('#view-title').textContent = model.copy.viewNames[activeView];
      document.querySelector('#view-summary').textContent = model.copy.viewSummaries[activeView];
      document.querySelector('#authority-value').textContent = humanize(item.authority);
      document.querySelector('#initial-value').textContent = humanize(item.initial);
      document.querySelector('#final-value').textContent = humanize(item.final);
      document.querySelector('#action-value').textContent = item.allowedActions.map(humanize).join(' · ');
      document.querySelector('#scenario-title').textContent = model.copy.scenarioNames[activeScenario] || activeScenario;
      document.querySelector('#scenario-id').textContent = activeView + ' / ' + activeScenario;
      document.querySelector('#event-list').innerHTML = item.events.map((event, index) => '<div class="event" data-index="0' + (index + 1) + '">' + humanize(event) + '</div>').join('');
      for (const button of viewButtons) button.setAttribute('aria-pressed', String(button.dataset.viewId === activeView));
      renderScenarioButtons();
      renderFlow();
    }

    for (const button of viewButtons) {
      button.addEventListener('click', () => {
        activeView = button.dataset.viewId;
        activeScenario = model.scenarioIds[activeView][0];
        history.pushState(null, '', '#' + activeView);
        render();
      });
    }
    window.addEventListener('hashchange', () => {
      const next = location.hash.slice(1);
      activeView = model.viewIds.includes(next) ? next : '${initialView}';
      activeScenario = model.scenarioIds[activeView][0];
      render();
    });
    document.querySelector('.theme-toggle').addEventListener('click', () => {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      localStorage.setItem(themeStorageKey, next);
    });
    render();
    document.fonts.ready.then(() => { window.__USAGE_BILLING_READY__ = true; });
  </script>
</body>
</html>
`;
}

export function renderDocument(localeId) {
  if (!locales[localeId]) throw new Error(`Unsupported locale: ${localeId}`);
  return html(localeId);
}

export async function writeDocument(localeId, content = renderDocument(localeId)) {
  const output = path.join(repositoryRoot, outputPaths[localeId]);
  await mkdir(path.dirname(output), { recursive: true });
  await writeFile(output, content);
  return output;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  for (const localeId of ['en', 'ko']) await writeDocument(localeId);
  process.stdout.write('Usage billing visual companion generated: locales=2 views=3 scenarios=16\n');
}
