# ai-service — Changes So Far

Tracks what's actually been done on branch `feature/ai-service` (off `origin/dev`), kept in sync
with `phases.md`. Updated as each phase lands — not a historical log, a current snapshot.

Git identity for this work: `Ananya Mishra <ananya.mishra@secureedge.in>` (set locally on this
repo only). No commits made yet — everything below is still an uncommitted working-tree change.

## Status: Phases 1-4 complete (of 8). Phases 5-8 not started.

## Files added

```
ai-service/
├── pom.xml
├── phases.md                                          (repo-level, not under ai-service/)
├── changes.md                                          (this file, repo-level)
└── src/main/
    ├── java/com/se/frms/ai/
    │   ├── AiServiceApplication.java
    │   ├── client/
    │   │   └── ClaudeClient.java
    │   ├── config/
    │   │   ├── HttpClientConfig.java
    │   │   └── RestClientConfig.java
    │   ├── controller/
    │   │   └── AiController.java
    │   ├── dto/
    │   │   ├── ChatRequest.java / ChatResponse.java
    │   │   ├── DraftTemplateRequest.java / DraftTemplateResponse.java
    │   │   ├── ExplainDecisionRequest.java / ExplainDecisionResponse.java
    │   │   ├── NlToRuleRequest.java / NlToRuleResponse.java
    │   │   ├── RiskScoreRequest.java / RiskScoreResponse.java
    │   │   └── SummarizeTrendsRequest.java / SummarizeTrendsResponse.java
    │   ├── entity/
    │   │   └── AiAuditLog.java
    │   ├── exception/
    │   │   ├── ClaudeCallException.java
    │   │   ├── FeatureDisabledException.java
    │   │   └── GlobalExceptionHandler.java
    │   ├── filter/
    │   │   └── CorrelationIdFilter.java
    │   ├── repository/
    │   │   └── AiAuditLogRepository.java
    │   ├── service/
    │   │   ├── AiService.java
    │   │   └── impl/AiServiceImpl.java
    │   └── util/
    │       └── PiiRedactor.java
    └── resources/
        └── application.properties

database/
└── create_ai_service_tables.sql
```

### `ai-service/pom.xml`
New Maven module. Copied verbatim from `scoring-service/pom.xml`'s current template:
`spring-boot-starter-parent` 3.2.12, `spring-cloud.version` 2023.0.4, Lombok pinned `1.18.40` +
explicit `maven-compiler-plugin` 3.13.0 annotation-processor config, `spring-boot-starter-test`.
Starters: `eureka-client`, `web`, `validation`, `actuator`, `data-jpa`, `postgresql` (runtime).
No new third-party dependency added (Phase 2's Claude call will use `RestClient`, already
available transitively via `spring-boot-starter-web`, matching every other service's outbound-call
pattern).

### `ai-service/src/main/java/com/se/frms/ai/AiServiceApplication.java`
Standard `@SpringBootApplication` entry point, no extra annotations (no `@EnableAsync` yet —
nothing async exists in this service so far).

### `ai-service/src/main/java/com/se/frms/ai/filter/CorrelationIdFilter.java`
Copied byte-for-byte from `decision-service`'s `CorrelationIdFilter` (only the package line
differs — this filter is identical across every service in the repo that has one). Reads/generates
an `X-Request-Id` header, puts it in `MDC` under `requestId` for the logging pattern, echoes it
back on the response.

### `ai-service/src/main/java/com/se/frms/ai/controller/AiController.java`
`@RestController` at `/api/v1/ai`, one endpoint: `GET /health` → `"ai-service is running"`. No
other endpoints yet — those arrive in Phase 3.

### `ai-service/src/main/resources/application.properties`
`spring.application.name=ai-service`, `server.port=8101` (confirmed free — ports in use:
8092-8100, 8761, 8085). Datasource for `se_frms_ai_db` (not yet created — no entity exists yet to
need it; Phase 2 adds the audit-log table). Eureka client block matching every other service
(`defaultZone=http://localhost:8761/eureka/`). Same logging pattern
(`%X{requestId}` placeholder) and Hikari sizing (`maximum-pool-size=12`) as `decision-service`'s.

