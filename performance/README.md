# 1,000-concurrent-request baseline

This is bounded tuning for testing, not a verified capacity or 200 ms SLA claim.

Target: 1,000 concurrent transactions, each reaching its final fraud decision in
under 200 ms. The current POST returns an asynchronous acknowledgement; its
latency cannot establish this target. Exact end-to-end instrumentation is needed
before claiming the decision SLA. Treat the present runner as an acceptance and
completion/reconciliation baseline, not an SLA certification tool.

## Changes

| Component | Setting | Purpose |
| --- | --- | --- |
| Gateway | HTTP pool 500 -> 1,000 | Allow the burst to reach transaction ingress without a 500-connection pool limit. Existing rate limits and timeouts remain in effect. |
| Transaction | Tomcat max workers 256, minimum spare 32, accept backlog 1,000 | Serve a burst with bounded threads and connection queuing. A queued request still contributes to latency. |
| Transaction | PostgreSQL `reWriteBatchedInserts=true` | Enable driver rewriting of compatible existing Hibernate INSERT batches. |
| Fraud engine | HTTP pool 256 total / 160 per route | Cover the 128 evaluation workers plus up to 12 consumer caller-runs tasks, with headroom for shared-client calls. |

The transaction-service's pre-existing per-route limit of 200 is preserved. Database connection budgets, Kafka partition/consumer counts, evaluation worker count, API responses, fraud rules, duplicate handling, timeouts, logging, notification delivery, and persistence/retry behavior are unchanged. No schema migration is required. JDBC rewriting should be verified with a representative PostgreSQL integration run; it does not remove index or storage costs.

The settings are starting values, not evidence that 1,000 requests complete in parallel. Serving a burst requires capacity throughout the chain, including Kafka, Redis, PostgreSQL and downstream services.

## Build and activation

Run `mvn -B -DskipTests package` in api-gateway, transaction-service, and fraud-engine-service, then restart those services using the rebuilt artifacts or your normal IDE launch configuration. No running processes are restarted by these changes.

Optional environment overrides: `GATEWAY_HTTP_MAX_CONNECTIONS`, `TRANSACTION_HTTP_THREADS`, `TRANSACTION_HTTP_MIN_SPARE`, `TRANSACTION_HTTP_ACCEPT_COUNT`, `FRAUD_HTTP_MAX_CONNECTIONS`, and `FRAUD_HTTP_MAX_CONNECTIONS_PER_ROUTE`. Keep minimum-spare <= maximum threads and per-route <= total HTTP connections. To revert, revert this tuning patch and rebuild; do not discard unrelated local changes.

Do not increase database pools just because the client concurrency is 1,000. Check aggregate PostgreSQL connections across all services and instances. The previously inspected local machine had approximately 692 MiB free RAM and incomplete services; that environment is not a reliable capacity baseline until resources and dependencies are ready.

## Execution

The standalone Node runner in this directory uses the existing transaction API. It does not start services, alter application configuration, disable notifications, or delete records.

1. Confirm a non-production environment with isolated email/SMS recipients/providers. Test markers do not suppress delivery.
2. Start PostgreSQL, Redis, Kafka, service registry, and the existing services through the normal deployment workflow. Confirm `http://localhost:8085/api/v1/transactions/health` succeeds and rule/policy caches have populated. The transaction health string alone does not prove all downstream dependencies are healthy.
3. Prefer a separate machine for load generation. Check CPU, free RAM, Kafka lag, database waits, and service logs during the run.
4. Run the following from this directory in PowerShell:

```powershell
node .\frms-load-test.mjs --dry-run
$env:FRMS_BASE_URL = 'http://localhost:8085'
$env:FRMS_TEST_ENV_CONFIRMED = 'yes'
# If JWT is enabled, supply FRMS_TEST_TOKEN securely in the environment.
node .\frms-load-test.mjs
```

The runner creates 1,901 synthetic transactions across bursts of 1, 50, 100, 250, 500, and 1,000. It stops increasing load on any rejected, failed, or unresolved transaction and saves IDs/results under `results/`. It never retries POSTs automatically or bypasses rate limits. A single-client sustained test may be constrained by the existing 1,000 requests/second limit; this burst test is not a sustained TPS benchmark.

## Interpreting results

POST latency is acceptance latency. The final decision is asynchronous. The runner polls final transaction status after each burst with at most 20 parallel GETs; these reads add load. Completion observation times are upper bounds, not precise decision latency measurements. Do not use them to certify p99 decision latency under 200 ms.

Successful final statuses do not prove scoring, decision, audit, and notification records are durable. Reconcile the recorded transaction IDs across those stores and check that Kafka lag drains. Existing asynchronous durability/retry limitations remain outside this performance-only change.

The payload uses INR 10, synthetic user/device IDs, and no coordinates. It does not cover cold geocoding or production payload/rule distributions. Follow this baseline with representative data, sustained load, and failure testing before making capacity claims.

## Validation on 2026-10-15

- Offline Maven packaging succeeded for gateway, transaction, and fraud engine. These modules have no test sources; packaging is not a load test.
- Runner syntax and dry-run checks succeeded; patch whitespace checks passed.
- On the latest read-only check, the transaction health string returned 200, but transaction and gateway Actuator health returned 503. Fraud engine port 8092 refused connections.
- Email and SMS are enabled in checked-in configuration. Confirm test-only delivery before submitting synthetic transactions; the runner does not suppress these effects.
- No load transactions were submitted and no services were restarted. Existing user edits were preserved. Rebuilt artifacts/configuration must be loaded by restarting the affected services before measurement.
