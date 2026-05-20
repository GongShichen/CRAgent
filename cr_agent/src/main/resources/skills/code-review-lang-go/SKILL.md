---
name: code-review-lang-go
description: Go review checks for context cancellation, goroutines, channels, errors, resource cleanup, HTTP, SQL, and tests.
languages: [go]
file_patterns: [*.go, go.mod, go.sum]
risk_triggers: [context, goroutine, channel, defer, error, http, sql, transaction, mutex]
modes: [diff, repo_audit]
---

# Go CR Skill

Use this skill for Go code and module changes.

## High-Signal Bug Patterns
- Context: network, DB, and goroutine work should propagate cancellation/deadlines; do not use `context.Background()` where request context exists.
- Goroutines/channels: check leaks, send-on-closed-channel, blocked sends, ownership of close, wait groups, and panic recovery.
- Errors: ignored errors, lost wrapping, wrong sentinel comparison, and misleading partial success are review-worthy.
- Resources: response bodies, files, rows, transactions, timers, and tickers must close/stop on every path.
- HTTP/SQL: body limits, timeouts, auth middleware, parameter binding, transaction rollback, and pagination are high signal.
- Modules: `go.mod` changes need compatibility and supply-chain reasoning.

## Evidence Requirements
- Cite exact control-flow path and resource/error lifecycle.
- For tests, prefer table-driven tests plus race/error/cancellation cases where relevant.

