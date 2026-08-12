# Database Scripts

Create one PostgreSQL database per service. Keep databases isolated and do not create cross-service foreign keys.

- se_frms_transaction_db
- se_frms_rule_cache_db
- se_frms_scoring_db
- se_frms_decision_db
- se_frms_notification_db
- se_frms_analytics_db
- se_frms_audit_db

The fraud-engine-service has no database in the initial skeleton.
