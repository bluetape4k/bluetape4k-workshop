#!/usr/bin/env node

import { readFile, realpath } from 'node:fs/promises';
import path from 'node:path';

const root = await realpath(process.cwd());
const manifestPath = path.join(root, 'docs/visual-companions/manifest.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
const errors = [];
const forbidden = [
  /<script\b[^>]*\bsrc\s*=/i,
  /<link\b[^>]*\brel\s*=\s*["']?stylesheet\b/i,
  /<(?:img|iframe|audio|video|source)\b[^>]*\bsrc\s*=\s*["']?\s*(?:https?:)?\/\//i,
  /<form\b/i,
  /\bfetch\s*\(/,
  /\bXMLHttpRequest\b/,
  /\bWebSocket\s*\(/,
  /\bnavigator\.sendBeacon\s*\(/,
];

function contained(relative) {
  const absolute = path.resolve(root, relative);
  if (!absolute.startsWith(`${root}${path.sep}`)) throw new Error(`Path escapes repository: ${relative}`);
  return absolute;
}

function requireMatch(value, pattern, message) {
  if (!pattern.test(value)) errors.push(message);
}

if (manifest.schemaVersion !== 1) errors.push('manifest.schemaVersion must be 1');
if (manifest.repository !== 'bluetape4k/bluetape4k-workshop') {
  errors.push('manifest.repository must be bluetape4k/bluetape4k-workshop');
}
if (!Array.isArray(manifest.documents) || manifest.documents.length === 0) {
  errors.push('manifest.documents must not be empty');
}

const ids = new Set();
let localeFileCount = 0;
for (const [index, document] of (manifest.documents ?? []).entries()) {
  const field = `documents[${index}]`;
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(document.id)) errors.push(`${field}.id is invalid`);
  if (ids.has(document.id)) errors.push(`${field}.id is duplicated`);
  ids.add(document.id);
  if (document.status !== 'approved' || document.public !== true) {
    errors.push(`${field} must be approved and public`);
  }
  if (
    document.presentation?.mode !== 'simulation'
    || document.presentation?.defaultView !== 'simulation'
    || document.presentation?.views?.length !== 1
    || document.presentation.views[0] !== 'simulation'
  ) {
    errors.push(`${field}.presentation must expose the simulation view`);
  }

  await readFile(contained(document.source), 'utf8').catch(() => {
    errors.push(`${field}.source does not exist`);
  });
  for (const locale of ['en', 'ko']) {
    const localeEntry = document.locales?.[locale];
    if (!localeEntry?.title || !localeEntry?.html) {
      errors.push(`${field}.locales.${locale} is required`);
      continue;
    }
    const html = await readFile(contained(localeEntry.html), 'utf8').catch(() => null);
    if (html === null) {
      errors.push(`${field}.locales.${locale}.html does not exist`);
      continue;
    }
    localeFileCount += 1;
    const prefix = `${document.id}.${locale}`;
    const firstStyle = html.search(/<style\b/i);
    const themeRead = html.indexOf('localStorage.getItem(storageKey)');
    requireMatch(html, /^\s*<!doctype html>/i, `${prefix} must start with doctype`);
    requireMatch(html, new RegExp(`<html\\b[^>]*lang=["']${locale}["']`, 'i'), `${prefix} must set lang=${locale}`);
    requireMatch(
      html,
      /<meta\b[^>]*name=["']color-scheme["'][^>]*content=["']light dark["']/i,
      `${prefix} must support light dark color schemes`,
    );
    if (themeRead < 0 || firstStyle < 0 || themeRead > firstStyle) {
      errors.push(`${prefix} must resolve starlight-theme before CSS`);
    }
    requireMatch(html, /:root\[data-theme=["']light["']\]/i, `${prefix} must define light theme tokens`);
    requireMatch(html, /:root\[data-theme=["']dark["']\]/i, `${prefix} must define dark theme tokens`);
    requireMatch(
      html,
      /<button\b[^>]*class=["'][^"']*theme-toggle[^"']*["'][^>]*aria-label=["'][^"']+["']/i,
      `${prefix} must expose an accessible theme toggle`,
    );
    requireMatch(html, /localStorage\.setItem\(themeStorageKey,/i, `${prefix} must persist theme selection`);
    requireMatch(html, /<section\b[^>]*id=["']simulation["']/i, `${prefix} must expose #simulation`);
    requireMatch(
      html,
      new RegExp(`data-source=["']${path.posix.basename(document.source)}["']`, 'i'),
      `${prefix} must identify its design source`,
    );
    requireMatch(html, /data-baseline=["'][0-9a-f]{40}["']/i, `${prefix} must identify its source baseline`);
    requireMatch(
      html,
      new RegExp(`href=["'][^"']*${path.posix.basename(document.source)}["']`, 'i'),
      `${prefix} must link to its design source`,
    );
    if (forbidden.some((pattern) => pattern.test(html))) errors.push(`${prefix} contains a forbidden surface`);
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exitCode = 1;
} else {
  console.log(`Visual companion validation passed: ${manifest.documents.length} documents / ${localeFileCount} locale files`);
}
