#!/usr/bin/env node

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  designSource,
  locales,
  scenarioIds,
  scenarios,
  viewIds,
} from './visual-companions/usage-billing-evolution-model.mjs';

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const htmlPaths = {
  en: 'docs/visual-companions/en/usage-billing-evolution.html',
  ko: 'docs/visual-companions/ko/usage-billing-evolution.html',
};

export function validateUsageBillingModel(model) {
  const errors = [];
  const seen = new Set();
  for (const viewId of model.viewIds) {
    if (!model.locales.en.viewNames[viewId] || !model.locales.ko.viewNames[viewId]) {
      errors.push(`missing localized view: ${viewId}`);
    }
    for (const scenarioId of model.scenarioIds[viewId] ?? []) {
      const key = `${viewId}/${scenarioId}`;
      if (seen.has(key)) errors.push(`duplicate scenario: ${key}`);
      seen.add(key);
      const item = model.scenarios[viewId]?.[scenarioId];
      if (!item || !Array.isArray(item.events) || item.events.length === 0) {
        errors.push(`missing scenario: ${key}`);
        continue;
      }
      if (!item.initial || !item.final || !item.authority) errors.push(`incomplete outcome: ${key}`);
      if (!Array.isArray(item.allowedActions) || item.allowedActions.length === 0) {
        errors.push(`missing allowed actions: ${key}`);
      }
      for (const localeId of ['en', 'ko']) {
        if (!model.locales[localeId].scenarioNames[scenarioId]) {
          errors.push(`missing localized scenario: ${localeId}/${scenarioId}`);
        }
      }
    }
  }
  return errors;
}

function validateHtml(localeId, html) {
  const errors = [];
  const required = [
    'id="simulation"',
    'id="workflow-capture"',
    'data-view-id="ledger"',
    'data-view-id="event-sourcing"',
    'data-view-id="microservices"',
    "window.addEventListener('hashchange'",
    'document.fonts.ready',
    'window.__USAGE_BILLING_READY__ = true',
  ];
  for (const token of required) {
    if (!html.includes(token)) errors.push(`${localeId} missing HTML contract: ${token}`);
  }
  if (!html.includes(`href="${locales[localeId].alternateHref}"`)) {
    errors.push(`${localeId} missing alternate locale link`);
  }
  if (!html.includes(`href="../../superpowers/specs/${designSource}"`)) {
    errors.push(`${localeId} missing design source link`);
  }
  const forbidden = [
    /\bfetch\s*\(/,
    /\bWebSocket\s*\(/,
    /<form\b/i,
    /<script\b[^>]+src=/i,
    /<link\b[^>]+stylesheet/i,
    /<(?:img|video|audio|iframe)\b[^>]+src=/i,
  ];
  if (forbidden.some((pattern) => pattern.test(html))) errors.push(`${localeId} contains a forbidden external surface`);
  return errors;
}

export async function validateUsageBillingVisualization() {
  const modelErrors = validateUsageBillingModel({ viewIds, scenarioIds, scenarios, locales });
  const htmlErrors = [];
  for (const localeId of ['en', 'ko']) {
    const html = await readFile(path.join(repositoryRoot, htmlPaths[localeId]), 'utf8');
    htmlErrors.push(...validateHtml(localeId, html));
  }
  return [...modelErrors, ...htmlErrors];
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const broken = structuredClone({ viewIds, scenarioIds, scenarios, locales });
  delete broken.scenarios.ledger.NORMAL;
  const fixtureErrors = validateUsageBillingModel(broken);
  if (!fixtureErrors.includes('missing scenario: ledger/NORMAL')) {
    throw new Error('Validator self-test failed to reject a missing ledger/NORMAL scenario');
  }

  const errors = await validateUsageBillingVisualization();
  if (errors.length > 0) {
    process.stderr.write(`${errors.join('\n')}\n`);
    process.exitCode = 1;
  } else {
    const count = Object.values(scenarioIds).reduce((total, ids) => total + ids.length, 0);
    process.stdout.write(`Usage billing visualization validation passed: views=${viewIds.length} scenarios=${count} locales=2\n`);
  }
}

