import http from 'node:http';
import https from 'node:https';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

// Standalone test tooling: does not edit or start any application service.
const dryRun = process.argv.includes('--dry-run');
const base = (process.env.FRMS_BASE_URL || '').replace(/\/$/, '');
const token = process.env.FRMS_TEST_TOKEN;
const runId = `load-${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`;
const stages = [1, 50, 100, 250, 500, 1000];
const timeoutMs = 15000;
const completionDeadlineMs = 120000;
const httpAgent = new http.Agent({ keepAlive: true, maxSockets: 1000 });
const httpsAgent = new https.Agent({ keepAlive: true, maxSockets: 1000 });
const report = { runId, stages: [], plan: stages, plannedTransactions: stages.reduce((a,b) => a+b,0),
  limitations: ['Burst concurrency test, not sustained TPS.',
    'POST latency measures acceptance, not final decision.',
    'Completion is polled after each burst; observation times are upper bounds, not precise decision latencies.',
    'Final transaction status alone does not verify scoring, decision, audit, or notification durability.',
    'Synthetic payload omits coordinates; external geocoding is not covered.'] };

function payload(i) {
  return { externalTransactionId: `${runId}-${i}`, ipAddress: '192.0.2.1',
    merchantId: 'LOAD_TEST_MERCHANT', userId: `LOAD_TEST_USER_${i % 100}`,
    channel: 'API', transactionType: 'TRANSFER', currency: 'INR', amount: 10,
    transactionData: { loadTestRunId: runId, deviceId: `LOAD_TEST_DEVICE_${i % 100}` },
    remarks: `Synthetic load test ${runId}`, createdBy: 'LOAD_TEST' };
}

function request(method, suffix, body) {
  return new Promise(resolve => {
    const started = performance.now();
    const url = new URL(`${base}/api/v1/transactions${suffix}`);
    const bytes = body ? JSON.stringify(body) : undefined;
    const headers = { Accept: 'application/json' };
    if (method === 'POST') headers['X-Request-Id'] = body.externalTransactionId;
    if (token) headers.Authorization = `Bearer ${token}`;
    if (bytes) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = Buffer.byteLength(bytes); }
    let done = false;
    let deadline;
    const finish = value => { if (!done) { done = true; clearTimeout(deadline); resolve({ ...value, ms: performance.now() - started }); } };
    const transport = url.protocol === 'https:' ? https : http;
    const req = transport.request(url, { method, headers, agent: url.protocol === 'https:' ? httpsAgent : httpAgent }, res => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { text += chunk; if (text.length > 1048576) req.destroy(new Error('response_too_large')); });
      res.on('end', () => { let data; try { data = JSON.parse(text); } catch {} finish({ status: res.statusCode, data, requestId: res.headers['x-request-id'] }); });
      res.on('error', () => finish({ status: 0, error: 'response_error' }));
    });
    req.on('error', err => finish({ status: 0, error: err.code || err.message }));
    deadline = setTimeout(() => req.destroy(new Error('request_deadline')), timeoutMs);
    req.end(bytes);
  });
}

function percentiles(values) {
  const sorted = [...values].sort((a,b) => a-b);
  const p = fraction => sorted[Math.max(0, Math.ceil(sorted.length * fraction)-1)] ?? null;
  return { count: values.length, p50: p(.5), p95: p(.95), p99: p(.99), max: p(1) };
}
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function pooled(items, worker) {
  let next = 0;
  await Promise.all(Array.from({length: Math.min(20, items.length)}, async () => {
    while (next < items.length) await worker(items[next++]);
  }));
}

