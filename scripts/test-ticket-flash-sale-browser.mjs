#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { spawn } from "node:child_process";

const urlFlag = process.argv.indexOf("--url");
const targetUrl = urlFlag >= 0 ? process.argv[urlFlag + 1] : "http://127.0.0.1:8080";
const candidates = [
  process.env.GOOGLE_CHROME_BIN,
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/google-chrome-stable",
].filter(Boolean);
const chrome = candidates.find(candidate => fs.existsSync(candidate));
assert.ok(chrome, "Chrome not found; set GOOGLE_CHROME_BIN");

const profile = fs.mkdtempSync(path.join(os.tmpdir(), "ticket-browser-"));
const child = spawn(chrome, [
  "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${profile}`,
  "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "about:blank",
], { stdio: "ignore" });

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
async function activePort() {
  const file = path.join(profile, "DevToolsActivePort");
  for (let attempt = 0; attempt < 100; attempt++) {
    if (fs.existsSync(file)) return fs.readFileSync(file, "utf8").trim().split("\n")[0];
    await delay(50);
  }
  throw new Error("Chrome DevTools port was not created");
}

let socket;
try {
  const port = await activePort();
  const created = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(targetUrl)}`, { method: "PUT" }).then(response => response.json());
  socket = new WebSocket(created.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  let id = 0;
  const pending = new Map();
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (!message.id) return;
    const waiter = pending.get(message.id);
    if (!waiter) return;
    pending.delete(message.id);
    message.error ? waiter.reject(new Error(message.error.message)) : waiter.resolve(message.result);
  });
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const requestId = ++id;
    pending.set(requestId, { resolve, reject });
    socket.send(JSON.stringify({ id: requestId, method, params }));
  });
  const evaluate = async expression => (await cdp("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true })).result.value;
  const waitFor = async selector => {
    for (let attempt = 0; attempt < 100; attempt++) {
      if (await evaluate(`Boolean(document.querySelector(${JSON.stringify(selector)}))`)) return;
      await delay(50);
    }
    throw new Error(`selector not found: ${selector}`);
  };

  await cdp("Runtime.enable");
  await cdp("Emulation.setDeviceMetricsOverride", { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
  await waitFor("#recovery-form");
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Tab", code: "Tab", windowsVirtualKeyCode: 9 });
  const focus = await evaluate(`({ tag: document.activeElement.tagName, outline: getComputedStyle(document.activeElement).outlineStyle })`);
  assert.notEqual(focus.tag, "BODY");
  assert.notEqual(focus.outline, "none");
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= document.documentElement.clientWidth`), true);
  assert.equal(await evaluate(`[...document.querySelectorAll("[data-status]")].every(el => el.textContent.trim().length > 0 && el.querySelector("[aria-label]"))`), true);
  await evaluate(`window.ticketDemo.disconnectSseForTest()`);
  await waitFor("[data-polling-fallback]:not([hidden])");
  assert.equal(await evaluate(`document.body.dataset.transport`), "polling");
  console.log("Ticket flash-sale browser smoke PASS: 360px layout, focus, non-color status, polling fallback.");
} finally {
  if (socket) socket.close();
  child.kill("SIGTERM");
  fs.rmSync(profile, { recursive: true, force: true });
}