### `ai-service/pom.xml` (updated)
Added `org.apache.httpcomponents.client5:httpclient5` — the same dependency `decision-service`
already uses for its pooled `RestClient` request factory. Not a new dependency to the repo, just
added to this module.

### `ai-service/src/main/java/com/se/frms/ai/config/HttpClientConfig.java`
Pooled `HttpComponentsClientHttpRequestFactory` bean, same shape as `decision-service`'s, sized
down (50 connections vs. 200 — one outbound target, not several internal services) and with a
longer read timeout (20s vs. 5s elsewhere) since an LLM completion has materially longer tail
latency than a CRUD/Postgres read. **Note for Phase 4**: the api-gateway timelimiter for
ai-service's route needs to be set comfortably above this 20s client timeout, not the ~10s I'd
sketched earlier, or the gateway would time out before this service's own client does.

### `ai-service/src/main/java/com/se/frms/ai/config/RestClientConfig.java`
Single `RestClient.Builder` bean on that pooled factory — no direct-vs-`@LoadBalanced` split
needed (ai-service has one outbound target: the external Claude API, not another Eureka service).

### `ai-service/src/main/java/com/se/frms/ai/client/ClaudeClient.java`
Calls `https://api.anthropic.com/v1/messages` (Anthropic Messages API). Reads
`ai.anthropic.api-key`/`ai.anthropic.model`/`ai.anthropic.max-tokens` via `@Value`. Throws
`ClaudeCallException` if the key is unconfigured, the response is empty, or the HTTP call fails.
Never logs the raw prompt/response itself (callers are responsible for passing already-redacted
text — see `PiiRedactor`).

### `ai-service/src/main/java/com/se/frms/ai/util/PiiRedactor.java`
Regex-based redaction: email addresses → `[REDACTED_EMAIL]`, digit runs of 9+ →
`[REDACTED_NUMBER]`. Not a full PII-detection system — just the two shapes the proposal calls out.

### `ai-service/src/main/java/com/se/frms/ai/entity/AiAuditLog.java` + `repository/AiAuditLogRepository.java`
One row per call: `endpoint`, `model`, `requestPayload`/`responsePayload` (plain `TEXT`, not
`jsonb` — the original plan sketch said jsonb, but what's actually stored is redacted prompt/
response text, not a structured JSON object, so `TEXT` is the accurate column type), `createdAt`.
Table `se_frms_ai_audit_log`.

### `ai-service/src/main/java/com/se/frms/ai/exception/ClaudeCallException.java`
Mirrors `decision-service`'s `ExternalServiceException` shape. Not yet wired to a
`@RestControllerAdvice` — that arrives in Phase 3 alongside the first real controller endpoints.

### `database/create_ai_service_tables.sql` (new)
`CREATE TABLE se_frms_ai_audit_log` + two indexes (`endpoint`, `created_at`), same style as
`create_decision_service_table.sql`.

### `ai-service/src/main/resources/application.properties` (updated)
Added `ai.anthropic.api-key=${ANTHROPIC_API_KEY:}` (env-var only, never hardcoded — per your
answer, follows this repo's existing convention of `${ENV_VAR:default}` placeholders rather than
a secrets-manager integration), `ai.anthropic.model`, `ai.anthropic.max-tokens`, and the
`frms.http.*` pool/timeout tuning properties `HttpClientConfig` reads.

### `ai-service/src/main/java/com/se/frms/ai/dto/*` (new, 12 files)
Request/response record pair per endpoint: `ExplainDecisionRequest/Response`,
`SummarizeTrendsRequest/Response`, `DraftTemplateRequest/Response`, `NlToRuleRequest/Response`,
and the two stubs' `RiskScoreRequest/Response`, `ChatRequest/Response`. Plain records with
`jakarta.validation` annotations on the components that need them, no Lombok — matches
`decision-service`'s `DecisionRequest`/`CaseResponse` style exactly.

