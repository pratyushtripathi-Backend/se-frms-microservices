import { appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Run on the service host. Management endpoints deliberately bind loopback.
const services = { gateway:18085, transaction:18099, fraud:18092, scoring:18094, decision:18095 };
const wanted = /^(process\.cpu\.usage|system\.cpu\.usage|jvm\.gc\.pause|jvm\.memory\.used|jvm\.threads\.live|hikaricp\.connections\.(active|pending|idle|acquire|timeout)|executor\.(active|queued|pool\.size)|tomcat\.threads\.(busy|current))$/;
const seconds = Number(process.env.FRMS_MONITOR_SECONDS || 120);
if (!Number.isFinite(seconds) || seconds < 1 || seconds > 3600) throw new Error('FRMS_MONITOR_SECONDS must be 1..3600');
const output = path.join(path.dirname(fileURLToPath(import.meta.url)), 'results', `metrics-${Date.now()}.jsonl`);
await mkdir(path.dirname(output), {recursive:true});
async function get(url) {
  const response = await fetch(url, {signal:AbortSignal.timeout(1500)});
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
const names = new Map();
console.log(`Recording ${seconds}s to ${output}. Start the load runner in another terminal.`);
const until = Date.now() + seconds*1000;
while (Date.now() < until) {
  const tick = Date.now();
  await Promise.all(Object.entries(services).map(async ([service, port]) => {
    const sample = {at:new Date().toISOString(), service, metrics:{}};
    const base = `http://127.0.0.1:${port}/actuator/metrics`;
    try {
      if (!names.has(service)) names.set(service, (await get(base)).names.filter(n=>wanted.test(n)));
      for (const name of names.get(service)) {
        try { sample.metrics[name] = await get(`${base}/${name}`); }
        catch (error) { sample.metrics[name] = {error:error.message}; }
      }
    } catch (error) { sample.error = error.message; }
    await appendFile(output, JSON.stringify(sample)+'\n');
  }));
  await new Promise(resolve=>setTimeout(resolve, Math.max(0,1000-(Date.now()-tick))));
}
console.log(`Saved ${output}`);
