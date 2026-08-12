# FRMS Microservices

Basic Spring Boot project skeleton for the FRMS microservices architecture.

## Services

- transaction-service: receives bank transactions, stores dynamic JSONB transaction data, calls Fraud Engine, returns the final response.
- fraud-engine-service: central orchestrator; calls Rule Cache, Scoring, and Decision; publishes fraud events to Kafka.
- rule-cache-service: syncs active rules and rule scores from the existing monolith; stores PostgreSQL cache and can use Redis for low-latency reads.
- scoring-service: evaluates active rules, calculates risk score, and owns behavioural analysis.
- decision-service: converts risk score into ALLOW, REVIEW, or BLOCK.
- notification-service: consumes fraud events and manages notification history.
- analytics-service: consumes fraud events for reporting, KPIs, trends, and rule performance.
- audit-service: consumes relevant events and stores audit history.

## Run

Each service is an independent Maven project:

```bash
cd transaction-service
mvn spring-boot:run
```

Default ports:

- transaction-service: 8091
- fraud-engine-service: 8092
- rule-cache-service: 8093
- scoring-service: 8094
- decision-service: 8095
- notification-service: 8096
- analytics-service: 8097
- audit-service: 8098

Start PostgreSQL, Kafka, and Redis where required before running dependent services.
## Service Registry Registration

Start `service-registry` first on port `8761`:

```bash
cd service-registry
mvn spring-boot:run
```

Then start `api-gateway` and the individual services. The following services are configured as Eureka clients and will register at `http://localhost:8761/eureka/`:

- api-gateway
- transaction-service
- fraud-engine-service
- rule-cache-service
- scoring-service
- decision-service
- notification-service
- analytics-service
- audit-service

Open the Eureka dashboard at `http://localhost:8761` and verify each service appears under Applications.

