---
name: code-review-lang-python
description: Python review checks for input trust, subprocess, serialization, filesystem safety, async/resource cleanup, packaging, and tests.
languages: [python]
file_patterns: [*.py, pyproject.toml, requirements.txt, setup.cfg]
risk_triggers: [subprocess, pickle, yaml, tempfile, pathlib, asyncio, flask, django, fastapi, secret, token]
modes: [diff, repo_audit]
---

# Python CR Skill

Use this skill for Python application, library, or tooling code.

## High-Signal Bug Patterns
- Unsafe input handling: `pickle`, `shelve`, unsafe YAML loaders, dynamic import/eval/exec, and template rendering of untrusted content need explicit trust evidence.
- Subprocess: `shell=True`, string commands, inherited environment, cwd, and untrusted args are security-sensitive.
- Filesystem: path traversal, tempfile race, symlink handling, cleanup on error, and permission modes are common defects.
- Async/resources: async tasks, context managers, sessions, file handles, DB transactions, and locks must close/cancel on failure.
- Web frameworks: FastAPI/Django/Flask request validation, auth decorators/middleware, CSRF, SQL construction, and response caching need path-specific checks.
- Packaging: dependency pins, extras, entry points, and import-time side effects can break users.

## Evidence Requirements
- Quote the exact changed line or surrounding function.
- For tests, prefer pytest/unittest scenarios tied to changed behavior, error path, or security boundary.

