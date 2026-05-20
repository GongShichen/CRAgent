---
name: code-review-lang-php
description: PHP, Laravel, and Symfony review checks for request validation, auth, SQL, escaping, uploads, sessions, and package changes.
languages: [php]
file_patterns: [*.php, composer.json, composer.lock, .env.example]
risk_triggers: [laravel, symfony, request, validator, authorize, policy, query, raw, upload, session, csrf]
modes: [diff, repo_audit]
---

# PHP / Laravel / Symfony CR Skill

Use this skill for PHP applications, frameworks, and Composer changes.

## High-Signal Bug Patterns
- Input/auth: validate request data, use policies/gates/middleware, and avoid trusting route params or hidden frontend fields.
- SQL/data: raw SQL, string interpolation, query builder misuse, N+1, missing pagination, and transaction side effects are high signal.
- Output/files: Blade/Twig escaping, raw HTML, file upload MIME/size/path validation, and path traversal need evidence.
- Session/CSRF: auth state, remember tokens, cookie attributes, CSRF exceptions, and redirect targets are security-sensitive.
- Errors/secrets: exception pages, logs, API responses, and config files must not expose secrets.
- Composer: dependency and autoload changes can alter runtime entry points or supply-chain risk.

## Evidence Requirements
- Cite controller/request/model/template line and the concrete external input.
- For tests, prefer PHPUnit/Pest feature tests covering auth, validation, and error paths.

