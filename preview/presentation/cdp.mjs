// node cdp.mjs  -> headless Edge over DevTools protocol; screenshots to shots/, prints checks
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const file = pathToFileURL(path.join(here, 'sahirep-presentation.html')).href;
const shots = path.join(here, 'shots'); fs.mkdirSync(shots, { recursive: true });
const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const port = 9333;
const proc = spawn(edge, ['--headless=new', `--remote-debugging-port=${port}`, '--disable-gpu', '--no-first-run',
  `--user-data-dir=${path.join(process.env.TEMP, 'sr-edge-prof')}`, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let targets;
for (let i = 0; i < 50; i++) { try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); if (targets.find(t => t.type === 'page')) break; } catch {} await sleep(200); }
const ws = new WebSocket(targets.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0; const pend = new Map(); const errors = [];
ws.onmessage = (m) => { const d = JSON.parse(m.data);
  if (d.id && pend.has(d.id)) { pend.get(d.id)(d); pend.delete(d.id); }
  else if (d.method === 'Runtime.exceptionThrown') errors.push('EXC ' + d.params.exceptionDetails.text + ' ' + (d.params.exceptionDetails.exception?.description || ''));
  else if (d.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(d.params.type)) errors.push('CON ' + d.params.args.map(a => a.value).join(' '));
  else if (d.method === 'Log.entryAdded' && d.params.entry.level === 'error') errors.push('LOG ' + d.params.entry.text + ' ' + d.params.entry.url);
};
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pend.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const ev = async (expr) => (await send('Runtime.evaluate', { expression: expr, returnByValue: true })).result.result.value;
await send('Runtime.enable'); await send('Log.enable'); await send('Page.enable');
const key = async (k, code) => { for (const t of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', { type: t, key: k, code: code || k, windowsVirtualKeyCode: ({ ArrowRight: 39, ArrowLeft: 37, Home: 36, End: 35, ArrowDown: 40, ' ': 32, o: 79 })[k] || 0 }); };
const views = [['1920x1080', 1920, 1080, false], ['1280x720', 1280, 720, false], ['phone', 390, 844, true]];
const only = process.argv[2];
for (const [name, w, h, mobile] of views) {
  if (only && only !== name) continue;
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile });
  await send('Page.navigate', { url: file + '#1' }); await sleep(1500);
  const N = await ev('document.querySelectorAll(".slide").length');
  console.log(`== ${name}: ${N} slides`);
  if (name === '1920x1080') { // keyboard test
    await key('ArrowRight'); await sleep(300); const a = await ev('location.hash');
    await key(' '); await sleep(300); const b = await ev('location.hash');
    await key('ArrowLeft'); await sleep(300); const c = await ev('location.hash');
    await key('End'); await sleep(300); const d = await ev('location.hash');
    await key('Home'); await sleep(300); const e = await ev('location.hash');
    await key('o'); await sleep(500); const ovOn = await ev('document.getElementById("ov").classList.contains("on")');
    await send('Page.captureScreenshot').then(r => fs.writeFileSync(path.join(shots, 'overview.png'), Buffer.from(r.result.data, 'base64')));
    await key('o'); await sleep(300);
    console.log('keys: right->', a, 'space->', b, 'left->', c, 'End->', d, 'Home->', e, 'overview on:', ovOn);
  }
  for (let i = 1; i <= N; i++) {
    await ev(`location.hash='#${i}';location.reload()`); await sleep(i === 4 ? 2800 : 1300);
    if (i === 5) { await key('ArrowDown'); await sleep(500); }
    if (name === '1920x1080') {
      const over = await ev(`(function(){var s=document.querySelector('.slide.active'),o=[];s.querySelectorAll('*').forEach(function(e){if(e.closest('svg')&&e.tagName!=='svg')return;var r=e.getBoundingClientRect();if(r.width&&(r.right>1915||r.bottom>1075||r.left<0))o.push(e.tagName+'.'+e.className+' r='+Math.round(r.right)+' b='+Math.round(r.bottom))});return o.slice(0,5).join(' | ')})()`);
      if (over) console.log(' slide', i, 'overflow:', over);
    }
    const r = await send('Page.captureScreenshot');
    fs.writeFileSync(path.join(shots, `${name}_${String(i).padStart(2, '0')}.png`), Buffer.from(r.result.data, 'base64'));
  }
  if (name === '1920x1080') {
    await ev(`location.hash='#7';location.reload()`); await sleep(1500);
    console.log('demo iframe:', await ev(`(function(){var f=document.getElementById('demoframe');var d=f.contentDocument;return d? (d.title+' | body text len '+d.body.innerText.length+' | buttons '+d.querySelectorAll('button').length):'no doc'})()`));
  }
}
if (!only) { await send('Emulation.clearDeviceMetricsOverride'); await send('Page.navigate', { url: file }); await sleep(1500);
  const p = await send('Page.printToPDF', { preferCSSPageSize: true, printBackground: true }); const b = Buffer.from(p.result.data, 'base64');
  fs.writeFileSync(path.join(shots, 'print.pdf'), b);
  console.log('print pdf pages:', (b.toString('latin1').match(/\/Type\s*\/Page[^s]/g) || []).length); }
console.log('errors:', errors.length ? errors : 'none');
ws.close(); proc.kill();
