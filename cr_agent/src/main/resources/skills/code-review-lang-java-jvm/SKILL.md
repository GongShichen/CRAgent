---
name: code-review-lang-java-jvm
description: Deep Java, Spring, and JVM review checks for security, concurrency, resources, persistence, and API compatibility.
languages: [java]
file_patterns: [*.java, pom.xml, build.gradle, build.gradle.kts]
risk_triggers: [spring, servlet, jpa, hibernate, jackson, deserialization, synchronized, executor, transaction, auth, permission]
modes: [diff, repo_audit]
---

# Java / Spring / JVM CR Skill

Use this skill only when Java/JVM code or build files are in scope. Report only findings grounded in the supplied diff, file slice, LSP fact, static check, or config.

## High-Signal Bug Patterns
- Trust boundary: controller/filter/interceptor/security config changes must preserve authentication, authorization, CSRF, CORS, and method-level security.
- Deserialization and reflection: unsafe Java serialization, polymorphic Jackson binding, class loading, SpEL, reflection, or dynamic proxies need explicit input trust evidence.
- Resource handling: streams, files, sockets, JDBC resources, locks, and transactions must close or roll back on all error paths.
- Concurrency: `synchronized`, locks, executors, futures, static mutable state, and caches need timeout, cancellation, visibility, and race reasoning.
- Persistence: JPA lazy loading, N+1, missing pagination, string-built queries, transaction boundary changes, and optimistic locking are review-worthy.
- Numeric/time: integer overflow, time-zone conversion, locale-sensitive string handling, and date truncation often hide production bugs.

## False Positive Boundaries
- Do not flag style, naming, or broad architecture unless it causes a concrete failure mode.
- Do not require tests for generated DTOs or pure wiring unless behavior or contract changed.

## Evidence Requirements
- Name the exact method/config line and explain the runtime path.
- Prefer LSP symbols/references for API or call-site claims.
- Use static/test failures as supporting evidence, not as the only proof of a code bug unless the failure is precise.

