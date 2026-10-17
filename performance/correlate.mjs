import {readFile, writeFile} from 'node:fs/promises';
import {gunzipSync} from 'node:zlib';
const [reportFile, ...logs] = process.argv.slice(2);
if (!reportFile || !logs.length) throw new Error('Usage: node correlate.mjs report.json gateway.log transaction.log fraud-engine.log');
const report=JSON.parse(await readFile(reportFile,'utf8'));
const records=report.stages.flatMap(s=>s.records.map(r=>({...r, concurrency:s.concurrency})));
const byId=new Map(records.filter(r=>r.transactionId).map(r=>[r.transactionId,r]));
const byRequest=new Map(records.map(r=>[r.requestId||r.externalTransactionId,r]));
const coverage={};
const fields=['duplicateCheckMs','publishMs','ingressToWorkerMs','fraudCallMs','resultUpdateMs','serverCompletionMs','executorWaitMs','rulesMs','locationMs','scoringMs','decisionMs'];
for (const file of logs) {
  let matched=0;
  const content=await readFile(file);
  for(const line of (file.endsWith('.gz') ? gunzipSync(content).toString('utf8') : content.toString('utf8')).split(/\r?\n/)) {
    const id=line.match(/transactionId=([\w-]+)/)?.[1];
    const request=line.match(/requestId=([\w-]+)/)?.[1];
    const r=byId.get(id)||byRequest.get(request);
    if(!r) continue;
    matched++;
    r.timings ||= {};
    for(const field of fields) {
      const match=line.match(new RegExp(`\\b${field}=(-?\\d+(?:\\.\\d+)?)`));
      if(match) r.timings[field]=Number(match[1]);
    }
    if(line.includes('Completed POST /api/v1/transactions')) {
      const duration=line.match(/elapsedMs=(\d+)/);
      if(duration) r.timings.gatewayMs=Number(duration[1]);
    }
    const completed=line.match(/completedEpochMs=(\d+)/);
    if(completed && r.clientStartedEpochMs) {
      r.timings.clientToServerCompletionMs=Number(completed[1])-r.clientStartedEpochMs;
    }
  }
  coverage[file]=matched;
}
function stats(values) {
  values.sort((a,b)=>a-b);
  const p=q=>values[Math.max(0,Math.ceil(values.length*q)-1)]??null;
  return {samples:values.length,p50:p(.5),p95:p(.95),p99:p(.99),max:p(1)};
}
const stages=report.stages.map(s=>({concurrency:s.concurrency, timings:Object.fromEntries(
  [...fields,'gatewayMs','clientToServerCompletionMs'].map(f=>[f,stats(records.filter(r=>r.concurrency===s.concurrency && Number.isFinite(r.timings?.[f])).map(r=>r.timings[f]))]))}));
const result={runId:report.runId,coverage,stages,records,
  limitations:['Missing timing samples are not zero latency.',
    'Server completion ends after the transaction status update, not all downstream persistence.',
    'Server elapsed time uses epoch milliseconds across producer/consumer; synchronized clocks are required across hosts.',
    'Client-to-server completion requires synchronized load-generator/server clocks and excludes response delivery.',
    'Gateway time excludes time before gateway receipt; server time starts in the servlet filter, after servlet scheduling.',
    'Redis and Kafka timing details for old console-only runs cannot be reconstructed from absent logs.']};
await writeFile(reportFile+'.correlated.json',JSON.stringify(result,null,2));
console.log(JSON.stringify({runId:result.runId,coverage,stages},null,2));
