---
name: code-review-lang-config-build
description: Build, dependency, CI, container, workflow, and configuration review checks for security, compatibility, and operational risk.
languages: [config]
file_patterns: [*.yml, *.yaml, *.json, *.toml, *.xml, Dockerfile, docker-compose*, package.json, pom.xml, build.gradle, Cargo.toml, go.mod, composer.json, Gemfile, Package.swift, CMakeLists.txt]
risk_triggers: [dependency, workflow, ci, docker, secret, token, permission, version, migration, build]
modes: [diff, repo_audit]
---

# Config / Build / Dependency CR Skill

Use this skill for configuration, build, dependency, CI, and deployment files.

## High-Signal Bug Patterns
- CI/workflows: token permissions, untrusted PR execution, script injection, artifact exposure, cache poisoning, and secret handling are security-sensitive.
- Dependencies: new packages, version loosen/tighten, lockfile drift, postinstall/build scripts, and source changes need compatibility/supply-chain reasoning.
- Containers: base image tags, root user, exposed ports, copied secrets, package manager cache, and healthcheck changes can alter runtime risk.
- Build systems: compiler flags, test target inclusion, generated code, source sets, and platform/deployment target changes can silently drop checks.
- Config/schema: auth settings, CORS, rate limits, feature flags, DB migration config, and env var defaults can break production.

## Evidence Requirements
- Cite the exact config key, workflow permission, package, or build target.
- Avoid broad dependency warnings unless the diff changes a concrete version, source, script, or permission.
