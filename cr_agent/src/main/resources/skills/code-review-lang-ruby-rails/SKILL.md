---
name: code-review-lang-ruby-rails
description: Ruby and Rails review checks for strong parameters, authorization filters, ActiveRecord, migrations, jobs, transactions, and tests.
languages: [ruby]
file_patterns: [*.rb, Gemfile, Gemfile.lock, *.gemspec, db/migrate/*]
risk_triggers: [rails, activerecord, params, permit, before_action, migration, job, sidekiq, sql]
modes: [diff, repo_audit]
---

# Ruby / Rails CR Skill

Use this skill for Ruby libraries and Rails applications.

## High-Signal Bug Patterns
- Request trust: Strong Parameters, controller filters, CSRF exceptions, redirects, and authorization policies must match sensitive actions.
- ActiveRecord: raw SQL interpolation, N+1, missing pagination, callbacks, scopes, and transaction boundaries need concrete evidence.
- Migrations: rollback, locking, backfill batching, defaults, nullability, and index creation can break production.
- Jobs/side effects: background jobs must be idempotent and retry-safe; email/webhook/payment side effects need ordering and deduplication.
- Errors/secrets: rescue blocks must not hide important failures; logs/responses must not expose credentials or PII.
- Gems: dependency changes and monkey patches can change global behavior.

## Evidence Requirements
- Cite the exact action/model/job/migration path and explain the request/data lifecycle.
- For tests, prefer RSpec/Minitest request/model/job specs that cover authorization, failure, and retry paths.