### `ai-service/src/main/java/com/se/frms/ai/exception/FeatureDisabledException.java` (new)
Thrown by `riskScore()`/`chat()` when their feature flag is off.

### `ai-service/src/main/java/com/se/frms/ai/exception/GlobalExceptionHandler.java` (new)
`@RestControllerAdvice`, same shape as `decision-service`'s: validation failures → 400,
`FeatureDisabledException` → 503, `ClaudeCallException` → 502, anything else → 500.

### `ai-service/src/main/java/com/se/frms/ai/service/AiService.java` + `service/impl/AiServiceImpl.java` (new)
One method per endpoint. The four real methods build a prompt (text block + `.formatted()`),
redact it, call `ClaudeClient`, redact the response, write a best-effort audit-log row (a failed
audit write logs a warning but never fails the actual AI response), and return it.
`nl-to-rule` additionally parses the response text as JSON into a `Map` via Jackson's
`ObjectMapper` (a Spring Boot auto-configured bean, no extra config needed) - a response Claude
doesn't return as valid JSON surfaces as a `ClaudeCallException`. `riskScore()`/`chat()` just
throw `FeatureDisabledException` when their flag is off (currently always, since both default to
`false`).

### `ai-service/src/main/java/com/se/frms/ai/client/ClaudeClient.java` (updated)
Added a `getModel()` getter so `AiServiceImpl` can record which model answered each call in the
audit log.

### `ai-service/src/main/java/com/se/frms/ai/controller/AiController.java` (updated)
Now has all 6 endpoints (`POST /explain-decision`, `/summarize-trends`, `/draft-template`,
`/nl-to-rule`, `/risk-score`, `/chat`, all `@Valid @RequestBody`) alongside the existing
`GET /health`.

### `ai-service/src/main/resources/application.properties` (updated)
Added `ai.features.risk-score.enabled=false` and `ai.features.chat.enabled=false` - the explicit
feature-flag off switch the AI integration proposal calls for as its mitigation for "new
microservice adds operational surface area."

## Files changed in other services

### `api-gateway/src/main/resources/application.yml` (updated)
New `ai-service` route block, same shape as `analytics-service`'s (redis `RequestRateLimiter`,
`CircuitBreaker` + `Retry`, `AddRequestHeader=X-Gateway-Source, api-gateway`). Also added
`resilience4j.circuitbreaker.instances.aiServiceCircuitBreaker` (smaller sliding window — 10
calls, 5 minimum — and a longer `waitDurationInOpenState` of 20s than the other services' 10-15s,
since Claude being slow/down isn't something that resolves itself as quickly as a Postgres blip)
and `resilience4j.timelimiter.instances.aiServiceCircuitBreaker` at **25s** — comfortably above
ai-service's own 20s client-side read timeout (`frms.http.read-timeout-ms=20000` in
`HttpClientConfig`), correcting the ~10s I'd originally sketched in the plan before Phase 2's
actual timeout value was settled.

### `api-gateway/src/main/java/com/se/frms/gateway/controller/FallbackController.java` (updated)
Added `aiServiceFallback()` mapped at `GET/POST /fallback/ai`, same `unavailable("ai-service")`
pattern as every other fallback method.

Phases 5-7 (decision-service, notification-service, analytics-service) haven't started.

## Verification status

**Not compiled or run** — no Java/Maven available in this working environment. Every file above
was hand-checked against the equivalent file in an existing service (same imports, same
annotation shapes, same package conventions) rather than compiler-verified. You'll need to
`mvn compile` / start the service yourself to confirm it actually builds and registers with
Eureka on port 8101. You'll also need to actually run `database/create_ai_service_tables.sql`
against a `se_frms_ai_db` Postgres database (not created automatically) and export
`ANTHROPIC_API_KEY` before the service can do anything beyond compile.

## Not yet touched (repo-wide)

- `api-gateway/src/main/resources/application.yml` — no route for ai-service yet, so it's not
  reachable through the gateway (only directly on `localhost:8101`).
- `decision-service`, `notification-service`, `analytics-service` — no changes yet.
- Root `README.md`, `database/README.md` — not updated yet (Phase 8).