async function run() {
  if (dryRun) { console.log(JSON.stringify({ ...report, samplePayload: payload(0), networkRequests: 0 }, null, 2)); return; }
  if (!base || process.env.FRMS_TEST_ENV_CONFIRMED !== 'yes') throw new Error('Set FRMS_BASE_URL and FRMS_TEST_ENV_CONFIRMED=yes after confirming non-production and isolated notifications.');
  const target = new URL(base);
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.search || target.hash) throw new Error('Use an HTTP(S) base URL without credentials, query, or fragment.');
  report.target = target.origin + target.pathname;
  report.startedAt = new Date().toISOString();
  const output = path.join(path.dirname(fileURLToPath(import.meta.url)), 'results', runId);
  await mkdir(output, { recursive: true });
  try {
    const health = await request('GET', '/health');
    report.health = { status: health.status, ms: health.ms };
    if (health.status !== 200) throw new Error(`Transaction health failed: HTTP ${health.status}. No transactions submitted.`);
    let index = 0;
    for (const concurrency of stages) {
      console.log(`Starting burst: ${concurrency} requests`);
      const records = await Promise.all(Array.from({ length: concurrency }, async () => {
        const data = payload(index++);
        const start = performance.now();
        const clientStartedEpochMs = Date.now();
        const r = await request('POST', '', data);
        return { externalTransactionId: data.externalTransactionId, transactionId: r.data?.transactionId,
          requestId: r.requestId, clientStartedEpochMs,
          httpStatus: r.status, responseStatus: r.data?.status, acceptanceMs: r.ms, error: r.error, start };
      }));
      const accepted = records.filter(r => r.httpStatus >= 200 && r.httpStatus < 300 && r.transactionId);
      const successfulDecisions = new Set(['ALLOW', 'REVIEW', 'BLOCK']);
      const pending = new Map(accepted.map(r => [r.transactionId, r]));
      const deadline = performance.now() + completionDeadlineMs;
      while (pending.size && performance.now() < deadline) {
        await pooled([...pending.values()], async r => {
          const result = await request('GET', `/${encodeURIComponent(r.transactionId)}`);
          r.lastPollHttpStatus = result.status;
          const status = result.data?.status;
          if (result.status === 200 && (successfulDecisions.has(status) || ['FRAUD_ENGINE_FAILED', 'DUPLICATE_FRAUD'].includes(status))) {
            r.finalStatus = status;
            r.completionObservedUpperBoundMs = performance.now() - r.start;
            pending.delete(r.transactionId);
          }
        });
        if (pending.size) await sleep(500);
      }
      const finalCount = accepted.filter(r => successfulDecisions.has(r.finalStatus)).length;
      const statusCounts = {};
      for (const r of records) statusCounts[r.httpStatus] = (statusCounts[r.httpStatus] || 0)+1;
      const stage = { concurrency, attempted: records.length, accepted: accepted.length, finalDecisions: finalCount,
        unresolved: pending.size, httpStatusCounts: statusCounts,
        acceptanceMsAllRequests: percentiles(records.map(r => r.acceptanceMs)),
        acceptanceMsAcceptedRequests: percentiles(accepted.map(r => r.acceptanceMs)),
        acceptedUnder200ms: accepted.filter(r => r.acceptanceMs < 200).length,
        completionObservedUpperBoundMs: percentiles(accepted.filter(r => r.finalStatus).map(r => r.completionObservedUpperBoundMs)),
        records: records.map(({ start, ...r }) => r) };
      report.stages.push(stage);
      console.log(JSON.stringify({ ...stage, records: undefined }, null, 2));
      await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
      // Do not increase load after any rejected, failed, or unresolved transaction.
      if (accepted.length !== concurrency || finalCount !== concurrency) throw new Error('Stopped before next stage: rejected, failed, or unresolved transactions. Inspect report; no automatic POST retries.');
      await sleep(3000);
    }
    report.outcome = 'All burst stages completed; inspect latency metrics and durability separately.';
  } catch (err) { report.outcome = err.message; process.exitCode = 1; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
    console.log(`Report: ${path.join(output, 'report.json')}`);
  }
}
try { await run(); } catch (err) { console.error(err.message); process.exitCode = 1; }
finally { httpAgent.destroy(); httpsAgent.destroy(); }
