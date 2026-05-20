---
name: code-review-lang-js-ts-frontend
description: JavaScript, TypeScript, React, Vue, Angular, Node, and frontend review checks for type safety, web security, state lifecycle, and API contracts.
languages: [javascript, typescript]
file_patterns: [*.js, *.jsx, *.ts, *.tsx, package.json, vite.config.*, next.config.*, webpack.config.*]
risk_triggers: [react, vue, angular, node, csrf, xss, token, localstorage, useeffect, promise, any, schema]
modes: [diff, repo_audit]
---

# JavaScript / TypeScript / Frontend CR Skill

Use this skill for JS/TS, frontend, and Node code.

## High-Signal Bug Patterns
- Type safety: `any`, unsafe casts, non-null assertions, unchecked JSON, and widened external API types can hide runtime failures.
- Web security: XSS, CSRF, open redirect, unsafe HTML, token storage, cookie attributes, and frontend-only authorization must be checked against backend enforcement.
- React/Vue/Angular lifecycle: effects, watchers, subscriptions, timers, async state updates, stale closures, and cleanup are high-risk.
- Async/API: Promise fan-out, partial failures, retries, abort signals, request deduplication, and cache invalidation need concrete reasoning.
- Node/server: path traversal, SSRF, command execution, body size, auth middleware ordering, and secret logging are security-sensitive.
- Build/deps: package scripts, lockfile changes, transitive dependency risk, and bundler config can change runtime behavior.

## Evidence Requirements
- Tie comments to user-visible behavior, API contract, or security boundary.
- For tests, prefer React Testing Library/Vitest/Jest/Playwright/Cypress cases that exercise the changed interaction or network path.

