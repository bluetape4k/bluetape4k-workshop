#!/usr/bin/env node

import { execFile, spawn } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { promisify } from 'node:util';

const execute = promisify(execFile);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const chrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const locales = ['en', 'ko'];
const themes = ['light', 'dark'];
const views = ['ledger', 'event-sourcing', 'microservices'];
const width = 1440;
const height = 900;

function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

function dimensions(buffer) {
  if (buffer.length < 24 || buffer.toString('hex', 0, 8) !== '89504e470d0a1a0a') {
    throw new Error('Capture is not a PNG');
  }
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

async function capture({ locale, theme, view, output, profile }) {
  const html = path.join(repositoryRoot, `docs/visual-companions/${locale}/usage-billing-evolution.html`);
  const url = `${pathToFileURL(html).href}?capture=1&theme=${theme}#${view}`;
  const args = [
    '--headless=new',
    '--hide-scrollbars',
    '--force-device-scale-factor=1',
    `--window-size=${width},${height}`,
    '--disable-background-networking',
    '--disable-default-apps',
    '--disable-extensions',
    '--disable-sync',
    '--metrics-recording-only',
    '--no-first-run',
    '--disable-gpu',
    '--disable-component-update',
    '--no-default-browser-check',
    '--password-store=basic',
    '--use-mock-keychain',
    '--virtual-time-budget=1000',
    `--user-data-dir=${profile}`,
    `--screenshot=${output}`,
    url,
  ];
  const child = spawn(chrome, args, { stdio: ['ignore', 'ignore', 'pipe'] });
  const exitPromise = new Promise((resolve, reject) => {
    child.once('exit', resolve);
    child.once('error', reject);
  });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  let previousSize = -1;
  let stablePolls = 0;
  for (let attempt = 0; attempt < 150; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 100));
    const size = await stat(output).then((value) => value.size).catch(() => -1);
    if (size > 0 && size === previousSize) stablePolls += 1;
    else stablePolls = 0;
    previousSize = size;
    if (stablePolls >= 2) break;
  }
  if (stablePolls < 2) {
    child.kill('SIGTERM');
    throw new Error(`capture timeout: locale=${locale} theme=${theme} view=${view} ${stderr.trim()}`);
  }
  child.kill('SIGTERM');
  await exitPromise;
}

async function captureSet(root, pass) {
  const directory = path.join(root, pass);
  const profileRoot = path.join(root, `${pass}-profiles`);
  await mkdir(directory, { recursive: true });
  await mkdir(profileRoot, { recursive: true });
  const results = new Map();
  for (const view of views) {
    for (const locale of locales) {
      for (const theme of themes) {
        const name = `usage-billing-evolution-${view}.${locale}.${theme}.png`;
        const output = path.join(directory, name);
        const profile = await mkdtemp(path.join(profileRoot, `${view}-${locale}-${theme}-`));
        await capture({ locale, theme, view, output, profile });
        const buffer = await readFile(output);
        const size = dimensions(buffer);
        if (size.width !== width || size.height !== height) {
          throw new Error(`capture dimensions: ${name} ${size.width}x${size.height}`);
        }
        results.set(name, { output, digest: sha256(buffer) });
      }
    }
  }
  return results;
}

async function main() {
  const version = (await execute(chrome, ['--version'])).stdout.trim().replace(/^Google Chrome\s+/, '');
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), 'usage-billing-captures-'));
  try {
    const first = await captureSet(temporaryRoot, 'first');
    const second = await captureSet(temporaryRoot, 'second');
    const destination = path.join(repositoryRoot, 'docs/images/visual-companions');
    await mkdir(destination, { recursive: true });
    let deterministic = 0;
    for (const [name, firstResult] of first) {
      const secondResult = second.get(name);
      if (!secondResult || firstResult.digest !== secondResult.digest) {
        const [viewAndPrefix, locale, theme] = name.replace('.png', '').split('.');
        const view = viewAndPrefix.replace('usage-billing-evolution-', '');
        throw new Error(`capture drift: locale=${locale} theme=${theme} view=${view} first=${firstResult.digest} second=${secondResult?.digest ?? 'missing'}`);
      }
      await copyFile(firstResult.output, path.join(destination, name));
      deterministic += 1;
    }
    process.stdout.write(`Usage billing captures passed: chrome=${version} assets=${first.size} dimensions=${width}x${height} deterministic=${deterministic}/${first.size}\n`);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
}

await main();
