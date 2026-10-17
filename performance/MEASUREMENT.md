# Measuring the 200 ms target

## Baseline recovered

Run `load-2026-10-15T12-16-40-362Z-472580fe` was matched to 19,010 lines in the compressed fraud-engine log for 2026-10-15. All 1,901 transactions had fraud timing evidence. At concurrency 1,000:

| Component | p50 ms | p95 ms | p99 ms |
| --- | --- | --- | --- |
| Rule lookup | 0 | 0 | 1 |
| Location | 0 | 0 | 0 |
| Scoring HTTP call | 14 | 148 | 187 |
| Decision HTTP call | 9 | 112 | 200 |

Do not add separate percentiles. Calls include downstream HTTP/pool/service time, not just computation. The old run lacks a request-ID mapping to gateway logs and retained transaction-service timings, so Redis/publish/ingress queue attribution is unavailable. Correlation output is saved beside the original report as `report.json.correlated.json`.

Kafka was verified as one partition with zero lag (offset 1902). With explicit user approval it was increased to 12 partitions on 2026-10-15. All 12 partitions have leader/ISR broker 1. This is irreversible in place and changes key-to-partition mapping. Consumers must refresh metadata/rebalance (or restart) before all partitions have assignments.

## Instrumentation

- Load runner supplies its unique external transaction ID as `X-Request-Id` and records the returned header and client submission epoch timestamp.
- Transaction filter records server ingress epoch milliseconds in MDC. Publisher carries it in an optional Kafka header; the event JSON/API schema is unchanged.
- Consumer supports both old messages and messages with timing headers. Missing/malformed timing headers do not reject transactions.
- `transaction_timing` logs end after the transaction status update commits. Fields separate ingress-to-worker delay, fraud HTTP call, result update, and total server completion.
- The performance profile enables per-record executor wait logs. Ingress-to-worker includes receipt handling, Kafka wait, batch database work, and executor queueing; executor wait is also recorded separately.
- Rolling transaction logs retain existing duplicate-check and publish durations.

These measurements are precise to the recorded clock resolution, not a universal client SLA measurement. Server completion starts at servlet-filter entry (after servlet scheduling), and stops after the transaction row update; it excludes asynchronous scoring/decision persistence, audit, and delivery. Producer/consumer wall clocks must be synchronized across hosts. Client-to-server completion additionally requires synchronized generator/server clocks. The client still receives an asynchronous acceptance response, not a final decision.

## Activate and repeat

Rebuild and restart gateway, transaction, fraud, scoring, and decision with `--spring.profiles.active=performance` (or add `performance` to existing profiles). In an IDE, use that program argument. Normal transaction endpoints and business settings stay the same. Diagnostic management endpoints move to loopback-only ports:

| Service | Diagnostic port |
| --- | --- |
| Gateway | 18085 |
| Transaction | 18099 |
| Fraud | 18092 |
| Scoring | 18094 |
| Decision | 18095 |

In one terminal on the service host:

```powershell
cd D:\Backend-Frms\se-frms-microservices\performance
node monitor.mjs
```

The collector samples available process/system CPU, GC pause counters, heap, thread, Hikari connection wait/pending, executor queue, and Tomcat worker metrics for 120 seconds. Missing endpoints/metrics are reported, never treated as zero. Hikari metrics expose application connection acquisition waits, not PostgreSQL lock/wait-event details. Aggregate metrics can contain multiple named pools; inspect the returned tags before attributing them to one executor. Sampling adds overhead and must be consistent across comparison runs.

In another terminal run the existing load runner after confirming test-only notifications. A separate generator machine may use `FRMS_BASE_URL=http://<service-host>:8085`; first verify existing network access. No firewall is opened by this change.

After completion:

```powershell
node correlate.mjs results/<run-id>/report.json ../api-gateway/logs/api-gateway.log ../transaction-service/logs/transaction-service.log ../fraud-engine-service/logs/fraud-engine-service.log
```

Supply actual log paths for the service working directories; rotated `.gz` files are supported. Compare acceptance and server completion distributions separately, check sample coverage, and inspect metrics around the burst. Do not label polling upper bounds as measured decision latency. No repeat load is automatically triggered by these tools.
