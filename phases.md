# ai-service — Execution Phases

Work item: add a new `ai-service` microservice to `se-frms-microservices` (branch
`feature/ai-service`, off `origin/dev`), per `FRMS_AI_Integration_Proposal.pdf` /
`FRMS_AI_Architecture_Flow.pdf`, plus real wiring into `decision-service`,
`notification-service`, and `analytics-service`. Full rationale and file-level design live in
the plan file (`/Users/saranshmishra/.claude/plans/zazzy-booping-teapot.md`); this file is the
short, trackable phase list, kept in sync with `changes.md` as work lands.

Each phase is its own commit, independently compilable, reviewed one at a time rather than as one
large diff. `mvn compile` is not available in this working environment (no Java/Maven installed
here), so each phase's code is hand-verified against the existing services' equivalents instead
of compiler-checked — compiling/running is on you, in your own IDE/environment.

## Phase 1 — `ai-service` skeleton ✅ done

New Maven module, `pom.xml` (copied from `scoring-service`'s current template), `AiServiceApplication`,
`filter/CorrelationIdFilter` (byte-identical to the other services'), `application.properties`
(port 8101, `se_frms_ai_db` datasource, Eureka block, logging pattern), and a bare
`GET /api/v1/ai/health`. No Claude call, no audit-log table, no real endpoints yet.

## Phase 2 — Claude client + audit log (still standalone) ✅ done

`config/HttpClientConfig.java` + `config/RestClientConfig.java` (pooled `RestClient` bean),
`client/ClaudeClient.java` (calls `https://api.anthropic.com/v1/messages`, config-driven API
key/model, never hardcoded), `util/PiiRedactor.java` (strip emails/long digit runs before
anything leaves the service or gets logged), `entity/AiAuditLog.java` +
`repository/AiAuditLogRepository.java`, `database/create_ai_service_tables.sql`. Still no
controller endpoints beyond health — this phase is "the plumbing compiles and can talk to a DB
and to Claude," not yet reachable from outside.

## Phase 3 — the 4 real endpoints + 2 disabled stubs ✅ done

DTOs (`ExplainDecisionRequest/Response`, `SummarizeTrendsRequest/Response`,
`DraftTemplateRequest/Response`, `NlToRuleRequest/Response`, plus stub
`RiskScoreRequest/Response`, `ChatRequest/Response`), `service/AiService` +
`service/impl/AiServiceImpl`, `controller/AiController` (full), `exception/FeatureDisabledException`,
`exception/ClaudeCallException`, `exception/GlobalExceptionHandler`. First point the service is
useful standalone (curl + `ANTHROPIC_API_KEY`), before touching any other service.
`ai.features.risk-score.enabled=false` / `ai.features.chat.enabled=false` by default — see plan's
Context section for why those two stay stubbed (no trained model, no in-repo chat consumer).

## Phase 4 — api-gateway wiring

New route block in `api-gateway/src/main/resources/application.yml` (`uri: lb://ai-service`,
`Path=/api/v1/ai/**`, redis `RequestRateLimiter`, `CircuitBreaker` + `Retry`,
`AddRequestHeader=X-Gateway-Source, api-gateway`), `resilience4j.circuitbreaker` /
`.timelimiter` instances for `aiServiceCircuitBreaker` (longer timeout than other routes — LLM
calls have a longer tail latency), and an `aiServiceFallback()` addition to `FallbackController.java`.

## Phase 5 — decision-service integration

`ai_narrative` TEXT column on `Decision` entity + `database/alter_decision_service_add_ai_narrative.sql`,
`aiNarrative` field on `CaseResponse`, new `client/AiNarrativeClient.java` (same shape as
`ScoringLookupClient`), wiring into `DecisionServiceImpl.mapToCaseResponse()` (best-effort, cached
after first generation via a new narrow `DecisionPersistenceService.saveAiNarrative()` write — a
separate transaction, since `getCases()` runs `@Transactional(readOnly = true)`), feature flag
`frms.ai.narrative.enabled=true`.

## Phase 6 — notification-service integration

`POST /api/v1/notification-templates/ai-draft` on `NotificationTemplateController` (gated by the
same existing `X-INTERNAL-API-KEY` check as the rest of that controller), new
`client/AiDraftTemplateClient.java` using `RestTemplate` (matches this service's own existing
outbound-call convention), transient draft only — no DB write. Feature flag
`frms.ai.draft-template.enabled=true`.

## Phase 7 — analytics-service integration

`GET /api/v1/analytics/ai-summary` on `AnalyticsController` (same param shape as the existing
`getFraudTrend`), new minimal `config/RestClientConfig.java` + `client/AiTrendSummaryClient.java`
(analytics-service currently has neither — it's Kafka-consumer-only). On-demand, no new
scheduler/entity (deviates from the proposal's weekly-cron design to match this service's actual
on-demand convention). Feature flag `frms.ai.trend-summary.enabled=true`.

## Phase 8 — docs

One-line additions to root `README.md` (ai-service bullet, port 8101) and `database/README.md`
(`se_frms_ai_db`). Not a full rewrite of either (both have pre-existing staleness re:
`auth-service`/`api-gateway` that isn't this task's to fix).

## Explicitly out of scope (all phases)

- `se_frms_backend` / `se_frms_frontend` — different repos, not touched by this work item.
- `/ai/risk-score` real ML model, `/ai/chat` real tool-calling — see Phase 3 note.
- pgvector / fuzzy blacklist matching (lives in `se_frms_backend`, Phase 4 of the original proposal).
- Any change to `fraud-engine-service`, `api-gateway`'s JWT posture, or other services' existing
  routes/config beyond what's listed above.
